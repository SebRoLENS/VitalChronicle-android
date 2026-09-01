"""Android-only dashboard presentation adapters."""
from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from google_health_viewer.analysis import build_daily_progress_snapshot, smooth_heart_rate_points
from mobile_bridge import SQLiteStore


def _one_minute_heart_rate(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Render today's heart-rate curve at one-minute resolution.

    The shared desktop snapshot currently exposes a 15-minute smoothed curve.
    Android keeps the same raw deterministic samples and replaces only the
    presentation series with one-minute median bins / one-minute smoothing.
    Stored heart-rate samples are never modified.
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
        metric["heart_day_smoothed"] = smooth_heart_rate_points(
            clean_points,
            bin_seconds=60,
            window_seconds=60,
        )
        metric["heart_smoothing_minutes"] = 1
    return snapshot


def dashboard_from_sqlite(database_path: str, reference_day: str | None = None) -> str:
    store = SQLiteStore(database_path)
    try:
        day = datetime.fromisoformat(reference_day).date() if reference_day else None
        snapshot = build_daily_progress_snapshot(store, day)
        snapshot = _one_minute_heart_rate(snapshot)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
