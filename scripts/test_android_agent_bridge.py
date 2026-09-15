#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

import android_agent_bridge as agent
import android_self_report_router as self_report_router


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

        # Short subjective statements are memory updates, not analysis requests.
        # In particular, negation must be preserved: "not tired" is not fatigue.
        router_state = root / "self_report_router.sqlite3"
        not_tired = json.loads(self_report_router.route(str(router_state), "I'm not tired"))
        assert not_tired["handled"] is True
        assert not_tired["captured"] is True
        assert not_tired["category"] == "fatigue"
        assert not_tired["state"] == "absent"
        assert "not feeling tired" in not_tired["answer"]
        assert not_tired["follow_up_queued"] is True

        feeling_good = json.loads(self_report_router.route(str(router_state), "Sto alla grande"))
        assert feeling_good["handled"] is True
        assert feeling_good["category"] == "wellbeing"
        assert feeling_good["state"] == "positive"
        assert "salvato localmente" in feeling_good["answer"]

        mixed_question = json.loads(self_report_router.route(
            str(router_state), "I'm not tired, so why is my HRV lower today?"
        ))
        assert mixed_question["handled"] is False
        assert mixed_question["captured"] is True
        assert mixed_question["state"] == "absent"

        bootstrap = json.loads(agent.bootstrap(str(health), str(state), "How active have I been?"))
        assert bootstrap["max_steps"] == 15
        assert bootstrap["max_factory_repairs"] == 3
        assert bootstrap["tool_count"] <= 10
        assert bootstrap["history_count"] == 0
        assert "get_data_coverage" in bootstrap["prompt"]
        assert "calculate_cardio_load" in bootstrap["prompt"]
        assert "get_sleep_stage_series" not in bootstrap["prompt"]
        assert len(bootstrap["system"]) < 1000
        assert "filesystem" in bootstrap["system"]
        assert "durable context needs confirmation" in bootstrap["system"]

        memory_state = root / "durable_context.sqlite3"
        durable_question = (
            "Di solito mi alleno in bicicletta cinque giorni a settimana. "
            "Considerando i dati disponibili, come dormo dopo l'attività intensa?"
        )
        durable_bootstrap = json.loads(agent.bootstrap(
            str(health), str(memory_state), durable_question
        ))
        pending_context = json.loads(agent.state(str(health), str(memory_state)))["pending_feedback"]
        assert pending_context["context"]["model_key"] == "training_routine_context"
        assert pending_context["context"]["candidate_statement"] == (
            "Di solito mi alleno in bicicletta cinque giorni a settimana"
        )
        assert "Considerando i dati" not in pending_context["context"]["candidate_statement"]
        assert "Rispondi sì o no" in pending_context["question"]
        agent.answer_feedback(
            str(health), str(memory_state), pending_context["feedback_id"], "sì"
        )
        learned_context = json.loads(agent.state(str(health), str(memory_state)))["user_model"]
        assert learned_context[0]["key"] == "training_routine_context"
        assert learned_context[0]["statement"] == (
            "Di solito mi alleno in bicicletta cinque giorni a settimana"
        )
        assert durable_bootstrap["tool_count"] <= 10

        hard_state = root / "hard_context.sqlite3"
        hard_question = (
            "Di solito mi alleno 3-4 volte a settimana, soprattutto la mattina. "
            "Normalmente vado a letto verso mezzanotte e mi sveglio verso le 7:30. "
            "Nei giorni in cui mi alleno per due giorni consecutivi, voglio capire se dopo il "
            "secondo allenamento HRV e frequenza cardiaca a riposo recuperano più lentamente "
            "quando la notte successiva dormo almeno il 15% meno della mia mediana personale "
            "rispetto a quando dormo almeno quanto la mia mediana. Considera gli ultimi 90 giorni."
        )
        hard_bootstrap = json.loads(agent.bootstrap(str(health), str(hard_state), hard_question))
        hard_db = sqlite3.connect(hard_state)
        pending_contexts = hard_db.execute(
            "SELECT context_json FROM feedback WHERE answered_at IS NULL "
            "AND context_json LIKE '%durable_context_confirmation%'"
        ).fetchall()
        hard_db.close()
        hard_keys = {json.loads(row[0])["model_key"] for row in pending_contexts}
        assert {"training_routine_context", "sleep_schedule_context"}.issubset(hard_keys)
        assert hard_bootstrap["factory_capability"] == (
            "analysis.composed.training_context.sleep_conditioned_return_comparison"
        )
        assert "compare_sleep_conditioned_consecutive_training_recovery" in hard_bootstrap["tool_names"]
        assert hard_bootstrap["tool_count"] <= 10

        agent.record_exchange(str(state), "How active have I been?", "You recorded 5,000 steps in the available sample.")
        follow_up = json.loads(agent.bootstrap(str(health), str(state), "And compared with before?"))
        assert follow_up["history_count"] == 2
        assert "How active have I been?" in follow_up["prompt"]
        assert "5,000 steps" in follow_up["prompt"]

        dialogue_state = root / "compact_dialogue.sqlite3"
        for index in range(8):
            agent.record_exchange(
                str(dialogue_state), f"long question {index} " + "x" * 3000,
                f"long answer {index} " + "y" * 3000,
            )
        compact = json.loads(agent.bootstrap(
            str(health), str(dialogue_state), "And compared with before?"
        ))
        assert compact["history_count"] == 4
        assert "long question 0" not in compact["prompt"]
        assert "long answer 7" in compact["prompt"]
        assert len(compact["prompt"]) < 14000

        seventeen_days = [{"date": f"2026-09-{day:02d}", "value": day} for day in range(1, 18)]
        compact_result = agent._bounded({"series": seventeen_days})
        assert len(compact_result["series"]) == 17
        long_result = agent._bounded({"series": list(range(5000))}, 1200)
        assert long_result["_context_truncation"]["truncated"] is True
        assert json.loads(json.dumps(long_result)) == long_result

        complex_request = json.loads(agent.bootstrap(
            str(health), str(state),
            "How often do days 30% above my personal activity baseline affect the next day?",
        ))
        assert complex_request["factory_candidate"] is True
        assert complex_request["factory_capability"].startswith("analysis.composed")
        assert "analyze_metric_threshold_responses" in complex_request["tool_names"]

        invalid_metadata = json.loads(agent.execute_tool(
            str(health), str(state), "create_learned_tool",
            json.dumps({
                "name": "analisi_personale",
                "description": "Analisi dei dati personali",
                "capability": "analysis.composed.test_personale",
                "pipeline": [{"op": "return", "source": "missing"}],
            }),
        ))
        assert invalid_metadata["status"] == "invalid_metadata"
        assert invalid_metadata["repairable"] is True

        exact_state = root / "exact_reuse.sqlite3"
        exact_store = agent._agent_store(str(exact_state))
        exact_capability = complex_request["factory_capability"]
        exact_store.add_learned_tool({
            "name": "exact_threshold_response",
            "description": "Exact personal baseline threshold response analysis",
            "capability": exact_capability,
            "parameters": {"type": "object", "properties": {}},
            "pipeline": [
                {"op": "call_tool", "tool": "get_available_metrics", "arguments": {}, "as": "result"},
                {"op": "return", "source": "result"},
            ],
        })
        exact_bootstrap = json.loads(agent.bootstrap(
            str(health), str(exact_state),
            "How often do days 30% above my personal activity baseline affect the next day?",
        ))
        assert exact_bootstrap["exact_registry_tool"] == "exact_threshold_response"
        assert "exact_threshold_response" in exact_bootstrap["tool_names"]

        monitoring_state = root / "monitoring.sqlite3"
        monitor_bootstrap = json.loads(agent.bootstrap(
            str(health), str(monitoring_state),
            "Ricordami di segnalare colazione ed energia dopo la palestra.",
        ))
        assert "create_monitoring_rule" in monitor_bootstrap["tool_names"]
        created_monitor = json.loads(agent.execute_tool(
            str(health), str(monitoring_state), "create_monitoring_rule",
            json.dumps({
                "name": "monitor_colazione_pre_palestra",
                "title": "Colazione prima della palestra",
                "question": "Hai fatto colazione e come ti sei sentito in palestra?",
                "cadence_days": 1,
                "keywords": ["colazione", "palestra"],
                "fields": ["colazione", "sonno", "energia"],
            }),
        ))
        assert created_monitor["status"] == "created"
        monitored = json.loads(agent.state(str(health), str(monitoring_state)))
        assert monitored["learned_tools"] == 0
        assert monitored["monitoring_rules_count"] == 1
        assert monitored["monitoring_rules"][0]["name"] == "monitor_colazione_pre_palestra"
        observation_text = (
            "Oggi ho fatto una colazione abbondante. Mi sentivo meno stanco in palestra. "
            "Vorrei monitorare questa cosa anche in futuro."
        )
        agent.bootstrap(str(health), str(monitoring_state), observation_text)
        monitored = json.loads(agent.state(str(health), str(monitoring_state)))
        assert monitored["monitoring_rules"][0]["observation_count"] == 1
        assert all(
            "Vorrei monitorare" not in item["statement"]
            for item in monitored["self_reports"]
        )
        agent.delete_monitoring_rule(
            str(health), str(monitoring_state), "monitor_colazione_pre_palestra"
        )
        assert json.loads(agent.state(str(health), str(monitoring_state)))["monitoring_rules"] == []

        logged = json.loads(agent.log_factory_event(
            str(state), "tool_factory_repair", "invalid operation", "test_tool",
            '{"status":"invalid_pipeline","pipeline_ops":["invented"]}',
        ))
        assert logged["logged"] is True
        factory_events = json.loads(agent.state(str(health), str(state)))["recent_tool_events"]
        assert any(item["event_type"] == "tool_factory_repair" for item in factory_events)

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
        assert reset["built_in_tools"] >= 44

        controller = Path(
            "app/src/main/java/io/github/sebrolens/vitalchronicle/android/PersonalAgentController.kt"
        ).read_text(encoding="utf-8")
        assert "context <= 8_192 -> 640" in controller
        assert "_vc_result_budget_chars" in controller
        assert "replayInitialContext = false" in controller
        assert '"invalid_metadata"' in controller
        # Personalisation reset intentionally keeps dialogue history, like desktop.
        assert len(reset["recent_conversation"]) == 2
        cleared = json.loads(agent.clear_conversation(str(state)))
        assert cleared["cleared"] == 2
        assert json.loads(agent.state(str(health), str(state)))["recent_conversation"] == []

    print("Android Personal Health Agent parity bridge OK")


if __name__ == "__main__":
    main()
