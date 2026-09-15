"""Android adapter for VitalChronicle's safe personal-health agent tools."""
from __future__ import annotations

import json
import re
import sqlite3
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from google_health_viewer.agent_store import AgentStore, PERSONAL_CONTEXT_KEY_SPECS
from google_health_viewer.agent_tool_factory import EnhancedSafeToolExecutor
from google_health_viewer import agent_hard_query_reliability_patch as hard_query
from google_health_viewer import agent_runtime_adaptive_patch as adaptive
from google_health_viewer import agent_runtime_efficiency_patch as efficiency
from google_health_viewer import agent_tool_factory_semantic_guard as semantic
from mobile_bridge import SQLiteStore

MAX_AGENT_STEPS = 15
MAX_FACTORY_REPAIR_ATTEMPTS = 3
MAX_RAW_SERIES_PROBES_BEFORE_FACTORY = 2
MAX_TOOL_RESULT_CHARS = 5000
CALIBRATION_VERSION = 2
THREAD_ID = "android-local"
CONVERSATION_LIMIT = 4
CONVERSATION_MESSAGE_CHARS = 600
MAX_ADVERTISED_TOOLS = 10
MAX_RESULT_LIST_ITEMS = 10

_COMPREHENSIVE_MARKERS = (
    "analisi totale", "analisi completa", "analisi profonda", "tutta la cronologia",
    "full analysis", "complete analysis", "deep analysis", "entire health history",
)
_TOPIC_MARKERS = {
    "sleep": ("sonno", "dorm", "notte", "letto", "svegl", "sleep", "slept", "bed", "night"),
    "training": ("allen", "palestra", "cardio", "bici", "cicl", "workout", "training", "gym", "bike", "load", "attiv", "activ"),
    "recovery": ("recuper", "readiness", "resilien", "hrv", "variabil", "stanc", "affatic", "stress", "recovery", "fatigue", "tired"),
}
_CONTEXT_TOPICS = {
    key: set(spec.get("topics") or ())
    for key, spec in PERSONAL_CONTEXT_KEY_SPECS.items()
}
_REPORT_TOPICS = {
    "sleep_quality": {"sleep", "recovery"}, "sleepiness": {"sleep", "recovery"},
    "fatigue": {"recovery", "training", "sleep"}, "soreness": {"training", "recovery"},
    "stress": {"recovery"}, "energy": {"recovery", "training"},
}
_SELF_REPORT_PATTERNS = {
    "fatigue": (
        "mi sento stanco", "mi sento stanca", "sono stanco", "sono stanca", "mi sento affaticato", "mi sento affaticata",
        "i feel tired", "i'm tired", "i am tired", "i feel fatigued", "ich bin müde", "estoy cansado", "estoy cansada", "je suis fatigué", "je suis fatiguée",
    ),
    "sleepiness": ("ho sonno", "mi sento assonnato", "mi sento assonnata", "i feel sleepy", "i'm sleepy", "schläfrig", "tengo sueño", "somnolent"),
    "soreness": ("sono indolenzito", "sono indolenzita", "dolori muscolari", "muscoli indolenziti", "i feel sore", "muscle soreness", "muskelkater", "dolor muscular", "courbatures"),
    "stress": ("mi sento stressato", "mi sento stressata", "sono stressato", "sono stressata", "i feel stressed", "i'm stressed", "gestresst", "estresado", "estresada", "stressé", "stressée"),
    "energy": ("mi sento energico", "mi sento energica", "pieno di energia", "piena di energia", "i feel energetic", "full of energy", "voller energie", "con mucha energía", "plein d'énergie", "pleine d'énergie"),
}

AGENT_SYSTEM_PROMPT = """You are VitalChronicle's read-only local health agent.
Use deterministic tools; missing is unavailable, not zero. Preserve units and date semantics (sleep=wake date; today may be partial).
Reuse exact tools. Create a declarative tool only for reusable composed/baseline/lag/recovery gaps; keep its metadata in English. No code, shell, filesystem, browser, network, or writes. Repair DSL errors and execute before answering. Never substitute metrics.
Use relevant current personal context; reports are subjective and durable context needs confirmation. Correlation is not causation. Never diagnose or change treatment.
Use create_monitoring_rule for recurring in-app check-ins, never as a learned tool, background listener, or system notification.
Use few calls; answer result-first without scratchpad.

Return exactly one JSON object:
{"action":"tool","name":"tool_name","arguments":{...}}
or
{"action":"final","answer":"clear user-facing answer"}
Never output scratchpad prose."""


