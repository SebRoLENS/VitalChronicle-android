package io.github.sebrolens.vitalchronicle.android

import com.chaquo.python.Python

class PythonCore {
    private val module by lazy { Python.getInstance().getModule("mobile_bridge") }
    private val dashboardModule by lazy { Python.getInstance().getModule("android_dashboard") }
    private val nanoRouterModule by lazy { Python.getInstance().getModule("nano_router") }

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

    fun compactEvidence(evidenceJson: String): String =
        module.callAttr("compact_evidence", evidenceJson).toString()
}
