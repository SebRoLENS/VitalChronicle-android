"""Android dashboard adapter backed by VitalChronicle's shared analysis core."""
from __future__ import annotations

import json
from datetime import datetime

from google_health_viewer.analysis import build_daily_progress_snapshot
from mobile_bridge import SQLiteStore


def dashboard_from_sqlite(database_path: str, reference_day: str | None = None) -> str:
    """Build the Android overview with exactly the same core logic as desktop.

    Android stores Google Health heart rate as server-side five-minute roll-ups,
    while desktop may store native samples. The shared core accepts both forms
    and normalizes both to the same five-minute-average dashboard series.
    """
    store = SQLiteStore(database_path)
    try:
        day = datetime.fromisoformat(reference_day).date() if reference_day else datetime.now().date()
        snapshot = build_daily_progress_snapshot(store, day)
        return json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
