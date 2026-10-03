package com.jarvis.android.space

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What the sky view shows: "all", "satellites", "planes", "stars" (the night sky), or "pass:<name>" (a satellite's next pass). */
internal object SkyModes {
    const val ALL = "all"
    const val STARS = "stars"
    const val SATELLITES = "satellites"
    const val PLANES = "planes"
    const val PASS = "pass:"
    /** The world map: the ISS and Tiangong over the Earth, day and night. */
    const val MAP = "map"
    /** The rain radar around the user, animated. */
    const val RADAR = "radar"
    /** A followed flight on the world map: "flight:<callsign>|<flight number>". */
    const val FLIGHT = "flight:"
    /** The sky through the camera, with names on what is there. */
    const val AR = "ar"

    /** Fuel stations and their prices. */
    const val FUEL = "fuel"

    /** The nearest pharmacies, bakeries, cash machines, toilets or chargers, open or closed. */
    const val NEARBY = "nearby"

    /** The rivers under flood watch (Vigicrues). */
    const val FLOODS = "floods"

    /** Tonight's sky for observing, hour by hour. */
    const val OBSERVE = "observe"

    /** The earthquakes of the day and the week. */
    const val QUAKES = "quakes"

    /** The air quality around a place. */
    const val AIR = "air"

    /** A drive and its weather on the world map. */
    const val ROUTE = "route"

    /** The aurora oval on the world map. */
    const val AURORA = "aurora"

    /** The next rocket launches with their countdown. */
    const val LAUNCHES = "launches"

    /** The camera guiding to one object: "ar:<name>". */
    const val AR_TARGET = "ar:"

    fun isAr(mode: String) = mode == AR || mode.startsWith(AR_TARGET)

    /** Whether a mode is drawn on the world map rather than as a sky chart. */
    fun onMap(mode: String) = mode == MAP || mode == RADAR || mode == AURORA || mode == ROUTE || mode == AIR || mode == QUAKES || mode == FLOODS || mode == FUEL || mode == NEARBY || mode.startsWith(FLIGHT)
}

private val SKY_BG = Color(0xFF050B14)
private val GRID = Color(0xFF2A4A63)
private val VISIBLE = Color(0xFFFFD54F)
private val LIT = Color(0xFF7FD8FF)
private val SHADOW = Color(0xFF3E5C7A)
private val STARLINK = Color(0x668FA9C4)
private val PLANE = Color(0xFFFF9F43)
private val SELECT = Color(0xFFFF4D8D)
private val STAR = Color(0xFFEAF2FF)
private val FIGURE = Color(0x5582A8D0)

/** The colour a planet is drawn in (as it looks: Mars red, Jupiter cream…). */
private fun planetColor(id: String): Color = when (id) {
    "pl:MARS" -> Color(0xFFFF7A59)
    "pl:JUPITER" -> Color(0xFFF3D9A4)
    "pl:SATURN" -> Color(0xFFE8C872)
    "pl:VENUS" -> Color(0xFFFFF6D5)
    "pl:MERCURY" -> Color(0xFFBDB6AA)
    "pl:URANUS" -> Color(0xFF9FE8E4)
    "pl:NEPTUNE" -> Color(0xFF7FA0FF)
    "moon" -> Color(0xFFE9E9E1)
    "sun" -> Color(0xFFFFD54F)
    else -> STAR
}

/** A thing in the sky, drawn and listed: its id ("sat:25544", "ac:4ca760"), where it is, and what to say about it. */
private data class SkyItem(val id: String, val look: Look, val title: String, val line: String, val color: Color, val big: Boolean, val heading: Double? = null)

/**
 * The live sky in place of the avatar: a chart of the sky above the user (the horizon round, the zenith in the middle, north up) with
 * the brightest satellites, the Starlink, and the aircraft around, where they really are, moving each second; a list of them with their
 * details; and, for the one touched, everything the sources say (a satellite's orbit, speed and next pass with its path in the sky;
 * an aircraft's model, owner, airline, route, height, speed, climb, photo).
 */
@Composable
internal fun SkyView(mode: String, big: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as JarvisApp).container }
    val showSats = mode != SkyModes.PLANES && mode != SkyModes.STARS
    val showNight = mode != SkyModes.PLANES
    val showPlanes = mode == SkyModes.ALL || mode == SkyModes.PLANES
    val passTarget = mode.removePrefix(SkyModes.PASS).takeIf { mode.startsWith(SkyModes.PASS) }

    val observer by produceState<Observer?>(null) {
        value = (com.jarvis.android.weather.locate(container.appContext) as? com.jarvis.android.weather.LocationOutcome.Found)?.let { Observer(it.fix.latitude, it.fix.longitude) }
    }
    var located by remember { mutableStateOf(false) }
    LaunchedEffect(observer) { if (observer != null) located = true else { delay(12_000); located = true } }

    val sats by produceState<List<Pair<Tle, Sgp4>>>(emptyList(), showSats) {
        if (!showSats) return@produceState
        value = withContext(Dispatchers.Default) {
            try {
                (SatelliteTool.orbits(container, "stations") + SatelliteTool.orbits(container, "visual")).distinctBy { it.number }
                    .map { it to Sgp4(it) }.filter { it.second.nearEarth }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val starlinks by produceState<List<Sgp4>>(emptyList(), showSats, passTarget) {
        if (!showSats || passTarget != null) return@produceState
        value = withContext(Dispatchers.Default) {
            try { SatelliteTool.orbits(container, "starlink").map { Sgp4(it) }.filter { it.nearEarth } } catch (_: Exception) { emptyList() }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

    // the satellites now (each second), the Starlink (every 5 s: there are thousands), the aircraft (every 10 s)
    val satsNow by produceState<List<SatNow>>(emptyList(), observer, sats, now) {
        val o = observer ?: return@produceState
        value = withContext(Dispatchers.Default) { sats.mapNotNull { (tle, sgp) -> satNow(sgp, tle, o, now) } }
    }
    val starlinkNow by produceState<List<Look>>(emptyList(), observer, starlinks, now / 5_000) {
        val o = observer ?: return@produceState
        value = withContext(Dispatchers.Default) {
            starlinks.mapNotNull { sgp -> sgp.at(now)?.let { s -> lookAt(o, temeToEcef(s.x, s.y, s.z, now)).takeIf { it.elevationDeg > 0 } } }
        }
    }
    val aircraft by produceState<List<Pair<Aircraft, Look>>>(emptyList(), observer, showPlanes) {
        val o = observer ?: return@produceState
        if (!showPlanes) return@produceState
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
    val dark = observer?.let { sunElevation(it, now) < -6 } ?: false
    // the night sky: the catalogue's stars (every 20 s: they move slowly), the Sun, the Moon and the planets (every 5 s)
    val catalogue by produceState<List<Star>>(emptyList(), showNight) {
        if (showNight) value = withContext(Dispatchers.IO) { try { SkyAssets.stars(container.appContext) } catch (_: Exception) { emptyList() } }
    }
    val starsNow by produceState<List<NightObject>>(emptyList(), observer, catalogue, now / 20_000) {
        val o = observer ?: return@produceState
        value = withContext(Dispatchers.Default) { starsAbove(catalogue, o, now, 5.0) }
    }
    val solarNow by produceState<List<NightObject>>(emptyList(), observer, showNight, now / 5_000) {
        val o = observer ?: return@produceState
        if (showNight) value = withContext(Dispatchers.Default) { solarSystem(o, now) }
    }
    val phase = remember(now / 600_000) { moonPhase(now) }

    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(passTarget, sats) {
        if (passTarget != null && selected == null) sats.firstOrNull { matches(it.first, passTarget) }?.let { selected = "sat:${it.first.number}" }
    }

    // what is drawn and listed
    val satItems = satsNow.filter { it.look.elevationDeg > 0 || "sat:${it.tle.number}" == selected }.sortedByDescending { it.look.elevationDeg }.map { s ->
        val visible = s.lit && dark && s.look.elevationDeg > 10
        SkyItem(
            "sat:${s.tle.number}", s.look, friendlyName(s.tle.name).replaceFirstChar { it.uppercase() },
            "${s.look.elevationDeg.toInt()}° ${towardDirection(s.look.azimuthDeg)} · ${s.altitudeKm.toInt()} km d’altitude · ${fmt(s.look.rangeKm)} km de vous" +
                if (visible) " · visible à l’œil nu" else if (s.lit) " · éclairé" else " · dans l’ombre",
            if (visible) VISIBLE else if (s.lit) LIT else SHADOW,
            s.tle.name.startsWith("ISS") || s.tle.name.startsWith("CSS"),
        )
    }
    val planeItems = aircraft.filter { it.second.elevationDeg > -2 }.sortedBy { it.second.rangeKm }.map { (a, l) ->
        SkyItem(
            "ac:${a.hex}", l, a.label,
            listOfNotNull(
                a.altitudeM?.let { "${fmt(it)} m" }, a.speedKmh?.let { "${fmt(it)} km/h" }, a.trackDeg?.let { "cap ${compass(it)}" },
                "${fmt(l.rangeKm)} km", a.typeCode.ifBlank { null }, squawkWords(a.squawk).ifBlank { null },
            ).joinToString(" · "),
            if (squawkWords(a.squawk).isNotEmpty()) SELECT else PLANE, false, a.trackDeg,
        )
    }
    val nightItems = (solarNow.filter { (it.look.elevationDeg > 0 && it.magnitude < 6) || it.id == selected } +
        starsNow.filter { (it.magnitude < 1.6 && it.star?.name != null) || it.id == selected }).map { n ->
        SkyItem(
            n.id, n.look,
            when (n.kind) {
                NightObject.Kind.MOON -> "La Lune"
                NightObject.Kind.SUN -> "Le Soleil"
                else -> n.name
            },
            "${n.look.elevationDeg.toInt()}° ${towardDirection(n.look.azimuthDeg)} · " + when (n.kind) {
                NightObject.Kind.MOON -> "${phase.name}, éclairée à ${(phase.lit * 100).toInt()} %"
                NightObject.Kind.PLANET -> "planète, magnitude ${"%.1f".format(Locale.FRANCE, n.magnitude)}"
                NightObject.Kind.STAR -> "étoile de ${CONSTELLATIONS[n.star?.constellation] ?: n.star?.constellation}, magnitude ${"%.1f".format(Locale.FRANCE, n.magnitude)}"
                NightObject.Kind.SUN -> "ne jamais le regarder en face"
            },
            planetColor(n.id), n.kind != NightObject.Kind.STAR,
        )
    }
    val items = when {
        mode == SkyModes.PLANES -> planeItems
        mode == SkyModes.STARS -> nightItems
        mode == SkyModes.SATELLITES || passTarget != null -> satItems + nightItems
        else -> planeItems + satItems + nightItems
    }

    val selectedSat = selected?.takeIf { it.startsWith("sat:") }?.removePrefix("sat:")?.toIntOrNull()?.let { n -> sats.firstOrNull { it.first.number == n } }
    val track by produceState<List<Look>>(emptyList(), selectedSat, observer, now / 30_000) {
        val o = observer ?: return@produceState
        val (_, sgp) = selectedSat ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) { skyTrack(sgp, o, now - 5 * 60_000L, now + 15 * 60_000L) }
    }
    val nextPass by produceState<Pass?>(null, selectedSat, observer) {
        val o = observer ?: return@produceState
        val (_, sgp) = selectedSat ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.Default) { passes(sgp, o, System.currentTimeMillis(), 72, 10.0).firstOrNull() }
    }
    val passTrack by produceState<List<Look>>(emptyList(), nextPass, selectedSat) {
        val p = nextPass ?: run { value = emptyList(); return@produceState }
        val o = observer ?: return@produceState
        val (_, sgp) = selectedSat ?: return@produceState
        value = withContext(Dispatchers.Default) { skyTrack(sgp, o, p.riseMs, p.setMs, 15_000L) }
    }

    Box(modifier.fillMaxSize().background(SKY_BG)) {
        when {
            observer == null && !located -> Text("Je cherche votre position…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            observer == null -> Text(
                "Sans votre position, pas de ciel à montrer : autorisez la position pour Jarvis (Paramètres > Position (météo)).",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > maxHeight * 1.2f
                val chart: @Composable (Modifier) -> Unit = { m ->
                    SkyChart(items, starlinkNow, starsNow, phase, solarNow.firstOrNull { it.kind == NightObject.Kind.SUN }?.look, track, passTrack, selected, dark, { selected = if (selected == it) null else it }, m)
                }
                val details: @Composable (Modifier) -> Unit = { m ->
                    SkyDetails(
                        container, items, selected, { selected = if (selected == it) null else it },
                        satsNow, aircraft, starlinkNow.size, dark, nextPass, selectedSat?.first, now, big, m,
                        solarNow + starsNow, observer, phase,
                    )
                }
                if (wide) Row(Modifier.fillMaxSize()) {
                    chart(Modifier.fillMaxHeight().aspectRatio(1f).padding(8.dp))
                    details(Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    chart(Modifier.fillMaxWidth().weight(1.15f).padding(6.dp))
                    details(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

private fun matches(tle: Tle, name: String): Boolean {
    val w = name.lowercase()
    return when {
        w.isEmpty() || "iss" in w || "internationale" in w -> tle.name.startsWith("ISS (ZARYA)")
        "tiangong" in w || "chinoise" in w || "css" in w -> tle.name.startsWith("CSS (TIANHE)")
        "hubble" in w -> tle.name == "HST"
        else -> tle.name.contains(name, ignoreCase = true)
    }
}

private fun fmt(v: Double): String = String.format(Locale.FRANCE, "%,d", v.toInt())

/** Where a point of the sky goes on a chart of radius [r] centred at [c]: the zenith in the middle, the horizon on the rim, north up. */
private fun place(l: Look, c: Offset, r: Float): Offset {
    val d = ((90 - l.elevationDeg.coerceIn(-2.0, 90.0)) / 90.0).toFloat() * r
    val a = Math.toRadians(l.azimuthDeg).toFloat()
    return Offset(c.x + d * sin(a), c.y - d * cos(a))
}

@Composable
private fun SkyChart(
    items: List<SkyItem>, starlink: List<Look>, stars: List<NightObject>, phase: MoonPhase, sun: Look?, track: List<Look>, passTrack: List<Look>,
    selected: String?, dark: Boolean, onSelect: (String) -> Unit, modifier: Modifier,
) {
    val labelColor = Color(0xFFB9D3EA)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxHeight().aspectRatio(1f).pointerInput(items) {
                detectTapGestures { p ->
                    val r = size.width / 2f * 0.92f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    items.minByOrNull { (place(it.look, c, r) - p).getDistance() }
                        ?.takeIf { (place(it.look, c, r) - p).getDistance() < 28.dp.toPx() }?.let { onSelect(it.id) }
                }
            },
        ) {
            val r = size.minDimension / 2f * 0.92f
            val c = center
            drawCircle(Brush.radialGradient(listOf(if (dark) Color(0xFF0B1A2E) else Color(0xFF16324F), SKY_BG), c, r), r, c)
            drawCircle(GRID, r, c, style = Stroke(1.5.dp.toPx()))
            listOf(30.0, 60.0).forEach { el -> drawCircle(GRID.copy(alpha = 0.6f), r * ((90 - el) / 90).toFloat(), c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))) }
            drawLine(GRID.copy(alpha = 0.5f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
            drawLine(GRID.copy(alpha = 0.5f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            cardinal("N", Offset(c.x, c.y - r - 2.dp.toPx()), labelColor)
            cardinal("E", Offset(c.x + r + 6.dp.toPx(), c.y), labelColor)
            cardinal("S", Offset(c.x, c.y + r + 8.dp.toPx()), labelColor)
            cardinal("O", Offset(c.x - r - 6.dp.toPx(), c.y), labelColor)
            // the constellations' figures, then the stars by brightness (the faintest only as part of a figure), the brightest named
            val byBayer = stars.associateBy { it.star?.bayer }
            FIGURES.forEach { figure ->
                figure.zipWithNext().forEach { (a, b) ->
                    val la = byBayer[a]?.look
                    val lb = byBayer[b]?.look
                    if (la != null && lb != null) drawLine(FIGURE, place(la, c, r), place(lb, c, r), 1.dp.toPx())
                }
            }
            val inFigures = FIGURES.flatten().toSet()
            stars.forEach { s ->
                if (s.magnitude > 4.5 && s.star?.bayer !in inFigures) return@forEach
                val size = (2.6 - 0.45 * s.magnitude).coerceIn(0.5, 3.4).toFloat().dp.toPx()
                drawCircle(STAR.copy(alpha = if (dark) 0.95f else 0.55f), size, place(s.look, c, r))
                if (s.magnitude < 1.3 && s.star?.name != null) label(s.name, Offset(place(s.look, c, r).x + 5.dp.toPx(), place(s.look, c, r).y + 3.dp.toPx()), STAR.copy(alpha = 0.7f))
            }
            starlink.forEach { drawCircle(STARLINK, 1.4.dp.toPx(), place(it, c, r)) }
            // a satellite's next pass (dashed) and its path around now
            if (passTrack.size > 1) drawPath(pathOf(passTrack, c, r), VISIBLE.copy(alpha = 0.8f), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            if (track.size > 1) drawPath(pathOf(track, c, r), SELECT.copy(alpha = 0.85f), style = Stroke(2.dp.toPx()))
            // below the horizon: in the list and the card, not on the chart (its rim would say "on the horizon")
            items.filter { it.look.elevationDeg > -1 }.forEach { it ->
                val p = place(it.look, c, r)
                when {
                    it.id.startsWith("ac:") -> plane(p, it.color, (it.heading ?: 0.0).toFloat(), it.id == selected)
                    it.id == "moon" -> moon(p, phase, sun?.let { s -> place(s, c, r) })
                    it.id == "sun" -> drawCircle(it.color, 8.dp.toPx(), p)
                    it.id.startsWith("pl:") -> { drawCircle(it.color, 4.dp.toPx(), p); label(it.title, Offset(p.x + 7.dp.toPx(), p.y + 4.dp.toPx()), it.color) }
                    it.id.startsWith("star:") -> {}
                    else -> drawCircle(it.color, (if (it.big) 5 else 3).dp.toPx(), p)
                }
                if (it.id == selected) drawCircle(SELECT, 11.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                if ((it.big && !it.id.startsWith("pl:")) || it.id == selected) label(shortLabel(it.title), Offset(p.x + 8.dp.toPx(), p.y - 6.dp.toPx()), Color.White)
            }
        }
    }
}

/** A short name for the chart: "ISS", "Tiangong", "Hubble", or the first words. */
internal fun shortLabel(title: String): String = when {
    "(ISS)" in title -> "ISS"
    "Tiangong" in title -> "Tiangong"
    "Hubble" in title -> "Hubble"
    else -> title.substringBefore(" (").take(16)
}

private fun pathOf(points: List<Look>, c: Offset, r: Float): Path = Path().apply {
    points.forEachIndexed { i, l -> val p = place(l, c, r); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}

/**
 * The Moon: a disc lit on the side of the Sun ([sun], its place on the chart, or to the right when the Sun is not drawn) as much as its
 * phase says: a lit half, and an ellipse that adds to it (gibbous) or eats into it (crescent).
 */
private fun DrawScope.moon(p: Offset, phase: MoonPhase, sun: Offset?) {
    val r = 8.dp.toPx()
    drawCircle(Color(0xFF2E3440), r, p)
    val toward = if (sun != null) kotlin.math.atan2(sun.y - p.y, sun.x - p.x) else 0f
    rotate(Math.toDegrees(toward.toDouble()).toFloat(), p) {
        val lit = Color(0xFFE9E9E1)
        drawArc(lit, -90f, 180f, true, Offset(p.x - r, p.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
        val w = (kotlin.math.abs(1 - 2 * phase.lit) * r).toFloat()
        drawOval(if (phase.lit > 0.5) lit else Color(0xFF2E3440), Offset(p.x - w, p.y - r), androidx.compose.ui.geometry.Size(2 * w, 2 * r))
    }
}

/** An aircraft: an arrow pointing where it flies (its heading, north up as the chart is). */
private fun DrawScope.plane(p: Offset, color: Color, heading: Float, chosen: Boolean) {
    val s = (if (chosen) 7 else 5).dp.toPx()
    val path = Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s * 0.8f, p.y + s); lineTo(p.x, p.y + s * 0.5f); lineTo(p.x - s * 0.8f, p.y + s); close() }
    rotate(heading, p) { drawPath(path, color) }
}

private fun DrawScope.cardinal(text: String, at: Offset, color: Color) = label(text, Offset(at.x - 4.dp.toPx(), at.y + 4.dp.toPx()), color, bold = true)

private fun DrawScope.label(text: String, at: Offset, color: Color, bold: Boolean = false) {
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textSize = 11.sp.toPx()
        isAntiAlias = true
        isFakeBoldText = bold
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
    })
}

@Composable
private fun SkyDetails(
    container: com.jarvis.android.JarvisContainer, items: List<SkyItem>, selected: String?, onSelect: (String) -> Unit,
    sats: List<SatNow>, aircraft: List<Pair<Aircraft, Look>>, starlinkCount: Int, dark: Boolean, nextPass: Pass?, passSat: Tle?, now: Long,
    big: Boolean, modifier: Modifier, night: List<NightObject> = emptyList(), observer: Observer? = null, phase: MoonPhase? = null,
) {
    val above = sats.count { it.look.elevationDeg > 0 }
    val visible = sats.count { it.look.elevationDeg > 10 && it.lit && dark }
    val text = Color(0xFFDCEBFA)
    val dim = Color(0xFF8FA9C4)
    LazyColumn(modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                (if (dark) "Nuit noire" else "Jour ou crépuscule") +
                    (if (sats.isNotEmpty()) " · $above satellites brillants au-dessus ($visible visibles à l’œil nu)" else "") +
                    (if (starlinkCount > 0) " · $starlinkCount Starlink" else "") + (if (aircraft.isNotEmpty()) " · ${aircraft.size} avions à 75 km" else "") +
                    (phase?.takeIf { night.isNotEmpty() }?.let { " · Lune ${it.name}, ${(it.lit * 100).toInt()} %" } ?: ""),
                color = dim, style = MaterialTheme.typography.labelSmall,
            )
        }
        val sel = items.firstOrNull { it.id == selected }
        if (selected != null) item {
            Column(Modifier.fillMaxWidth().background(Color(0x3319A0FF), RoundedCornerShape(10.dp)).padding(8.dp)) {
                if (selected.startsWith("ac:")) aircraft.firstOrNull { "ac:${it.first.hex}" == selected }?.let { (a, l) -> AircraftCard(container, a, l, text, dim) }
                else if (!selected.startsWith("sat:")) night.firstOrNull { it.id == selected }?.let { n -> NightCard(n, observer, phase, now, text, dim) }
                else sats.firstOrNull { "sat:${it.tle.number}" == selected }?.let { s -> SatelliteCard(s, nextPass, passSat, now, dark, text, dim) }
                    ?: sel?.let { Text(it.title, color = text) }
            }
        }
        items(items.take(if (big) 80 else 40), key = { it.id }) { it ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(it.id) }.background(if (it.id == selected) Color(0x2219A0FF) else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(vertical = 3.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.padding(end = 8.dp).background(it.color, RoundedCornerShape(50)).height(10.dp).aspectRatio(1f))
                Column {
                    Text(it.title, color = text, style = MaterialTheme.typography.labelLarge, fontWeight = if (it.big) FontWeight.Bold else FontWeight.Normal)
                    Text(it.line, color = dim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, text: Color, dim: Color) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.42f))
        Text(value, color = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun NightCard(n: NightObject, observer: Observer?, phase: MoonPhase?, now: Long, text: Color, dim: Color) {
    val zone = ZoneId.systemDefault()
    fun hm(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { t -> "%02dh%02d".format(t.hour, t.minute) } } ?: "—"
    val vector: ((Long) -> Triple<Double, Double, Double>)? = when {
        n.kind == NightObject.Kind.MOON -> { t -> moonVector(t) }
        n.kind == NightObject.Kind.SUN -> { t -> sunPosition(t) }
        n.id.startsWith("pl:") -> Planet.values().firstOrNull { "pl:${it.name}" == n.id }?.let { p -> { t: Long -> planetVector(p, t) } }
        else -> n.star?.let { s -> { t: Long -> starVector(s, t) } }
    }
    val times by produceState<Pair<Long?, Long?>?>(null, n.id, observer) {
        val o = observer ?: return@produceState
        val v = vector ?: return@produceState
        value = withContext(Dispatchers.Default) { riseSet(o, System.currentTimeMillis(), 24, v) }
    }
    Text(
        when (n.kind) { NightObject.Kind.MOON -> "La Lune"; NightObject.Kind.SUN -> "Le Soleil"; else -> n.name },
        color = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
    )
    Detail("Nature", when (n.kind) {
        NightObject.Kind.MOON -> "satellite naturel de la Terre"
        NightObject.Kind.SUN -> "notre étoile (ne jamais la regarder en face)"
        NightObject.Kind.PLANET -> "planète du système solaire"
        NightObject.Kind.STAR -> "étoile" + (n.star?.let { " de ${CONSTELLATIONS[it.constellation] ?: it.constellation} (${it.bayer})" } ?: "")
    }, text, dim)
    Detail("Dans votre ciel", if (n.look.elevationDeg > 0) "${n.look.elevationDeg.toInt()}° de haut, ${towardDirection(n.look.azimuthDeg)} (${n.look.azimuthDeg.toInt()}°)" else "sous l’horizon", text, dim)
    if (n.kind == NightObject.Kind.MOON && phase != null) {
        Detail("Phase", "${phase.name}, éclairée à ${(phase.lit * 100).toInt()} %", text, dim)
        Detail("Distance", "${fmt(n.distanceKm)} km", text, dim)
    }
    if (n.kind == NightObject.Kind.PLANET) {
        Detail("Distance", "${"%.2f".format(Locale.FRANCE, n.distanceKm / 149_597_870.7)} UA (${fmt(n.distanceKm / 1_000_000)} millions de km)", text, dim)
        Detail("Lumière", "partie il y a ${fmt(n.distanceKm / 299_792.458 / 60)} min", text, dim)
    }
    if (n.kind != NightObject.Kind.SUN) Detail("Éclat", "magnitude ${"%.1f".format(Locale.FRANCE, n.magnitude)} (plus c’est bas, plus c’est brillant)", text, dim)
    times?.let { (rise, set) ->
        Detail("Lever", hm(rise), text, dim)
        Detail("Coucher", hm(set), text, dim)
    }
}

@Composable
private fun SatelliteCard(s: SatNow, pass: Pass?, passSat: Tle?, now: Long, dark: Boolean, text: Color, dim: Color) {
    Text(friendlyName(s.tle.name).replaceFirstChar { it.uppercase() }, color = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Detail("Dans votre ciel", if (s.look.elevationDeg > 0) "${s.look.elevationDeg.toInt()}° de haut, ${towardDirection(s.look.azimuthDeg)} (${s.look.azimuthDeg.toInt()}°)" else "sous l’horizon", text, dim)
    Detail("Distance", "${fmt(s.look.rangeKm)} km", text, dim)
    Detail("Altitude", "${fmt(s.altitudeKm)} km", text, dim)
    Detail("Vitesse", "${fmt(s.speedKmh)} km/h", text, dim)
    Detail("Au-dessus de", "%.2f°, %.2f°".format(Locale.FRANCE, s.latitude, s.longitude), text, dim)
    Detail("Soleil", if (s.lit) "éclairé" else "dans l’ombre de la Terre", text, dim)
    Detail("À l’œil nu", if (s.lit && dark && s.look.elevationDeg > 10) "visible maintenant" else "pas maintenant", text, dim)
    Detail("Orbite", "un tour en ${s.tle.periodMin.toInt()} min, inclinée à ${Math.toDegrees(s.tle.inclination).toInt()}°", text, dim)
    Detail("N° de catalogue", s.tle.number.toString(), text, dim)
    if (pass != null && passSat?.number == s.tle.number) {
        val zone = ZoneId.systemDefault()
        fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
        val inMin = ((pass.riseMs - now) / 60_000).coerceAtLeast(0)
        Detail(
            "Prochain passage",
            (if (pass.riseMs <= now) "en cours" else "dans ${if (inMin >= 60) "${inMin / 60} h ${inMin % 60} min" else "$inMin min"}") +
                " : ${hm(pass.riseMs)} ${fromDirection(pass.riseAzimuth)}, ${pass.maxElevation.toInt()}° à ${hm(pass.maxMs)}, ${hm(pass.setMs)} ${towardDirection(pass.setAzimuth)} — " +
                if (pass.visible) "visible (tracé jaune)" else if (pass.daylight) "de jour" else "dans l’ombre",
            text, dim,
        )
    }
}

@Composable
private fun AircraftCard(container: com.jarvis.android.JarvisContainer, a: Aircraft, l: Look, text: Color, dim: Color) {
    val info by produceState<Pair<Route?, AircraftInfo?>?>(null, a.hex, a.flight) {
        value = withContext(Dispatchers.IO) { SkyLookups.route(container, a.flight) to SkyLookups.aircraft(container, a.hex) }
    }
    val photo by produceState<ImageBitmap?>(null, info?.second?.photo) {
        val url = info?.second?.photo?.takeIf { it.isNotBlank() } ?: return@produceState
        value = withContext(Dispatchers.IO) { SkyLookups.photo(container, url) }
    }
    val route = info?.first
    val model = info?.second
    Row {
        Column(Modifier.weight(1f)) {
            Text(a.label + (route?.airline?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (route != null) Text(
                "${route.originCity.ifBlank { route.originAirport }} (${route.originCode}) → ${route.destinationCity.ifBlank { route.destinationAirport }} (${route.destinationCode})",
                color = VISIBLE, style = MaterialTheme.typography.labelMedium,
            )
        }
        photo?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.height(54.dp).aspectRatio(1.5f)) }
    }
    Detail("Appareil", listOf(model?.manufacturer, model?.model).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { a.typeCode } + categoryWords(a.category).let { if (it.isNotBlank()) " ($it)" else "" }, text, dim)
    Detail("Immatriculation", a.registration, text, dim)
    Detail("Propriétaire", listOfNotNull(model?.owner?.ifBlank { null }, model?.ownerCountry?.ifBlank { null }).joinToString(", "), text, dim)
    Detail("Altitude", a.altitudeFt?.let { "${fmt(a.altitudeM ?: 0.0)} m (${fmt(it)} pieds)" } ?: "", text, dim)
    Detail("Vitesse", a.speedKmh?.let { "${fmt(it)} km/h (${fmt(a.speedKt ?: 0.0)} nœuds)" } ?: "", text, dim)
    Detail("Cap", a.trackDeg?.let { "${it.toInt()}°, ${towardDirection(it)}" } ?: "", text, dim)
    Detail("Vario", a.verticalMs?.let { v -> if (kotlin.math.abs(v) < 0.5) "en palier" else if (v > 0) "monte de ${"%.1f".format(Locale.FRANCE, v)} m/s" else "descend de ${"%.1f".format(Locale.FRANCE, -v)} m/s" } ?: "", text, dim)
    Detail("Dans votre ciel", "${l.elevationDeg.toInt()}° de haut, ${towardDirection(l.azimuthDeg)}, à ${fmt(l.rangeKm)} km", text, dim)
    Detail("Transpondeur", listOfNotNull(a.squawk.ifBlank { null }, squawkWords(a.squawk).ifBlank { null }, a.emergency.ifBlank { null }).joinToString(" · ") + " · adresse ${a.hex.uppercase()}", text, dim)
}

/** The lookups for the aircraft chosen (adsbdb), kept for the session: a flight's route, an aircraft's model and photo. */
internal object SkyLookups {
    // "not found" is kept too (a map that takes nulls): a flight without a known route is not asked for again
    private val routes = java.util.Collections.synchronizedMap(HashMap<String, Route?>())
    private val models = java.util.Collections.synchronizedMap(HashMap<String, AircraftInfo?>())

    private fun get(container: com.jarvis.android.JarvisContainer, url: String): String? = try {
        container.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    fun route(container: com.jarvis.android.JarvisContainer, flight: String): Route? {
        val f = flight.trim().uppercase().filter { it.isLetterOrDigit() }
        if (f.length < 3) return null
        if (routes.containsKey(f)) return routes[f]
        return get(container, "https://api.adsbdb.com/v0/callsign/$f")?.let { parseRoute(it) }.also { routes[f] = it }
    }

    fun aircraft(container: com.jarvis.android.JarvisContainer, hex: String): AircraftInfo? {
        val h = hex.trim().lowercase().filter { it.isLetterOrDigit() }
        if (h.length != 6) return null
        if (models.containsKey(h)) return models[h]
        return get(container, "https://api.adsbdb.com/v0/aircraft/$h")?.let { parseAircraftInfo(it) }.also { models[h] = it }
    }

    fun photo(container: com.jarvis.android.JarvisContainer, url: String): ImageBitmap? = try {
        container.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.takeIf { it.size < 2_000_000 }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    } catch (_: Exception) {
        null
    }
}
