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
        assert bootstrap["max_steps"] == 15
        assert bootstrap["max_factory_repairs"] == 3
        assert bootstrap["tool_count"] >= 40
        assert bootstrap["history_count"] == 0
        assert "get_data_coverage" in bootstrap["prompt"]
        assert "get_sleep_stage_series" in bootstrap["prompt"]
        assert "Evidence, Reliability" in bootstrap["system"]
        assert "filesystem" in bootstrap["system"]
        assert "Recent conversation history is short-term dialogue context only" in bootstrap["system"]

        agent.record_exchange(str(state), "How active have I been?", "You recorded 5,000 steps in the available sample.")
        follow_up = json.loads(agent.bootstrap(str(health), str(state), "And compared with before?"))
        assert follow_up["history_count"] == 2
        assert "How active have I been?" in follow_up["prompt"]
        assert "5,000 steps" in follow_up["prompt"]

        complex_request = json.loads(agent.bootstrap(
            str(health), str(state),
            "How often do days 30% above my personal activity baseline affect the next day?",
        ))
        assert complex_request["factory_candidate"] is True
        assert complex_request["factory_capability"].startswith("analysis.composed")

        available = json.loads(agent.execute_tool(str(health), str(state), "get_available_metrics", "{}"))
        assert available["count"] == 1
        assert available["data_types"][0]["data_type"] == "steps"

        self_report = json.loads(agent.bootstrap(str(health), str(state), "Mi sento stanco oggi"))
        assert "relevant_self_reports" in self_report["prompt"]
        pending = json.loads(agent.state(str(health), str(state)))["pending_feedback"]
        assert pending and pending["feedback_id"]
        agent.answer_feedback(str(health), str(state), pending["feedback_id"], "Soprattutto muscolare")

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
        assert isinstance(after["tools"], list)
        assert isinstance(after["user_model"], list)
        assert isinstance(after["self_reports"], list)
        assert isinstance(after["recent_tool_events"], list)
        assert len(after["recent_conversation"]) == 2

        calibration = json.loads(agent.calibrate(str(health), str(state), "Vorrei calibrare l'app sui miei dati"))
        assert calibration["available"] is True
        assert calibration["question_count"] >= 1
        assert calibration["questions"][0]["context"]["calibration"] is True
        calibration_state = json.loads(agent.state(str(health), str(state)))
        assert calibration_state["calibration_pending"] is True
        assert calibration_state["calibration_remaining"] == calibration["question_count"]
        assert calibration_state["pending_feedback"]["question"] == calibration["questions"][0]["question"]
        while True:
            current = json.loads(agent.state(str(health), str(state)))
            item = current["pending_feedback"]
            if item is None:
                break
            agent.answer_feedback(str(health), str(state), item["feedback_id"], "Risposta di calibrazione")
        calibrated = json.loads(agent.state(str(health), str(state)))
        assert calibrated["calibration_version"] == agent.CALIBRATION_VERSION
        assert calibrated["calibration_pending"] is False

        reset = json.loads(agent.reset_personalisation(str(health), str(state)))
        assert reset["associations"] == 0
        assert reset["learned_tools"] == 0
        assert reset["built_in_tools"] >= 40
        # Personalisation reset intentionally keeps dialogue history, like desktop.
        assert len(reset["recent_conversation"]) == 2
        cleared = json.loads(agent.clear_conversation(str(state)))
        assert cleared["cleared"] == 2
        assert json.loads(agent.state(str(health), str(state)))["recent_conversation"] == []

    print("Android Personal Health Agent parity bridge OK")


if __name__ == "__main__":
    main()
