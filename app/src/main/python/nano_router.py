"""Fail-safe multilingual deterministic retrieval for Gemini Nano.

The language model never decides which health metrics to load.  This router uses
VitalChronicle's installed Weblate catalogues, typo-tolerant fuzzy matching and a
confidence ladder.  High-confidence matches may narrow the packet; uncertain
matches may only broaden it (domain fallback, then general fallback).
"""
from __future__ import annotations

import json
from difflib import SequenceMatcher
from functools import lru_cache
from typing import Any

import mobile_bridge as mb
from google_health_viewer.ai_insights import build_ai_ready_snapshot
from google_health_viewer.constants import DATA_TYPES
from google_health_viewer.i18n import CATALOGUE_DIR

HIGH_CONFIDENCE = 0.90
MEDIUM_CONFIDENCE = 0.84
MATCH_BAND = 0.12

# Official metric/category labels come from Weblate automatically.  This small
# dictionary is intentionally limited to abbreviations and colloquial wording
# which is not normally appropriate as a UI translation.
EXTRA_METRIC_ALIASES: dict[str, tuple[str, ...]] = {
    "daily-heart-rate-variability": (
        "hrv", "heart rate variability", "variabilita cardiaca",
        "variabilita della frequenza cardiaca", "recupero cardiaco", "cuore recupera",
    ),
    "daily-resting-heart-rate": (
        "rhr", "resting hr", "heart rate at rest", "fc a riposo", "battito a riposo",
    ),
    "heart-rate": ("bpm", "battito", "battito cardiaco"),
    "active-energy-burned": (
        "active calories", "active calorie", "calorie attive", "energia attiva",
        "consumo calorico attivo",
    ),
    "daily-oxygen-saturation": ("spo2", "saturazione", "saturazione ossigeno"),
    "daily-respiratory-rate": ("breathing rate", "frequenza respiratoria"),
    "daily-sleep-temperature-derivations": (
        "sleep temperature", "skin temperature", "temperatura sonno", "temperatura cutanea",
    ),
    "daily-vo2-max": ("vo2", "vo2max", "vo2 max"),
    "sleep": ("sleep duration", "durata sonno", "dormito", "dormire"),
    "steps": ("step count", "numero passi"),
    "exercise": ("workout", "training", "allenamento", "esercizio"),
    "nutrition-log": ("food", "diet", "alimentazione", "cibo", "dieta"),
    "hydration-log": ("water", "idratazione", "acqua"),
}

# Prefer daily summaries when a family has both daily and high-frequency forms.
# This is particularly important on Android, where raw HRV is deliberately kept
# for a shorter interval than its daily summary.
PREFERRED_FAMILIES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("daily-heart-rate-variability", ("daily-heart-rate-variability", "heart-rate-variability")),
    ("daily-oxygen-saturation", ("daily-oxygen-saturation", "oxygen-saturation")),
    ("daily-respiratory-rate", ("daily-respiratory-rate", "respiratory-rate-sleep-summary")),
    ("daily-vo2-max", ("daily-vo2-max", "vo2-max", "run-vo2-max")),
)

DOMAIN_SOURCE_MESSAGES: dict[str, tuple[str, ...]] = {
    "activity": ("Activity", "Activity and fitness"),
    "sleep": ("Sleep",),
    "heart": ("Heart rate", "Resting heart rate", "Heart-rate variability"),
    "vitals": (
        "Oxygen saturation", "Daily oxygen saturation", "Daily respiratory rate",
        "Core body temperature", "Blood pressure", "Blood glucose",
    ),
    "weight": ("Weight", "Body fat"),
    "workouts": ("Workouts",),
    "nutrition": ("Nutrition", "Nutrition and hydration"),
}

