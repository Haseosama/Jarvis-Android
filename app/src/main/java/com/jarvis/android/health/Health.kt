package com.jarvis.android.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/*
 * "Combien de pas aujourd'hui ?", "comment j'ai dormi ?": read from Health Connect, where the watch, the phone's step
 * counter or a sports app writes them. Read only, with the permissions the user gives in Health Connect's own screen;
 * nothing is written and nothing is kept by Jarvis.
 */

internal val HEALTH_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
)

internal enum class HealthStatus { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

internal fun healthStatus(context: Context): HealthStatus = when (HealthConnectClient.getSdkStatus(context)) {
    HealthConnectClient.SDK_AVAILABLE -> HealthStatus.AVAILABLE
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthStatus.NEEDS_UPDATE
    else -> HealthStatus.UNAVAILABLE
}

internal fun healthClient(context: Context): HealthConnectClient? =
    if (healthStatus(context) == HealthStatus.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

internal suspend fun grantedHealth(context: Context): Set<String> = try {
    healthClient(context)?.permissionController?.getGrantedPermissions().orEmpty()
} catch (_: Exception) {
    emptySet()
}

/** "7 h 05", "45 min". */
internal fun durationWords(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
}

/** "8 432" */
internal fun thousands(n: Long): String = String.format(Locale.FRANCE, "%,d", n).replace('\u202F', '\u00A0').replace(' ', '\u00A0')

/** "3,2 km", "850 m". */
internal fun distanceWords(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.FRANCE, "%.1f km", meters / 1000).replace(",0 km", " km")

internal data class SleepStage(val stage: Int, val start: Instant, val end: Instant)

/** Minutes really asleep: the stages minus awake and out of bed; the whole session when it has no stages. */
internal fun asleepMinutes(start: Instant, end: Instant, stages: List<SleepStage>): Long {
    if (stages.isEmpty()) return Duration.between(start, end).toMinutes()
    val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED)
    return stages.filter { it.stage !in awake }.sumOf { Duration.between(it.start, it.end).toMinutes() }
}

internal data class DayActivity(val steps: Long, val meters: Double)

internal suspend fun activityBetween(client: HealthConnectClient, start: Instant, end: Instant, granted: Set<String>): DayActivity {
    val metrics = buildSet {
        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
        if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
    }
    if (metrics.isEmpty()) return DayActivity(-1, -1.0)
    val range = TimeRangeFilter.between(start, end)
    val r = client.aggregate(AggregateRequest(metrics, range))
    var steps = r[StepsRecord.COUNT_TOTAL] ?: 0L
    var meters = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
    // Health Connect's totals only count the apps in its "data sources" priority list; an app never put there (a
    // pedometer installed later) gives nothing. Then the records themselves: the largest source, so a watch and the
    // phone counting the same walk are not added up.
    if (steps == 0L && StepsRecord.COUNT_TOTAL in metrics) {
        steps = bestSource(client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records.map { it.metadata.dataOrigin.packageName to it.count.toDouble() }).toLong()
    }
    if (meters == 0.0 && DistanceRecord.DISTANCE_TOTAL in metrics) {
        meters = bestSource(client.readRecords(ReadRecordsRequest(DistanceRecord::class, range)).records.map { it.metadata.dataOrigin.packageName to it.distance.inMeters })
    }
    return DayActivity(steps, meters)
}

/** The total of the source ([app] to value) that recorded the most: several apps counting the same steps are not added. */
internal fun bestSource(values: List<Pair<String, Double>>): Double =
    values.groupBy({ it.first }, { it.second }).values.maxOfOrNull { it.sum() } ?: 0.0

internal data class NightSleep(val start: Instant, val end: Instant, val asleepMinutes: Long)

/** The night that ended on [day]: sleep sessions from 18:00 the day before to 14:00, joined together. */
internal suspend fun nightEndingOn(client: HealthConnectClient, day: LocalDate, zone: ZoneId): NightSleep? {
    val from = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
    val to = day.atTime(14, 0).atZone(zone).toInstant()
    val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records
    if (sessions.isEmpty()) return null
    val minutes = sessions.sumOf { s -> asleepMinutes(s.startTime, s.endTime, s.stages.map { SleepStage(it.stage, it.startTime, it.endTime) }) }
    return NightSleep(sessions.minOf { it.startTime }, sessions.maxOf { it.endTime }, minutes)
}

internal data class HeartDay(val avg: Long, val min: Long, val max: Long)

internal suspend fun heartBetween(client: HealthConnectClient, start: Instant, end: Instant): HeartDay? {
    val r = client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX), TimeRangeFilter.between(start, end)))
    val avg = r[HeartRateRecord.BPM_AVG] ?: return null
    return HeartDay(avg, r[HeartRateRecord.BPM_MIN] ?: avg, r[HeartRateRecord.BPM_MAX] ?: avg)
}

internal fun hourWords(at: Instant, zone: ZoneId): String {
    val t = at.atZone(zone)
    return if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"
}

internal fun describeNight(n: NightSleep, zone: ZoneId): String =
    "${durationWords(n.asleepMinutes)} de sommeil, de ${hourWords(n.start, zone)} à ${hourWords(n.end, zone)}"

internal fun describeActivity(a: DayActivity): String = buildList {
    if (a.steps >= 0) add("${thousands(a.steps)} pas")
    if (a.meters > 0) add(distanceWords(a.meters))
}.joinToString(", ")

/** One line for the morning briefing ("yesterday 8 432 steps; last night 7 h 05 of sleep"), or "" without data or permission. */
internal suspend fun briefingHealthLine(context: Context): String = withTimeoutOrNull(4_000) {
    try {
        val client = healthClient(context) ?: return@withTimeoutOrNull ""
        val granted = grantedHealth(context)
        if (granted.isEmpty()) return@withTimeoutOrNull ""
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val parts = mutableListOf<String>()
        val yesterday = activityBetween(client, today.minusDays(1).atStartOfDay(zone).toInstant(), today.atStartOfDay(zone).toInstant(), granted)
        if (yesterday.steps > 0) parts += "yesterday ${describeActivity(yesterday)}"
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            nightEndingOn(client, today, zone)?.let { parts += "last night ${describeNight(it, zone)}" }
        }
        parts.joinToString("; ")
    } catch (_: Exception) {
        ""
    }
}.orEmpty()
