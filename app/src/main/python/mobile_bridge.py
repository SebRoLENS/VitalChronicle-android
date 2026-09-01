"""Thin Android adapter around VitalChronicle's shared deterministic Python core."""
from __future__ import annotations

import hashlib
import json
import math
import re
import sqlite3
import statistics
import unicodedata
from collections import defaultdict
from datetime import datetime, timedelta
from typing import Any

from google_health_viewer.ai_insights import build_ai_ready_snapshot
from google_health_viewer.ai_pipeline import ensure_compact_evidence
from google_health_viewer.analysis import (
    available_metrics,
    build_daily_progress_snapshot,
    display_points,
    duration_hours,
    raw_points,
    visual_profile,
)
from google_health_viewer.constants import DATA_TYPES
from google_health_viewer.utils import extract_source, extract_times, parse_timestamp


# Android Nano retrieval targets. These are evidence-only estimates: ML Kit performs
# the authoritative count on the complete request (system + question + evidence).
NANO_EVIDENCE_TARGETS = {
    "specific_relation": 700,
    "specific": 900,
    "domain": 1300,
    "general": 1800,
}
NANO_ABSOLUTE_EVIDENCE_LIMIT = 2200
ASSOCIATION_MIN_PAIRED_DAYS = 10
ASSOCIATION_REPORTING_THRESHOLD = 0.4


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
    """Query Android's local archive lazily instead of copying it through JNI/JSON."""

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
    """Conservative JSON estimate; ML Kit performs the authoritative count later."""
    compact = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return max(1, math.ceil(len(compact) / 3.0))


def _normalise_text(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.casefold())
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return " ".join(value.split())


# Concepts are ordered so a more specific phrase (e.g. resting heart rate) wins
# before a generic one (heart rate). Each concept resolves to one preferred data
# type for retrieval, avoiding duplicate raw/daily representations of the same idea.
QUESTION_CONCEPTS: tuple[tuple[str, tuple[str, ...], tuple[str, ...]], ...] = (
    (
        "resting_heart_rate",
        ("daily-resting-heart-rate",),
        (
            "resting heart rate",
            "resting hr",
            "heart rate at rest",
            "frequenza cardiaca a riposo",
            "battito a riposo",
            "fc a riposo",
        ),
    ),
    (
        "hrv",
        ("daily-heart-rate-variability", "heart-rate-variability"),
        (
            "hrv",
            "heart rate variability",
            "heart-rate variability",
            "variabilita cardiaca",
            "variabilita della frequenza cardiaca",
        ),
    ),
    (
        "active_energy",
        ("active-energy-burned",),
        (
            "active calories",
            "active calorie",
            "active energy",
            "calorie attive",
            "energia attiva",
            "calorie bruciate attive",
        ),
    ),
    (
        "total_calories",
        ("total-calories",),
        ("total calories", "calorie totali", "energia totale"),
    ),
    (
        "heart_rate",
        ("daily-resting-heart-rate", "heart-rate"),
        ("heart rate", "battito cardiaco", "frequenza cardiaca", "bpm"),
    ),
    (
        "sleep",
        ("sleep",),
        ("sleep", "sonno", "dormito", "dormire", "sleep duration", "durata sonno"),
    ),
    (
        "steps",
        ("steps",),
        ("steps", "step count", "passi", "numero passi"),
    ),
    (
        "distance",
        ("distance",),
        ("distance", "distanza", "chilometri", "kilometers", "km"),
    ),
    (
        "active_minutes",
        ("active-minutes",),
        ("active minutes", "minuti attivi"),
    ),
    (
        "zone_minutes",
        ("active-zone-minutes",),
        ("active zone minutes", "zone minutes", "minuti zona attiva", "minuti in zona"),
    ),
    (
        "oxygen",
        ("daily-oxygen-saturation", "oxygen-saturation"),
        ("spo2", "oxygen saturation", "saturazione ossigeno", "saturazione"),
    ),
    (
        "respiratory_rate",
        ("daily-respiratory-rate", "respiratory-rate-sleep-summary"),
        ("respiratory rate", "breathing rate", "frequenza respiratoria", "respirazione"),
    ),
    (
        "temperature",
        ("daily-sleep-temperature-derivations", "core-body-temperature"),
        ("temperature", "temperatura", "sleep temperature", "temperatura sonno"),
    ),
    (
        "vo2",
        ("daily-vo2-max", "vo2-max", "run-vo2-max"),
        ("vo2", "vo2 max", "vo2max"),
    ),
    (
        "weight",
        ("weight",),
        ("weight", "peso"),
    ),
    (
        "body_fat",
        ("body-fat",),
        ("body fat", "massa grassa", "grasso corporeo"),
    ),
    (
        "exercise",
        ("exercise",),
        ("exercise", "workout", "workouts", "allenamento", "allenamenti", "esercizio"),
    ),
    (
        "nutrition",
        ("nutrition-log",),
        ("nutrition", "food", "alimentazione", "nutrizione", "cibo"),
    ),
    (
        "hydration",
        ("hydration-log",),
        ("hydration", "water", "idratazione", "acqua"),
    ),
)