EXTRA_DOMAIN_ALIASES: dict[str, tuple[str, ...]] = {
    "activity": (
        "movement", "moving", "physical activity", "movimento", "muovo", "mi muovo",
        "attivita fisica", "piu attivo", "meno attivo", "sedentary", "sedentario",
    ),
    "sleep": ("dormire", "dormito"),
    "heart": ("heart", "cardiac", "recovery", "cuore", "cardiaco", "cardiaca", "recupero"),
    "vitals": ("vitals", "vital signs", "parametri vitali"),
    "weight": ("body composition", "composizione corporea"),
    "workouts": ("workout", "training", "allenamento", "esercizio"),
    "nutrition": ("food", "diet", "hydration", "alimentazione", "cibo", "dieta", "idratazione"),
}

INTENT_SOURCE_SEEDS: dict[str, tuple[str, ...]] = {
    "association": ("Association", "Associations", "Correlation", "Relationship", "Relation"),
    "trend": ("Trend", "Change"),
    "anomaly": ("Anomaly", "Anomalies"),
    "comparison": ("Comparison", "Compare", "Difference"),
    "today": ("Today",),
    "general": ("Overview", "Summary", "General"),
}

INTENT_SOURCE_STEMS: dict[str, tuple[str, ...]] = {
    "association": ("association", "correlation", "relationship"),
    "trend": ("trend",),
    "anomaly": ("anomaly", "anomalies"),
    "comparison": ("comparison", "compare"),
    "today": ("today",),
    "general": ("overview", "summary"),
}

EXTRA_INTENT_ALIASES: dict[str, tuple[str, ...]] = {
    "association": (
        "correl", "relationship", "relation", "linked", "affect", "influence",
        "correlazione", "correlato", "correlata", "relazione", "associazione", "legame",
        "influenza", "collegato", "collegata", "giorno dopo", "next day",
    ),
    "trend": (
        "andamento", "evoluzione", "cambiamento", "changed", "aument", "dimin", "improv", "peggior",
    ),
    "anomaly": ("outlier", "anomal", "insolito", "insolita", "strano", "strana"),
    "comparison": ("rispetto", "confront", "differenza", "difference"),
    "today": ("oggi", "current day", "giorno corrente", "adesso", "ora"),
    "general": (
        "overall", "general", "summarize", "riassunto", "generale", "panoramica",
        "most meaningful", "pattern", "patterns", "cosa noti", "come sto",
    ),
}


def _normalise_text(value: str) -> str:
    """Unicode-safe normalisation.

    Unlike the old Android router, this keeps letters and digits from every
    script instead of deleting everything outside ASCII.
    """
    import unicodedata

    value = unicodedata.normalize("NFKD", str(value).casefold())
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    return " ".join("".join(ch if ch.isalnum() else " " for ch in value).split())


@lru_cache(maxsize=1)
def _catalogues() -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    if not CATALOGUE_DIR.is_dir():
        return result
    for path in sorted(CATALOGUE_DIR.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict):
            continue
        clean = {
            str(key): str(value)
            for key, value in data.items()
            if isinstance(key, str) and isinstance(value, str) and value.strip()
        }
        if clean:
            result[path.stem.lower()] = clean
    return result


@lru_cache(maxsize=1)
def _translation_reverse() -> dict[str, str]:
    reverse: dict[str, str] = {}
    for catalogue in _catalogues().values():
        for source, translated in catalogue.items():
            source_n = _normalise_text(source)
            translated_n = _normalise_text(translated)
            if source_n:
                reverse.setdefault(source_n, source)
            if translated_n:
                reverse.setdefault(translated_n, source)
    return reverse


def _source_message(value: str) -> str:
    return _translation_reverse().get(_normalise_text(value), value)


@lru_cache(maxsize=1024)
def _translated_variants(value: str) -> tuple[str, ...]:
    source = _source_message(value)
    variants = {source, value}
    for catalogue in _catalogues().values():
        translated = catalogue.get(source)
        if translated and translated.strip():
            variants.add(translated)
    return tuple(sorted((item for item in variants if item.strip()), key=lambda item: (_normalise_text(item), item)))


