package com.jarvis.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.MainActivity
import com.jarvis.android.R
import com.jarvis.android.avatar.AvatarSnapshot
import com.jarvis.android.reminders.ReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * The home-screen widget: the chosen face, the weather where the phone is and the next reminder; a tap anywhere opens the app and
 * starts a session. Refreshed every 30 minutes by the launcher, when a reminder is added, cancelled or rung, and when the face's look
 * changes in the settings. The face is drawn once and kept in a file until its look changes; the weather is fetched at most every
 * 20 minutes and its last line is kept for when the position or the network is missing.
 */
class JarvisWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        // what is already known shows at once; the rest follows
        show(app, manager, appWidgetIds, cachedState(app))
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(UPDATE_BUDGET_MS) { lock.withLock { show(app, manager, ids(app), freshState(app)) } }
            } catch (_: Exception) {
                // decoration: a failure leaves the widget as it was
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        private class State(val face: Bitmap?, val weather: String, val reminder: String)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val lock = Mutex()
        private const val PREFS = "jarvis_widget"
        private const val KEY_FACE = "face_key"
        private const val KEY_WEATHER = "weather_line"
        private const val KEY_WEATHER_AT = "weather_at"
        private const val FACE_FILE = "widget_face.png"
        private const val FACE_PX = 288
        private const val UPDATE_BUDGET_MS = 40_000L
        private const val WEATHER_FRESH_MS = 20 * 60_000L
        private const val WEATHER_KEEP_MS = 3 * 60 * 60_000L

        /** Asks the widgets on the home screen, if any, to refresh. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val ids = ids(app)
            if (ids.isEmpty()) return
            app.sendBroadcast(
                Intent(app, JarvisWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, JarvisWidgetProvider::class.java))

        private fun show(context: Context, manager: AppWidgetManager, ids: IntArray, state: State) {
            if (ids.isEmpty()) return
            val open = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val talk = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_jarvis)
                views.setOnClickPendingIntent(R.id.widget_root, talk)
                if (state.face != null) views.setImageViewBitmap(R.id.widget_face, state.face)
                else views.setImageViewResource(R.id.widget_face, R.mipmap.ic_launcher)
                views.setTextViewText(R.id.widget_weather, state.weather)
                views.setViewVisibility(R.id.widget_weather, if (state.weather.isEmpty()) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_reminder, state.reminder)
                manager.updateAppWidget(id, views)
            }
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** What can be shown without waiting: the face drawn last time, the last weather line, the next reminder. */
        private fun cachedState(context: Context): State {
            val face = try { File(context.filesDir, FACE_FILE).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } } catch (_: Exception) { null }
            return State(face, keptWeather(context, System.currentTimeMillis()).orEmpty(), reminderText(context))
        }

        private suspend fun freshState(context: Context): State {
            val container = (context.applicationContext as JarvisApp).container
            val face = withContext(Dispatchers.Default) { face(context, container) }
            val weather = weather(context, container)
            return State(face, weather, reminderText(context))
        }

        private fun reminderText(context: Context): String {
            val now = System.currentTimeMillis()
            val next = try { nextReminder(ReminderService.list(context), now) } catch (_: Exception) { null }
            return if (next == null) context.getString(R.string.widget_no_reminder) else "⏰ " + reminderLine(next, now, ZoneId.systemDefault())
        }

        /** The face as the settings show it, drawn again only when its look changed; null when the face is turned off. */
        private suspend fun face(context: Context, container: JarvisContainer): Bitmap? {
            val config = container.configStore
            if (!config.avatarFace.first()) return null
            val controller = container.avatar
            // the settings, applied here too: the app may just have been started for the widget, before it read them itself
            val model = config.avatarModel.first()
            val label = com.jarvis.android.avatar.avatarFace(model).label
            val hair = config.avatarHair.first()[label].orEmpty()
            val colour = config.avatarHairColour.first()[label].orEmpty()
            val custom = com.jarvis.android.avatar.FaceCustomizer.decode(config.avatarHaseoCustom.first())
            val level = com.jarvis.android.avatar.PolygonLevel.of(config.avatarPolygonLevel.first())
            val skin = config.avatarSkin.first(); val lips = config.avatarLips.first(); val cap = config.avatarCap.first()
            val hue = config.themeHue.first()
            withContext(Dispatchers.Main) {
                controller.model = model; controller.hair = hair; controller.hairColour = colour
                controller.custom = custom; controller.polygonLevel = level
                controller.skin = skin; controller.lips = lips; controller.cap = cap
            }
            // an update of the app may draw the faces differently: its time is part of the key
            val installed = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
            val key = AvatarSnapshot.key(controller) + "|" + hue + "|" + installed
            val file = File(context.filesDir, FACE_FILE)
            if (prefs(context).getString(KEY_FACE, null) == key && file.isFile) {
                BitmapFactory.decodeFile(file.path)?.let { return it }
            }
            val colours = widgetColours(hue)
            val density = context.resources.displayMetrics.density
            val bitmap = AvatarSnapshot.draw(controller, FACE_PX, density.coerceIn(1f, 3f), colours.primary, colours.accent, colours.background)
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                prefs(context).edit().putString(KEY_FACE, key).apply()
            } catch (_: Exception) {
            }
            return bitmap
        }

        private class Colours(val primary: Int, val accent: Int, val background: Int)

        /** The theme's colours for a hue, as JarvisTheme makes them, over the widget's own dark background (drawable/widget_bg). */
        private fun widgetColours(hue: Float) = Colours(
            primary = android.graphics.Color.HSVToColor(floatArrayOf(hue.mod(360f), 0.70f, 1f)),
            accent = android.graphics.Color.HSVToColor(floatArrayOf((hue + 45f).mod(360f), 0.60f, 1f)),
            background = 0xFF0A1A24.toInt(),
        )

        /** The weather line kept from the last fetch, while it is less than three hours old. */
        private fun keptWeather(context: Context, now: Long): String? {
            val p = prefs(context)
            val at = p.getLong(KEY_WEATHER_AT, 0L)
            return p.getString(KEY_WEATHER, null)?.takeIf { now - at in 0..WEATHER_KEEP_MS }
        }

        private suspend fun weather(context: Context, container: JarvisContainer): String {
            val now = System.currentTimeMillis()
            val p = prefs(context)
            if (now - p.getLong(KEY_WEATHER_AT, 0L) in 0..WEATHER_FRESH_MS) p.getString(KEY_WEATHER, null)?.let { return it }
            val line = try { fetchWeather(context, container) } catch (_: Exception) { null }
            if (line != null) {
                p.edit().putString(KEY_WEATHER, line).putLong(KEY_WEATHER_AT, now).apply()
                return line
            }
            return keptWeather(context, now) ?: when {
                !com.jarvis.android.location.hasLocationPermission(context) -> context.getString(R.string.widget_weather_no_location)
                else -> context.getString(R.string.widget_weather_unavailable)
            }
        }

        /** The weather at the phone's last known position (up to six hours old, as the morning briefing), or null. */
        private suspend fun fetchWeather(context: Context, container: JarvisContainer): String? {
            val found = withTimeoutOrNull(12_000) {
                com.jarvis.android.location.locate(context, maxAgeMs = 6 * 60 * 60_000L) as? com.jarvis.android.location.LocationOutcome.Found
            } ?: return null
            val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", "%.3f".format(Locale.ROOT, found.fix.latitude))
                .addQueryParameter("longitude", "%.3f".format(Locale.ROOT, found.fix.longitude))
                .addQueryParameter("current", "temperature_2m,weather_code,is_day")
                .addQueryParameter("temperature_unit", "celsius").build()
            val body = withContext(Dispatchers.IO) {
                container.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            } ?: return null
            return parseWidgetWeather(body)?.let { weatherLine(it, found.place) }
        }
    }
}