class AndroidAgentHealthStore(SQLiteStore):
    """Bounded read-only Android health store implementing the agent tool contract."""

    def counts(self) -> dict[str, int]:
        rows = self._connection.execute(
            "SELECT data_type,COUNT(*) AS n FROM records GROUP BY data_type ORDER BY data_type"
        ).fetchall()
        return {str(row["data_type"]): int(row["n"]) for row in rows}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _ensure_android_tables(path: str) -> None:
    db = sqlite3.connect(path, timeout=30.0)
    try:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS android_agent_messages(
                message_id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_android_agent_messages
                ON android_agent_messages(message_id);
            CREATE TABLE IF NOT EXISTS monitoring_rules(
                monitor_id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                question TEXT NOT NULL,
                cadence_days INTEGER NOT NULL DEFAULT 1,
                keywords_json TEXT NOT NULL DEFAULT '[]',
                fields_json TEXT NOT NULL DEFAULT '[]',
                status TEXT NOT NULL DEFAULT 'active',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_prompted_at TEXT
            );
            CREATE TABLE IF NOT EXISTS monitoring_observations(
                observation_id TEXT PRIMARY KEY,
                monitor_id TEXT NOT NULL,
                statement TEXT NOT NULL,
                observed_at TEXT NOT NULL,
                context_json TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_monitoring_observations
                ON monitoring_observations(monitor_id, observed_at, created_at);
            """
        )
        has_self_reports = db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='self_reports'"
        ).fetchone()
        if has_self_reports:
            rows = db.execute("SELECT report_id,statement FROM self_reports").fetchall()
            for report_id, statement in rows:
                normalized = _normalise_self_report(str(statement or ""))
                if normalized and normalized != statement:
                    db.execute(
                        "UPDATE self_reports SET statement=?,updated_at=? WHERE report_id=?",
                        (normalized, _now(), report_id),
                    )
        db.commit()
    finally:
        db.close()


def _agent_store(path: str) -> AgentStore:
    store = AgentStore(Path(path))
    _ensure_android_tables(path)
    return store


def _conversation_history(agent_path: str, limit: int = CONVERSATION_LIMIT) -> list[dict[str, str]]:
    _ensure_android_tables(agent_path)
    db = sqlite3.connect(agent_path, timeout=10.0)
    db.row_factory = sqlite3.Row
    try:
        rows = db.execute(
            "SELECT role,content FROM android_agent_messages ORDER BY message_id DESC LIMIT ?",
            (max(1, min(40, int(limit))),),
        ).fetchall()
    finally:
        db.close()
    return [
        {"role": str(row["role"]), "content": str(row["content"])[:CONVERSATION_MESSAGE_CHARS]}
        for row in reversed(rows)
        if str(row["role"]) in {"user", "assistant"}
    ]


def record_exchange(agent_path: str, question: str, answer: str) -> str:
    _ensure_android_tables(agent_path)
    question = question.strip()
    answer = answer.strip()
    if not question or not answer:
        return json.dumps({"stored": False})
    db = sqlite3.connect(agent_path, timeout=30.0)
    try:
        now = _now()
        db.execute("INSERT INTO android_agent_messages(role,content,created_at) VALUES('user',?,?)", (question[:12000], now))
        db.execute("INSERT INTO android_agent_messages(role,content,created_at) VALUES('assistant',?,?)", (answer[:16000], now))
        db.execute(
            "DELETE FROM android_agent_messages WHERE message_id NOT IN "
            "(SELECT message_id FROM android_agent_messages ORDER BY message_id DESC LIMIT 40)"
        )
        db.commit()
    finally:
        db.close()
    return json.dumps({"stored": True})


def clear_conversation(agent_path: str) -> str:
    _ensure_android_tables(agent_path)
    db = sqlite3.connect(agent_path, timeout=30.0)
    try:
        cursor = db.execute("DELETE FROM android_agent_messages")
        db.commit()
        count = int(cursor.rowcount or 0)
    finally:
        db.close()
    return json.dumps({"cleared": count})


def _archive_bounds(database_path: str) -> dict[str, Any] | None:
    db = sqlite3.connect(database_path, timeout=10.0)
    try:
        row = db.execute(
            "SELECT MIN(substr(COALESCE(start_time,end_time),1,10)), "
            "MAX(substr(COALESCE(start_time,end_time),1,10)) FROM records "
            "WHERE COALESCE(start_time,end_time) IS NOT NULL"
        ).fetchone()
    finally:
        db.close()
    if not row or not row[0] or not row[1]:
        return None
    return {"start": str(row[0]), "end": str(row[1])}


def _compact_tools(schemas: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for schema in schemas:
        function = schema.get("function") or {}
        params = function.get("parameters") or {}
        properties = params.get("properties") or {}
        required = set(params.get("required") or [])
        args = ", ".join(f"{name}{'*' if name in required else ''}" for name in properties) or "no arguments"
        lines.append(f"- {function.get('name')}({args}): {function.get('description','')}")
    return "\n".join(lines)


def _tool_subset(
    schemas: list[dict[str, Any]],
    question: str,
    *,
    required_names: set[str] | None = None,
    maximum: int = MAX_ADVERTISED_TOOLS,
) -> list[dict[str, Any]]:
    text = question.casefold()
    controls = {
        "get_available_metrics", "get_data_coverage", "get_metric_series",
        "get_daily_summary", "get_baseline", "get_missing_data",
        "search_tool_registry", "create_learned_tool", "ask_user_feedback",
    }
    if any(marker in text for marker in ("monitor", "ricord", "segnal", "promemoria", "track", "remind", "check-in")):
        controls.update({"create_monitoring_rule", "record_monitoring_observation", "list_monitoring_rules"})
    domain_terms = {
        "sleep": ("sleep", "sonno", "notte", "dorm", "rem", "profondo", "risvegl"),
        "training": ("training", "allen", "attiv", "activ", "cardio", "bici", "cicl", "workout", "zona attiva"),
        "fitness": ("fitness", "vo2", "forma fisica", "progress"),
        "recovery": ("recovery", "recuper", "hrv", "frequenza cardiaca", "readiness", "stanc"),
        "analysis": ("correl", "relazione", "confront", "trend", "anom", "mediana", "baseline", "percent"),
        "coaching": ("consigli", "raccomand", "dovrei", "recommend"),
    }
    selected = {domain for domain, terms in domain_terms.items() if any(term in text for term in terms)}
    if not selected:
        selected = {"sleep", "training", "recovery", "analysis"}
    schema_terms = {
        "sleep": ("sleep", "awakening"),
        "training": ("training", "workout", "activity", "cardio", "load"),
        "fitness": ("fitness", "vo2", "progression"),
        "recovery": ("recovery", "hrv", "rhr", "readiness"),
        "analysis": ("compare", "correlation", "outlier", "trend"),
        "coaching": ("recommend", "coaching"),
    }

    preferred_names = set(required_names or ())
    preferred: list[dict[str, Any]] = []
    required: list[dict[str, Any]] = []
    relevant: list[dict[str, Any]] = []
    deferred: list[dict[str, Any]] = []
    for schema in schemas:
        function = schema.get("function") if isinstance(schema, dict) else None
        if not isinstance(function, dict):
            continue
        name = str(function.get("name") or "")
        searchable = f"{name} {function.get('description', '')}".casefold()
        if name in preferred_names:
            preferred.append(schema)
        elif name in controls:
            required.append(schema)
        elif any(any(term in searchable for term in schema_terms[domain]) for domain in selected):
            relevant.append(schema)
        else:
            deferred.append(schema)
    return (preferred[:3] + required + relevant + deferred)[:max(1, int(maximum))]


def _request_topics(question: str) -> set[str]:
    text = question.casefold()
    return {topic for topic, markers in _TOPIC_MARKERS.items() if any(marker in text for marker in markers)}


def _item_topics(item: dict[str, Any]) -> set[str]:
    key = str(item.get("key") or "")
    topics = set(_CONTEXT_TOPICS.get(key, set()))
    haystack = f"{key} {item.get('statement', '')}".casefold()
    for topic, markers in _TOPIC_MARKERS.items():
        if any(marker in haystack for marker in markers):
            topics.add(topic)
    return topics


def _report_topics(item: dict[str, Any]) -> set[str]:
    category = str(item.get("category") or "").casefold()
    topics = set(_REPORT_TOPICS.get(category, set()))
    haystack = f"{category} {item.get('statement', '')}".casefold()
    for topic, markers in _TOPIC_MARKERS.items():
        if any(marker in haystack for marker in markers):
            topics.add(topic)
    return topics


def _relevant_personal_evidence(question: str, store: AgentStore) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    personal = store.user_model()
    reports = store.recent_self_reports(days=30, limit=24)
    comprehensive = not question.strip() or any(marker in question.casefold() for marker in _COMPREHENSIVE_MARKERS)
    if comprehensive:
        return personal[:16], reports[:16]
    topics = _request_topics(question)
    if not topics:
        return [], []
    return (
        [item for item in personal if _item_topics(item) & topics][:12],
        [item for item in reports if _report_topics(item) & topics][:12],
    )


def _factory_hint(question: str) -> dict[str, Any]:
    text = question.casefold()
    groups = {
        "personal_baseline": ("baseline", "basale", "media personale", "personal average", "personal baseline"),
        "temporal_event_response": ("notte successiva", "giorno successivo", "next night", "next day", " dopo ", " after ", "lag"),
        "recovery_latency": ("torni", "torna", "ritorni", "recuper", "return to", "recover", "recovery time"),
        "threshold_frequency": ("%", "quanto spesso", "how often", "supera", "exceed", "diminuisce", "decrease"),
    }
    signals = [label for label, markers in groups.items() if any(marker in text for marker in markers)]
    hint = {
        "consider_reusable_tool": len(signals) >= 2,
        "signals": signals,
        "capability": "analysis.composed" + ("." + ".".join(signals) if signals else ""),
    }
    hint = efficiency._supplement_factory_hint(question, hint)
    requirements = semantic._semantic_requirements(question)
    if semantic._is_training_context_comparison(requirements):
        marker = "training-context recovery comparison"
        if marker not in hint["signals"]:
            hint["signals"].append(marker)
        hint["consider_reusable_tool"] = True
        hint["semantic_requirements"] = sorted(requirements)
        hint["capability"] = semantic._COMPOSED_CONTEXT_CAPABILITY
        hint["instruction"] = (
            "Preserve consecutive-training versus after-rest semantics; do not replace the "
            "request with a generic high-load recovery proxy."
        )
    if hard_query._is_sleep_conditioned_consecutive_recovery(question):
        if hard_query._SLEEP_CONDITIONED_SIGNAL not in hint["signals"]:
            hint["signals"].append(hard_query._SLEEP_CONDITIONED_SIGNAL)
        hint["consider_reusable_tool"] = True
        hint["capability"] = hard_query._SLEEP_CONDITIONED_COMPOSED_CAPABILITY
        hint["instruction"] = (
            "Preserve second-day training selection, following-night sleep cohorts, the personal "
            "sleep baseline and every requested recovery metric."
        )
    return hint


def _detect_self_report(question: str) -> dict[str, str] | None:
    folded = question.strip().casefold()
    for category, markers in _SELF_REPORT_PATTERNS.items():
        if any(marker in folded for marker in markers):
            return {"category": category, "statement": _normalise_self_report(question)}
    return None


def _normalise_self_report(statement: str) -> str:
    operational = (
        "vorrei monitor", "voglio monitor", "ricordami", "ricordamelo",
        "tracciare questi dati", "i want to monitor", "remind me", "track this",
    )
    sentences = re.split(r"(?<=[.!?])\s+|[\r\n]+", statement.strip())
    kept = [
        item.strip() for item in sentences
        if item.strip() and not any(marker in item.casefold() for marker in operational)
    ]
    return " ".join(kept).strip() or statement.strip()


def _monitoring_schemas() -> list[dict[str, Any]]:
    def schema(name: str, description: str, properties: dict[str, Any], required=()) -> dict[str, Any]:
        return {"type": "function", "function": {"name": name, "description": description, "parameters": {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False}}}
    return [
        schema(
            "create_monitoring_rule",
            "Create or update a recurring in-app personal check-in; not a learned tool or system notification.",
            {
                "name": {"type": "string"}, "title": {"type": "string"},
                "description": {"type": "string"}, "question": {"type": "string"},
                "cadence_days": {"type": "integer", "minimum": 1, "maximum": 30},
                "keywords": {"type": "array", "items": {"type": "string"}},
                "fields": {"type": "array", "items": {"type": "string"}},
            },
            ("name", "title", "question", "cadence_days", "keywords"),
        ),
        schema("record_monitoring_observation", "Record a dated observation for an existing monitoring rule.", {"name": {"type": "string"}, "statement": {"type": "string"}, "observed_at": {"type": "string"}, "context": {"type": "object"}}, ("name", "statement")),
        schema("list_monitoring_rules", "List active recurring in-app monitoring rules.", {}),
    ]


def _list_monitoring_rules(agent_path: str) -> list[dict[str, Any]]:
    _ensure_android_tables(agent_path)
    db = sqlite3.connect(agent_path, timeout=10.0)
    db.row_factory = sqlite3.Row
    try:
        rows = db.execute(
            "SELECT r.*,COUNT(o.observation_id) observation_count,MAX(o.observed_at) last_observed_at "
            "FROM monitoring_rules r LEFT JOIN monitoring_observations o ON o.monitor_id=r.monitor_id "
            "WHERE r.status='active' GROUP BY r.monitor_id ORDER BY r.name"
        ).fetchall()
    finally:
        db.close()
    return [{
        "monitor_id": row["monitor_id"], "name": row["name"], "title": row["title"],
        "description": row["description"], "question": row["question"],
        "cadence_days": int(row["cadence_days"]),
        "keywords": json.loads(row["keywords_json"] or "[]"),
        "fields": json.loads(row["fields_json"] or "[]"), "status": row["status"],
        "created_at": row["created_at"], "updated_at": row["updated_at"],
        "last_prompted_at": row["last_prompted_at"],
        "observation_count": int(row["observation_count"] or 0),
        "last_observed_at": row["last_observed_at"],
    } for row in rows]


def _create_monitoring_rule(agent_path: str, arguments: dict[str, Any], store: AgentStore) -> dict[str, Any]:
    import uuid
    name = str(arguments.get("name") or "").strip().lower()
    if not re.fullmatch(r"[a-z][a-z0-9_]{2,63}", name):
        return {"status": "invalid_spec", "error": "Monitoring-rule name must be snake_case"}
    question = str(arguments.get("question") or "").strip()
    if not question:
        return {"status": "invalid_spec", "error": "Monitoring rules require a question"}
    now = _now()
    existing = next((item for item in _list_monitoring_rules(agent_path) if item["name"] == name), None)
    monitor_id = existing["monitor_id"] if existing else str(uuid.uuid4())
    keywords = [str(item).strip().casefold()[:80] for item in arguments.get("keywords", []) if str(item).strip()][:12]
    fields = [str(item).strip()[:80] for item in arguments.get("fields", []) if str(item).strip()][:8]
    cadence = max(1, min(30, int(arguments.get("cadence_days") or 1)))
    db = sqlite3.connect(agent_path, timeout=30.0)
    try:
        db.execute(
            "INSERT INTO monitoring_rules VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET "
            "title=excluded.title,description=excluded.description,question=excluded.question,cadence_days=excluded.cadence_days,keywords_json=excluded.keywords_json,fields_json=excluded.fields_json,status='active',updated_at=excluded.updated_at",
            (monitor_id, name, str(arguments.get("title") or name)[:160], str(arguments.get("description") or "")[:1000], question[:1000], cadence, json.dumps(keywords, ensure_ascii=False), json.dumps(fields, ensure_ascii=False), "active", existing["created_at"] if existing else now, now, existing["last_prompted_at"] if existing else now),
        )
        db.commit()
    finally:
        db.close()
    status = "updated" if existing else "created"
    store.log_tool_event(f"monitoring_rule_{status}", f"Monitoring rule {name} {status}.")
    monitor = next(item for item in _list_monitoring_rules(agent_path) if item["name"] == name)
    return {"status": status, "monitor": monitor}


def _record_monitoring_observation(agent_path: str, arguments: dict[str, Any]) -> dict[str, Any]:
    import uuid
    name = str(arguments.get("name") or "").strip()
    statement = str(arguments.get("statement") or "").strip()
    monitor = next((item for item in _list_monitoring_rules(agent_path) if item["name"] == name), None)
    if not monitor or not statement:
        return {"stored": False, "error": "Unknown monitor or empty observation"}
    now = _now()
    observation_id = str(uuid.uuid4())
    db = sqlite3.connect(agent_path, timeout=30.0)
    try:
        duplicate = db.execute("SELECT observation_id FROM monitoring_observations WHERE monitor_id=? AND statement=? AND created_at>=? LIMIT 1", (monitor["monitor_id"], statement, (datetime.now(timezone.utc) - timedelta(hours=12)).isoformat())).fetchone()
        if duplicate:
            observation_id = str(duplicate[0])
        else:
            db.execute("INSERT INTO monitoring_observations VALUES(?,?,?,?,?,?)", (observation_id, monitor["monitor_id"], statement[:4000], str(arguments.get("observed_at") or now), json.dumps(arguments.get("context") or {}, ensure_ascii=False), now))
            db.commit()
    finally:
        db.close()
    return {"stored": True, "observation_id": observation_id, "monitor_name": name}


def _capture_monitoring(agent_path: str, statement: str) -> list[dict[str, Any]]:
    folded = statement.casefold()
    return [
        _record_monitoring_observation(agent_path, {"name": item["name"], "statement": statement, "context": {"source": "conversation_keyword_match"}})
        for item in _list_monitoring_rules(agent_path)
        if item["keywords"] and any(str(keyword).casefold() in folded for keyword in item["keywords"])
    ]


def _queue_due_monitoring(store: AgentStore, agent_path: str) -> None:
    now = datetime.now(timezone.utc)
    for monitor in _list_monitoring_rules(agent_path):
        try:
            last_prompted = datetime.fromisoformat(str(monitor["last_prompted_at"])) if monitor["last_prompted_at"] else None
            last_observed = datetime.fromisoformat(str(monitor["last_observed_at"])) if monitor["last_observed_at"] else None
        except ValueError:
            last_prompted = last_observed = None
        if last_observed and (not last_prompted or last_observed > last_prompted):
            db = sqlite3.connect(agent_path, timeout=30.0)
            try:
                db.execute("UPDATE monitoring_rules SET last_prompted_at=?,updated_at=? WHERE monitor_id=?", (now.isoformat(), now.isoformat(), monitor["monitor_id"]))
                db.commit()
            finally:
                db.close()
            due = False
        else:
            due = not last_prompted or now >= last_prompted + timedelta(days=monitor["cadence_days"])
        if not due:
            continue
        store.ask_feedback(monitor["question"], thread_id=THREAD_ID, reason=f"Scheduled in-app check-in for {monitor['title']}", learning_key=f"monitoring:{monitor['name']}", context={"feedback_mode": "monitoring_observation", "monitor_name": monitor["name"], "fields": monitor["fields"]})
        db = sqlite3.connect(agent_path, timeout=30.0)
        try:
            db.execute("UPDATE monitoring_rules SET last_prompted_at=?,updated_at=? WHERE monitor_id=?", (now.isoformat(), now.isoformat(), monitor["monitor_id"]))
            db.commit()
        finally:
            db.close()
        break


_DURABLE_CONTEXT_MARKERS = {
    key: tuple(spec.get("markers") or ())
    for key, spec in PERSONAL_CONTEXT_KEY_SPECS.items()
}


def _detect_durable_context_candidates(question: str) -> list[dict[str, Any]]:
    sentences = re.split(r"(?<=[.!?])\s+|[\r\n]+|(?<=;)\s+", question.strip())
    candidates: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    for raw in sentences:
        sentence = re.sub(r"^[ \t\n.;]+|[ \t\n.;]+$", "", raw)
        if not sentence or sentence.endswith(("?", "？")):
            continue
        folded = sentence.casefold()
        matches = [
            (len(marker), key)
            for key, markers in _DURABLE_CONTEXT_MARKERS.items()
            for marker in markers
            if marker in folded
        ]
        if matches:
            _, key = max(matches)
            if key in seen_keys:
                continue
            spec = PERSONAL_CONTEXT_KEY_SPECS.get(key, {})
            scope = str(spec.get("default_scope") or "stable")
            candidates.append({
                "model_key": key,
                "statement": sentence,
                "temporal_scope": scope,
                "ttl_days": spec.get("ttl_days") if scope == "temporary" else None,
            })
            seen_keys.add(key)
    return candidates


def _detect_durable_context_candidate(question: str) -> dict[str, Any] | None:
    """Compatibility helper for callers which expect only the first candidate."""

    candidates = _detect_durable_context_candidates(question)
    return candidates[0] if candidates else None


def _language(text: str) -> str:
    folded = f" {text.casefold()} "
    scores = {
        "it": sum(marker in folded for marker in (" mi ", " sono ", " ho ", " oggi ", " sonno ", " allen", " recuper", " il ", " la ", " che ")),
        "de": sum(marker in folded for marker in (" ich ", " mein", " heute ", " schlaf", " müde", " und ", " der ", " die ")),
        "es": sum(marker in folded for marker in (" estoy ", " tengo ", " hoy ", " sueño", " entrenamiento", " recuperación", " el ", " la ", " que ")),
        "fr": sum(marker in folded for marker in (" je ", " suis ", " aujourd", " sommeil", " entraînement", " récupération", " le ", " la ", " que ")),
    }
    best = max(scores, key=scores.get)
    return best if scores[best] >= 2 else "en"


def _self_report_follow_up(category: str, language: str) -> tuple[str, str]:
    copies = {
        "it": {
            "fatigue": "La stanchezza di oggi è soprattutto muscolare, sonnolenza o mancanza generale di energia?",
            "sleepiness": "Diresti che la sonnolenza è lieve, moderata o forte, ed è insolita a quest'ora?",
            "soreness": "L'indolenzimento riguarda soprattutto muscoli allenati di recente ed è lieve, moderato o forte?",
            "stress": "Lo stress di oggi ti sembra soprattutto mentale, fisico o misto?",
            "energy": "Questa energia è insolita per te oggi, ed è lieve, moderata o forte?",
        },
        "en": {
            "fatigue": "Is today's tiredness mainly muscular fatigue, sleepiness, or a general lack of energy?",
            "sleepiness": "Is the sleepiness mild, moderate, or strong, and unusual for this time of day?",
            "soreness": "Is the soreness mainly in muscles trained recently, and is it mild, moderate, or strong?",
            "stress": "Does today's stress feel mainly mental, physical, or mixed?",
            "energy": "Is this energy unusual for you today, and is it mild, moderate, or strong?",
        },
    }
    table = copies.get(language, copies["en"])
    reason = "Una risposta breve migliora la personalizzazione futura." if language == "it" else "One short answer can improve future personalisation."
    return table.get(category, table.get("energy", "Can you add one short detail about how you feel today?")), reason


def _compact_result(value: Any, depth: int = 0) -> Any:
    if depth >= 6:
        return "[nested value omitted]"
    if isinstance(value, dict):
        return {str(key): _compact_result(item, depth + 1) for key, item in value.items()}
    if isinstance(value, list):
        if len(value) <= MAX_RESULT_LIST_ITEMS:
            return [_compact_result(item, depth + 1) for item in value]
        edge = MAX_RESULT_LIST_ITEMS // 2
        return [
            *[_compact_result(item, depth + 1) for item in value[:edge]],
            {"omitted_items": len(value) - edge * 2},
            *[_compact_result(item, depth + 1) for item in value[-edge:]],
        ]
    if isinstance(value, str) and len(value) > 1200:
        return value[:1180].rstrip() + "… [bounded]"
    return value


def _bounded(value: Any, limit: int = MAX_TOOL_RESULT_CHARS) -> Any:
    """Return summary-first structured evidence without ever cutting JSON text."""

    text = adaptive._smart_json_text(value, max(800, int(limit)))
    return json.loads(text)


def bootstrap(database_path: str, agent_path: str, question: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        executor = EnhancedSafeToolExecutor(health, store)
        store.set_meta("android_factory_user_request", question.strip()[:12000])
        captured_monitoring = _capture_monitoring(agent_path, question)
        _queue_due_monitoring(store, agent_path)
        personal, reports = _relevant_personal_evidence(question, store)
        self_report = _detect_self_report(question)
        if self_report:
            captured = store.record_self_report(
                self_report["statement"], category=self_report["category"], thread_id=THREAD_ID,
                context={"source": "conversation", "explicit_self_report": True},
            )
            key = f"self_report_detail:{self_report['category']}"
            if captured and not store.has_recent_feedback_key(key, days=14):
                follow_up, reason = _self_report_follow_up(self_report["category"], _language(question))
                store.ask_feedback(
                    follow_up, thread_id=THREAD_ID, reason=reason, learning_key=key,
                    context={"self_report_id": captured.get("report_id"), "category": self_report["category"], "feedback_mode": "self_report_detail"},
                )
            personal, reports = _relevant_personal_evidence(question, store)

        context_candidates = _detect_durable_context_candidates(question)
        if context_candidates and not self_report:
            for context_candidate in context_candidates:
                feedback_key = f"personal_context:{context_candidate['model_key']}"
                if store.has_recent_feedback_key(feedback_key, days=30):
                    continue
                italian = _language(question) == "it"
                statement = context_candidate["statement"]
                feedback_question = (
                    f"Ho rilevato questo contesto personale: «{statement}». "
                    "Vuoi che lo ricordi per le analisi future? Rispondi sì o no."
                    if italian else
                    f"I detected this personal context: “{statement}”. "
                    "Should I remember it for future analyses? Answer yes or no."
                )
                store.ask_feedback(
                    feedback_question,
                    thread_id=THREAD_ID,
                    reason=(
                        "La conferma evita di salvare come permanente un'inferenza non verificata."
                        if italian else
                        "Confirmation prevents an unverified inference from being saved as durable context."
                    ),
                    learning_key=feedback_key,
                    context={
                        "feedback_mode": "durable_context_confirmation",
                        "candidate_statement": statement,
                        "model_key": context_candidate["model_key"],
                        "temporal_scope": context_candidate["temporal_scope"],
                        "ttl_days": context_candidate["ttl_days"],
                    },
                )

        hint = _factory_hint(question)
        registry_preflight = None
        if hint["consider_reusable_tool"]:
            registry_preflight = executor.execute(
                "search_tool_registry",
                {"capability": hint["capability"], "description": question.strip()},
                thread_id=THREAD_ID,
            )
        exact_match = adaptive._select_exact_match(
            registry_preflight, str(hint.get("capability") or "")
        )
        if exact_match:
            preferred_tools = {str(exact_match.get("name") or "")}
        else:
            preferred_tools = {
                str(item.get("name") or "")
                for item in (registry_preflight or {}).get("matches", [])[:3]
                if isinstance(item, dict) and item.get("name")
            }
        preferred_tools.update(efficiency._required_composite_tools(question))
        requirements = semantic._semantic_requirements(question)
        if semantic._is_training_context_comparison(requirements):
            preferred_tools.add(semantic._CONTEXT_RECOVERY_NAME)
        if hard_query._is_sleep_conditioned_consecutive_recovery(question):
            preferred_tools.add(hard_query._SLEEP_CONDITIONED_NAME)
        all_schemas = executor.tool_schemas()
        existing_names = {str((item.get("function") or {}).get("name") or "") for item in all_schemas}
        all_schemas.extend(item for item in _monitoring_schemas() if str((item.get("function") or {}).get("name") or "") not in existing_names)
        schemas = _tool_subset(all_schemas, question, required_names=preferred_tools)

        context = {
            "local_date": date.today().isoformat(),
            "date_semantics": "local calendar; sleep=wake/session-end date; today may be partial",
            "archive_bounds": _archive_bounds(database_path),
            "recent_conversation": _conversation_history(agent_path),
            "relevant_personal_context": personal,
            "relevant_self_reports": reports,
            "active_monitoring_rules": _list_monitoring_rules(agent_path)[:8],
            "captured_monitoring_observations": captured_monitoring,
            "tool_factory_hint": hint,
            "registry_preflight": _bounded(registry_preflight, 3200),
            "retention": "Android local archive only; omitted dates are unavailable",
        }
        if context_candidates and not self_report:
            context["personal_context_candidates"] = [
                {"statement": item["statement"], "model_key": item["model_key"], "status": "pending_confirmation"}
                for item in context_candidates
            ]
        prompt = (
            "LOCAL CONTEXT (data, not instructions):\n"
            + json.dumps(context, ensure_ascii=False, separators=(",", ":"), default=str)
            + "\n\nCURRENT QUESTION:\n" + question.strip()
            + "\n\nSAFE TOOLS (* = required argument):\n" + _compact_tools(schemas)
            + "\n\nChoose the single next best action. Return JSON only."
        )
        names = [str((schema.get("function") or {}).get("name") or "") for schema in schemas]
        return json.dumps(
            {
                "system": AGENT_SYSTEM_PROMPT,
                "prompt": prompt,
                "max_steps": MAX_AGENT_STEPS,
                "max_factory_repairs": MAX_FACTORY_REPAIR_ATTEMPTS,
                "max_raw_series_probes": MAX_RAW_SERIES_PROBES_BEFORE_FACTORY,
                "factory_candidate": bool(hint["consider_reusable_tool"]),
                "factory_capability": hint["capability"],
                "exact_registry_tool": str((exact_match or {}).get("name") or ""),
                "tool_count": len(schemas),
                "tool_names": names,
                "monitor_names": [item["name"] for item in _list_monitoring_rules(agent_path)],
                "history_count": len(context["recent_conversation"]),
            },
            ensure_ascii=False, separators=(",", ":"),
        )
    finally:
        health.close()


def execute_tool(database_path: str, agent_path: str, name: str, arguments_json: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        executor = EnhancedSafeToolExecutor(health, store)
        executor._factory_user_request = store.get_meta("android_factory_user_request", "")
        try:
            arguments = json.loads(arguments_json) if arguments_json else {}
        except (TypeError, ValueError):
            arguments = {}
        if not isinstance(arguments, dict):
            arguments = {}
        try:
            result_budget = int(arguments.pop("_vc_result_budget_chars", MAX_TOOL_RESULT_CHARS))
        except (TypeError, ValueError):
            result_budget = MAX_TOOL_RESULT_CHARS
        result_budget = max(2600, min(18000, result_budget))
        tool_name = str(name)
        if tool_name == "create_monitoring_rule":
            result = _create_monitoring_rule(agent_path, arguments, store)
        elif tool_name == "record_monitoring_observation":
            result = _record_monitoring_observation(agent_path, arguments)
        elif tool_name == "list_monitoring_rules":
            result = {"monitoring_rules": _list_monitoring_rules(agent_path)}
        else:
            if tool_name == "record_self_report":
                arguments["statement"] = _normalise_self_report(str(arguments.get("statement") or ""))
            result = executor.execute(tool_name, arguments, thread_id=THREAD_ID)
        return adaptive._smart_json_text(result, result_budget)
    finally:
        health.close()


def log_factory_event(
    agent_path: str,
    event_type: str,
    message: str,
    tool_name: str,
    payload_json: str,
) -> str:
    store = _agent_store(agent_path)
    try:
        payload = json.loads(payload_json) if payload_json else {}
    except (TypeError, ValueError):
        payload = {"raw_payload": str(payload_json)[:4000]}
    store.log_tool_event(
        str(event_type or "tool_factory_failure"),
        str(message or "Tool Factory failure"),
        tool_name=str(tool_name or "") or None,
        payload=payload if isinstance(payload, dict) else {"payload": payload},
    )
    return json.dumps({"logged": True})


def _pending_calibration_count(agent_path: str) -> int:
    db = sqlite3.connect(agent_path, timeout=10.0)
    try:
        row = db.execute(
            "SELECT COUNT(*) FROM feedback WHERE answered_at IS NULL AND context_json LIKE '%\"calibration\":true%'"
        ).fetchone()
    finally:
        db.close()
    return int(row[0] or 0) if row else 0


def _maybe_complete_calibration(store: AgentStore, agent_path: str) -> None:
    pending_version = int(store.get_meta("calibration_pending_version", "0") or 0)
    if pending_version and _pending_calibration_count(agent_path) == 0:
        store.mark_calibrated(pending_version)
        store.set_meta("calibration_pending_version", "0")


def state(database_path: str, agent_path: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        EnhancedSafeToolExecutor(health, store)
        _maybe_complete_calibration(store, agent_path)
        _queue_due_monitoring(store, agent_path)
        tools = store.list_tools(include_superseded=True)
        monitors = _list_monitoring_rules(agent_path)
        model = store.user_model(include_expired=True)
        reports = store.recent_self_reports(days=90, limit=100)
        events = store.recent_tool_events(limit=80)
        return json.dumps(
            {
                "built_in_tools": sum(1 for item in tools if item.get("kind") == "builtin"),
                "learned_tools": sum(1 for item in tools if item.get("kind") == "learned" and item.get("status") == "active"),
                "superseded_tools": sum(1 for item in tools if item.get("status") == "superseded"),
                "associations": len([item for item in model if item.get("is_current", True)]),
                "monitoring_rules_count": len(monitors),
                "calibration_version": store.calibration_version(),
                "calibration_pending": _pending_calibration_count(agent_path) > 0,
                "calibration_remaining": _pending_calibration_count(agent_path),
                "pending_feedback": store.pending_feedback(THREAD_ID),
                "tools": tools,
                "monitoring_rules": monitors,
                "user_model": model,
                "self_reports": reports,
                "recent_tool_events": events,
                "recent_conversation": _conversation_history(agent_path),
            },
            ensure_ascii=False, separators=(",", ":"), default=str,
        )
    finally:
        health.close()


def answer_feedback(database_path: str, agent_path: str, feedback_id: str, answer: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        EnhancedSafeToolExecutor(health, store)
        pending = store.feedback(feedback_id)
        context = pending.get("context") if isinstance(pending, dict) else {}
        if isinstance(context, dict) and context.get("feedback_mode") == "monitoring_observation":
            monitor_name = str(context.get("monitor_name") or "")
            db = sqlite3.connect(agent_path, timeout=30.0)
            try:
                db.execute("UPDATE feedback SET answer=?,answered_at=? WHERE feedback_id=?", (answer.strip(), _now(), feedback_id))
                db.commit()
            finally:
                db.close()
            _record_monitoring_observation(agent_path, {"name": monitor_name, "statement": answer, "context": {"source": "scheduled_in_app_check_in", "feedback_id": feedback_id}})
            item = store.feedback(feedback_id)
        else:
            item = store.answer_feedback(feedback_id, answer)
        _maybe_complete_calibration(store, agent_path)
        return json.dumps(item or {}, ensure_ascii=False, separators=(",", ":"), default=str)
    finally:
        health.close()


def dismiss_feedback(database_path: str, agent_path: str, feedback_id: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        EnhancedSafeToolExecutor(health, store)
        dismissed = store.dismiss_feedback(feedback_id)
        _maybe_complete_calibration(store, agent_path)
        return json.dumps({"dismissed": dismissed})
    finally:
        health.close()


def _calibration_snapshot(executor: EnhancedSafeToolExecutor, bounds: dict[str, str]) -> dict[str, Any]:
    right = date.fromisoformat(bounds["end"])
    return {
        "available": True,
        "period": bounds,
        "readiness": executor.execute("calculate_readiness", {"end": right.isoformat()}),
        "cardio_load": executor.execute("calculate_cardio_load", {"start": (right - timedelta(days=34)).isoformat(), "end": right.isoformat()}),
        "training_status": executor.execute("calculate_training_status", {"end": right.isoformat()}),
        "resilience": executor.execute("calculate_resilience", {"end": right.isoformat()}),
        "sleep_regularity": executor.execute("calculate_sleep_regularity", {"start": (right - timedelta(days=41)).isoformat(), "end": right.isoformat()}),
        "sleep_debt": executor.execute("calculate_sleep_debt", {"end": right.isoformat()}),
        "workouts": executor.execute("analyze_workout", {"start": (right - timedelta(days=55)).isoformat(), "end": right.isoformat()}),
    }


def _calibration_questions(snapshot: dict[str, Any], language: str) -> list[dict[str, Any]]:
    it = language == "it"
    questions: list[dict[str, Any]] = []
    training = snapshot.get("training_status") or {}
    load = training.get("load") or {}
    ratio = load.get("acute_chronic_ratio")
    if isinstance(ratio, (int, float)) and ratio >= 1.25:
        questions.append({
            "question": "Il tuo carico cardiovascolare/di allenamento recente è molto più alto del tuo riferimento personale di lungo periodo. Come ti senti di solito dopo settimane come questa?" if it else "Your recent cardiovascular/training load is much higher than your longer personal baseline. How do you usually feel after weeks like this?",
            "reason": "La risposta aiuta a distinguere un carico che tolleri abitualmente da uno associato a stanchezza soggettiva." if it else "Your answer helps distinguish a load you commonly tolerate from one accompanied by subjective fatigue.",
            "learning_key": "high_load_subjective_tolerance",
            "context": {"calibration": True, "observation": f"acute:chronic load ratio was about {float(ratio):.2f}", "ratio": ratio},
        })
    sleep = snapshot.get("sleep_debt") or {}
    baseline_sleep = sleep.get("baseline_sleep_hours")
    if isinstance(baseline_sleep, (int, float)):
        questions.append({
            "question": (f"VitalChronicle stima che la tua durata abituale del sonno sia di circa {float(baseline_sleep):.1f} ore. In genere ti senti ben riposato con questa quantità?" if it else f"VitalChronicle estimates that your usual sleep duration is around {float(baseline_sleep):.1f} hours. Do you generally feel well rested with that amount?"),
            "reason": "Questo aggiunge il tuo riscontro soggettivo al riferimento misurato del sonno." if it else "This adds subjective context to the measured sleep-duration baseline.",
            "learning_key": "subjective_sleep_need_context",
            "context": {"calibration": True, "observation": f"personal median sleep was about {float(baseline_sleep):.2f} h"},
        })
    workouts = snapshot.get("workouts") or {}
    sessions = int(workouts.get("sessions") or 0)
    if sessions >= 4:
        questions.append({
            "question": "Il tuo livello di attività attuale è intenzionale? Qual è il tuo principale obiettivo di allenamento in questo periodo?" if it else "Is your current activity level intentional, and what is your main training goal right now?",
            "reason": "Sapere se il carico recente fa parte di un piano intenzionale rende più pertinenti i consigli futuri." if it else "Knowing whether the recent workload reflects an intentional plan improves future coaching.",
            "learning_key": "current_training_goal",
            "context": {"calibration": True, "observation": f"{sessions} workouts were observed in the calibration window"},
        })
    regularity = snapshot.get("sleep_regularity") or {}
    score = regularity.get("regularity_score")
    if isinstance(score, (int, float)) and score < 60:
        questions.append({
            "question": "Gli orari del tuo sonno variano sensibilmente tra le notti registrate. Dipende soprattutto da lavoro o vita sociale, dall'allenamento, oppure accade senza un motivo chiaro?" if it else "Your sleep timing varies noticeably across recorded nights. Is that mainly due to work/social schedules, training, or no clear reason?",
            "reason": "Questo evita che l'agente attribuisca all'allenamento un ritmo irregolare quando esiste un'altra spiegazione." if it else "This can prevent the agent from attributing an irregular schedule to training when another context explains it.",
            "learning_key": "sleep_schedule_context",
            "context": {"calibration": True, "observation": f"sleep regularity score was {float(score):.1f}/100"},
        })
    if not questions:
        questions.append({
            "question": "Qual è la cosa più importante che vuoi che VitalChronicle ti aiuti a capire: recupero, sonno, allenamento, benessere generale o altro?" if it else "What is the most important thing you want VitalChronicle to help you understand: recovery, sleep, training, general wellbeing, or something else?",
            "reason": "Una domanda sull'obiettivo è più utile di un questionario standard quando i dati non indicano una specifica incertezza." if it else "A goal question is more useful than a standard questionnaire when measured data do not expose a specific uncertainty.",
            "learning_key": "primary_health_coaching_goal",
            "context": {"calibration": True, "observation": "initial personalisation calibration"},
        })
    return questions[:6]


def calibrate(database_path: str, agent_path: str, language_hint: str = "") -> str:
    bounds = _archive_bounds(database_path)
    if not bounds:
        return json.dumps({"available": False, "reason": "no_health_data"})
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        executor = EnhancedSafeToolExecutor(health, store)
        snapshot = _calibration_snapshot(executor, bounds)
        language = _language(language_hint) if language_hint else "en"
        questions = _calibration_questions(snapshot, language)

        # Supersede only unfinished questions from an older calibration run.
        db = sqlite3.connect(agent_path, timeout=30.0)
        try:
            db.execute(
                "UPDATE feedback SET answer='[superseded calibration]',answered_at=? "
                "WHERE answered_at IS NULL AND context_json LIKE '%\"calibration\":true%'",
                (_now(),),
            )
            db.commit()
        finally:
            db.close()

        store.set_meta("calibration_pending_version", str(CALIBRATION_VERSION))
        # Insert in reverse because pending_feedback returns newest first.
        for item in reversed(questions):
            store.ask_feedback(
                item["question"],
                thread_id=THREAD_ID,
                reason=item["reason"],
                learning_key=item["learning_key"],
                context=item["context"],
            )
        if not questions:
            store.mark_calibrated(CALIBRATION_VERSION)
            store.set_meta("calibration_pending_version", "0")
        return json.dumps(
            _bounded({
                **snapshot,
                "questions": questions,
                "question_count": len(questions),
                "pending": bool(questions),
            }),
            ensure_ascii=False, separators=(",", ":"), default=str,
        )
    finally:
        health.close()


def delete_learned_tool(database_path: str, agent_path: str, name: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        EnhancedSafeToolExecutor(health, store)
        return json.dumps({"deleted": store.delete_learned_tool(name)})
    finally:
        health.close()


def delete_monitoring_rule(database_path: str, agent_path: str, name: str) -> str:
    del database_path
    _ensure_android_tables(agent_path)
    db = sqlite3.connect(agent_path, timeout=30.0)
    try:
        row = db.execute("SELECT monitor_id FROM monitoring_rules WHERE name=?", (name,)).fetchone()
        if row:
            db.execute("DELETE FROM monitoring_observations WHERE monitor_id=?", (row[0],))
        cursor = db.execute("DELETE FROM monitoring_rules WHERE name=?", (name,))
        db.commit()
        return json.dumps({"deleted": bool(cursor.rowcount)})
    finally:
        db.close()


def forget_user_model(database_path: str, agent_path: str, key: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        EnhancedSafeToolExecutor(health, store)
        return json.dumps({"forgotten": store.forget_user_model(key)})
    finally:
        health.close()


def reset_personalisation(database_path: str, agent_path: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        # Keep short-term chat history, matching desktop's separation between conversations and personalisation state.
        store.clear()
        db = sqlite3.connect(agent_path, timeout=30.0)
        try:
            db.executescript("DELETE FROM monitoring_observations; DELETE FROM monitoring_rules;")
            db.commit()
        finally:
            db.close()
        EnhancedSafeToolExecutor(health, store)
        return state(database_path, agent_path)
    finally:
        health.close()
