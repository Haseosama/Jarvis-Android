package com.jarvis.android.watch

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.MainActivity
import com.jarvis.android.R
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "jarvis_watches"
private const val CHANNEL_ID = "jarvis_watch"

internal fun watchStore(context: Context) = WatchStore(File(context.filesDir, "watches.json"))

/** Takes the measure of one watch now: a price, 1.0/0.0 for a site up/down, °C, free MB. Null when it could not be measured. */
internal suspend fun sampleWatch(context: Context, http: OkHttpClient, watch: Watch): Double? = withContext(Dispatchers.IO) {
    when (watch.kind) {
        KIND_CRYPTO -> try {
            val url = "https://api.coingecko.com/api/v3/simple/price?ids=${watch.target}&vs_currencies=eur"
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (response.isSuccessful) parseCoinPrice(response.body?.string().orEmpty(), watch.target) else null
            }
        } catch (_: Exception) {
            null
        }
        KIND_SITE -> try {
            val client = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()
            val head = client.newCall(Request.Builder().url(watch.target).head().header("User-Agent", "Jarvis-watch").build()).execute().use { it.code }
            val code = if (head == 405 || head == 501) client.newCall(Request.Builder().url(watch.target).get().header("User-Agent", "Jarvis-watch").build()).execute().use { it.code } else head
            if (statusMeansUp(code)) 1.0 else 0.0
        } catch (_: java.io.IOException) {
            0.0   // no answer at all: the site is down (or the phone is offline; see the constraint on the worker)
        } catch (_: IllegalArgumentException) {
            null
        }
        KIND_TEMPERATURE -> {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths == Int.MIN_VALUE) null else tenths / 10.0
        }
        KIND_MEMORY -> {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            info.availMem / 1_000_000.0
        }
        else -> null
    }
}

/** Runs about every 15 minutes while at least one watch exists (and the phone is online), and notifies when a condition starts. */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as JarvisApp).container
        val store = watchStore(applicationContext)
        val watches = store.load()
        if (watches.isEmpty()) return Result.success()
        val now = System.currentTimeMillis()
        val updated = watches.map { watch ->
            val result = evaluateWatch(watch, sampleWatch(applicationContext, container.http, watch), now)
            result.alert?.let { notify(watch, it) }
            result.watch
        }
        store.replace(updated)
        return Result.success()
    }

    private fun notify(watch: Watch, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Surveillances Jarvis"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(tr("Jarvis : alerte"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(7500 + watch.id, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: nothing to show.
        }
    }
}

internal object WatchScheduler {
    /** Runs the checks while there is something to watch, and not otherwise. */
    fun sync(context: Context) {
        val work = WorkManager.getInstance(context)
        if (watchStore(context).load().isEmpty()) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        // The network is needed for prices and sites; the battery and memory checks simply wait for it too.
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        work.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }
}
