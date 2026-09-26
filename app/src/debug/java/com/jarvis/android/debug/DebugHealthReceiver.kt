package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes sample health data (yesterday and today's steps, last night's sleep, today's heart rate) into Health Connect,
 * to test the health tool on an emulator that has no watch:
 * `adb shell am broadcast -a com.jarvis.android.DEBUG_HEALTH -p <package>`, then read the tag `DebugHealth`.
 * Debug builds only (the write permissions are declared in the debug manifest); protected by the DUMP permission.
 */
class DebugHealthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = HealthConnectClient.getOrCreate(app)
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant()
                val offset = zone.rules.getOffset(java.time.Instant.now())
                val meta = Metadata.manualEntry()
                val y = today.minusDays(1)
                val night = today
                client.insertRecords(listOf<androidx.health.connect.client.records.Record>(
                    StepsRecord(at(y, 9), offset, at(y, 18), offset, 8432, meta),
                    DistanceRecord(at(y, 9), offset, at(y, 18), offset, Length.meters(6100.0), meta),
                    StepsRecord(at(today, 8), offset, at(today, 10), offset, 2150, meta),
                    SleepSessionRecord(
                        at(night.minusDays(1), 23, 10), offset, at(night, 6, 55), offset, meta, "Nuit", null,
                        listOf(
                            SleepSessionRecord.Stage(at(night.minusDays(1), 23, 10), at(night, 3, 0), SleepSessionRecord.STAGE_TYPE_DEEP),
                            SleepSessionRecord.Stage(at(night, 3, 0), at(night, 3, 25), SleepSessionRecord.STAGE_TYPE_AWAKE),
                            SleepSessionRecord.Stage(at(night, 3, 25), at(night, 6, 55), SleepSessionRecord.STAGE_TYPE_LIGHT),
                        ),
                    ),
                    HeartRateRecord(at(today, 8), offset, at(today, 8, 30), offset, listOf(
                        HeartRateRecord.Sample(at(today, 8, 5), 62), HeartRateRecord.Sample(at(today, 8, 15), 95), HeartRateRecord.Sample(at(today, 8, 25), 71),
                    ), meta),
                ))
                Log.i("DebugHealth", "sample data written")
            } catch (e: Exception) {
                Log.e("DebugHealth", "failed: $e")
            } finally {
                pending.finish()
            }
        }
    }
}