def _dedupe_aliases(values: list[str] | tuple[str, ...] | set[str]) -> tuple[str, ...]:
    result: dict[str, str] = {}
    for value in values:
        normalised = _normalise_text(value)
        if normalised:
            result.setdefault(normalised, value)
    return tuple(result.values())


def _metric_vocabulary(snapshot: dict[str, Any]) -> dict[str, tuple[str, ...]]:
    specs = {spec.key: spec for spec in DATA_TYPES}
    result: dict[str, tuple[str, ...]] = {}
    for metric in snapshot.get("metrics", []):
        if not isinstance(metric, dict):
            continue
        data_type = str(metric.get("data_type") or "")
        if not data_type:
            continue
        aliases: list[str] = [data_type.replace("-", " ")]
        label = str(metric.get("label") or "")
        if label:
            aliases.extend(_translated_variants(label))
        spec = specs.get(data_type)
        if spec is not None:
            aliases.extend(_translated_variants(str(spec.label)))
        aliases.extend(EXTRA_METRIC_ALIASES.get(data_type, ()))
        result[data_type] = _dedupe_aliases(aliases)
    return result


def _domain_vocabulary() -> dict[str, tuple[str, ...]]:
    aliases: dict[str, list[str]] = {domain: [] for domain in mb.DOMAIN_ORDER}
    for domain, source_messages in DOMAIN_SOURCE_MESSAGES.items():
        for source in source_messages:
            aliases[domain].extend(_translated_variants(source))
    for spec in DATA_TYPES:
        domain = mb._domain_for(spec.key)
        aliases.setdefault(domain, []).extend(_translated_variants(str(spec.category)))
    for domain, extras in EXTRA_DOMAIN_ALIASES.items():
        aliases.setdefault(domain, []).extend(extras)
    return {domain: _dedupe_aliases(values) for domain, values in aliases.items() if values}


@lru_cache(maxsize=None)
def _intent_terms(intent: str) -> tuple[str, ...]:
    aliases: list[str] = []
    for seed in INTENT_SOURCE_SEEDS.get(intent, ()):
        aliases.extend(_translated_variants(seed))

    # Reuse short Weblate messages automatically.  E.g. translating the UI key
    # "Anomaly" immediately teaches the router the translated word as well.
    stems = INTENT_SOURCE_STEMS.get(intent, ())
    if stems:
        for catalogue in _catalogues().values():
            for source in catalogue:
                source_n = _normalise_text(source)
                if len(source_n.split()) <= 4 and any(stem in source_n for stem in stems):
                    aliases.extend(_translated_variants(source))
    aliases.extend(EXTRA_INTENT_ALIASES.get(intent, ()))
    return _dedupe_aliases(aliases)


def _exact_position(text: str, alias: str) -> int | None:
    if not text or not alias:
        return None
    if " " not in alias and len(alias) <= 3:
        try:
            return text.split().index(alias)
        except ValueError:
            return None
    position = text.find(alias)
    return position if position >= 0 else None


def _phrase_score(text: str, alias: str) -> tuple[float, str, int]:
    alias_n = _normalise_text(alias)
    if not alias_n:
        return 0.0, "none", -1
    exact_position = _exact_position(text, alias_n)
    if exact_position is not None:
        return 1.0, "exact", exact_position

    alias_tokens = alias_n.split()
    query_tokens = text.split()
    if not alias_tokens or not query_tokens:
        return 0.0, "none", -1
    if len(alias_n) <= 3 and len(alias_tokens) == 1:
        return 0.0, "none", -1

    best_score = 0.0
    best_position = -1
    widths = {len(alias_tokens)}
    if len(alias_tokens) > 1:
        widths.add(len(alias_tokens) - 1)
        widths.add(len(alias_tokens) + 1)
    for width in sorted(value for value in widths if value > 0):
        if width > len(query_tokens):
            continue
        for index in range(0, len(query_tokens) - width + 1):
            candidate = " ".join(query_tokens[index : index + width])
            # Avoid comparing wildly different fragments, which creates false
            # positives such as weight≈height when an exact height match exists.
            size_ratio = len(candidate) / max(1, len(alias_n))
            if size_ratio < 0.55 or size_ratio > 1.65:
                continue
            score = SequenceMatcher(None, candidate, alias_n, autojunk=False).ratio()
            if score > best_score:
                best_score = score
                best_position = index
    return best_score, "fuzzy" if best_score else "none", best_position


