"""Fast local routing for explicit subjective self-reports on Android.

Short statements about how the user feels are dated personal context, not
health-analysis questions, so this path deliberately avoids generative AI.
"""
from __future__ import annotations

import json
from pathlib import Path

from google_health_viewer.agent_store import AgentStore


# Check absence/negation before positive phrases: "I'm not tired" must never be
# interpreted as fatigue being present.
_ABSENT_PATTERNS: dict[str, tuple[str, ...]] = {
    "fatigue": (
        "i'm not tired", "i am not tired", "not tired", "i don't feel tired", "i do not feel tired",
        "non sono stanco", "non sono stanca", "non mi sento stanco", "non mi sento stanca",
        "non sono affaticato", "non sono affaticata", "nicht müde", "no estoy cansado", "no estoy cansada",
        "pas fatigué", "pas fatiguée",
    ),
    "sleepiness": (
        "i'm not sleepy", "i am not sleepy", "not sleepy", "i don't feel sleepy", "i do not feel sleepy",
        "non ho sonno", "non sono assonnato", "non sono assonnata", "nicht schläfrig", "no tengo sueño",
        "pas somnolent", "pas somnolente",
    ),
    "soreness": (
        "i'm not sore", "i am not sore", "not sore", "no muscle soreness", "i don't feel sore", "i do not feel sore",
        "non sono indolenzito", "non sono indolenzita", "nessun dolore muscolare", "keinen muskelkater",
        "sin dolor muscular", "pas de courbatures",
    ),
    "stress": (
        "i'm not stressed", "i am not stressed", "not stressed", "i don't feel stressed", "i do not feel stressed",
        "non sono stressato", "non sono stressata", "non mi sento stressato", "non mi sento stressata",
        "nicht gestresst", "no estoy estresado", "no estoy estresada", "pas stressé", "pas stressée",
    ),
}

_PRESENT_PATTERNS: dict[str, tuple[str, ...]] = {
    "fatigue": (
        "mi sento stanco", "mi sento stanca", "sono stanco", "sono stanca", "mi sento affaticato", "mi sento affaticata",
        "i feel tired", "i'm tired", "i am tired", "i feel fatigued", "ich bin müde", "estoy cansado", "estoy cansada",
        "je suis fatigué", "je suis fatiguée",
    ),
    "sleepiness": (
        "ho sonno", "mi sento assonnato", "mi sento assonnata", "i feel sleepy", "i'm sleepy", "i am sleepy",
        "schläfrig", "tengo sueño", "somnolent", "somnolente",
    ),
    "soreness": (
        "sono indolenzito", "sono indolenzita", "dolori muscolari", "muscoli indolenziti", "i feel sore", "i'm sore",
        "i am sore", "muscle soreness", "muskelkater", "dolor muscular", "courbatures",
    ),
    "stress": (
        "mi sento stressato", "mi sento stressata", "sono stressato", "sono stressata", "i feel stressed", "i'm stressed",
        "i am stressed", "gestresst", "estresado", "estresada", "stressé", "stressée",
    ),
    "energy": (
        "mi sento energico", "mi sento energica", "pieno di energia", "piena di energia", "i feel energetic", "i'm energetic",
        "i am energetic", "full of energy", "voller energie", "con mucha energía", "plein d'énergie", "pleine d'énergie",
    ),
}

_LOW_ENERGY_PATTERNS = (
    "i have no energy", "i've got no energy", "low energy", "i feel drained", "i'm drained", "i am drained",
    "non ho energie", "senza energie", "mi sento scarico", "mi sento scarica", "sono scarico", "sono scarica",
    "keine energie", "sin energía", "sans énergie",
)

