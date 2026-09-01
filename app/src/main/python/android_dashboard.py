"""Android-only dashboard presentation adapters."""
from __future__ import annotations

import json
import math
import statistics
from datetime import datetime, timedelta
from typing import Any

from google_health_viewer.analysis import (
    available_metrics,
    build_daily_progress_snapshot,
    display_points,
    raw_points,
    visual_profile,
)
from mobile_bridge import SQLiteStore


def _heart_rate_today_from_store(
    store: SQLiteStore,
    reference_day,
) -> dict[str, Any] | None:
    """Build today's heart-rate card directly from Android's local rollups.

    This is intentionally an Android-side fallback. The shared desktop core
    normally emits ``heart-rate-today`` itself, but the dashboard must not lose
    the card if an upstream core change temporarily stops creating that virtual
    metric. Android stores one server-side average per five-minute interval, so
    no additional smoothing is applied here.
    """
    records = store.list_records(
        "heart-rate",
        start=reference_day.isoformat(),
        end=(reference_day + timedelta(days=1)).isoformat(),
        limit=10000,
    )
    if not records:
        return None

    heart_metrics = available_metrics(records, "heart-rate")
    if not heart_metrics:
        return None

    profile = visual_profile("heart-rate", heart_metrics[0])
    source_points = display_points(raw_points(records, heart_metrics[0]), profile)
    points: list[tuple[float, float]] = []
    for point in source_points:
        if not isinstance(point, (list, tuple)) or len(point) < 2:
            continue
        try:
            timestamp = float(point[0])
            value = float(point[1])
        except (TypeError, ValueError):
            continue
        if math.isfinite(timestamp) and math.isfinite(value) and 20 <= value <= 250:
            points.append((timestamp, value))

    if not points:
        return None

    points.sort(key=lambda item: item[0])
    values = [value for _timestamp, value in points]
    return {
        "data_type": "heart-rate-today",
        "label": "Today's heart rate",
        "metric": "5-minute average",
        "unit": profile.unit,
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

    If the shared core does not emit its virtual ``heart-rate-today`` metric,
    reconstruct it from the local Android archive so the daily heart-rate panel
    remains available across shared-core updates.
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
    if heart_metric is None:
        heart_metric = _heart_rate_today_from_store(store, reference_day)
        if heart_metric is not None:
            metrics.append(heart_metric)
            return snapshot
        return snapshot

    points = heart_metric.get("heart_day_points") or []
    clean_points: list[tuple[float, float]] = []
    for point in points:
        if isinstance(point, (list, tuple)) and len(point) >= 2:
            try:
                timestamp = float(point[0])
                value = float(point[1])
            except (TypeError, ValueError):
                continue
            if math.isfinite(timestamp) and math.isfinite(value) and 20 <= value <= 250:
                clean_points.append((timestamp, value))

    if clean_points:
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
    return snapshot


def dashboard_from_sqlite(database_path: str, reference_day: str | None = None) -> str:
    store = SQLiteStore(database_path)
    try:
        day = datetime.fromisoformat(reference_day).date() if reference_day else datetime.now().date()
        snapshot = build_daily_progress_snapshot(store, day)
        snapshot = _five_minute_heart_rate(snapshot, store, day)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
