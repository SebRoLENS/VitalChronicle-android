#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

import android_agent_bridge as agent


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        health = root / "health_data.sqlite3"
        state = root / "vitalchronicle_agent.sqlite3"
        db = sqlite3.connect(health)
        db.executescript(
            """
            CREATE TABLE records (
              data_type TEXT NOT NULL, record_id TEXT NOT NULL, record_kind TEXT NOT NULL,
              start_time TEXT, end_time TEXT, source TEXT NOT NULL, payload TEXT NOT NULL,
              updated_at TEXT NOT NULL, PRIMARY KEY (data_type, record_id)
            );
            """
        )
        db.execute(
            "INSERT INTO records VALUES(?,?,?,?,?,?,?,?)",
            ("steps", "one", "data_point", "2026-09-08T08:00:00+00:00", None, "test", '{"count":5000}', "2026-09-08T09:00:00+00:00"),
        )
        db.commit(); db.close()

        bootstrap = json.loads(agent.bootstrap(str(health), str(state), "How active have I been?"))
        assert bootstrap["max_steps"] == 8
        assert bootstrap["tool_count"] >= 40
        assert "get_data_coverage" in bootstrap["prompt"]
        assert "filesystem" in bootstrap["system"]

        available = json.loads(agent.execute_tool(str(health), str(state), "get_available_metrics", "{}"))
        assert available["count"] == 1
        assert available["data_types"][0]["data_type"] == "steps"

        queued = json.loads(agent.execute_tool(
            str(health), str(state), "ask_user_feedback",
            json.dumps({
                "question": "How did this activity level feel?",
                "reason": "Improve personalisation",
                "learning_key": "activity_tolerance",
                "context": {"observation": "a high-step day"},
            }),
        ))
        assert queued["queued"] is True
        before = json.loads(agent.state(str(health), str(state)))
        assert before["pending_feedback"]["feedback_id"]
        agent.answer_feedback(
            str(health), str(state), before["pending_feedback"]["feedback_id"], "comfortable"
        )
        after = json.loads(agent.state(str(health), str(state)))
        assert after["associations"] == 1
        assert after["pending_feedback"] is None

        reset = json.loads(agent.reset_personalisation(str(health), str(state)))
        assert reset["associations"] == 0
        assert reset["learned_tools"] == 0
        assert reset["built_in_tools"] >= 40

    print("Android Personal Health Agent bridge OK")


if __name__ == "__main__":
    main()
