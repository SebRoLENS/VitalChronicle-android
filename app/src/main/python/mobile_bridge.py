"""Thin Android adapter around VitalChronicle's shared deterministic Python core."""
from __future__ import annotations

import hashlib
import json
import math
import sqlite3
from collections import defaultdict
from datetime import datetime
from typing import Any

from google_health_viewer.ai_insights import build_ai_ready_snapshot
from google_health_viewer.ai_pipeline import ensure_compact_evidence
from google_health_viewer.analysis import build_daily_progress_snapshot
from google_health_viewer.constants import DATA_TYPES
from google_health_viewer.utils import extract_source, extract_times


# Gemini Nano is an on-device model.  The canonical desktop compact packet can
# approach 4k JSON tokens, which is useful for larger local models but is too close
# to ML Kit Prompt API's complete-input limit and needlessly expensive on a phone.
# Android therefore applies one additional *presentation-only* compaction pass.
# The rich deterministic snapshot remains unchanged and available for fallback/UI.
NANO_TARGET_EVIDENCE_TOKENS = 1400


class JsonStore:
    """Compatibility store for small JSON payloads."""

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


class SQLiteStore:
    """Query Android's local archive lazily instead of copying it through JNI/JSON.

    The shared desktop analysis functions only require ``list_records``. Reading
    SQLite directly lets them request one bounded data type at a time, avoiding
    the large SQLite -> Kotlin JSON -> Python JSON duplication which can exhaust
    the Android heap with dense heart-rate histories.
    """

    def __init__(self, database_path: str):
        self._connection = sqlite3.connect(database_path, timeout=10.0)
        self._connection.row_factory = sqlite3.Row
        self._connection.execute("PRAGMA query_only=ON")

    def close(self) -> None:
        self._connection.close()

    def list_records(
        self,
        data_type: str,
        start: str | None = None,
        end: str | None = None,
        limit: int = 20000,
        newest: bool = False,
    ) -> list[dict[str, Any]]:
        safe_limit = max(1, min(int(limit), 30000))
        clauses = ["data_type=?"]
        args: list[Any] = [data_type]
        if start:
            clauses.append("(start_time IS NULL OR start_time>=?)")
            args.append(start)
        if end:
            clauses.append("(start_time IS NULL OR start_time<?)")
            args.append(end)
        order = "DESC" if newest else "ASC"
        sql = f"""
            SELECT data_type,record_id,record_kind,start_time,end_time,source,payload
            FROM records
            WHERE {' AND '.join(clauses)}
            ORDER BY COALESCE(start_time,end_time,'') {order}
            LIMIT ?
        """
        args.append(safe_limit)
        rows = self._connection.execute(sql, args).fetchall()
        result = []
        for row in rows:
            try:
                payload = json.loads(row["payload"])
            except (TypeError, ValueError):
                payload = {}
            result.append(
                {
                    "data_type": row["data_type"],
                    "record_id": row["record_id"],
                    "record_kind": row["record_kind"],
                    "start_time": row["start_time"],
                    "end_time": row["end_time"],
                    "source": row["source"],
                    "payload": payload,
                }
            )
        if newest:
            result.reverse()
        return result


def _selected(source: Any, keys: tuple[str, ...]) -> dict[str, Any]:
    if not isinstance(source, dict):
        return {}
    return {key: source[key] for key in keys if key in source and source[key] is not None}


def _estimated_tokens(value: Any) -> int:
    """Conservative estimate; ML Kit performs the authoritative count later."""
    compact = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return max(1, math.ceil(len(compact) / 3.0))


def _nano_metric(metric: dict[str, Any], *, lean: bool = False) -> dict[str, Any]:
    result = _selected(metric, ("data_type", "label", "metric", "unit", "summary_scope", "data_role"))
    summary = _selected(
        metric.get("summary"),
        ("count", "latest", "mean", "median", "minimum", "maximum", "trend_percent", "anomaly_count"),
    )
    if summary:
        result["summary"] = summary

    coverage = _selected(
        metric.get("coverage"),
        ("observed_calendar_days", "coverage_percent", "missing_calendar_days", "longest_missing_run_days"),
    )
    if coverage:
        result["coverage"] = coverage

    today = _selected(
        metric.get("today"),
        ("status", "today_so_far", "same_time_mean", "same_time_days", "same_time_percent"),
    )
    if today:
        result["today"] = today

    if not lean:
        evidence = metric.get("evidence") or {}
        compact_evidence: dict[str, Any] = {}
        matched = _selected(
            evidence.get("matched_change"),
            ("window_days", "recent_mean", "previous_mean", "percent_change", "standardized_change"),
        )
        if matched:
            compact_evidence["change"] = matched
        trend = _selected(
            evidence.get("trend"),
            ("window_days", "observed_days", "direction", "percent_per_week", "r_squared"),
        )
        if trend:
            compact_evidence["trend"] = trend
        anomaly = _selected(
            evidence.get("anomaly"),
            ("window_days", "baseline_samples", "baseline_median", "latest_date", "latest_robust_z"),
        )
        if anomaly:
            compact_evidence["anomaly"] = anomaly
        baselines = evidence.get("personal_baselines")
        if isinstance(baselines, dict):
            compact_baselines = {
                key: _selected(value, ("samples", "mean", "standard_deviation"))
                for key, value in baselines.items()
                if key in {"7_days", "28_days", "90_days"} and isinstance(value, dict)
            }
            compact_baselines = {key: value for key, value in compact_baselines.items() if value}
            if compact_baselines:
                compact_evidence["baselines"] = compact_baselines
        if compact_evidence:
            result["evidence"] = compact_evidence
    return result


