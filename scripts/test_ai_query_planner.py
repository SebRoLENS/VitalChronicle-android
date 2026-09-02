#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

from ai_planner_bridge import catalog_from_sqlite, evidence_from_sqlite, planner_request, resolve_plan


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        db_path = str(Path(tmp) / "health.sqlite3")
        db = sqlite3.connect(db_path)
        db.executescript("""
            CREATE TABLE records (
              data_type TEXT NOT NULL,
              record_id TEXT NOT NULL,
              record_kind TEXT NOT NULL DEFAULT 'data_point',
              start_time TEXT,
              end_time TEXT,
              source TEXT NOT NULL DEFAULT '',
              payload TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              PRIMARY KEY (data_type, record_id)
            );
        """)
        rows = [
            ("sleep", "s1", "data_point", "2026-08-31T22:00:00+00:00", "2026-09-01T06:00:00+00:00", "", '{"sleep":{"sleepSummary":{"minutesAsleep":"450","secret":12345}}}', "2026-09-02T00:00:00+00:00"),
            ("daily-heart-rate-variability", "h1", "daily_rollup", "2026-09-01T00:00:00+00:00", "2026-09-01T00:00:00+00:00", "", '{"dailyHeartRateVariability":{"averageHeartRateVariabilityMilliseconds":48.0}}', "2026-09-02T00:00:00+00:00"),
            ("steps", "p1", "data_point", "2026-09-01T09:00:00+00:00", "2026-09-01T09:05:00+00:00", "", '{"steps":{"count":800}}', "2026-09-02T00:00:00+00:00"),
        ]
        db.executemany("INSERT INTO records VALUES (?,?,?,?,?,?,?,?)", rows)
        db.commit(); db.close()

        catalog_json = catalog_from_sqlite(db_path)
        assert "12345" not in catalog_json and "secret" not in catalog_json
        catalog = json.loads(catalog_json)
        assert {row["key"] for row in catalog["datasets"]} == {"sleep", "daily-heart-rate-variability", "steps"}

        request = json.loads(planner_request(catalog_json, "Is my sleep related to HRV over the last month?"))
        assert "available_local_data" in request["prompt"]
        assert "12345" not in request["prompt"]

        raw = json.dumps({
            "data_types": ["sleep", "daily-heart-rate-variability"],
            "window": "last_n_days",
            "days": 30,
            "detail": "daily",
            "reason": "Need matched sleep and HRV data",
        })
        plan_json = resolve_plan(catalog_json, raw)
        plan = json.loads(plan_json)
        assert plan["data_types"] == ["sleep", "daily-heart-rate-variability"]
        assert "steps" not in plan["data_types"]

        evidence = json.loads(evidence_from_sqlite(db_path, plan_json))
        assert evidence["retrieval"]["mode"] == "ai_planned"
        assert evidence["retrieval"]["selected_data_types"] == ["sleep", "daily-heart-rate-variability"]
        assert "activity" not in evidence.get("domains", {})

    print("AI-first shared query planner regression test passed")


if __name__ == "__main__":
    main()
