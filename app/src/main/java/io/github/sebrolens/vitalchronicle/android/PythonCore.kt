package io.github.sebrolens.vitalchronicle.android

import com.chaquo.python.Python

class PythonCore {
    private val module by lazy { Python.getInstance().getModule("mobile_bridge") }

    fun specs(): List<DataTypeSpec> = parseSpecs(module.callAttr("data_type_specs").toString())

    fun normalize(dataType: String, payloadJson: String, recordKind: String): String =
        module.callAttr("normalize_records", dataType, payloadJson, recordKind).toString()

    fun dashboard(recordsJson: String, referenceDay: String): String =
        module.callAttr("dashboard", recordsJson, referenceDay).toString()

    fun evidence(recordsJson: String, start: String, endExclusive: String): String =
        module.callAttr("evidence", recordsJson, start, endExclusive).toString()
}
