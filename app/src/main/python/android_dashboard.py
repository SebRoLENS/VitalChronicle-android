"""Android-only dashboard presentation adapters."""
from __future__ import annotations

import json
import math
import statistics
from datetime import datetime, timedelta
from typing import Any

from google_health_viewer.analysis import build_daily_progress_snapshot
from mobile_bridge import SQLiteStore


class _HeartRateCompatibleStore(SQLiteStore):
    """Expose Android five-minute rollups in the canonical shared-core shape.

    Google Health rollups use ``heartRate.beatsPerMinuteAvg``. Newer Android
    syncs also persist a compatibility ``beatsPerMinute`` field, but existing
    local rollup rows may predate that augmentation. The shared core and the
    Android fallback both use the same store, so normalize the read view once
    here instead of maintaining two independent heart-rate implementations.
    The SQLite payload itself is intentionally left untouched.
    """

    def list_records(
        self,
        data_type: str,
        start: str | None = None,
        end: str | None = None,
        limit: int = 20000,
        newest: bool = False,
    ) -> list[dict[str, Any]]:
        records = super().list_records(data_type, start, end, limit, newest)
        if data_type != "heart-rate":
            return records

        for record in records:
            payload = record.get("payload")
            if not isinstance(payload, dict):
                continue
            heart_rate = payload.get("heartRate")
            if not isinstance(heart_rate, dict) or "beatsPerMinute" in heart_rate:
                continue
            average = heart_rate.get("beatsPerMinuteAvg")
            try:
                value = float(average)
            except (TypeError, ValueError):
                continue
            if math.isfinite(value):
                heart_rate["beatsPerMinute"] = value
        return records


