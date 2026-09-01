"""Android-only dashboard presentation adapters."""
from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from google_health_viewer.analysis import build_daily_progress_snapshot
from mobile_bridge import SQLiteStore


def _five_minute_heart_rate(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Render today's server-side five-minute heart-rate averages directly.

    Android downloads heart rate through Google Health's rollUp endpoint with a
    300-second window. The shared core therefore already receives one averaged
    point per five-minute interval; applying another local smoothing pass would
    blur the data unnecessarily and misrepresent its real temporal resolution.
    """
    for metric in snapshot.get("metrics", []):
        if not isinstance(metric, dict) or metric.get("data_type") != "heart-rate-today":
            continue
        points = metric.get("heart_day_points") or []
        clean_points: list[tuple[float, float]] = []
        for point in points:
            if isinstance(point, (list, tuple)) and len(point) >= 2:
                try:
                    clean_points.append((float(point[0]), float(point[1])))
                except (TypeError, ValueError):
                    continue
        metric["heart_day_smoothed"] = clean_points
        metric["heart_smoothing_minutes"] = 5
        metric["metric"] = "5-minute average"
    return snapshot


def dashboard_from_sqlite(database_path: str, reference_day: str | None = None) -> str:
    store = SQLiteStore(database_path)
    try:
        day = datetime.fromisoformat(reference_day).date() if reference_day else None
        snapshot = build_daily_progress_snapshot(store, day)
        snapshot = _five_minute_heart_rate(snapshot)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
