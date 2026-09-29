package com.jarvis.android.space

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where a point of the sky falls on the screen when the phone is held as [rotation] says (Android's rotation matrix, device to
 * East-North-Up, from the rotation vector sensor, so its north is the magnetic one): [declination] turns the true azimuth into a
 * magnetic one. The back camera looks along the device's -Z; [focalPx] is its focal length in screen pixels. Null behind the camera.
 */
internal fun arProject(rotation: FloatArray, declination: Double, elevationDeg: Double, azimuthDeg: Double, focalPx: Double, width: Double, height: Double): Pair<Double, Double>? {
    val el = Math.toRadians(elevationDeg)
    val az = Math.toRadians(azimuthDeg - declination)
    val e = cos(el) * sin(az)
    val n = cos(el) * cos(az)
    val u = sin(el)
    // world to device: the transpose of the rotation matrix
    val dx = rotation[0] * e + rotation[3] * n + rotation[6] * u
    val dy = rotation[1] * e + rotation[4] * n + rotation[7] * u
    val dz = rotation[2] * e + rotation[5] * n + rotation[8] * u
    val forward = -dz
    if (forward < 0.05) return null
    return width / 2 + focalPx * dx / forward to height / 2 - focalPx * dy / forward
}

/** The direction the back camera looks at: elevation and true azimuth (degrees). */
internal fun arPointing(rotation: FloatArray, declination: Double): Pair<Double, Double> {
    // the camera's axis (device -Z) in East-North-Up
    val e = -rotation[2]
    val n = -rotation[5]
    val u = -rotation[8]
    val el = Math.toDegrees(kotlin.math.asin(u.toDouble().coerceIn(-1.0, 1.0)))
    val az = (Math.toDegrees(kotlin.math.atan2(e.toDouble(), n.toDouble())) + declination + 360) % 360
    return el to az
}

/** Something to name in the camera's picture. */
private data class ArThing(
    val id: String, val label: String, val look: Look, val color: Color, val radiusDp: Float, val labelled: Boolean, val detail: String,
    val star: Star? = null, val drawn: Boolean = true,
)

/**
 * How to turn the phone from where it points to a target: degrees to turn right (negative: left, the shorter way) and to raise
 * (negative: lower).
 */
internal fun arGuide(pointEl: Double, pointAz: Double, targetEl: Double, targetAz: Double): Pair<Double, Double> {
    val turn = ((targetAz - pointAz) % 360 + 540) % 360 - 180
    return turn to targetEl - pointEl
}

/** The guide in words: "tournez à droite de 40°, levez de 20°", or "c’est au centre". */
internal fun arGuideWords(turn: Double, raise: Double): String {
    val parts = ArrayList<String>()
    if (abs(turn) >= 3) parts += "tournez à ${if (turn > 0) "droite" else "gauche"} de ${abs(turn).toInt()}°"
    if (abs(raise) >= 3) parts += "${if (raise > 0) "levez" else "baissez"} le téléphone de ${abs(raise).toInt()}°"
    return if (parts.isEmpty()) "c’est au centre" else parts.joinToString(", ")
}

/** Whether a name in the sky is the one asked for: "Jupiter", "la Lune", "l’ISS", "Sirius", "AFR1234". */
internal fun arNameMatches(label: String, id: String, wanted: String): Boolean {
    fun clean(t: String) = com.jarvis.android.offline.normalize(t.substringBefore(" (")).removePrefix("la ").removePrefix("le ").removePrefix("l ").removePrefix("les ")
    val w = clean(wanted)
    if (w.isEmpty()) return false
    val l = clean(label)
    return l == w || when (id) {
        "moon" -> w == "lune"
        "sun" -> w == "soleil"
        else -> id.startsWith("sat:") && l == "iss" && ("station spatiale" in w || w == "iss")
    }
}

private val AR_SUN = Color(0xFFFFD54F)
private val AR_MOON = Color(0xFFE8EEF5)
private val AR_STAR = Color(0xFFDDE8FF)
private val AR_SAT = Color(0xFF7FD8FF)
private val AR_PLANE = Color(0xFFFF9F43)
private val AR_HORIZON = Color(0x994DFFB8)
private val AR_FIGURE = Color(0x667FA8D8)
private val AR_FIGURE_NAME = Color(0xAA9FC3EA)
private val AR_TARGET = Color(0xFFFF4D8D)

private fun capitalized(name: String) = name.removePrefix("la ").removePrefix("le ").replaceFirstChar { it.uppercase() }

