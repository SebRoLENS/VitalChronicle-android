#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

from android_dashboard import dashboard_from_sqlite


def main() -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
        database_path = Path(tmpdir) / "health.sqlite3"
        connection = sqlite3.connect(database_path)
        try:
            connection.execute(
                """
                CREATE TABLE records (
                  data_type TEXT NOT NULL,
                  record_id TEXT NOT NULL,
                  record_kind TEXT NOT NULL,
                  start_time TEXT,
                  end_time TEXT,
                  source TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  PRIMARY KEY (data_type, record_id)
                )
                """
            )
            for index, (start, end, bpm) in enumerate(
                (
                    ("2026-09-02T10:00:00+00:00", "2026-09-02T10:05:00+00:00", 72.5),
                    ("2026-09-02T10:05:00+00:00", "2026-09-02T10:10:00+00:00", 80.5),
                )
            ):
                payload = json.dumps(
                    {
                        "startTime": start,
                        "endTime": end,
                        "heartRate": {"beatsPerMinuteAvg": bpm},
                    },
                    separators=(",", ":"),
                )
                connection.execute(
                    """
                    INSERT INTO records(
                        data_type,record_id,record_kind,start_time,end_time,source,payload,updated_at
                    ) VALUES(?,?,?,?,?,?,?,?)
                    """,
                    (
                        "heart-rate",
                        f"heart-rate:5m:{index}",
                        "five_minute_rollup",
                        start,
                        end,
                        "test",
                        payload,
                        start,
                    ),
                )
            connection.commit()
        finally:
            connection.close()

        snapshot = json.loads(dashboard_from_sqlite(str(database_path), "2026-09-02"))
        heart = next(
            metric
            for metric in snapshot["metrics"]
            if metric["data_type"] == "heart-rate-today"
        )
        assert heart["heart_smoothing_minutes"] == 5
        assert heart["heart_day_sample_count"] == 2
        assert [point[1] for point in heart["heart_day_smoothed"]] == [72.5, 80.5]
        assert heart["current"] == 80.5


if __name__ == "__main__":
    main()