_POSITIVE_WELLBEING_PATTERNS = (
    "i feel great", "i'm feeling great", "i am feeling great", "i feel good", "i'm feeling good", "i am feeling good",
    "i'm fine", "i am fine", "i'm doing great", "i am doing great", "i'm doing well", "i am doing well",
    "sto alla grande", "sto benissimo", "sto bene", "mi sento benissimo", "mi sento bene", "mi sento in forma",
    "mir geht es gut", "ich fühle mich gut", "estoy muy bien", "me siento bien", "je vais très bien", "je me sens bien",
)

_QUERY_MARKERS = (
    "?", "why ", " why", "how ", " how", "what ", " what", "when ", "where ", "compare", "compared",
    " versus ", " vs ", "does ", "do i ", "should ", "could ", "can ", "is this ", "is it ",
    "perché", "perche", "come ", "quanto", "quanta", "quanti", "quante", "cosa ", "dimmi", "analizza", "spieg",
    "warum ", "wie ", "qué ", "por qué", "como ", "cómo ", "pourquoi ", "comment ",
)

_STRONG_LANGUAGE_MARKERS = {
    "it": (
        "sto alla grande", "sto benissimo", "sto bene", "mi sento", "sono stanco", "sono stanca",
        "non sono", "non ho sonno", "ho sonno", "senza energie",
    ),
    "de": ("ich bin", "ich fühle", "mir geht", "nicht müde", "keine energie"),
    "es": ("estoy ", "me siento", "tengo sueño", "no tengo sueño", "sin energía"),
    "fr": ("je suis", "je me sens", "je vais", "pas fatigu", "sans énergie"),
}


def _normalise(text: str) -> str:
    return " ".join(text.strip().casefold().replace("’", "'").split())


def _detect(text: str) -> dict[str, str] | None:
    folded = _normalise(text)
    if not folded:
        return None
    for category, markers in _ABSENT_PATTERNS.items():
        if any(marker in folded for marker in markers):
            return {"category": category, "state": "absent"}
    if any(marker in folded for marker in _LOW_ENERGY_PATTERNS):
        return {"category": "energy", "state": "low"}
    if any(marker in folded for marker in _POSITIVE_WELLBEING_PATTERNS):
        return {"category": "wellbeing", "state": "positive"}
    for category, markers in _PRESENT_PATTERNS.items():
        if any(marker in folded for marker in markers):
            return {"category": category, "state": "present"}
    return None


def _is_standalone_statement(text: str) -> bool:
    folded = _normalise(text)
    if not folded or len(folded) > 240:
        return False
    padded = f" {folded} "
    return not any(marker in padded for marker in _QUERY_MARKERS)


def _language(text: str) -> str:
    folded = _normalise(text)
    for language, markers in _STRONG_LANGUAGE_MARKERS.items():
        if any(marker in folded for marker in markers):
            return language
    padded = f" {folded} "
    scores = {
        "it": sum(m in padded for m in (" mi ", " sono ", " sto ", " ho ", " oggi ", " non ", " bene ", " stanco")),
        "de": sum(m in padded for m in (" ich ", " bin ", " heute ", " nicht ", " müde", " gut ")),
        "es": sum(m in padded for m in (" estoy ", " tengo ", " hoy ", " no ", " cansad", " bien ")),
        "fr": sum(m in padded for m in (" je ", " suis ", " aujourd", " pas ", " fatigu", " bien ")),
    }
    best = max(scores, key=scores.get)
    return best if scores[best] >= 2 else "en"


