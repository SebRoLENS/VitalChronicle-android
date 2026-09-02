from __future__ import annotations

import collections
import datetime
import math
import statistics
import typing

FIVE_MINUTE_SECONDS = 300


def _first_timestamp(value: typing.Any, analysis) -> float | None:
    raw = analysis._find_named_value(
        value,
        {"physicaltime", "starttime", "endtime", "time"},
    )
    return analysis.parse_timestamp(raw) if isinstance(raw, str) else None


def heart_rate_sample_points(
    records: list[dict[str, typing.Any]],
    analysis,
) -> list[tuple[float, float]]:
    """Read canonical heart-rate samples and Google Health roll-up averages.

    Desktop normally stores native samples, while Android stores Google Health
    five-minute roll-ups to keep the local archive bounded. Both representations
    are converted to the same timestamp/value series here before dashboard
    aggregation.
    """
    points: list[tuple[float, float]] = []
    for record in records:
        payload = record.get("payload")
        if not isinstance(payload, dict):
            continue

        fallback = analysis._record_timestamp(record) or _first_timestamp(payload, analysis)
        samples = analysis._find_named_list(payload, "samples")
        for sample in samples:
            bpm = analysis.coerce_number(
                analysis._find_named_value(sample, {"beatsperminute", "beatsperminuteavg"})
            )
            timestamp = _first_timestamp(sample, analysis) or fallback
            if timestamp is not None and bpm is not None:
                points.append((timestamp, bpm))

        if not samples:
            bpm = analysis.coerce_number(
                analysis._find_named_value(payload, {"beatsperminuteavg", "beatsperminute"})
            )
            if fallback is not None and bpm is not None:
                points.append((fallback, bpm))
    return sorted(points)


def five_minute_average_points(
    points: list[tuple[float, float]],
    *,
    bin_seconds: int = FIVE_MINUTE_SECONDS,
    window_seconds: int | None = None,
) -> list[tuple[float, float]]:
    """Return one arithmetic mean for every five-minute heart-rate interval.

    ``window_seconds`` remains accepted for compatibility with older callers but
    is deliberately ignored: the shared core no longer applies a second moving
    average over the five-minute bins.
    """
    del window_seconds
    if bin_seconds <= 0:
        raise ValueError("Heart-rate averaging interval must be positive")

    buckets: dict[int, list[float]] = collections.defaultdict(list)
    for timestamp, value in points:
        if math.isfinite(timestamp) and math.isfinite(value) and 20 <= value <= 250:
            buckets[math.floor(timestamp / bin_seconds)].append(value)

    return [
        (
            bucket * bin_seconds + bin_seconds / 2,
            statistics.fmean(values),
        )
        for bucket, values in sorted(buckets.items())
        if values
    ]


def install_shared_heart_rate_core(analysis) -> None:
    """Install the common five-minute heart-rate semantics into analysis.py."""
    if getattr(analysis, "_FIVE_MINUTE_HEART_RATE_CORE_INSTALLED", False):
        return

    original_dashboard = analysis.build_daily_progress_snapshot

    def shared_points(
        records: list[dict[str, typing.Any]],
    ) -> list[tuple[float, float]]:
        return heart_rate_sample_points(records, analysis)

    def shared_average(
        points: list[tuple[float, float]],
        *,
        bin_seconds: int = FIVE_MINUTE_SECONDS,
        window_seconds: int | None = None,
    ) -> list[tuple[float, float]]:
        return five_minute_average_points(
            points,
            bin_seconds=bin_seconds,
            window_seconds=window_seconds,
        )

    def shared_dashboard(
        store,
        reference_day: datetime.date | None = None,
        *,
        heart_day: datetime.date | None = None,
    ) -> dict[str, typing.Any]:
        snapshot = original_dashboard(store, reference_day, heart_day=heart_day)
        for metric in snapshot.get("metrics", []):
            if not isinstance(metric, dict) or metric.get("data_type") != "heart-rate-today":
                continue
            averaged = metric.get("heart_day_smoothed") or []
            clean: list[tuple[float, float]] = []
            for point in averaged:
                if not isinstance(point, (list, tuple)) or len(point) < 2:
                    continue
                try:
                    timestamp = float(point[0])
                    value = float(point[1])
                except (TypeError, ValueError):
                    continue
                if math.isfinite(timestamp) and math.isfinite(value) and 20 <= value <= 250:
                    clean.append((timestamp, value))
            if not clean:
                continue
            clean.sort(key=lambda item: item[0])
            values = [value for _timestamp, value in clean]
            metric.update(
                {
                    "metric": analysis._("5-minute average"),
                    "current": clean[-1][1],
                    "heart_day_smoothed": clean,
                    "heart_smoothing_minutes": 5,
                    "heart_day_min": min(values),
                    "heart_day_max": max(values),
                    "heart_day_mean": statistics.fmean(values),
                    "heart_day_sample_count": len(clean),
                }
            )
        return snapshot

    analysis._heart_rate_sample_points = shared_points
    analysis.smooth_heart_rate_points = shared_average
    analysis.build_daily_progress_snapshot = shared_dashboard
    analysis._FIVE_MINUTE_HEART_RATE_CORE_INSTALLED = True