def _best_alias_match(text: str, aliases: tuple[str, ...]) -> dict[str, Any] | None:
    best: dict[str, Any] | None = None
    for alias in aliases:
        score, method, position = _phrase_score(text, alias)
        if best is None or score > best["score"] or (
            score == best["score"] and len(_normalise_text(alias)) > len(_normalise_text(best["alias"]))
        ):
            best = {"score": score, "method": method, "position": position, "alias": alias}
    return best if best and best["score"] >= MEDIUM_CONFIDENCE else None


def _suppress_overlapping_matches(matches: list[dict[str, Any]]) -> list[dict[str, Any]]:
    ordered = sorted(
        matches,
        key=lambda item: (-item["score"], -len(_normalise_text(item["alias"])), item["data_type"]),
    )
    kept: list[dict[str, Any]] = []
    for candidate in ordered:
        candidate_alias = _normalise_text(candidate["alias"])
        shadowed = False
        for existing in kept:
            existing_alias = _normalise_text(existing["alias"])
            same_place = candidate["position"] >= 0 and existing["position"] >= 0 and abs(candidate["position"] - existing["position"]) <= 1
            if (
                same_place
                and candidate_alias != existing_alias
                and candidate_alias in existing_alias
                and existing["score"] >= candidate["score"]
            ):
                shadowed = True
                break
        if not shadowed:
            kept.append(candidate)

    by_type = {item["data_type"]: item for item in kept}
    for preferred, family in PREFERRED_FAMILIES:
        present = [by_type[data_type] for data_type in family if data_type in by_type]
        if len(present) < 2 or preferred not in by_type:
            continue
        preferred_row = by_type[preferred]
        for row in present:
            if row["data_type"] == preferred:
                continue
            if preferred_row["score"] >= row["score"] - 0.05:
                by_type.pop(row["data_type"], None)
    return sorted(by_type.values(), key=lambda item: (-item["score"], item["position"], item["data_type"]))


