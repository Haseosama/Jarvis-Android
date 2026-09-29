package com.jarvis.android.space

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The Moon, the planets and the stars, for the sky chart: where they are, computed on the phone.
 * - Planets: the Keplerian elements and their rates of JPL's "Approximate Positions of the Planets" (E. M. Standish, valid 1800-2050,
 *   a fraction of a degree at most), heliocentric, then seen from the Earth.
 * - The Moon: the low-precision series of the Astronomical Almanac (about 0.3°), with its distance, so its parallax comes out.
 * - Stars: the Yale Bright Star Catalog (assets/sky/stars.tsv, J2000), brought to the equinox of date by the general precession.
 * Everything is turned into a direction of the equinox of date, then into the sky of a place as for satellites (Greenwich sidereal time).
 */

private const val D = PI / 180
private const val AU_KM = 149_597_870.7

internal fun centuries(timeMs: Long): Double = (julian(timeMs) - 2451545.0) / 36525.0

/** The obliquity of the ecliptic of date, radians. */
internal fun obliquity(t: Double): Double = (23.439291 - 0.0130042 * t) * D

/** The general precession in longitude since J2000, radians. */
private fun precession(t: Double): Double = 1.396971 * t * D

/** Ecliptic (of date) longitude, latitude (radians) and distance to an equatorial vector of date (the frame sidereal time turns). */
internal fun eclipticToEquatorial(lon: Double, lat: Double, r: Double, t: Double): Triple<Double, Double, Double> {
    val e = obliquity(t)
    val x = r * cos(lat) * cos(lon)
    val y = r * cos(lat) * sin(lon)
    val z = r * sin(lat)
    return Triple(x, y * cos(e) - z * sin(e), y * sin(e) + z * cos(e))
}

/** Right ascension and declination (degrees) of an equatorial vector. */
internal fun raDec(v: Triple<Double, Double, Double>): Pair<Double, Double> {
    val (x, y, z) = v
    val ra = (atan2(y, x) / D + 360) % 360
    return ra to asin(z / sqrt(x * x + y * y + z * z)) / D
}

/** A planet's orbit: a (AU), e, I, L, long. of perihelion, long. of node (degrees) at J2000 and their rates per century. */
private class Orbit(
    val a: Double, val e: Double, val i: Double, val l: Double, val peri: Double, val node: Double,
    val da: Double, val de: Double, val di: Double, val dl: Double, val dperi: Double, val dnode: Double,
) {
    /** Heliocentric ecliptic (J2000) position, AU. */
    fun position(t: Double): Triple<Double, Double, Double> {
        val a = a + da * t
        val e = e + de * t
        val i = (i + di * t) * D
        val l = l + dl * t
        val peri = peri + dperi * t
        val node = (node + dnode * t) * D
        val w = peri * D - node
        var m = ((l - peri) % 360) * D
        if (m > PI) m -= 2 * PI
        if (m < -PI) m += 2 * PI
        var ecc = m + e * sin(m)
        repeat(8) { ecc -= (ecc - e * sin(ecc) - m) / (1 - e * cos(ecc)) }
        val xp = a * (cos(ecc) - e)
        val yp = a * sqrt(1 - e * e) * sin(ecc)
        val x = (cos(w) * cos(node) - sin(w) * sin(node) * cos(i)) * xp + (-sin(w) * cos(node) - cos(w) * sin(node) * cos(i)) * yp
        val y = (cos(w) * sin(node) + sin(w) * cos(node) * cos(i)) * xp + (-sin(w) * sin(node) + cos(w) * cos(node) * cos(i)) * yp
        val z = sin(w) * sin(i) * xp + cos(w) * sin(i) * yp
        return Triple(x, y, z)
    }
}

/** A planet: its French name, its orbit, and how bright it usually looks (for the order and the size of its dot). */
internal enum class Planet(val french: String, val magnitude: Double, internal val orbitData: DoubleArray) {
    MERCURY("Mercure", 0.0, doubleArrayOf(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593, 0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081)),
    VENUS("Vénus", -4.2, doubleArrayOf(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255, 0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418)),
    MARS("Mars", 0.7, doubleArrayOf(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891, 0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343)),
    JUPITER("Jupiter", -2.3, doubleArrayOf(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909, -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106)),
    SATURN("Saturne", 0.6, doubleArrayOf(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448, -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794)),
    URANUS("Uranus", 5.7, doubleArrayOf(19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503, -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589)),
    NEPTUNE("Neptune", 7.8, doubleArrayOf(30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574, 0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664));

}