DOMAIN_ALIASES = {
    "activity": ("activity", "attivita", "fitness", "movimento"),
    "sleep": ("sleep", "sonno"),
    "heart": ("heart", "cardiac", "cuore", "cardiaco", "cardiaca"),
    "vitals": ("vitals", "parametri vitali", "vital signs"),
    "weight": ("weight", "peso", "body composition", "composizione corporea"),
    "workouts": ("workouts", "workout", "exercise", "allenamenti", "allenamento"),
    "nutrition": ("nutrition", "food", "hydration", "alimentazione", "nutrizione", "idratazione"),
}

RELATION_WORDS = (
    "correlation", "correlazione", "correlato", "correlata", "relationship",
    "relation", "relazione", "association", "associazione", "legame", "linked",
    "collegato", "collegata", "influenza", "influence", "affect", "causa", "cause",
)
TREND_WORDS = (
    "trend", "andamento", "evoluzione", "cambiamento", "changed", "change",
    "aument", "dimin", "improv", "peggior",
)
ANOMALY_WORDS = ("anomaly", "anomal", "outlier", "insolito", "insolita", "strano", "strana")
COMPARISON_WORDS = ("compare", "comparison", "confront", "rispetto", "differenza", "difference")
TODAY_WORDS = ("today", "oggi", "current day", "giorno corrente", "adesso", "ora")
GENERAL_WORDS = (
    "overall", "overview", "general", "summary", "summarize", "riassunto", "generale",
    "panoramica", "most meaningful", "pattern", "patterns", "cosa noti", "come sto",
)


def _contains_phrase(text: str, phrase: str) -> bool:
    normalised = _normalise_text(phrase)
    return f" {normalised} " in f" {text} "


def _contains_any(text: str, phrases: tuple[str, ...]) -> bool:
    # A few intent words are stems ("aument", "dimin", ...), so allow token prefix.
    tokens = text.split()
    for phrase in phrases:
        normalised = _normalise_text(phrase)
        if " " in normalised:
            if _contains_phrase(text, normalised):
                return True
        elif any(token.startswith(normalised) for token in tokens):
            return True
    return False


