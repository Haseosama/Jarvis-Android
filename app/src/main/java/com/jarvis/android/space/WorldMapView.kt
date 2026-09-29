package com.jarvis.android.space

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

private val OCEAN = Color(0xFF071A2C)
private val LAND = Color(0xFF22506F)
private val BORDER = Color(0x663F6D8F)
private val NIGHT = Color(0x88000000)
private val ISS_COLOR = Color(0xFFFFD54F)
private val CSS_COLOR = Color(0xFF7FD8FF)
private val FLIGHT_COLOR = Color(0xFFFF9F43)
private val YOU = Color(0xFF4DFFB8)
private val AURORA_COLOR = Color(0xFF3DFF7A)

/** One picture of the rain radar: when, and where its tiles are. */
internal data class RadarFrame(val time: Long, val path: String, val forecast: Boolean)

/** RainViewer's list of radar pictures: the host, then the past ones and the few to come. */
internal fun parseRadarFrames(json: String): Pair<String, List<RadarFrame>>? = try {
    val o = Json.parseToJsonElement(json).jsonObject
    val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return null
    val radar = o["radar"]?.jsonObject ?: return null
    fun list(k: String, forecast: Boolean) = (radar[k] as? JsonArray).orEmpty().mapNotNull { f ->
        val fo = f as? JsonObject ?: return@mapNotNull null
        val t = fo["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val p = fo["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        RadarFrame(t * 1000, p, forecast)
    }
    host to (list("past", false) + list("nowcast", true))
} catch (_: Exception) {
    null
}

/** The zoom RainViewer's free tiles stop at. */
private const val RADAR_ZOOM = 7

/**
 * The world map in place of the avatar: Natural Earth's land and borders, the night side, the Sun's point, and one of three things:
 * the ISS and Tiangong where they are with their tracks ("map"), a followed flight with its route ("flight:<callsign>|<number>"), or
 * the rain radar of the last two hours around the user, animated ("radar"). Pinch and drag to look round.
 */
@Composable
internal fun WorldMapView(mode: String, big: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as JarvisApp).container }
    val data by produceState<MapData?>(null) { value = withContext(Dispatchers.IO) { try { MapData.get(context) } catch (_: Exception) { null } } }
    val observer by produceState<Observer?>(null) {
        value = (com.jarvis.android.weather.locate(container.appContext) as? com.jarvis.android.weather.LocationOutcome.Found)?.let { Observer(it.fix.latitude, it.fix.longitude) }
    }
    val radar = mode == SkyModes.RADAR
    val flightKey = mode.removePrefix(SkyModes.FLIGHT).takeIf { mode.startsWith(SkyModes.FLIGHT) }
    val callsign = flightKey?.substringBefore('|')
    val flightNumber = flightKey?.substringAfter('|', flightKey) ?: ""

    var cx by remember { mutableDoubleStateOf(0.5) }
    var cy by remember { mutableDoubleStateOf(mercY(20.0)) }
    var zoom by remember { mutableDoubleStateOf(1.0) }
    var placed by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

    // the ISS and Tiangong (the world map)
    // the aurora oval and Kp (the aurora map)
    val aurora by produceState<AuroraGrid?>(null, mode) { if (mode == SkyModes.AURORA) value = SpaceWeather.aurora(container) }
    val kpNow by produceState<Double?>(null, mode) { if (mode == SkyModes.AURORA) value = SpaceWeather.kpNow(container) }
    val stations by produceState<List<Pair<Tle, Sgp4>>>(emptyList(), mode) {
        if (mode != SkyModes.MAP) return@produceState
        value = withContext(Dispatchers.Default) {
            try { SatelliteTool.orbits(container, "stations").filter { it.name.startsWith("ISS (ZARYA)") || it.name.startsWith("CSS (TIANHE)") }.map { it to Sgp4(it) } } catch (_: Exception) { emptyList() }
        }
    }
    // a followed flight: its route once, its position every 15 s, its trail
    val route by produceState<FlightRoute?>(null, flightNumber) { if (flightNumber.isNotBlank()) value = Flights.route(container, flightNumber) }
    val trail = remember { mutableStateListOf<Pair<Double, Double>>() }
    val aircraft by produceState<Aircraft?>(null, callsign) {
        val cs = callsign ?: return@produceState
        while (true) {
            Flights.live(container, cs)?.let { a -> value = a; if (trail.lastOrNull() != (a.latitude to a.longitude)) trail += a.latitude to a.longitude }
            delay(15_000)
        }
    }
    // the rain radar: its frames, their tiles around the user, one after the other
    val frames by produceState<Pair<String, List<RadarFrame>>?>(null, radar) {
        if (!radar) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                container.http.newCall(Request.Builder().url("https://api.rainviewer.com/public/weather-maps.json").header("User-Agent", "Jarvis-Android").build())
                    .execute().use { r -> if (r.isSuccessful) parseRadarFrames(r.body?.string().orEmpty()) else null }
            } catch (_: Exception) {
                null
            }
        }
    }
    val tiles = remember { HashMap<String, ImageBitmap?>() }
    var tilesVersion by remember { mutableIntStateOf(0) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        val list = frames?.second ?: return@LaunchedEffect
        frame = list.indexOfLast { !it.forecast }.coerceAtLeast(0)
        while (true) {
            delay(if (frame == list.indexOfLast { !it.forecast }) 2_000 else 650)
            frame = (frame + 1) % list.size
        }
    }

    // where to look first: the user (radar), the flight, or the whole world
    LaunchedEffect(observer, aircraft, route) {
        if (placed) return@LaunchedEffect
        when {
            radar && observer != null -> { cx = mercX(observer!!.lonDeg); cy = mercY(observer!!.latDeg); zoom = 2.0.pow(RADAR_ZOOM) / 2.6; placed = true }
            callsign != null && aircraft != null -> {
                val a = aircraft!!
                val r = route
                if (r?.originLat != null && r.destLat != null) {
                    val xs = listOf(mercX(r.originLon!!), mercX(r.destLon!!), mercX(a.longitude))
                    val ys = listOf(mercY(r.originLat), mercY(r.destLat), mercY(a.latitude))
                    cx = (xs.min() + xs.max()) / 2; cy = (ys.min() + ys.max()) / 2
                    zoom = (0.75 / maxOf(xs.max() - xs.min(), (ys.max() - ys.min()) * 1.3, 0.004)).coerceIn(1.0, 200.0)
                } else { cx = mercX(a.longitude); cy = mercY(a.latitude); zoom = 30.0 }
                placed = true
            }
            mode == SkyModes.MAP -> placed = true
            mode == SkyModes.AURORA && observer != null -> {
                val o = observer!!
                cx = mercX(o.lonDeg); cy = mercY((o.latDeg + if (o.latDeg >= 0) 12 else -12).coerceIn(-80.0, 80.0)); zoom = 2.6; placed = true
            }
        }
    }

    Box(modifier.fillMaxSize().clipToBounds().background(OCEAN)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { centroid, pan, change, _ ->
                    val w = size.width.toDouble().coerceAtLeast(1.0)
                    val z2 = (zoom * change).coerceIn(1.0, if (radar) 2.0.pow(RADAR_ZOOM + 1) else 400.0)
                    // keep the point under the fingers where it is
                    val fx = cx + (centroid.x - size.width / 2.0) / (w * zoom)
                    val fy = cy + (centroid.y - size.height / 2.0) / (w * zoom)
                    cx = (fx - (centroid.x - size.width / 2.0) / (w * z2) - pan.x / (w * z2)).coerceIn(0.0, 1.0)
                    cy = (fy - (centroid.y - size.height / 2.0) / (w * z2) - pan.y / (w * z2)).coerceIn(0.0, 1.0)
                    zoom = z2
                }
            },
        ) {
            size = IntSize(this.size.width.toInt(), this.size.height.toInt())
            val scale = this.size.width * zoom
            // never past the map's top or bottom (85° N and S)
            val halfH = this.size.height / 2 / scale
            if (halfH < 0.5) cy = cy.coerceIn(halfH, 1 - halfH) else cy = 0.5
            fun px(mx: Double, my: Double) = Offset(((mx - cx) * scale + this.size.width / 2).toFloat(), ((my - cy) * scale + this.size.height / 2).toFloat())
            fun geo(lat: Double, lon: Double) = px(mercX(lon), mercY(lat))
            val d = data ?: return@Canvas
            // the land, the borders
            val landPath = Path()
            d.land.forEach { ring -> for (i in 0 until ring.size / 2) { val p = px(ring[2 * i].toDouble(), ring[2 * i + 1].toDouble()); if (i == 0) landPath.moveTo(p.x, p.y) else landPath.lineTo(p.x, p.y) }; landPath.close() }
            drawPath(landPath, LAND)
            val borderPath = Path()
            d.borders.forEach { ring -> for (i in 0 until ring.size / 2) { val p = px(ring[2 * i].toDouble(), ring[2 * i + 1].toDouble()); if (i == 0) borderPath.moveTo(p.x, p.y) else borderPath.lineTo(p.x, p.y) } }
            drawPath(borderPath, BORDER, style = Stroke(1f))
            // the rain radar, on the land
            if (radar) {
                val (host, list) = frames ?: (null to emptyList())
                val f = list.getOrNull(frame)
                if (host != null && f != null) {
                    @Suppress("UNUSED_VARIABLE") val v = tilesVersion
                    val n = 2.0.pow(RADAR_ZOOM)
                    val x0 = floor((cx - this.size.width / 2 / scale) * n).toInt()
                    val x1 = floor((cx + this.size.width / 2 / scale) * n).toInt()
                    val y0 = floor((cy - this.size.height / 2 / scale) * n).toInt()
                    val y1 = floor((cy + this.size.height / 2 / scale) * n).toInt()
                    for (tx in x0..x1) for (ty in y0..y1) {
                        if (ty < 0 || ty >= n) continue
                        val key = "${f.path}/$tx/$ty"
                        val img = tiles[key]
                        if (img != null) {
                            val tl = px(tx / n, ty / n)
                            val br = px((tx + 1) / n, (ty + 1) / n)
                            drawImage(img, dstOffset = IntOffset(tl.x.toInt(), tl.y.toInt()), dstSize = IntSize((br.x - tl.x).toInt() + 1, (br.y - tl.y).toInt() + 1), alpha = 0.85f)
                        }
                    }
                }
            }
            // the night, the Sun's point
            if (!radar) {
                val night = Path()
                nightPolygon(now).forEachIndexed { i, (lon, lat) -> val p = geo(lat, lon); if (i == 0) night.moveTo(p.x, p.y) else night.lineTo(p.x, p.y) }
                night.close()
                drawPath(night, NIGHT)
                val (sLat, sLon) = subSolar(now)
                drawCircle(ISS_COLOR, 6.dp.toPx(), geo(sLat, sLon))
            }
            // the aurora oval: each degree of the Earth by its chance
            aurora?.let { g ->
                for (lon in 0 until 360) for (lat in -84..84) {
                    val v = g.at(lat, lon)
                    if (v < 4) continue
                    val l = if (lon >= 180) lon - 360 else lon
                    val tl = geo(lat + 0.5, l - 0.5)
                    val br = geo(lat - 0.5, l + 0.5)
                    drawRect(AURORA_COLOR.copy(alpha = (v / 60f).coerceIn(0.06f, 0.85f)), tl, androidx.compose.ui.geometry.Size(br.x - tl.x + 0.5f, br.y - tl.y + 0.5f))
                }
            }
            // the cities (more as one zooms in)
            val minPop = when { zoom < 3 -> Int.MAX_VALUE; zoom < 8 -> 5_000_000; zoom < 25 -> 1_000_000; zoom < 60 -> 400_000; else -> 150_000 }
            d.cities.forEach { c ->
                if (c.population < minPop && !(c.capital && zoom >= 3)) return@forEach
                val p = geo(c.lat, c.lon)
                if (p.x < 0 || p.y < 0 || p.x > this.size.width || p.y > this.size.height) return@forEach
                drawCircle(Color(0xFF8FA9C4), 2.dp.toPx(), p)
                mapLabel(c.name, Offset(p.x + 4.dp.toPx(), p.y - 3.dp.toPx()), Color(0xFFB9D3EA), 10)
            }
            observer?.let { o -> drawCircle(YOU, 5.dp.toPx(), geo(o.latDeg, o.lonDeg)); drawCircle(Color.Black, 5.dp.toPx(), geo(o.latDeg, o.lonDeg), style = Stroke(1.5f)) }
            // the ISS and Tiangong: the track since three quarters of an hour and for the next orbit, and where they are
            stations.forEach { (tle, sgp) ->
                val color = if (tle.name.startsWith("ISS")) ISS_COLOR else CSS_COLOR
                groundTrack(sgp, now - 45 * 60_000L, now + 95 * 60_000L, 60_000L).forEach { seg ->
                    val path = Path()
                    seg.forEachIndexed { i, (lat, lon) -> val p = geo(lat, lon); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    drawPath(path, color.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }
                sgp.at(now)?.let { s ->
                    val (lat, lon, _) = subPoint(temeToEcef(s.x, s.y, s.z, now))
                    val p = geo(lat, lon)
                    drawCircle(color, 6.dp.toPx(), p)
                    mapLabel(if (tle.name.startsWith("ISS")) "ISS" else "Tiangong", Offset(p.x + 8.dp.toPx(), p.y - 6.dp.toPx()), color, 12, bold = true)
                }
            }
            // a flight: its route, its trail, the aircraft
            route?.let { r ->
                if (r.originLat != null && r.originLon != null && r.destLat != null && r.destLon != null) {
                    val path = Path()
                    greatCircle(r.originLat, r.originLon, r.destLat, r.destLon).forEachIndexed { i, (lat, lon) -> val p = geo(lat, lon); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    drawPath(path, FLIGHT_COLOR.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                    listOf(Triple(r.originLat, r.originLon, r.route.originCode), Triple(r.destLat, r.destLon, r.route.destinationCode)).forEach { (lat, lon, code) ->
                        val p = geo(lat, lon); drawCircle(Color.White, 4.dp.toPx(), p); mapLabel(code, Offset(p.x + 6.dp.toPx(), p.y + 4.dp.toPx()), Color.White, 11, bold = true)
                    }
                }
            }
            if (trail.size > 1) {
                val path = Path()
                trail.forEachIndexed { i, (lat, lon) -> val p = geo(lat, lon); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                drawPath(path, FLIGHT_COLOR, style = Stroke(2.dp.toPx()))
            }
            aircraft?.let { a ->
                val p = geo(a.latitude, a.longitude)
                val s = 9.dp.toPx()
                val arrow = Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s * 0.8f, p.y + s); lineTo(p.x, p.y + s * 0.5f); lineTo(p.x - s * 0.8f, p.y + s); close() }
                rotate((a.trackDeg ?: 0.0).toFloat(), p) { drawPath(arrow, FLIGHT_COLOR) }
            }
        }
        // the card: what is shown, in figures
        val text = Color(0xFFDCEBFA)
        val dim = Color(0xFF8FA9C4)
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xCC050B14), RoundedCornerShape(10.dp)).padding(8.dp)) {
            when {
                radar -> {
                    val list = frames?.second.orEmpty()
                    val f = list.getOrNull(frame)
                    Text("Radar de pluie" + (f?.let { " · " + radarTime(it, now) } ?: " · chargement…"), color = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("Beige : pluie faible · bleu : modérée · jaune puis rouge : forte · les 2 dernières heures en boucle", color = dim, style = MaterialTheme.typography.labelSmall)
                    Text("Radar : RainViewer · Carte : Natural Earth", color = dim, style = MaterialTheme.typography.labelSmall)
                }
                mode == SkyModes.AURORA -> {
                    val o = observer
                    val g = aurora
                    val needed = o?.let { kpNeeded(geomagneticLatitude(it.latDeg, it.lonDeg)) }
                    Text("Aurores : " + (kpNow?.let { "Kp %.1f · %s".format(Locale.FRANCE, it, stormScale(it)) } ?: "Kp…"), color = AURORA_COLOR, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (o != null && g != null) {
                        val v = auroraFrom(g, o.latDeg, o.lonDeg)
                        Text(if (v.percent >= 10) "Visible d’ici : ${v.percent} %" + (if (v.towardPole) ", bas vers le ${if (o.latDeg >= 0) "nord" else "sud"}" else ", au-dessus de vous") else "Pas visible d’ici pour l’instant (${v.percent} %)", color = text, style = MaterialTheme.typography.labelMedium)
                        needed?.let { Text("Il faut un Kp d’environ %.0f ici".format(Locale.FRANCE, kotlin.math.ceil(it)), color = dim, style = MaterialTheme.typography.labelSmall) }
                    }
                    Text("Vert : probabilité d’aurore dans l’heure (modèle OVATION, NOAA) · ombre : la nuit", color = dim, style = MaterialTheme.typography.labelSmall)
                }
                callsign != null -> {
                    val a = aircraft
                    val r = route
                    Text("Vol $flightNumber" + (r?.route?.airline?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    r?.route?.let { Text("${it.originCity} (${it.originCode}) → ${it.destinationCity} (${it.destinationCode})", color = ISS_COLOR, style = MaterialTheme.typography.labelMedium) }
                    if (a == null) Text("Pas vu en vol pour l’instant (pas encore parti, déjà arrivé, ou hors de portée des récepteurs)", color = dim, style = MaterialTheme.typography.labelSmall)
                    else {
                        Text(
                            listOfNotNull(
                                a.altitudeM?.let { "${fmtMap(it)} m" }, a.speedKmh?.let { "${fmtMap(it)} km/h" }, a.trackDeg?.let { "cap ${compass(it)}" },
                                a.verticalMs?.let { v -> if (kotlin.math.abs(v) < 0.5) "en palier" else if (v > 0) "monte" else "descend" },
                            ).joinToString(" · "),
                            color = text, style = MaterialTheme.typography.labelMedium,
                        )
                        if (r != null) minutesLeft(a, r)?.let { m ->
                            val eta = Instant.ofEpochMilli(now + m * 60_000L).atZone(ZoneId.systemDefault()).toLocalTime()
                            val left = if (r.destLat != null && r.destLon != null) com.jarvis.android.actions.distanceKm(a.latitude, a.longitude, r.destLat, r.destLon) else null
                            Text("Arrivée estimée vers %02dh%02d".format(eta.hour, eta.minute) + (left?.let { " · encore ${fmtMap(it)} km" } ?: ""), color = dim, style = MaterialTheme.typography.labelSmall)
                        }
                        data?.let { d -> nearestCity(d.cities, a.latitude, a.longitude, 300.0) }?.let { (c, km) ->
                            Text("Près de ${c.name} (${fmtMap(km)} km)", color = dim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                else -> {
                    stations.firstOrNull { it.first.name.startsWith("ISS") }?.let { (_, sgp) ->
                        sgp.at(now)?.let { s ->
                            val (lat, lon, alt) = subPoint(temeToEcef(s.x, s.y, s.z, now))
                            val near = data?.let { nearestCity(it.cities, lat, lon, 500.0) }
                            Text("ISS : %.1f°, %.1f° · %d km d’altitude · %s km/h".format(Locale.FRANCE, lat, lon, alt.toInt(), fmtMap(kotlin.math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz) * 3600)), color = ISS_COLOR, style = MaterialTheme.typography.labelMedium)
                            Text(near?.let { "Près de ${it.first.name} (${fmtMap(it.second)} km)" } ?: "Au-dessus d’un océan ou d’une région peu peuplée", color = dim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("Pointillés : la trace de l’ISS (jaune) et de Tiangong (bleu) · ombre : la nuit · point jaune : le Soleil au zénith", color = dim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // the radar's tiles: the current picture first, then the others, a few at a time
    LaunchedEffect(frames, cx, cy, zoom, size.width) {
        val (host, list) = frames ?: return@LaunchedEffect
        if (size.width == 0) return@LaunchedEffect
        val n = 2.0.pow(RADAR_ZOOM)
        val scale = size.width * zoom
        val x0 = floor((cx - size.width / 2 / scale) * n).toInt()
        val x1 = floor((cx + size.width / 2 / scale) * n).toInt()
        val y0 = floor((cy - size.height / 2 / scale) * n).toInt().coerceAtLeast(0)
        val y1 = floor((cy + size.height / 2 / scale) * n).toInt().coerceAtMost(n.toInt() - 1)
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 30) return@LaunchedEffect
        val order = list.indices.sortedByDescending { if (it == frame) Int.MAX_VALUE else it }
        for (i in order) for (tx in x0..x1) for (ty in y0..y1) {
            val key = "${list[i].path}/$tx/$ty"
            if (tiles.containsKey(key)) continue
            val wrapped = Math.floorMod(tx, n.toInt())
            tiles[key] = withContext(Dispatchers.IO) {
                try {
                    container.http.newCall(Request.Builder().url("$host${list[i].path}/256/$RADAR_ZOOM/$wrapped/$ty/2/1_1.png").header("User-Agent", "Jarvis-Android").build())
                        .execute().use { r -> r.body?.bytes()?.takeIf { r.isSuccessful }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                } catch (_: Exception) {
                    null
                }
            }
            tilesVersion++
        }
    }
}

private fun fmtMap(v: Double): String = String.format(Locale.FRANCE, "%,d", v.toInt())

/** "il y a 40 min", "maintenant", "dans 20 min (prévision)". */
private fun radarTime(f: RadarFrame, now: Long): String {
    val min = ((f.time - now) / 60_000).toInt()
    val hm = Instant.ofEpochMilli(f.time).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
    return when {
        f.forecast -> "$hm (prévision)"
        min > -12 -> "$hm (le plus récent)"
        else -> "$hm (il y a ${-min} min)"
    }
}

private fun DrawScope.mapLabel(text: String, at: Offset, color: Color, sizeSp: Int, bold: Boolean = false) {
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textSize = sizeSp.sp.toPx()
        isAntiAlias = true
        isFakeBoldText = bold
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
    })
}