def _nano_coverage(coverage: Any, *, lean: bool = False) -> dict[str, Any]:
    result = _selected(
        coverage,
        (
            "requested_start",
            "requested_end",
            "requested_calendar_days",
            "first_measurement_date",
            "last_measurement_date",
            "calendar_days_with_measurements_percent",
            "longest_measurement_gap_days",
            "starts_after_requested_start",
            "ends_before_requested_end",
            "scope_is_partially_observed",
        ),
    )
    limited = coverage.get("limited_daily_metrics") if isinstance(coverage, dict) else None
    if isinstance(limited, list) and limited:
        maximum = 4 if lean else 8
        result["limited_metrics"] = [
            _selected(item, ("data_type", "coverage_percent", "missing_calendar_days", "longest_missing_run_days"))
            for item in limited[:maximum]
            if isinstance(item, dict)
        ]
    return result


def _nano_packet(compact: dict[str, Any], *, lean: bool = False) -> dict[str, Any]:
    source_packet = compact.get("packet") or {}
    domains: dict[str, list[dict[str, Any]]] = {}
    for domain, metrics in (compact.get("domains") or {}).items():
        if not isinstance(metrics, list):
            continue
        # Keep coverage across all available metric types, but only the compact
        # numerical evidence needed for language interpretation.
        limit = 2 if lean else 4
        rows = [_nano_metric(item, lean=lean) for item in metrics[:limit] if isinstance(item, dict)]
        if rows:
            domains[str(domain)] = rows

    evidence_limit = 4 if lean else 7
    strongest = []
    for item in (compact.get("strongest_evidence") or [])[:evidence_limit]:
        if not isinstance(item, dict):
            continue
        strongest.append(
            _selected(item, ("evidence_id", "kind", "data_types", "headline", "relevance_score", "confidence", "caveat"))
        )

    association_limit = 2 if lean else 3
    associations = [
        _selected(
            item,
            ("left", "right", "left_data_type", "right_data_type", "r", "paired_days", "timing", "reliability_score"),
        )
        for item in (compact.get("associations") or [])[:association_limit]
        if isinstance(item, dict)
    ]

    packet = {
        "packet": {
            "health_evidence_present": True,
            "pipeline_version": "android-nano-evidence-v1",
            "source_pipeline_version": source_packet.get("pipeline_version"),
            "analysis_scope": source_packet.get("analysis_scope"),
            "metric_count": source_packet.get("metric_count"),
        },
        "period": compact.get("period") or {},
        "observation": compact.get("observation") or {},
        "coverage": _nano_coverage(compact.get("coverage") or {}, lean=lean),
        "domains": domains,
        "strongest_evidence": strongest,
        "associations": associations,
        "archive_quality": compact.get("archive_quality") or {},
    }
    return packet


def _build_nano_evidence(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Return a small evidence packet suitable for latency-sensitive Nano inference."""
    canonical = ensure_compact_evidence(snapshot)
    packet = _nano_packet(canonical, lean=False)
    if _estimated_tokens(packet) > NANO_TARGET_EVIDENCE_TOKENS:
        packet = _nano_packet(canonical, lean=True)

    # Final safety pass: if unusually broad data still exceeds the phone target,
    # retain one representative metric per domain and only the strongest evidence.
    if _estimated_tokens(packet) > NANO_TARGET_EVIDENCE_TOKENS:
        for domain, metrics in list((packet.get("domains") or {}).items()):
            packet["domains"][domain] = metrics[:1]
        packet["strongest_evidence"] = (packet.get("strongest_evidence") or [])[:3]
        packet["associations"] = (packet.get("associations") or [])[:1]
        packet["coverage"].pop("limited_metrics", None)

    packet["packet"]["estimated_tokens"] = _estimated_tokens(packet)
    packet["packet"]["json_bytes"] = len(
        json.dumps(packet, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    )
    packet["packet"]["target_evidence_tokens"] = NANO_TARGET_EVIDENCE_TOKENS
    return packet


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
    day = datetime.fromisoformat(reference_day).date() if reference_day else None
    return json.dumps(build_daily_progress_snapshot(store, day), ensure_ascii=False)


def evidence(records_json: str, start: str, end: str) -> str:
    records = json.loads(records_json)
    snapshot = build_ai_ready_snapshot(JsonStore(records), start, end)
    return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))


def dashboard_from_sqlite(database_path: str, reference_day: str | None = None) -> str:
    store = SQLiteStore(database_path)
    try:
        day = datetime.fromisoformat(reference_day).date() if reference_day else None
        snapshot = build_daily_progress_snapshot(store, day)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()


def evidence_from_sqlite(database_path: str, start: str, end: str) -> str:
    store = SQLiteStore(database_path)
    try:
        snapshot = build_ai_ready_snapshot(store, start, end)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()


def compact_evidence(evidence_json: str) -> str:
    snapshot = json.loads(evidence_json)
    compact = _build_nano_evidence(snapshot)
    return json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
