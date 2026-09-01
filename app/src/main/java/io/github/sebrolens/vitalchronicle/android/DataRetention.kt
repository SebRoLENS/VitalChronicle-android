package io.github.sebrolens.vitalchronicle.android

import java.time.LocalDate

/**
 * Local archive retention policy.
 *
 * Daily/low-volume health history is kept for at most 90 days. High-volume
 * cardiac streams keep a shorter 15-day archive. Heart rate is downloaded as a
 * server-side five-minute rollup, so it can use the full cardiac retention
 * window without storing the previous flood of raw samples.
 */
object DataRetention {
    const val GENERAL_DAYS = 90
    const val HIGH_VOLUME_CARDIAC_DAYS = 15

    val HIGH_VOLUME_CARDIAC_TYPES = setOf(
        "heart-rate",
        "heart-rate-variability",
        "electrocardiogram",
    )

    fun daysFor(dataType: String): Int = when {
        dataType in HIGH_VOLUME_CARDIAC_TYPES -> HIGH_VOLUME_CARDIAC_DAYS
        else -> GENERAL_DAYS
    }

    fun requestedDays(dataType: String, requestedDays: Int): Int =
        requestedDays.coerceIn(1, GENERAL_DAYS).coerceAtMost(daysFor(dataType))

    fun cutoffDate(dataType: String, today: LocalDate = LocalDate.now()): LocalDate =
        today.minusDays((daysFor(dataType) - 1).toLong())
}
