"""Thin Android bridge to VitalChronicle's shared AI-first query planner core."""
from __future__ import annotations

import json
import sqlite3
from typing import Any

from google_health_viewer.ai_pipeline import ensure_compact_evidence
from google_health_viewer.ai_query_planner_core import (
    PLANNER_OUTPUT_TOKENS,
    _parse_json_object,
    _planner_messages,
    build_data_catalog,
    build_planned_snapshot,
    fallback_data_plan,
    resolve_data_plan,
)
from mobile_bridge import SQLiteStore


class AndroidPlannerStore(SQLiteStore):
    """SQLiteStore plus the connection factory expected by the shared planner catalogue."""

    def __init__(self, database_path: str):
        self._database_path = database_path
        super().__init__(database_path)

    def _connect(self):
        connection = sqlite3.connect(self._database_path, timeout=10.0)
        connection.row_factory = sqlite3.Row
        return connection


def catalog_from_sqlite(database_path: str) -> str:
    store = AndroidPlannerStore(database_path)
    try:
        return json.dumps(build_data_catalog(store), ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()


def planner_request(catalog_json: str, question: str) -> str:
    catalog = json.loads(catalog_json)
    messages = _planner_messages(catalog, question, [])
    return json.dumps(
        {
            "system": messages[0]["content"],
            "prompt": messages[1]["content"],
            "max_output_tokens": PLANNER_OUTPUT_TOKENS,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def resolve_plan(catalog_json: str, raw_plan: str) -> str:
    catalog = json.loads(catalog_json)
    try:
        plan = resolve_data_plan(_parse_json_object(raw_plan), catalog)
    except Exception:
        plan = fallback_data_plan(catalog, reason="android_model_planner_fallback")
    return json.dumps(plan, ensure_ascii=False, separators=(",", ":"))


def evidence_from_sqlite(database_path: str, plan_json: str) -> str:
    plan = json.loads(plan_json)
    store = AndroidPlannerStore(database_path)
    try:
        snapshot, _period = build_planned_snapshot(store, plan)
        packet = ensure_compact_evidence(snapshot)
        packet["retrieval"] = {
            "mode": "ai_planned",
            "planner_version": plan.get("planner_version"),
            "selected_data_types": plan.get("data_types") or [],
            "days": plan.get("days"),
            "start_date": plan.get("start_date"),
            "end_date": plan.get("end_date"),
            "detail": plan.get("detail"),
            "reason": plan.get("reason"),
        }
        return json.dumps(packet, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