def _all_compact_metrics(compact: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for domain, metrics in (compact.get("domains") or {}).items():
        if not isinstance(metrics, list):
            continue
        for metric in metrics:
            if isinstance(metric, dict):
                row = dict(metric)
                row.setdefault("domain", str(domain))
                result.append(row)
    return result


def _question_selection(
    question: str,
    compact: dict[str, Any],
) -> tuple[list[str], list[str], list[str], str]:
    text = _normalise_text(question)
    metrics = _all_compact_metrics(compact)
    available_types = {str(metric.get("data_type", "")) for metric in metrics}
    type_domain = {
        str(metric.get("data_type", "")): str(metric.get("domain", "other"))
        for metric in metrics
    }
    today_intent = _contains_any(text, TODAY_WORDS)

    mentioned: list[tuple[int, str, str]] = []
    for concept, preferred_types, aliases in QUESTION_CONCEPTS:
        best_position: int | None = None
        for alias in aliases:
            alias_n = _normalise_text(alias)
            position = f" {text} ".find(f" {alias_n} ")
            if position >= 0 and (best_position is None or position < best_position):
                best_position = position
        if best_position is None:
            continue
        candidates = list(preferred_types)
        if concept == "heart_rate" and today_intent:
            candidates = ["heart-rate", "daily-resting-heart-rate"]
        chosen = next((data_type for data_type in candidates if data_type in available_types), None)
        if chosen:
            mentioned.append((best_position, chosen, concept))

    # Also recognise exact labels / API keys not already covered by aliases.
    for metric in metrics:
        data_type = str(metric.get("data_type", ""))
        label = str(metric.get("label", ""))
        if not data_type or any(item[1] == data_type for item in mentioned):
            continue
        aliases = (data_type.replace("-", " "), label)
        positions = [
            f" {text} ".find(f" {_normalise_text(alias)} ")
            for alias in aliases
            if _normalise_text(alias)
        ]
        positions = [position for position in positions if position >= 0]
        if positions:
            mentioned.append((min(positions), data_type, data_type))

    mentioned.sort(key=lambda item: item[0])
    selected_types: list[str] = []
    for _position, data_type, _concept in mentioned:
        if data_type not in selected_types:
            selected_types.append(data_type)

    selected_domains: list[str] = []
    for domain, aliases in DOMAIN_ALIASES.items():
        if any(_contains_phrase(text, alias) for alias in aliases):
            if domain not in selected_domains:
                selected_domains.append(domain)
    for data_type in selected_types:
        domain = type_domain.get(data_type)
        if domain and domain not in selected_domains:
            selected_domains.append(domain)

    intents: list[str] = []
    if _contains_any(text, RELATION_WORDS): intents.append("association")
    if _contains_any(text, TREND_WORDS): intents.append("trend")
    if _contains_any(text, ANOMALY_WORDS): intents.append("anomaly")
    if _contains_any(text, COMPARISON_WORDS): intents.append("comparison")
    if today_intent: intents.append("today")
    if _contains_any(text, GENERAL_WORDS): intents.append("general")

    if "association" in intents and len(selected_types) >= 2:
        mode = "specific_relation"
    elif selected_types:
        mode = "specific" if len(selected_types) <= 3 else "domain"
    elif selected_domains:
        mode = "domain"
    else:
        mode = "general"
    return selected_types, selected_domains, intents, mode


def _nano_metric(metric: dict[str, Any], *, lean: bool = False) -> dict[str, Any]:
    result = _selected(
        metric,
        ("data_type", "label", "metric", "unit", "summary_scope", "data_role", "domain"),
    )
    summary = _selected(
        metric.get("summary"),
        ("count", "latest", "mean", "median", "minimum", "maximum", "trend_percent", "anomaly_count"),
    )
    if summary:
        result["summary"] = summary

    coverage = _selected(
        metric.get("coverage"),
        ("observed_calendar_days", "coverage_percent", "records_considered", "missing_calendar_days", "longest_missing_run_days"),
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
            ("window_days", "recent_days", "recent_mean", "previous_days", "previous_mean", "percent_change", "standardized_change"),
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
                key: _selected(value, ("samples", "observed_days", "mean", "standard_deviation"))
                for key, value in baselines.items()
                if key in {"7_days", "28_days", "90_days"} and isinstance(value, dict)
            }
            compact_baselines = {key: value for key, value in compact_baselines.items() if value}
            if compact_baselines:
                compact_evidence["baselines"] = compact_baselines
        if compact_evidence:
            result["evidence"] = compact_evidence
    return result


def _coverage_for_types(snapshot: dict[str, Any], selected_types: list[str]) -> dict[str, Any]:
    coverage = snapshot.get("requested_interval_coverage") or {}
    result = _selected(
        coverage,
        (
            "requested_start", "requested_end", "requested_calendar_days", "first_measurement_date",
            "last_measurement_date", "calendar_days_with_measurements",
            "calendar_days_with_measurements_percent", "scope_is_partially_observed",
        ),
    )
    rows = [
        _selected(
            item,
            (
                "data_type", "label", "observed_calendar_days", "coverage_percent",
                "records_considered", "missing_calendar_days", "longest_missing_run_days", "data_role",
            ),
        )
        for item in coverage.get("metrics", [])
        if isinstance(item, dict)
        and (not selected_types or str(item.get("data_type", "")) in selected_types)
    ]
    if rows:
        result["metrics"] = rows
    notice = coverage.get("coverage_notice")
    if notice:
        result["notice"] = notice
    return result


def _daily_values_for_type(
    store: SQLiteStore,
    data_type: str,
    start: str,
    end: str,
) -> dict[Any, float]:
    records = store.list_records(data_type, start, end, limit=30000)
    available = available_metrics(records, data_type)
    if not available:
        return {}
    metric = available[0]
    profile = visual_profile(data_type, metric)
    if data_type == "sleep":
        points: list[tuple[float, float]] = []
        for record in records:
            timestamp = parse_timestamp(record.get("end_time") or record.get("start_time"))
            value = duration_hours(record)
            if timestamp is not None and value is not None:
                points.append((timestamp, value))
        aggregation = "mean"
    else:
        points = display_points(raw_points(records, metric), profile)
        aggregation = "sum" if profile.aggregation == "sum" else "mean"

    grouped: dict[Any, list[float]] = defaultdict(list)
    for timestamp, value in points:
        if math.isfinite(value):
            grouped[datetime.fromtimestamp(timestamp).date()].append(float(value))  # noqa: DTZ006
    return {
        day: sum(values) if aggregation == "sum" else statistics.fmean(values)
        for day, values in grouped.items()
        if values
    }


def _pearson(pairs: list[tuple[float, float]]) -> float | None:
    if len(pairs) < ASSOCIATION_MIN_PAIRED_DAYS:
        return None
    left = [pair[0] for pair in pairs]
    right = [pair[1] for pair in pairs]
    left_mean = statistics.fmean(left)
    right_mean = statistics.fmean(right)
    numerator = sum((x - left_mean) * (y - right_mean) for x, y in pairs)
    denominator = math.sqrt(
        sum((x - left_mean) ** 2 for x in left) * sum((y - right_mean) ** 2 for y in right)
    )
    return numerator / denominator if denominator > 1e-12 else None


def _association_result(pairs: list[tuple[float, float]], timing: str) -> dict[str, Any]:
    paired_days = len(pairs)
    result: dict[str, Any] = {
        "timing": timing,
        "paired_days": paired_days,
        "minimum_required": ASSOCIATION_MIN_PAIRED_DAYS,
        "reporting_abs_r_threshold": ASSOCIATION_REPORTING_THRESHOLD,
    }
    if paired_days < ASSOCIATION_MIN_PAIRED_DAYS:
        result["result"] = "not_calculated"
        result["reason"] = "insufficient_overlapping_days"
        return result
    r = _pearson(pairs)
    if r is None:
        result["result"] = "not_calculated"
        result["reason"] = "insufficient_variation"
        return result
    result["r"] = round(r, 3)
    if abs(r) < ASSOCIATION_REPORTING_THRESHOLD:
        result["result"] = "below_reporting_threshold"
        result["reason"] = "absolute_correlation_below_0.4"
    else:
        result["result"] = "association_detected"
        result["reliability_score"] = round(abs(r) * min(1.0, paired_days / 30.0), 3)
    return result


def _relation_checks(
    store: SQLiteStore,
    selected_types: list[str],
    start: str,
    end: str,
) -> list[dict[str, Any]]:
    if len(selected_types) < 2:
        return []
    # A specific question should normally resolve to two concepts. Bound the work
    # for ambiguous questions rather than generating every possible pair.
    chosen = selected_types[:3]
    labels = {spec.key: spec.label for spec in DATA_TYPES}
    daily = {data_type: _daily_values_for_type(store, data_type, start, end) for data_type in chosen}
    result: list[dict[str, Any]] = []
    for left_index, left_type in enumerate(chosen):
        for right_type in chosen[left_index + 1 :]:
            left = daily.get(left_type, {})
            right = daily.get(right_type, {})
            shared = sorted(set(left) & set(right))
            same_pairs = [(left[day], right[day]) for day in shared]
            left_lag_pairs = [
                (value, right[day + timedelta(days=1)])
                for day, value in left.items()
                if day + timedelta(days=1) in right
            ]
            right_lag_pairs = [
                (value, left[day + timedelta(days=1)])
                for day, value in right.items()
                if day + timedelta(days=1) in left
            ]
            result.append(
                {
                    "left_data_type": left_type,
                    "left": labels.get(left_type, left_type),
                    "right_data_type": right_type,
                    "right": labels.get(right_type, right_type),
                    "same_day": _association_result(same_pairs, "same_day"),
                    "left_precedes_right_by_one_day": _association_result(left_lag_pairs, "left_precedes_right_by_one_day"),
                    "right_precedes_left_by_one_day": _association_result(right_lag_pairs, "right_precedes_left_by_one_day"),
                    "interpretation_rule": "association_only_not_causation",
                    "absence_rule": "an unreported association is not evidence that no relationship exists",
                }
            )
    return result


def _select_metrics(
    compact: dict[str, Any],
    selected_types: list[str],
    selected_domains: list[str],
    mode: str,
    *,
    lean: bool = False,
) -> list[dict[str, Any]]:
    metrics = _all_compact_metrics(compact)
    if mode in {"specific", "specific_relation"}:
        rows = [metric for metric in metrics if str(metric.get("data_type", "")) in selected_types]
        maximum = 3
    elif mode == "domain":
        rows = [metric for metric in metrics if str(metric.get("domain", "")) in selected_domains]
        maximum = 4 if lean else 6
    else:
        # Preserve breadth for a general analysis while bounding each domain.
        per_domain = 1 if lean else 2
        grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for metric in metrics:
            grouped[str(metric.get("domain", "other"))].append(metric)
        rows = []
        for domain in ("activity", "sleep", "heart", "vitals", "weight", "workouts", "nutrition", "other"):
            rows.extend(grouped.get(domain, [])[:per_domain])
        maximum = len(rows)
    return [_nano_metric(metric, lean=lean) for metric in rows[:maximum]]


def _select_insights(
    compact: dict[str, Any],
    selected_types: list[str],
    mode: str,
    *,
    lean: bool = False,
) -> list[dict[str, Any]]:
    raw = [item for item in compact.get("strongest_evidence", []) if isinstance(item, dict)]
    if selected_types:
        wanted = set(selected_types)
        relevant = [
            item for item in raw
            if wanted.intersection(str(value) for value in (item.get("data_types") or []))
        ]
    else:
        relevant = raw
    limit = {
        "specific_relation": 2 if lean else 4,
        "specific": 3 if lean else 5,
        "domain": 4 if lean else 6,
        "general": 5 if lean else 8,
    }[mode]
    return [
        _selected(item, ("evidence_id", "kind", "data_types", "headline", "relevance_score", "confidence", "caveat"))
        for item in relevant[:limit]
    ]


def _select_associations(
    compact: dict[str, Any],
    selected_types: list[str],
    mode: str,
    *,
    lean: bool = False,
) -> list[dict[str, Any]]:
    raw = [item for item in compact.get("associations", []) if isinstance(item, dict)]
    wanted = set(selected_types)
    if mode == "specific_relation" and len(wanted) >= 2:
        relevant = [
            item for item in raw
            if {str(item.get("left_data_type", "")), str(item.get("right_data_type", ""))}.issubset(wanted)
        ]
    elif wanted:
        relevant = [
            item for item in raw
            if str(item.get("left_data_type", "")) in wanted or str(item.get("right_data_type", "")) in wanted
        ]
    else:
        relevant = raw
    limit = 1 if lean else (2 if mode != "general" else 4)
    return [
        _selected(
            item,
            ("left", "right", "left_data_type", "right_data_type", "r", "paired_days", "timing", "reliability_score"),
        )
        for item in relevant[:limit]
    ]


def _build_retrieval_packet(
    snapshot: dict[str, Any],
    question: str,
    store: SQLiteStore | None = None,
    start: str | None = None,
    end: str | None = None,
    *,
    lean: bool = False,
) -> dict[str, Any]:
    compact = ensure_compact_evidence(snapshot)
    selected_types, selected_domains, intents, mode = _question_selection(question, compact)
    source_packet = compact.get("packet") or {}
    target = NANO_EVIDENCE_TARGETS[mode]

    packet: dict[str, Any] = {
        "packet": {
            "health_evidence_present": True,
            "pipeline_version": "android-question-retrieval-v1",
            "source_pipeline_version": source_packet.get("pipeline_version"),
            "analysis_scope": source_packet.get("analysis_scope"),
        },
        "retrieval": {
            "mode": mode,
            "intents": intents,
            "selected_data_types": selected_types,
            "selected_domains": selected_domains,
            "target_evidence_tokens": target,
            "absolute_evidence_limit": NANO_ABSOLUTE_EVIDENCE_LIMIT,
            "selection_is_deterministic": True,
        },
        "period": compact.get("period") or {},
        "observation": compact.get("observation") or {},
        "coverage": _coverage_for_types(snapshot, selected_types),
        "metrics": _select_metrics(compact, selected_types, selected_domains, mode, lean=lean),
        "insights": _select_insights(compact, selected_types, mode, lean=lean),
        "associations": _select_associations(compact, selected_types, mode, lean=lean),
        "rules": [
            "missing data are missing, never zero",
            "association does not imply causation",
            "an unreported association does not prove absence of a relationship",
            "today may be incomplete",
        ],
    }
    if mode == "specific_relation" and store is not None and start is not None and end is not None:
        packet["relation_checks"] = _relation_checks(store, selected_types, start, end)
    return packet


def _fit_retrieval_packet(
    snapshot: dict[str, Any],
    question: str,
    store: SQLiteStore | None = None,
    start: str | None = None,
    end: str | None = None,
) -> dict[str, Any]:
    packet = _build_retrieval_packet(snapshot, question, store, start, end, lean=False)
    mode = str((packet.get("retrieval") or {}).get("mode", "general"))
    target = NANO_EVIDENCE_TARGETS.get(mode, NANO_EVIDENCE_TARGETS["general"])

    if _estimated_tokens(packet) > target:
        packet = _build_retrieval_packet(snapshot, question, store, start, end, lean=True)

    # Progressive deterministic trimming. Relation checks and the relevant metrics
    # are preserved for specific questions; generic evidence is discarded first.
    while _estimated_tokens(packet) > target and len(packet.get("insights", [])) > 1:
        packet["insights"].pop()
    while _estimated_tokens(packet) > target and len(packet.get("associations", [])) > 1:
        packet["associations"].pop()
    while (
        _estimated_tokens(packet) > target
        and mode in {"domain", "general"}
        and len(packet.get("metrics", [])) > (2 if mode == "domain" else 5)
    ):
        packet["metrics"].pop()

    if _estimated_tokens(packet) > NANO_ABSOLUTE_EVIDENCE_LIMIT:
        # Hard safety limit: retain routing, coverage, directly relevant metrics and
        # relation diagnostics; remove optional narrative evidence first.
        packet["insights"] = packet.get("insights", [])[:1]
        packet["associations"] = packet.get("associations", [])[:1]
        packet["metrics"] = packet.get("metrics", [])[:3]
        packet["coverage"].pop("notice", None)

    estimate = _estimated_tokens(packet)
    packet["packet"]["estimated_tokens"] = estimate
    packet["packet"]["json_bytes"] = len(
        json.dumps(packet, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    )
    packet["packet"]["target_evidence_tokens"] = target
    packet["packet"]["absolute_evidence_limit"] = NANO_ABSOLUTE_EVIDENCE_LIMIT
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


def nano_evidence_from_sqlite(
    database_path: str,
    start: str,
    end: str,
    question: str,
) -> str:
    """Build only the evidence relevant to one Android Nano question.

    The rich deterministic snapshot is created and consumed entirely inside Python.
    Kotlin receives only the small retrieval packet, avoiding a full snapshot
    round-trip across the Python/JVM boundary.
    """
    store = SQLiteStore(database_path)
    try:
        snapshot = build_ai_ready_snapshot(store, start, end)
        packet = _fit_retrieval_packet(snapshot, question, store, start, end)
        return json.dumps(packet, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()


def compact_evidence(evidence_json: str) -> str:
    """Compatibility entry point: compact a rich snapshot as a general question."""
    snapshot = json.loads(evidence_json)
    compact = _fit_retrieval_packet(snapshot, "")
    return json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
