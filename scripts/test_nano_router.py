#!/usr/bin/env python3
"""Small dependency-free regression tests for Android Nano routing."""
from __future__ import annotations

import json

import nano_router

SNAPSHOT = {
    "metrics": [
        {"data_type": "active-energy-burned", "label": "Active energy burned"},
        {"data_type": "daily-heart-rate-variability", "label": "Daily heart-rate variability"},
        {"data_type": "heart-rate-variability", "label": "Heart-rate variability"},
        {"data_type": "heart-rate", "label": "Heart rate"},
        {"data_type": "daily-resting-heart-rate", "label": "Resting heart rate"},
        {"data_type": "steps", "label": "Steps"},
        {"data_type": "sleep", "label": "Sleep"},
    ]
}


def route(question: str) -> dict:
    return json.loads(nano_router.route_question(json.dumps(SNAPSHOT), question))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


# Common spelling mistakes must still allow a narrow, high-confidence route.
r = route("C'e correlazione tra HRV e calrie ative?")
require(r["mode"] == "specific_relation", f"typo relation routed as {r}")
require(r["confidence"] == "high", f"typo relation confidence was not high: {r}")
require("daily-heart-rate-variability" in r["selected_types"], f"HRV missing: {r}")
require("active-energy-burned" in r["selected_types"], f"active energy missing: {r}")

r = route("Come va la mia variabilta cardiaca?")
require(r["mode"] == "specific", f"HRV typo did not route specifically: {r}")
require("daily-heart-rate-variability" in r["selected_types"], f"daily HRV not preferred: {r}")

# Colloquial/ambiguous language must broaden, never silently narrow away a domain.
r = route("Quando mi muovo di piu sembra che il cuore recupera meglio il giorno dopo?")
require(r["mode"] == "domain", f"colloquial relation should broaden to domains: {r}")
require(r["confidence"] == "medium", f"colloquial relation should be medium confidence: {r}")
require("activity" in r["selected_domains"] and "heart" in r["selected_domains"], f"domains missing: {r}")

# Use the installed Weblate catalogue itself: a translated metric label must become
# router vocabulary without being hard-coded here.
translated = [
    item for item in nano_router._translated_variants("Active energy burned")
    if nano_router._normalise_text(item) != nano_router._normalise_text("Active energy burned")
]
if translated:
    r = route(f"{translated[0]}?")
    require("active-energy-burned" in r["selected_types"], f"catalogue translation was not recognised: {translated[0]!r} -> {r}")

# Non-Latin scripts must survive normalization. Unknown language/formulation is a
# safe general fallback rather than an empty or falsely specific packet.
require(nano_router._normalise_text("心拍数") == "心拍数", "non-Latin text was destroyed")
r = route("健康データについて何か気づくことはありますか")
require(r["mode"] == "general" and r["confidence"] == "low", f"unknown-language fallback unsafe: {r}")

print("nano_router regression tests: OK")