private val EARTH = Orbit(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0, 0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)
private val ORBITS = Planet.values().associateWith { p -> p.orbitData.let { Orbit(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8], it[9], it[10], it[11]) } }

/** A planet seen from the Earth's centre at [timeMs]: an equatorial vector of date, in km. */
internal fun planetVector(p: Planet, timeMs: Long): Triple<Double, Double, Double> {
    val t = centuries(timeMs)
    val (px, py, pz) = ORBITS.getValue(p).position(t)
    val (ex, ey, ez) = EARTH.position(t)
    val gx = px - ex
    val gy = py - ey
    val gz = pz - ez
    val r = sqrt(gx * gx + gy * gy + gz * gz)
    val lon = atan2(gy, gx) + precession(t)
    val lat = asin(gz / r)
    return eclipticToEquatorial(lon, lat, r * AU_KM, t)
}

/** The Moon at [timeMs], from the Earth's centre: an equatorial vector of date, km (the Astronomical Almanac's short series). */
internal fun moonVector(timeMs: Long): Triple<Double, Double, Double> {
    val t = centuries(timeMs)
    fun s(a: Double, b: Double) = sin((a + b * t) * D)
    fun c(a: Double, b: Double) = cos((a + b * t) * D)
    val lon = 218.32 + 481267.881 * t + 6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) + 0.66 * s(235.7, 890534.22) +
        0.21 * s(269.9, 954397.74) - 0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03)
    val lat = 5.13 * s(93.3, 483202.02) + 0.28 * s(228.2, 960400.89) - 0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21)
    val parallax = 0.9508 + 0.0518 * c(135.0, 477198.87) + 0.0095 * c(259.3, -413335.36) + 0.0078 * c(235.7, 890534.22) + 0.0028 * c(269.9, 954397.74)
    val r = 6378.14 / sin(parallax * D)
    return eclipticToEquatorial(lon * D, lat * D, r, t)
}

/** The Moon's phase: the lit fraction (0 new … 1 full), waxing or not, and its name in French. */
internal data class MoonPhase(val lit: Double, val waxing: Boolean) {
    val name: String get() = when {
        lit < 0.03 -> "nouvelle lune"
        lit > 0.97 -> "pleine lune"
        abs(lit - 0.5) < 0.06 -> if (waxing) "premier quartier" else "dernier quartier"
        lit < 0.5 -> if (waxing) "premier croissant" else "dernier croissant"
        else -> if (waxing) "gibbeuse croissante" else "gibbeuse décroissante"
    }
}

internal fun moonPhase(timeMs: Long): MoonPhase {
    val m = moonVector(timeMs)
    val s = sunPosition(timeMs)
    fun unit(v: Triple<Double, Double, Double>) = sqrt(v.first * v.first + v.second * v.second + v.third * v.third).let { Triple(v.first / it, v.second / it, v.third / it) }
    val mu = unit(m)
    val su = unit(s)
    val cosElong = mu.first * su.first + mu.second * su.second + mu.third * su.third
    // waxing: the Moon east of the Sun (its right ascension ahead, within half a turn)
    val (raM, _) = raDec(m)
    val (raS, _) = raDec(s)
    val ahead = ((raM - raS) % 360 + 360) % 360
    return MoonPhase((1 - cosElong) / 2, ahead < 180)
}

/** A star of the catalogue: its HR number, J2000 position (degrees), magnitude and designation ("58Alp Ori"). */
internal data class Star(val hr: Int, val ra: Double, val dec: Double, val mag: Double, val designation: String) {
    /** "Alp Ori", without the Flamsteed number. */
    val bayer: String get() = BAYER.find(designation)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: designation.trimStart { it.isDigit() }.trim()
    val constellation: String get() = designation.takeLast(3)
    val name: String? get() = STAR_NAMES[bayer]
}

/** "Alp1Cen", "58Alp Ori": the Greek letter and the constellation, without a Flamsteed number or an index. */
private val BAYER = Regex("([A-Z][a-z]{1,2})\\s*\\d?\\s*([A-Z][A-Za-z]{2})$")

/** The catalogue's stars (assets/sky/stars.tsv). */
internal fun parseStars(tsv: String): List<Star> = tsv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
    val f = l.split('\t')
    if (f.size < 5) null else try { Star(f[0].toInt(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4]) } catch (_: NumberFormatException) { null }
}.toList()

