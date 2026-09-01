package io.github.sebrolens.vitalchronicle.android

/**
 * Delete only one representation of a data type without touching the rest of
 * the local health archive. Used to migrate pre-0.3.2 raw heart-rate samples to
 * the server-side five-minute rollup representation.
 */
fun HealthDatabase.deleteRecordsByKind(dataType: String, recordKind: String): Int = synchronized(this) {
    writableDatabase.delete(
        "records",
        "data_type=? AND record_kind=?",
        arrayOf(dataType, recordKind),
    )
}