def _metric_matches(text: str, snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for data_type, aliases in _metric_vocabulary(snapshot).items():
        best = _best_alias_match(text, aliases)
        if best is not None:
            matches.append({"data_type": data_type, **best})
    if not matches:
        return []
    best_score = max(item["score"] for item in matches)
    matches = [
        item for item in matches
        if item["score"] >= MEDIUM_CONFIDENCE and item["score"] >= best_score - MATCH_BAND
    ]
    return _suppress_overlapping_matches(matches)


def _domain_matches(text: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for domain, aliases in _domain_vocabulary().items():
        best = _best_alias_match(text, aliases)
        if best is not None:
            result.append({"domain": domain, **best})
    if not result:
        return []
    best_score = max(item["score"] for item in result)
    return sorted(
        [item for item in result if item["score"] >= best_score - MATCH_BAND],
        key=lambda item: (-item["score"], item["domain"]),
    )


def _has_intent(text: str, intent: str) -> bool:
    return _best_alias_match(text, _intent_terms(intent)) is not None


def _selection(question: str, snapshot: dict[str, Any]) -> dict[str, Any]:
    text = _normalise_text(question)
    metric_matches = _metric_matches(text, snapshot) if text else []
    domain_matches = _domain_matches(text) if text else []
    intents = [
        intent for intent in ("association", "trend", "anomaly", "comparison", "today", "general")
        if _has_intent(text, intent)
    ] if text else ["general"]

    high_metrics = [item for item in metric_matches if item["score"] >= HIGH_CONFIDENCE]
    medium_metrics = [item for item in metric_matches if item["score"] >= MEDIUM_CONFIDENCE]
    high_domains = [item for item in domain_matches if item["score"] >= HIGH_CONFIDENCE]
    medium_domains = [item for item in domain_matches if item["score"] >= MEDIUM_CONFIDENCE]

    all_domains: list[str] = []
    for item in medium_domains:
        if item["domain"] not in all_domains:
            all_domains.append(item["domain"])
    for item in medium_metrics:
        domain = mb._domain_for(item["data_type"])
        if domain not in all_domains:
            all_domains.append(domain)

    relation = "association" in intents
    confidence = "low"
    mode = "general"
    fallback = "general"
    selected_types: list[str] = []
    selected_domains: list[str] = []

    if relation:
        if len(high_metrics) >= 2:
            confidence = "high"
            mode = "specific_relation"
            fallback = "none"
            selected_types = [item["data_type"] for item in high_metrics[:3]]
            selected_domains = list(dict.fromkeys(mb._domain_for(data_type) for data_type in selected_types))
        elif len(medium_metrics) >= 2 or (high_metrics and all_domains):
            confidence = "medium"
            mode = "domain"
            fallback = "domain_expansion"
            selected_domains = all_domains
    elif high_metrics:
        confidence = "high"
        mode = "specific" if len(high_metrics) <= 3 else "domain"
        fallback = "none" if mode == "specific" else "domain_expansion"
        if mode == "specific":
            selected_types = [item["data_type"] for item in high_metrics[:3]]
            selected_domains = list(dict.fromkeys(mb._domain_for(data_type) for data_type in selected_types))
        else:
            selected_domains = all_domains
    elif medium_metrics or high_domains or medium_domains:
        confidence = "medium"
        mode = "domain"
        fallback = "domain_expansion"
        selected_domains = all_domains

    if mode == "domain" and not selected_domains:
        # An uncertain route must never result in an empty packet.
        confidence = "low"
        mode = "general"
        fallback = "general"

    diagnostics = []
    for item in metric_matches[:4]:
        diagnostics.append(
            {
                "data_type": item["data_type"],
                "score": round(float(item["score"]), 3),
                "method": item["method"],
                "matched_as": item["alias"],
            }
        )
    return {
        "mode": mode,
        "confidence": confidence,
        "fallback": fallback,
        "intents": intents,
        "selected_types": selected_types,
        "selected_domains": selected_domains,
        "matched_types": [item["data_type"] for item in metric_matches[:6]],
        "matches": diagnostics,
    }


def _available_types_for_domains(snapshot: dict[str, Any], domains: list[str]) -> list[str]:
    wanted = set(domains)
    return [
        str(metric.get("data_type"))
        for metric in snapshot.get("metrics", [])
        if isinstance(metric, dict)
        and metric.get("data_type")
        and mb._domain_for(str(metric.get("data_type"))) in wanted
    ]


def _build_packet(
    snapshot: dict[str, Any],
    question: str,
    store: mb.SQLiteStore | None,
    start: str | None,
    end: str | None,
    *,
    lean: bool,
) -> dict[str, Any]:
    route = _selection(question, snapshot)
    mode = route["mode"]
    selected_types = list(route["selected_types"])
    selected_domains = list(route["selected_domains"])
    target = mb.NANO_EVIDENCE_TARGETS[mode]

    if mode == "domain":
        effective_types = _available_types_for_domains(snapshot, selected_domains)
    elif mode in {"specific", "specific_relation"}:
        effective_types = selected_types
    else:
        effective_types = []

    metrics = mb._selected_metrics(snapshot, selected_types, selected_domains, mode, lean=lean)
    metric_types = [
        str(metric.get("data_type")) for metric in metrics
        if isinstance(metric, dict) and metric.get("data_type")
    ]

    packet: dict[str, Any] = {
        "packet": {
            "health_evidence_present": True,
            "pipeline_version": "android-multilingual-fuzzy-retrieval-v3",
            "analysis_scope": snapshot.get("analysis_scope"),
        },
        "retrieval": {
            "mode": mode,
            "confidence": route["confidence"],
            "fallback": route["fallback"],
            "intents": route["intents"],
            "selected_data_types": selected_types,
            "selected_domains": selected_domains,
            "matched_data_types": route["matched_types"],
            "matches": route["matches"],
            "target_evidence_tokens": target,
            "absolute_evidence_limit": mb.NANO_ABSOLUTE_EVIDENCE_LIMIT,
            "selection_is_deterministic": True,
            "selection_uses_installed_translations": True,
            "selection_source": "full_deterministic_snapshot",
        },
        "period": snapshot.get("period") or {},
        "observation": mb._selected(
            snapshot.get("observation_context"),
            (
                "observed_at", "local_date", "local_time", "selected_period_includes_today",
                "current_day_is_incomplete", "elapsed_day_percent",
            ),
        ),
        "coverage": mb._coverage_for_types(snapshot, metric_types),
        "metrics": metrics,
        "insights": mb._selected_insights(snapshot, effective_types, mode, lean=lean),
        "associations": mb._selected_associations(snapshot, effective_types, mode, lean=lean),
        "rules": [
            "missing data are missing, never zero",
            "association does not imply causation",
            "an unreported association does not prove absence of a relationship",
            "today may be incomplete",
            "medium or low retrieval confidence broadens evidence rather than excluding uncertain metrics",
        ],
    }
    if mode == "specific_relation" and store is not None and start is not None and end is not None:
        packet["relation_checks"] = mb._relation_checks(store, selected_types, start, end)
    return packet


def _fit_packet(
    snapshot: dict[str, Any],
    question: str,
    store: mb.SQLiteStore | None = None,
    start: str | None = None,
    end: str | None = None,
) -> dict[str, Any]:
    packet = _build_packet(snapshot, question, store, start, end, lean=False)
    mode = str((packet.get("retrieval") or {}).get("mode", "general"))
    target = mb.NANO_EVIDENCE_TARGETS.get(mode, mb.NANO_EVIDENCE_TARGETS["general"])

    if mb._estimated_tokens(packet) > target:
        packet = _build_packet(snapshot, question, store, start, end, lean=True)
    while mb._estimated_tokens(packet) > target and len(packet.get("insights", [])) > 1:
        packet["insights"].pop()
    while mb._estimated_tokens(packet) > target and len(packet.get("associations", [])) > 1:
        packet["associations"].pop()
    while (
        mb._estimated_tokens(packet) > target
        and mode in {"domain", "general"}
        and len(packet.get("metrics", [])) > (2 if mode == "domain" else 5)
    ):
        packet["metrics"].pop()

    if mb._estimated_tokens(packet) > mb.NANO_ABSOLUTE_EVIDENCE_LIMIT:
        packet["insights"] = packet.get("insights", [])[:1]
        packet["associations"] = packet.get("associations", [])[:1]
        packet["metrics"] = packet.get("metrics", [])[:3]
        if isinstance(packet.get("coverage"), dict):
            packet["coverage"].pop("notice", None)

    estimate = mb._estimated_tokens(packet)
    packet["packet"]["estimated_tokens"] = estimate
    packet["packet"]["json_bytes"] = len(
        json.dumps(packet, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    )
    packet["packet"]["target_evidence_tokens"] = target
    packet["packet"]["absolute_evidence_limit"] = mb.NANO_ABSOLUTE_EVIDENCE_LIMIT
    return packet


def nano_evidence_from_sqlite(database_path: str, start: str, end: str, question: str) -> str:
    store = mb.SQLiteStore(database_path)
    try:
        snapshot = build_ai_ready_snapshot(store, start, end)
        packet = _fit_packet(snapshot, question, store, start, end)
        return json.dumps(packet, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()


def route_question(snapshot_json: str, question: str) -> str:
    """Small test/debug entry point which never reads health records."""
    snapshot = json.loads(snapshot_json)
    return json.dumps(_selection(question, snapshot), ensure_ascii=False, separators=(",", ":"))
