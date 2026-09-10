package io.github.sebrolens.vitalchronicle.android

import com.chaquo.python.Python

class PythonCore {
    private val module by lazy { Python.getInstance().getModule("mobile_bridge") }
    private val dashboardModule by lazy { Python.getInstance().getModule("android_dashboard") }
    private val nanoRouterModule by lazy { Python.getInstance().getModule("nano_router") }
    private val plannerModule by lazy { Python.getInstance().getModule("ai_planner_bridge") }
    private val agentModule by lazy { Python.getInstance().getModule("android_agent_bridge") }
    private val selfReportRouterModule by lazy { Python.getInstance().getModule("android_self_report_router") }

    fun specs(): List<DataTypeSpec> = parseSpecs(module.callAttr("data_type_specs").toString())

    fun normalize(dataType: String, payloadJson: String, recordKind: String): String =
        module.callAttr("normalize_records", dataType, payloadJson, recordKind).toString()

    fun dashboard(recordsJson: String, referenceDay: String): String =
        module.callAttr("dashboard", recordsJson, referenceDay).toString()

    fun evidence(recordsJson: String, start: String, endExclusive: String): String =
        module.callAttr("evidence", recordsJson, start, endExclusive).toString()

    fun dashboardFromDatabase(databasePath: String, referenceDay: String): String =
        dashboardModule.callAttr("dashboard_from_sqlite", databasePath, referenceDay).toString()

    fun evidenceFromDatabase(databasePath: String, start: String, endExclusive: String): String =
        module.callAttr("evidence_from_sqlite", databasePath, start, endExclusive).toString()

    fun nanoEvidenceFromDatabase(
        databasePath: String,
        start: String,
        endExclusive: String,
        question: String,
    ): String = nanoRouterModule.callAttr(
        "nano_evidence_from_sqlite",
        databasePath,
        start,
        endExclusive,
        question,
    ).toString()

    fun aiPlannerCatalogFromDatabase(databasePath: String): String =
        plannerModule.callAttr("catalog_from_sqlite", databasePath).toString()

    fun aiPlannerRequest(catalogJson: String, question: String): String =
        plannerModule.callAttr("planner_request", catalogJson, question).toString()

    fun resolveAiPlan(catalogJson: String, rawPlan: String): String =
        plannerModule.callAttr("resolve_plan", catalogJson, rawPlan).toString()

    fun plannedEvidenceFromDatabase(databasePath: String, planJson: String): String =
        plannerModule.callAttr("evidence_from_sqlite", databasePath, planJson).toString()

    fun routePersonalAgentSelfReport(agentPath: String, text: String): String =
        selfReportRouterModule.callAttr("route", agentPath, text).toString()

    fun personalAgentBootstrap(databasePath: String, agentPath: String, question: String): String =
        agentModule.callAttr("bootstrap", databasePath, agentPath, question).toString()

    fun executePersonalAgentTool(
        databasePath: String,
        agentPath: String,
        name: String,
        argumentsJson: String,
    ): String = agentModule.callAttr(
        "execute_tool", databasePath, agentPath, name, argumentsJson
    ).toString()

    fun recordPersonalAgentExchange(agentPath: String, question: String, answer: String): String =
        agentModule.callAttr("record_exchange", agentPath, question, answer).toString()

    fun clearPersonalAgentConversation(agentPath: String): String =
        agentModule.callAttr("clear_conversation", agentPath).toString()

    fun personalAgentState(databasePath: String, agentPath: String): String =
        agentModule.callAttr("state", databasePath, agentPath).toString()

    fun calibratePersonalAgent(
        databasePath: String,
        agentPath: String,
        languageHint: String = "",
    ): String = agentModule.callAttr("calibrate", databasePath, agentPath, languageHint).toString()

    fun answerPersonalAgentFeedback(
        databasePath: String,
        agentPath: String,
        feedbackId: String,
        answer: String,
    ): String = agentModule.callAttr(
        "answer_feedback", databasePath, agentPath, feedbackId, answer
    ).toString()

    fun dismissPersonalAgentFeedback(
        databasePath: String,
        agentPath: String,
        feedbackId: String,
    ): String = agentModule.callAttr(
        "dismiss_feedback", databasePath, agentPath, feedbackId
    ).toString()

    fun deletePersonalAgentLearnedTool(
        databasePath: String,
        agentPath: String,
        name: String,
    ): String = agentModule.callAttr(
        "delete_learned_tool", databasePath, agentPath, name
    ).toString()

    fun forgetPersonalAgentAssociation(
        databasePath: String,
        agentPath: String,
        key: String,
    ): String = agentModule.callAttr(
        "forget_user_model", databasePath, agentPath, key
    ).toString()

    fun resetPersonalAgent(databasePath: String, agentPath: String): String =
        agentModule.callAttr("reset_personalisation", databasePath, agentPath).toString()

    fun compactEvidence(evidenceJson: String): String =
        module.callAttr("compact_evidence", evidenceJson).toString()
}
