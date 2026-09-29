package com.jarvis.android.space

import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.Tool
import com.jarvis.android.actions.objectSchema
import com.jarvis.android.actions.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The night sky by voice: what can be seen now (the Moon, the planets, the brightest stars), and what a bright point is. */
object NightSkyTool : Tool {
    override val name = "night_sky"
    override val description =
        "Le ciel de la nuit, calculé sur le téléphone pour la position de l’utilisateur. action « now » : ce qu’on voit maintenant (la Lune " +
            "et sa phase, les planètes levées avec leur direction et leur hauteur, et quand se lèvent les autres, les étoiles les plus brillantes) ; " +
            "« identify » : ce qu’est un point brillant, direction (nord, sud-est…) et height (bas, haut, au-dessus, ou en degrés). Pour « qu’est-ce " +
            "qu’on voit ce soir ? », « où est la Lune ? », « c’est quoi le point brillant au sud ? », « c’est quelle étoile là-haut ? »."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "now ou identify.")
        string("direction", "Pour identify : où l’utilisateur regarde (nord, nord-est, est, sud…).")
        string("height", "Pour identify : à quelle hauteur (bas, haut, très haut, au-dessus, ou « 30° »).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.Default) {
        val o = (com.jarvis.android.weather.locate(ctx.appContext) as? com.jarvis.android.weather.LocationOutcome.Found)?.let { Observer(it.fix.latitude, it.fix.longitude) }
            ?: return@withContext "Je n’ai pas votre position : autorisez la position pour Jarvis (Paramètres > Position (météo))."
        val now = System.currentTimeMillis()
        val solar = solarSystem(o, now)
        val sunEl = solar.first { it.kind == NightObject.Kind.SUN }.look.elevationDeg
        val stars = starsAbove(SkyAssets.stars(ctx.appContext), o, now, 2.5)
        when (args.stringArg("action").trim().lowercase()) {
            "identify", "identifier", "quoi" -> {
                val (az, el) = pointOfSky(args.stringArg("direction") + " " + args.stringArg("height"))
                val target = Look(el, az ?: 0.0, 0.0)
                val near = (solar.filter { it.kind != NightObject.Kind.SUN && it.look.elevationDeg > 0 } + stars)
                    .map { it to (if (az == null) kotlin.math.abs(it.look.elevationDeg - el) else skyAngle(it.look, target)) }
                    .filter { it.second < 25 }
                    .sortedBy { (it.first.magnitude - 1.5 * (25 - it.second) / 25) }
                if (near.isEmpty()) return@withContext "Rien de brillant par là en ce moment (ni planète, ni étoile brillante) : c’est peut-être un avion ou un satellite (sky_view pour les voir)."
                near.take(4).joinToString("\n", prefix = "Là (${args.stringArg("direction")} ${args.stringArg("height")}), le plus probable d’abord :\n") { (n, d) ->
                    "- ${describe(n, now)} (à ${d.toInt()}° de l’endroit indiqué)"
                } + if (sunEl > -6) "\n(Il ne fait pas encore nuit : seuls la Lune, Vénus et Jupiter se voient bien.)" else ""
            }
            else -> {
                val zone = ZoneId.systemDefault()
                fun hm(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { t -> "%02dh%02d".format(t.hour, t.minute) } }
                val moon = solar.first { it.kind == NightObject.Kind.MOON }
                val phase = moonPhase(now)
                val (mRise, mSet) = riseSet(o, now) { moonVector(it) }
                val moonLine = "La Lune (${phase.name}, éclairée à ${(phase.lit * 100).toInt()} %) : " +
                    if (moon.look.elevationDeg > 0) "à ${moon.look.elevationDeg.toInt()}° de haut ${towardDirection(moon.look.azimuthDeg)}${hm(mSet)?.let { ", se couche à $it" }.orEmpty()}"
                    else "sous l’horizon${hm(mRise)?.let { ", se lève à $it" }.orEmpty()}"
                val planets = solar.filter { it.kind == NightObject.Kind.PLANET && it.magnitude < 2 }.map { p ->
                    val planet = Planet.values().first { "pl:${it.name}" == p.id }
                    if (p.look.elevationDeg > 0) "- ${p.name} : ${p.look.elevationDeg.toInt()}° de haut ${towardDirection(p.look.azimuthDeg)}"
                    else "- ${p.name} : sous l’horizon" + riseSet(o, now) { planetVector(planet, it) }.first.let { r -> hm(r)?.let { ", se lève à $it" }.orEmpty() }
                }
                val bright = stars.sortedBy { it.magnitude }.take(6).joinToString(", ") { s ->
                    "${s.name} (${CONSTELLATIONS[s.star?.constellation] ?: s.star?.constellation}, ${towardDirection(s.look.azimuthDeg)})"
                }
                (if (sunEl > -6) "Il ne fait pas encore nuit noire (le Soleil est à ${sunEl.toInt()}°). " else "Il fait nuit. ") + moonLine + ".\nPlanètes :\n" +
                    planets.joinToString("\n") + (if (bright.isNotEmpty()) "\nÉtoiles les plus brillantes levées : $bright." else "") +
                    "\n(Pour les voir sur une carte : sky_view.)"
            }
        }
    }

    private fun describe(n: NightObject, now: Long): String = when (n.kind) {
        NightObject.Kind.MOON -> "la Lune, ${moonPhase(now).name}"
        NightObject.Kind.PLANET -> "${n.name}, une planète (magnitude ${"%.1f".format(Locale.FRANCE, n.magnitude)}), à ${"%.1f".format(Locale.FRANCE, n.distanceKm / 149_597_870.7)} UA"
        NightObject.Kind.STAR -> "${n.name}, une étoile de ${CONSTELLATIONS[n.star?.constellation] ?: n.star?.constellation} (magnitude ${"%.1f".format(Locale.FRANCE, n.magnitude)})"
        NightObject.Kind.SUN -> "le Soleil"
    } + ", ${n.look.elevationDeg.toInt()}° de haut ${towardDirection(n.look.azimuthDeg)}"
}
