package io.github.m313.contingentwatcher.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Time helpers mirroring the JavaScript Date semantics of the VS Code extension. */
object Time {
    const val HOUR: Long = 3_600_000L
    const val DAY: Long = 24 * HOUR

    /** Zone used for "local" calculations; overridable in tests. */
    @Volatile
    var zone: ZoneId = ZoneId.systemDefault()

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Like JavaScript's Date.prototype.toISOString(). */
    fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))

    /** Parses an ISO timestamp; null if invalid. */
    fun parse(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                ZonedDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun utc(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)

    fun local(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    fun utcDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
            .plusMonths((month - 1).toLong())
            .plusDays((day - 1).toLong())
            .plusHours(hour.toLong())
            .plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    fun startOfUtcDay(t: Long): Long = Math.floorDiv(t, DAY) * DAY

    fun localDayStart(t: Long): Long = local(t).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun nextLocalDay(t: Long): Long = local(t).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Local date as YYYY-MM-DD. */
    fun localIsoDate(t: Long): String = local(t).toLocalDate().toString()

    fun localDateOf(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (_: Exception) {
        null
    }

    /** "2026-12-01T00:00:00.000Z" for the 1st of the next UTC month. */
    fun firstOfNextUtcMonth(now: Long): String {
        val d = utc(now)
        return iso(utcDate(d.year, d.monthValue + 1, 1))
    }
}