/** A star's direction of date, as an equatorial vector (unit length times [r] km). */
internal fun starVector(s: Star, timeMs: Long, r: Double = 1.0e12): Triple<Double, Double, Double> {
    val t = centuries(timeMs)
    // J2000 equatorial -> J2000 ecliptic, precessed in longitude, -> equatorial of date
    val e0 = obliquity(0.0)
    val ra = s.ra * D
    val dec = s.dec * D
    val x = cos(dec) * cos(ra)
    val y = cos(dec) * sin(ra)
    val z = sin(dec)
    val ye = y * cos(e0) + z * sin(e0)
    val ze = -y * sin(e0) + z * cos(e0)
    val lon = atan2(ye, x) + precession(t)
    val lat = asin(ze)
    return eclipticToEquatorial(lon, lat, r, t)
}

/** How an equatorial vector of date (km from the Earth's centre) is seen from [o] at [timeMs]. */
internal fun lookAtSky(o: Observer, v: Triple<Double, Double, Double>, timeMs: Long): Look = lookAt(o, temeToEcef(v.first, v.second, v.third, timeMs))

/** The French names of the best-known stars, by Bayer designation. */
internal val STAR_NAMES: Map<String, String> = mapOf(
    "Alp CMa" to "Sirius", "Alp Car" to "Canopus", "Alp Boo" to "Arcturus", "Alp Lyr" to "Véga", "Alp Aur" to "Capella", "Bet Ori" to "Rigel",
    "Alp CMi" to "Procyon", "Alp Ori" to "Bételgeuse", "Alp Aql" to "Altaïr", "Alp Tau" to "Aldébaran", "Alp Sco" to "Antarès", "Alp Vir" to "Spica",
    "Bet Gem" to "Pollux", "Alp PsA" to "Fomalhaut", "Alp Cyg" to "Deneb", "Alp Leo" to "Régulus", "Alp Gem" to "Castor", "Gam Ori" to "Bellatrix",
    "Eps Ori" to "Alnilam", "Zet Ori" to "Alnitak", "Del Ori" to "Mintaka", "Kap Ori" to "Saïph", "Alp UMi" to "Étoile polaire", "Alp UMa" to "Dubhe",
    "Bet UMa" to "Merak", "Gam UMa" to "Phecda", "Del UMa" to "Megrez", "Eps UMa" to "Alioth", "Zet UMa" to "Mizar", "Eta UMa" to "Alkaïd",
    "Bet UMi" to "Kochab", "Alp Cas" to "Schedar", "Bet Cas" to "Caph", "Alp Per" to "Mirfak", "Bet Per" to "Algol", "Alp Ari" to "Hamal",
    "Bet Leo" to "Denebola", "Bet Cyg" to "Albireo", "Gam Cyg" to "Sadr", "Alp Oph" to "Rasalhague", "Alp And" to "Alphératz", "Alp Peg" to "Markab",
    "Bet Peg" to "Scheat", "Gam Peg" to "Algénib", "Alp Cet" to "Menkar", "Bet Cet" to "Diphda", "Bet Tau" to "Elnath", "Gam Gem" to "Alhena",
    "Eps CMa" to "Adhara", "Del CMa" to "Wezen", "Alp Eri" to "Achernar", "Bet Cen" to "Hadar", "Alp Cen" to "Rigil Kentaurus", "Alp Cru" to "Acrux",
    "Bet Cru" to "Mimosa", "Lam Sco" to "Shaula", "Sig Sgr" to "Nunki", "Eps Sgr" to "Kaus Australis", "Alp CrB" to "Alphecca", "Gam Dra" to "Eltanin",
    "Alp Dra" to "Thuban", "Alp Her" to "Rasalgethi", "Bet And" to "Mirach", "Gam And" to "Almach", "Bet Aur" to "Menkalinan", "Alp Col" to "Phact",
    "Gam Leo" to "Algieba", "Alp Hya" to "Alphard", "Bet Lib" to "Zubeneschamali", "Alp Lib" to "Zubenelgenubi",
)