def _follow_up(category: str, state: str, language: str) -> tuple[str, str]:
    if language == "it":
        if state == "absent" and category == "fatigue":
            question = "Il fatto che tu non ti senta stanco oggi è normale per te a quest'ora o ti senti meglio del solito?"
        elif state == "absent" and category == "sleepiness":
            question = "L'assenza di sonnolenza oggi è normale per te a quest'ora o ti senti più vigile del solito?"
        elif state == "absent" and category == "soreness":
            question = "L'assenza di indolenzimento è normale rispetto ai tuoi allenamenti recenti o ti senti recuperato meglio del solito?"
        elif state == "absent" and category == "stress":
            question = "Il basso stress di oggi è normale per te o ti senti più rilassato del solito?"
        elif category == "wellbeing":
            question = "Il fatto che tu ti senta così bene oggi è normale per te o ti senti meglio del solito?"
        elif category == "fatigue":
            question = "La stanchezza di oggi è soprattutto muscolare, sonnolenza o mancanza generale di energia?"
        elif category == "sleepiness":
            question = "Diresti che la sonnolenza è lieve, moderata o forte, ed è insolita a quest'ora?"
        elif category == "soreness":
            question = "L'indolenzimento riguarda soprattutto muscoli allenati di recente ed è lieve, moderato o forte?"
        elif category == "stress":
            question = "Lo stress di oggi ti sembra soprattutto mentale, fisico o misto?"
        else:
            question = "Il tuo livello di energia oggi è normale per te, più alto o più basso del solito?"
        return question, "Una risposta breve migliora la personalizzazione futura senza trasformare questo stato momentaneo in un tratto permanente."

    if language == "de":
        return (
            "Ist dieses Befinden heute für dich typisch oder deutlich besser bzw. schlechter als sonst?",
            "Eine kurze Antwort verbessert die Personalisierung, ohne einen momentanen Zustand als dauerhaftes Merkmal zu behandeln.",
        )
    if language == "es":
        return (
            "¿Este estado de hoy es habitual para ti o te sientes claramente mejor o peor de lo normal?",
            "Una respuesta breve mejora la personalización sin convertir un estado momentáneo en un rasgo permanente.",
        )
    if language == "fr":
        return (
            "Cet état aujourd'hui est-il habituel pour toi, ou te sens-tu nettement mieux ou moins bien que d'habitude ?",
            "Une réponse courte améliore la personnalisation sans transformer un état momentané en trait permanent.",
        )

    if state == "absent" and category == "fatigue":
        question = "Is not feeling tired today typical for you at this time, or do you feel better than usual?"
    elif state == "absent" and category == "sleepiness":
        question = "Is the lack of sleepiness typical for you at this time, or do you feel more alert than usual?"
    elif state == "absent" and category == "soreness":
        question = "Is having no soreness typical after your recent training, or do you feel better recovered than usual?"
    elif state == "absent" and category == "stress":
        question = "Is feeling unstressed today typical for you, or do you feel more relaxed than usual?"
    elif category == "wellbeing":
        question = "Is feeling this good today typical for you, or do you feel better than usual?"
    elif category == "fatigue":
        question = "Is today's tiredness mainly muscular fatigue, sleepiness, or a general lack of energy?"
    elif category == "sleepiness":
        question = "Is the sleepiness mild, moderate, or strong, and unusual for this time of day?"
    elif category == "soreness":
        question = "Is the soreness mainly in muscles trained recently, and is it mild, moderate, or strong?"
    elif category == "stress":
        question = "Does today's stress feel mainly mental, physical, or mixed?"
    else:
        question = "Is your energy today typical for you, higher than usual, or lower than usual?"
    return question, "One short answer improves future personalisation without turning a temporary state into a permanent trait."


