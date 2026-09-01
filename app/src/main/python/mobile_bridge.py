"""Thin Android adapter around VitalChronicle's shared deterministic Python core."""
from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from datetime import datetime
from typing import Any

from google_health_viewer.ai_insights import build_ai_ready_snapshot
from google_health_viewer.analysis import build_daily_progress_snapshot
from google_health_viewer.constants import DATA_TYPES
from google_health_viewer.utils import extract_source, extract_times


class JsonStore:
    def __init__(self, records: list[dict[str, Any]]):
        self._by_type: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for record in records:
            self._by_type[str(record.get("data_type", ""))].append(record)

    def list_records(
        self,
        data_type: str,
        start: str | None = None,
        end: str | None = None,
        limit: int = 20000,
        newest: bool = False,
    ) -> list[dict[str, Any]]:
        rows = list(self._by_type.get(data_type, ()))
        if start:
            rows = [r for r in rows if not r.get("start_time") or r["start_time"] >= start]
        if end:
            rows = [r for r in rows if not r.get("start_time") or r["start_time"] < end]
        rows.sort(key=lambda r: r.get("start_time") or "", reverse=newest)
        rows = rows[:limit]
        if newest:
            rows.reverse()
        return rows


def data_type_specs() -> str:
    return json.dumps(
        [
            {
                "key": spec.key,
                "label": spec.label,
                "category": spec.category,
                "scope": spec.scope,
                "record_type": spec.record_type,
                "operation": spec.operation,
                "filter_field": spec.filter_field,
                "auto_sync": spec.auto_sync,
            }
            for spec in DATA_TYPES
        ],
        ensure_ascii=False,
    )


def normalize_records(data_type: str, payload_json: str, record_kind: str = "data_point") -> str:
    payloads = json.loads(payload_json)
    normalized = []
    for payload in payloads:
        start, end = extract_times(payload)
        if payload.get("name"):
            record_id = str(payload["name"])
        elif record_kind == "daily_rollup" and (start or end):
            record_id = f"{data_type}:rollup:{start or ''}:{end or ''}"
        else:
            raw = json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")
            record_id = f"{data_type}:{hashlib.sha256(raw).hexdigest()}"
        normalized.append(
            {
                "data_type": data_type,
                "record_id": record_id,
                "record_kind": record_kind,
                "start_time": start,
                "end_time": end,
                "source": extract_source(payload),
                "payload": payload,
            }
        )
    return json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))


def dashboard(records_json: str, reference_day: str | None = None) -> str:
    records = json.loads(records_json)
    store = JsonStore(records)
    day = None
    if reference_day:
        day = datetime.fromisoformat(reference_day).date()
    return json.dumps(build_daily_progress_snapshot(store, day), ensure_ascii=False)


def evidence(records_json: str, start: str, end: str) -> str:
    records = json.loads(records_json)
    snapshot = build_ai_ready_snapshot(JsonStore(records), start, end)
    return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