/** The French names of the constellations drawn, by abbreviation. */
internal val CONSTELLATIONS: Map<String, String> = mapOf(
    "UMa" to "Grande Ourse", "UMi" to "Petite Ourse", "Cas" to "Cassiopée", "Ori" to "Orion", "Cyg" to "Cygne", "Lyr" to "Lyre", "Aql" to "Aigle",
    "Leo" to "Lion", "Sco" to "Scorpion", "Tau" to "Taureau", "Gem" to "Gémeaux", "CMa" to "Grand Chien", "CMi" to "Petit Chien", "Boo" to "Bouvier",
    "Peg" to "Pégase", "And" to "Andromède", "Per" to "Persée", "Aur" to "Cocher", "Cru" to "Croix du Sud", "Sgr" to "Sagittaire", "Vir" to "Vierge",
    "Her" to "Hercule", "CrB" to "Couronne boréale", "Dra" to "Dragon", "Cep" to "Céphée", "Ari" to "Bélier", "Cet" to "Baleine", "Oph" to "Ophiuchus",
    "Cen" to "Centaure", "Car" to "Carène", "PsA" to "Poisson austral", "Eri" to "Éridan", "Hya" to "Hydre", "Lib" to "Balance", "Crv" to "Corbeau",
)

/** The figures of the best-known constellations: lines through their stars (Bayer designations, "Alp Peg"). */
internal val FIGURES: List<List<String>> = listOf(
    listOf("Eta UMa", "Zet UMa", "Eps UMa", "Del UMa", "Gam UMa", "Bet UMa", "Alp UMa", "Del UMa"),
    listOf("Alp UMi", "Del UMi", "Eps UMi", "Zet UMi", "Bet UMi", "Gam UMi", "Eta UMi", "Zet UMi"),
    listOf("Eps Cas", "Del Cas", "Gam Cas", "Alp Cas", "Bet Cas"),
    listOf("Alp Ori", "Gam Ori"), listOf("Gam Ori", "Del Ori", "Eps Ori", "Zet Ori", "Alp Ori"), listOf("Del Ori", "Bet Ori"), listOf("Zet Ori", "Kap Ori"),
    listOf("Alp Ori", "Lam Ori", "Gam Ori"),
    listOf("Alp Cyg", "Gam Cyg", "Eta Cyg", "Bet Cyg"), listOf("Del Cyg", "Gam Cyg", "Eps Cyg"),
    listOf("Alp Lyr", "Zet Lyr", "Bet Lyr", "Gam Lyr", "Del Lyr", "Zet Lyr"),
    listOf("Gam Aql", "Alp Aql", "Bet Aql"), listOf("Alp Aql", "Del Aql", "Lam Aql"),
    listOf("Alp Leo", "Eta Leo", "Gam Leo", "Zet Leo", "Mu Leo", "Eps Leo"), listOf("Gam Leo", "Del Leo", "Bet Leo", "The Leo", "Alp Leo"),
    listOf("Bet Sco", "Del Sco", "Pi Sco"), listOf("Del Sco", "Sig Sco", "Alp Sco", "Tau Sco", "Eps Sco", "Mu Sco", "Zet Sco", "Eta Sco", "The Sco", "Iot Sco", "Kap Sco", "Lam Sco"),
    listOf("Bet Tau", "Eps Tau", "Del Tau", "Gam Tau", "The Tau", "Alp Tau", "Zet Tau"),
    listOf("Alp Gem", "Bet Gem"), listOf("Alp Gem", "Eps Gem", "Mu Gem"), listOf("Bet Gem", "Del Gem", "Gam Gem"),
    listOf("Alp CMa", "Bet CMa"), listOf("Alp CMa", "Del CMa", "Eps CMa"), listOf("Del CMa", "Eta CMa"),
    listOf("Alp CMi", "Bet CMi"),
    listOf("Alp Boo", "Eps Boo", "Del Boo", "Bet Boo", "Gam Boo", "Rho Boo", "Alp Boo"),
    listOf("Alp Peg", "Bet Peg", "Alp And", "Gam Peg", "Alp Peg"), listOf("Alp And", "Del And", "Bet And", "Gam And"),
    listOf("Gam Per", "Alp Per", "Del Per", "Eps Per", "Zet Per"), listOf("Alp Per", "Bet Per"),
    listOf("Alp Aur", "Bet Aur", "The Aur", "Bet Tau", "Iot Aur", "Eta Aur", "Alp Aur"),
    listOf("Alp Cru", "Gam Cru"), listOf("Bet Cru", "Del Cru"),
    listOf("Alp Vir", "Gam Vir", "Del Vir", "Eps Vir"),
    listOf("Alp CrB", "Bet CrB"), listOf("Alp CrB", "Gam CrB", "Del CrB", "Eps CrB"),
    listOf("Alp Cep", "Bet Cep", "Gam Cep", "Iot Cep", "Zet Cep", "Alp Cep"),
)