def _acknowledgement(category: str, state: str, language: str, follow_up_queued: bool) -> str:
    if language == "it":
        descriptions = {
            ("fatigue", "absent"): "che in questo momento non ti senti stanco",
            ("sleepiness", "absent"): "che in questo momento non hai sonno",
            ("soreness", "absent"): "che in questo momento non sei indolenzito",
            ("stress", "absent"): "che in questo momento non ti senti stressato",
            ("wellbeing", "positive"): "che oggi ti senti bene",
            ("energy", "low"): "che oggi hai poca energia",
            ("energy", "present"): "che oggi ti senti energico",
            ("fatigue", "present"): "che oggi ti senti stanco",
            ("sleepiness", "present"): "che oggi hai sonno",
            ("soreness", "present"): "che oggi sei indolenzito",
            ("stress", "present"): "che oggi ti senti stressato",
        }
        answer = f"Ricevuto — ho salvato localmente {descriptions.get((category, state), 'come ti senti oggi')} come osservazione soggettiva datata, non come caratteristica permanente."
        if follow_up_queued:
            answer += " Se vuoi, puoi rispondere alla breve domanda di approfondimento qui sotto."
        return answer

    if language == "de":
        answer = "Verstanden — ich habe dein aktuelles Befinden lokal als datierte subjektive Beobachtung gespeichert, nicht als dauerhaftes Merkmal."
        return answer + (" Du kannst die kurze Rückfrage unten beantworten, wenn du die Personalisierung verfeinern möchtest." if follow_up_queued else "")
    if language == "es":
        answer = "Entendido: he guardado localmente cómo te sientes ahora como una observación subjetiva fechada, no como un rasgo permanente."
        return answer + (" Si quieres, puedes responder a la breve pregunta de seguimiento de abajo." if follow_up_queued else "")
    if language == "fr":
        answer = "Compris — j'ai enregistré localement ton état actuel comme une observation subjective datée, pas comme un trait permanent."
        return answer + (" Tu peux répondre à la courte question ci-dessous si tu veux affiner la personnalisation." if follow_up_queued else "")

    descriptions = {
        ("fatigue", "absent"): "that you're not feeling tired right now",
        ("sleepiness", "absent"): "that you're not feeling sleepy right now",
        ("soreness", "absent"): "that you're not feeling sore right now",
        ("stress", "absent"): "that you're not feeling stressed right now",
        ("wellbeing", "positive"): "that you're feeling good today",
        ("energy", "low"): "that your energy feels low today",
        ("energy", "present"): "that you're feeling energetic today",
        ("fatigue", "present"): "that you're feeling tired today",
        ("sleepiness", "present"): "that you're feeling sleepy today",
        ("soreness", "present"): "that you're feeling sore today",
        ("stress", "present"): "that you're feeling stressed today",
    }
    answer = f"Got it — I saved {descriptions.get((category, state), 'how you feel today')} locally as a dated subjective observation, not as a permanent trait."
    if follow_up_queued:
        answer += " You can answer the short follow-up below if you want to refine the personalisation."
    return answer


def route(agent_path: str, text: str) -> str:
    """Capture a subjective statement and optionally short-circuit generation.

    handled=true is returned only for a standalone self-report. When a report is
    embedded in a real question, it is captured but normal health analysis continues.
    """
    statement = text.strip()
    detected = _detect(statement)
    if not detected:
        return json.dumps({"handled": False, "captured": False}, ensure_ascii=False)

    category = detected["category"]
    state = detected["state"]
    store = AgentStore(Path(agent_path))
    report = store.record_self_report(
        statement,
        category=category,
        thread_id="android-local",
        context={
            "source": "conversation",
            "explicit_self_report": True,
            "state": state,
            "router": "android_self_report_v2",
        },
    )

    standalone = _is_standalone_statement(statement)
    queued = False
    if standalone and report:
        learning_key = f"self_report_detail:{category}:{state}"
        if not store.has_recent_feedback_key(learning_key, days=14):
            language = _language(statement)
            question, reason = _follow_up(category, state, language)
            store.ask_feedback(
                question,
                thread_id="android-local",
                reason=reason,
                learning_key=learning_key,
                context={
                    "self_report_id": report.get("report_id"),
                    "category": category,
                    "state": state,
                    "feedback_mode": "self_report_detail",
                },
            )
            queued = True

    language = _language(statement)
    return json.dumps(
        {
            "handled": standalone,
            "captured": True,
            "category": category,
            "state": state,
            "follow_up_queued": queued,
            "answer": _acknowledgement(category, state, language, queued) if standalone else "",
        },
        ensure_ascii=False,
        separators=(",", ":"),
        default=str,
    )