/** "ISS", "Tiangong", "Hubble", or the satellite's own name. */
private fun satLabel(name: String) = when {
    name.startsWith("ISS (ZARYA)") -> "ISS"
    name.startsWith("CSS (TIANHE)") -> "Tiangong"
    name.startsWith("HST") -> "Hubble"
    else -> name.replace(Regex("\\s+"), " ").trim()
}

private fun arFmt(v: Double): String = String.format(Locale.FRANCE, "%,d", v.toInt())

/** The camera's focal length over its sensor's long side (both in mm), from Camera2; 0.78 (a 65° view) when not told. */
private fun focalRatio(context: Context): Double = try {
    val m = context.getSystemService(CameraManager::class.java)
    val id = m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
    val c = id?.let { m.getCameraCharacteristics(it) }
    val f = c?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val w = c?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { maxOf(it.width, it.height) }
    if (f != null && w != null && f > 0 && w > 0) (f / w).toDouble() else 0.78
} catch (_: Exception) {
    0.78
}

/**
 * The sky through the camera: point the phone at the sky and the Sun, the Moon, the planets, the bright stars, the satellites and the
 * aircraft around get their names where they are, with the horizon and the cardinal points; what is in the middle is told in detail.
 */
@Composable
internal fun ArSkyView(big: Boolean, target: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as JarvisApp).container }
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val observer by produceState<Observer?>(null) {
        value = (com.jarvis.android.weather.locate(container.appContext) as? com.jarvis.android.weather.LocationOutcome.Found)?.let { Observer(it.fix.latitude, it.fix.longitude) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val declination = remember(observer) { observer?.let { GeomagneticField(it.latDeg.toFloat(), it.lonDeg.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() } ?: 0.0 }

    // the phone's orientation, smoothed a little
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val hasSensor = remember { sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null }
    var rotation by remember { mutableStateOf<FloatArray?>(null) }
    var accuracy by remember { mutableStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }
    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            val r = FloatArray(9)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                val screen = context.display?.rotation ?: Surface.ROTATION_0
                val out = FloatArray(9)
                when (screen) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
                    else -> r.copyInto(out)
                }
                val prev = rotation
                rotation = if (prev == null) out else FloatArray(9) { prev[it] * 0.7f + out[it] * 0.3f }
            }

            override fun onAccuracyChanged(sensor: Sensor, a: Int) { accuracy = a }
        }
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensors.unregisterListener(listener) }
    }

    // what is in the sky: the Sun, the Moon and the planets, the bright stars, the satellites, the aircraft
    val catalogue by produceState<List<Star>>(emptyList()) { value = withContext(Dispatchers.IO) { try { SkyAssets.stars(container.appContext) } catch (_: Exception) { emptyList() } } }
    val sats by produceState<List<Pair<Tle, Sgp4>>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            try {
                (SatelliteTool.orbits(container, "stations") + SatelliteTool.orbits(container, "visual")).distinctBy { it.number }.map { it to Sgp4(it) }.filter { it.second.nearEarth }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val aircraft by produceState<List<Pair<Aircraft, Look>>>(emptyList(), observer) {
        val o = observer ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) {
                try {
                    val url = "https://api.adsb.lol/v2/lat/%.4f/lon/%.4f/dist/40".format(Locale.US, o.latDeg, o.lonDeg)
                    container.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { r ->
                        if (r.isSuccessful) parseAdsbLol(r.body?.string().orEmpty()).filter { !it.onGround }.map { it to lookAtAircraft(o, it) } else value
                    }
                } catch (_: Exception) {
                    value
                }
            }
            delay(10_000)
        }
    }
    val things by produceState<List<ArThing>>(emptyList(), observer, catalogue, sats, aircraft, now / 2_000) {
        val o = observer ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val out = ArrayList<ArThing>()
            solarSystem(o, now).forEach { n ->
                val below = n.look.elevationDeg < 0
                val (color, r) = when (n.kind) {
                    NightObject.Kind.SUN -> AR_SUN to 9f
                    NightObject.Kind.MOON -> AR_MOON to 8f
                    else -> Color(0xFFFFE9B0) to 4f
                }
                out += ArThing(
                    n.id, capitalized(n.name) + if (below) " (sous l’horizon)" else "", n.look, if (below) color.copy(alpha = 0.45f) else color, r, true,
                    "${arFmt(n.distanceKm)} km" + if (n.kind == NightObject.Kind.PLANET) " · magnitude %.1f".format(Locale.FRANCE, n.magnitude) else "",
                )
            }
            val inFigures = FIGURES.flatten().toSet()
            starsAbove(catalogue, o, now, 5.0).forEach { s ->
                val drawn = s.magnitude <= 4.0 || s.star?.bayer in inFigures
                val where = s.star?.let { st -> CONSTELLATIONS[st.constellation]?.let { " de $it" } }.orEmpty()
                out += ArThing(
                    s.id, s.name, s.look, AR_STAR, (3.2f - s.magnitude.toFloat() * 0.55f).coerceIn(1f, 3.5f), s.magnitude <= 1.6,
                    "étoile$where, magnitude %.1f".format(Locale.FRANCE, s.magnitude), s.star, drawn,
                )
            }
            sats.forEach { (tle, sgp) ->
                val s = satNow(sgp, tle, o, now) ?: return@forEach
                if (s.look.elevationDeg < 0) return@forEach
                val main = tle.name.startsWith("ISS (ZARYA)") || tle.name.startsWith("CSS (TIANHE)") || tle.name.startsWith("HST")
                out += ArThing(
                    "sat:${tle.number}", satLabel(tle.name), s.look, if (s.lit) AR_SAT else AR_SAT.copy(alpha = 0.4f), if (main) 5f else 3f, main,
                    "satellite à ${arFmt(s.altitudeKm)} km d’altitude, ${arFmt(s.look.rangeKm)} km de vous, ${arFmt(s.speedKmh)} km/h" + if (s.lit) ", éclairé" else ", dans l’ombre",
                )
            }
            aircraft.forEach { (a, l) ->
                if (l.elevationDeg < 0) return@forEach
                out += ArThing(
                    "ac:${a.hex}", a.label, l, AR_PLANE, 4f, true,
                    listOfNotNull("avion", a.altitudeM?.let { "${arFmt(it)} m" }, a.speedKmh?.let { "${arFmt(it)} km/h" }, "${arFmt(l.rangeKm)} km de vous").joinToString(" · "),
                )
            }
            out
        }
    }

    // the object asked for, if any, and how to turn to it
    val wanted = remember(things, target) { target?.takeIf { it.isNotBlank() }?.let { t -> things.firstOrNull { arNameMatches(it.label, it.id, t) } } }

    var previewSize by remember { mutableStateOf<Size?>(null) }
    val focal = remember { focalRatio(context) }

    Box(modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        if (granted) {
            val textureView = remember { TextureView(context) }
            AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
            DisposableEffect(lifecycleOwner) {
                val future = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null
                future.addListener({
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                        fun provide(texture: SurfaceTexture) {
                            texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                            val surface = Surface(texture)
                            previewSize = request.resolution
                            fitPreview(textureView, request.resolution)
                            request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { surface.release() }
                        }
                        val st = textureView.surfaceTexture
                        if (st != null) provide(st)
                        else textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = provide(t)
                            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) { previewSize?.let { fitPreview(textureView, it) } }
                            override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = false
                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                        }
                    }
                    try {
                        p.unbindAll()
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    } catch (e: Exception) {
                        android.util.Log.w("JarvisAr", "Caméra indisponible : ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(context))
                onDispose { provider?.unbindAll() }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val r = rotation ?: return@Canvas
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            // the camera's picture covers the view: its long side over the view's long side, cropped
            val res = previewSize
            val ratio = res?.let { maxOf(it.width, it.height).toDouble() / minOf(it.width, it.height) } ?: (4.0 / 3.0)
            val long = maxOf(w, h)
            val short = minOf(w, h)
            val shownLong = maxOf(long, short * ratio)
            val f = focal * shownLong
            fun at(l: Look) = arProject(r, declination, l.elevationDeg, l.azimuthDeg, f, w, h)?.let { Offset(it.first.toFloat(), it.second.toFloat()) }

            // the horizon and the cardinal points
            var last: Offset? = null
            for (az in 0..360 step 3) {
                val p = at(Look(0.0, az.toDouble(), 1.0))
                if (p != null && last != null && abs(p.x - last.x) < w) drawLine(AR_HORIZON, last, p, 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f)))
                last = p
            }
            listOf("N" to 0, "NE" to 45, "E" to 90, "SE" to 135, "S" to 180, "SO" to 225, "O" to 270, "NO" to 315).forEach { (t, az) ->
                at(Look(0.0, az.toDouble(), 1.0))?.let { p -> arLabel(t, Offset(p.x - 6.dp.toPx(), p.y + 16.dp.toPx()), AR_HORIZON, 14, true) }
            }
            // the constellations: their figures, their names in the middle of their stars
            val starAt = HashMap<String, Offset>()
            things.forEach { t -> val b = t.star?.bayer ?: return@forEach; at(t.look)?.let { starAt[b] = it } }
            FIGURES.forEach { figure ->
                figure.zipWithNext().forEach { (a, b) ->
                    val pa = starAt[a]
                    val pb = starAt[b]
                    if (pa != null && pb != null && abs(pa.x - pb.x) < w && abs(pa.y - pb.y) < h) drawLine(AR_FIGURE, pa, pb, 1.dp.toPx())
                }
            }
            FIGURES.flatten().distinct().groupBy { it.takeLast(3) }.forEach { (code, stars) ->
                val pts = stars.mapNotNull { starAt[it] }
                val name = CONSTELLATIONS[code] ?: return@forEach
                if (pts.size < 2) return@forEach
                val m = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
                if (m.x in 0f..size.width && m.y in 0f..size.height) arLabel(name, Offset(m.x - arPaint(AR_FIGURE, 11, false).measureText(name) / 2, m.y), AR_FIGURE_NAME, 11, false)
            }
            // the objects, the faintest first
            val shown = things.filter { it.drawn }.mapNotNull { t -> at(t.look)?.takeIf { p -> p.x > -50 && p.y > -50 && p.x < w + 50 && p.y < h + 50 }?.let { t to it } }
            shown.sortedBy { it.first.radiusDp }.forEach { (t, p) ->
                if (t.id.startsWith("ac:")) drawCircle(t.color, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                else drawCircle(t.color, t.radiusDp.dp.toPx(), p)
            }
            // the names, the most important first, none over another
            val taken = ArrayList<RectF>()
            shown.filter { it.first.labelled }.sortedByDescending { (t, _) -> arRank(t) }.forEach { (t, p) ->
                val bold = t.id.startsWith("sat:") || t.id == "moon" || t.id.startsWith("pl:")
                val at = Offset(p.x + (t.radiusDp + 4).dp.toPx(), p.y - 4.dp.toPx())
                val box = RectF(at.x, at.y - 12.sp.toPx(), at.x + arPaint(Color.White, 12, bold).measureText(t.label), at.y + 3.dp.toPx())
                if (taken.any { RectF.intersects(it, box) }) return@forEach
                taken += box
                arLabel(t.label, at, t.color.copy(alpha = 1f), 12, bold)
            }
            // the middle, and the way to the object asked for
            val c = Offset(size.width / 2, size.height / 2)
            wanted?.let { t ->
                val p = at(t.look)
                val margin = 40.dp.toPx()
                if (p != null && p.x in margin..(size.width - margin) && p.y in margin..(size.height - margin)) {
                    drawCircle(AR_TARGET, 18.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                    drawCircle(AR_TARGET.copy(alpha = 0.4f), 26.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                } else {
                    val (el, az) = arPointing(r, declination)
                    val (turn, raise) = arGuide(el, az, t.look.elevationDeg, t.look.azimuthDeg)
                    var dx = if (p != null) p.x - c.x else (if (turn >= 0) 1f else -1f)
                    var dy = if (p != null) p.y - c.y else (-raise / 45).toFloat()
                    val len = hypot(dx, dy).coerceAtLeast(1e-3f)
                    dx /= len; dy /= len
                    val reach = minOf(size.width, size.height) * 0.36f
                    val tip = Offset(c.x + dx * reach, c.y + dy * reach)
                    val s = 16.dp.toPx()
                    val back = Offset(tip.x - dx * s * 1.6f, tip.y - dy * s * 1.6f)
                    val arrow = androidx.compose.ui.graphics.Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(back.x - dy * s * 0.8f, back.y + dx * s * 0.8f)
                        lineTo(back.x + dy * s * 0.8f, back.y - dx * s * 0.8f)
                        close()
                    }
                    drawPath(arrow, AR_TARGET)
                    arLabel(t.label, Offset(back.x - dx * s - 20.dp.toPx(), back.y - dy * s), AR_TARGET, 13, true)
                }
            }
            drawCircle(Color(0xAAFFFFFF), 22.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawLine(Color(0xAAFFFFFF), Offset(c.x - 30.dp.toPx(), c.y), Offset(c.x - 14.dp.toPx(), c.y), 1.dp.toPx())
            drawLine(Color(0xAAFFFFFF), Offset(c.x + 14.dp.toPx(), c.y), Offset(c.x + 30.dp.toPx(), c.y), 1.dp.toPx())
        }

        // the card: where the phone points, what is in the middle
        val text = Color(0xFFDCEBFA)
        val dim = Color(0xFF8FA9C4)
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xCC050B14), RoundedCornerShape(10.dp)).padding(8.dp)) {
            val r = rotation
            when {
                !granted -> Text("Autorisez la caméra pour voir le ciel à travers le téléphone.", color = text, style = MaterialTheme.typography.labelMedium)
                !hasSensor -> Text("Ce téléphone n’a pas de capteur d’orientation : la réalité augmentée n’est pas possible.", color = text, style = MaterialTheme.typography.labelMedium)
                r == null -> Text("Lecture de l’orientation…", color = text, style = MaterialTheme.typography.labelMedium)
                else -> {
                    val (el, az) = arPointing(r, declination)
                    if (!target.isNullOrBlank()) {
                        val t = wanted
                        if (t == null) Text("« $target » : pas trouvé dans le ciel d’ici en ce moment", color = AR_TARGET, style = MaterialTheme.typography.labelMedium)
                        else {
                            val (turn, raise) = arGuide(el, az, t.look.elevationDeg, t.look.azimuthDeg)
                            val below = if (t.look.elevationDeg < 0) " (sous l’horizon : la Terre le cache)" else ""
                            Text("${t.label.substringBefore(" (")}$below : ${arGuideWords(turn, raise)}", color = AR_TARGET, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    val centre = things.filter { it.labelled || it.radiusDp >= 2.5f }.minByOrNull { skyAngle(it.look, Look(el, az, 1.0)) }?.takeIf { skyAngle(it.look, Look(el, az, 1.0)) < 6 }
                    if (centre != null) {
                        Text(centre.label, color = centre.color.copy(alpha = 1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(centre.detail, color = text, style = MaterialTheme.typography.labelSmall)
                        Text("hauteur %d° · direction %s".format(centre.look.elevationDeg.toInt(), compass(centre.look.azimuthDeg)), color = dim, style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("Visez un objet pour le détail · vous regardez vers le %s, %d° %s".format(compass(az), abs(el.toInt()), if (el >= 0) "au-dessus de l’horizon" else "sous l’horizon"), color = text, style = MaterialTheme.typography.labelMedium)
                    }
                    if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) Text("Boussole imprécise : faites un 8 avec le téléphone pour la calibrer.", color = AR_SUN, style = MaterialTheme.typography.labelSmall)
                    if (observer == null) Text("Position inconnue : les objets ne peuvent pas être placés.", color = AR_SUN, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** The camera's picture fills the view without being stretched (the sensor's rotation is already in the SurfaceTexture's transform). */
private fun fitPreview(view: TextureView, res: Size) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    if (w == 0f || h == 0f) return
    val rotation = view.display?.rotation ?: Surface.ROTATION_0
    val m = Matrix()
    val cx = w / 2
    val cy = h / 2
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        val viewRect = RectF(0f, 0f, w, h)
        val bufferRect = RectF(0f, 0f, res.height.toFloat(), res.width.toFloat()).apply { offset(cx - centerX(), cy - centerY()) }
        m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(h / res.height, w / res.width)
        m.postScale(scale, scale, cx, cy)
        m.postRotate(90f * (rotation - 2), cx, cy)
    } else {
        // upright: the long side of the picture is the view's height
        val ratio = maxOf(res.width, res.height).toFloat() / minOf(res.width, res.height)
        val shownW = maxOf(w, h / ratio)
        val shownH = shownW * ratio
        m.setScale(shownW / w, shownH / h, cx, cy)
        if (rotation == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
    }
    view.setTransform(m)
}

/** Which name wins a place: the Sun and the Moon, the planets, the stations, the aircraft, the brightest stars, the rest. */
private fun arRank(t: ArThing): Int = when {
    t.id == "sun" || t.id == "moon" -> 6
    t.id.startsWith("pl:") -> 5
    t.id.startsWith("sat:") -> 4
    t.id.startsWith("ac:") -> 3
    else -> 2
}

private fun DrawScope.arPaint(color: Color, sizeSp: Int, bold: Boolean) = android.graphics.Paint().apply {
    this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    textSize = sizeSp.sp.toPx()
    isAntiAlias = true
    isFakeBoldText = bold
    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
}

private fun DrawScope.arLabel(text: String, at: Offset, color: Color, sizeSp: Int, bold: Boolean) {
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, arPaint(color, sizeSp, bold))
}