def _finite_number(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _iso_timestamp(value: Any) -> float | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(text).timestamp()
    except (TypeError, ValueError, OverflowError):
        return None


def _civil_date(value: Any):
    if not isinstance(value, dict):
        return None
    date = value.get("date") if isinstance(value.get("date"), dict) else value
    try:
        return datetime(
            int(date.get("year")),
            int(date.get("month")),
            int(date.get("day")),
        ).date()
    except (AttributeError, TypeError, ValueError):
        return None


def _heart_rate_rollup_point(
    record: dict[str, Any],
    reference_day,
) -> tuple[float, float] | None:
    """Read one heart-rate point without relying on shared-core metric parsing.

    Google Health sample records use ``heartRate.sampleTime.physicalTime`` while
    ``dataPoints:rollUp`` returns its interval as top-level ``startTime`` /
    ``endTime``. Older Android rows therefore legitimately have no canonical
    ``start_time`` in SQLite. Read both representations so an upstream parser
    change can never make the Android daily panel disappear again.
    """
    payload = record.get("payload")
    if not isinstance(payload, dict):
        return None
    heart_rate = payload.get("heartRate")
    if not isinstance(heart_rate, dict):
        return None

    value = _finite_number(heart_rate.get("beatsPerMinuteAvg"))
    if value is None:
        value = _finite_number(heart_rate.get("beatsPerMinute"))
    if value is None or not 20 <= value <= 250:
        return None

    interval = heart_rate.get("interval") if isinstance(heart_rate.get("interval"), dict) else {}
    sample_time = heart_rate.get("sampleTime") if isinstance(heart_rate.get("sampleTime"), dict) else {}

    civil_candidates = (
        payload.get("civilStartTime"),
        interval.get("civilStartTime"),
        sample_time.get("civilTime"),
    )
    explicit_civil_day = next(
        (day for day in (_civil_date(candidate) for candidate in civil_candidates) if day is not None),
        None,
    )

    timestamp_candidates = (
        payload.get("startTime"),
        record.get("start_time"),
        interval.get("startTime"),
        sample_time.get("physicalTime"),
        payload.get("endTime"),
        record.get("end_time"),
        interval.get("endTime"),
    )
    timestamp = next(
        (ts for ts in (_iso_timestamp(candidate) for candidate in timestamp_candidates) if ts is not None),
        None,
    )
    if timestamp is None:
        return None

    if explicit_civil_day is not None:
        if explicit_civil_day != reference_day:
            return None
    elif datetime.fromtimestamp(timestamp).date() != reference_day:
        return None

    return timestamp, value


def _heart_rate_today_from_store(
    store: SQLiteStore,
    reference_day,
) -> dict[str, Any] | None:
    """Build today's heart-rate card directly from Android's local rollups.

    The read intentionally does not apply a SQL time filter. Historical Android
    rollup rows can have ``start_time`` NULL because Google exposes the rollup
    interval at top level, whereas canonical sample records keep time inside
    ``heartRate.sampleTime``. The retained heart-rate archive is only 15 days,
    so reading the bounded stream and filtering by the actual payload timestamp
    is cheap and, more importantly, works for both old and new local databases.
    """
    records = store.list_records("heart-rate", limit=10000)
    if not records:
        return None

    points = [
        point
        for record in records
        if (point := _heart_rate_rollup_point(record, reference_day)) is not None
    ]
    if not points:
        return None

    points.sort(key=lambda item: item[0])
    values = [value for _timestamp, value in points]
    return {
        "data_type": "heart-rate-today",
        "label": "Today's heart rate",
        "metric": "5-minute average",
        "unit": "bpm",
        "completion": False,
        "value_date": reference_day.isoformat(),
        "latest_available": False,
        "current": points[-1][1],
        "baseline": None,
        "percentage": None,
        "delta_percent": None,
        "days_used": 0,
        "window_days": 0,
        "heart_day_points": points,
        "heart_day_smoothed": points,
        "heart_smoothing_minutes": 5,
        "heart_day_date": reference_day.isoformat(),
        "heart_day_min": min(values),
        "heart_day_max": max(values),
        "heart_day_mean": statistics.fmean(values),
        "heart_day_sample_count": len(points),
    }


def _insert_heart_metric(metrics: list[Any], heart_metric: dict[str, Any]) -> None:
    for index, metric in enumerate(metrics):
        if isinstance(metric, dict) and metric.get("data_type") == "daily-resting-heart-rate":
            metrics.insert(index + 1, heart_metric)
            return
    metrics.append(heart_metric)


def _five_minute_heart_rate(
    snapshot: dict[str, Any],
    store: SQLiteStore,
    reference_day,
) -> dict[str, Any]:
    """Render today's server-side five-minute heart-rate averages directly.

    Android downloads heart rate through Google Health's rollUp endpoint with a
    300-second window. The shared core therefore already receives one averaged
    point per five-minute interval; applying another local smoothing pass would
    blur the data unnecessarily and misrepresent its real temporal resolution.

    If the shared core does not emit a usable ``heart-rate-today`` metric,
    reconstruct it directly from Android's bounded local archive. This path does
    not depend on shared-core metric discovery or timestamp extraction.
    """
    metrics = snapshot.setdefault("metrics", [])
    heart_metric = next(
        (
            metric
            for metric in metrics
            if isinstance(metric, dict) and metric.get("data_type") == "heart-rate-today"
        ),
        None,
    )

    clean_points: list[tuple[float, float]] = []
    if heart_metric is not None:
        points = heart_metric.get("heart_day_points") or []
        for point in points:
            if isinstance(point, (list, tuple)) and len(point) >= 2:
                try:
                    timestamp = float(point[0])
                    value = float(point[1])
                except (TypeError, ValueError):
                    continue
                if math.isfinite(timestamp) and math.isfinite(value) and 20 <= value <= 250:
                    clean_points.append((timestamp, value))

    if not clean_points:
        fallback = _heart_rate_today_from_store(store, reference_day)
        if fallback is None:
            return snapshot
        if heart_metric is None:
            _insert_heart_metric(metrics, fallback)
            return snapshot
        heart_metric.update(fallback)
        clean_points = list(fallback["heart_day_smoothed"])

    clean_points.sort(key=lambda item: item[0])
    values = [value for _timestamp, value in clean_points]
    heart_metric["heart_day_smoothed"] = clean_points
    heart_metric["heart_smoothing_minutes"] = 5
    heart_metric["heart_day_min"] = min(values)
    heart_metric["heart_day_max"] = max(values)
    heart_metric["heart_day_mean"] = statistics.fmean(values)
    heart_metric["heart_day_sample_count"] = len(clean_points)
    heart_metric["current"] = clean_points[-1][1]
    heart_metric["metric"] = "5-minute average"
    heart_metric.setdefault("unit", "bpm")
    return snapshot


def dashboard_from_sqlite(database_path: str, reference_day: str | None = None) -> str:
    store = _HeartRateCompatibleStore(database_path)
    try:
        day = datetime.fromisoformat(reference_day).date() if reference_day else datetime.now().date()
        snapshot = build_daily_progress_snapshot(store, day)
        snapshot = _five_minute_heart_rate(snapshot, store, day)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
