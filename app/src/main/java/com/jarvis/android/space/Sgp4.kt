package com.jarvis.android.space

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Where a satellite is, from its published orbit (a "two-line element set", as CelesTrak gives them): the SGP4 model the orbits are
 * made for (Vallado, Crawford, Hujsak and Kelso, "Revisiting Spacetrack Report #3", 2006): near-Earth for the satellites that go
 * round in less than 225 minutes (the ISS, Hubble, Starlink, most of the ones seen by eye), with the deep-space terms of Sdp4.kt for
 * the others (GPS, geostationary). Then, for a place on Earth: how high in the sky and in which direction, whether the Sun lights it,
 * and whether the sky is dark enough to see it.
 */

private const val TWO_PI = 2 * PI
private const val DEG = PI / 180

// WGS-72, the constants the element sets are computed with
private const val MU = 398600.8
private const val RE = 6378.135
private val XKE = 60.0 / sqrt(RE * RE * RE / MU)
private const val J2 = 0.001082616
private const val J3 = -0.00000253881
private const val J4 = -0.00000165597
private const val J3OJ2 = J3 / J2

/** A published orbit: name, catalogue number, epoch (ms since 1970) and the mean elements (angles in radians, motion in rad/min). */
internal data class Tle(
    val name: String, val number: Int, val epochMs: Long,
    val inclination: Double, val raan: Double, val eccentricity: Double, val argPerigee: Double, val meanAnomaly: Double,
    val meanMotion: Double, val bstar: Double,
) {
    /** Minutes per revolution. */
    val periodMin: Double get() = TWO_PI / meanMotion
}

/** The element sets of a CelesTrak text file (a name line, then lines 1 and 2), skipping any that cannot be read. */
internal fun parseTles(text: String): List<Tle> {
    val lines = text.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
    val out = ArrayList<Tle>()
    var i = 0
    while (i + 1 < lines.size) {
        val (name, l1, l2) = if (lines[i].startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ")) Triple("", lines[i], lines[i + 1])
        else if (i + 2 < lines.size && lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ")) Triple(lines[i].trim(), lines[i + 1], lines[i + 2])
        else { i++; continue }
        parseTle(name, l1, l2)?.let { out += it }
        i += if (name.isEmpty()) 2 else 3
    }
    return out
}

internal fun parseTle(name: String, l1: String, l2: String): Tle? = try {
    val year2 = l1.substring(18, 20).trim().toInt()
    val year = if (year2 < 57) 2000 + year2 else 1900 + year2
    val day = l1.substring(20, 32).trim().toDouble()
    val epoch = java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + ((day - 1) * 86_400_000).toLong()
    // "28098-4" is 0.28098e-4
    val b = l1.substring(53, 61).trim()
    val bstar = if (b.isEmpty()) 0.0 else {
        val sign = if (b.startsWith("-")) -1 else 1
        val body = b.trimStart('-', '+')
        val mant = body.dropLast(2).trimEnd('-', '+').ifEmpty { "0" }
        val exp = body.takeLast(2).replace("+", "").toInt()
        sign * ("0.$mant".toDouble()) * 10.0.pow(exp)
    }
    Tle(
        name = name.ifEmpty { l2.substring(2, 7).trim() },
        number = l2.substring(2, 7).trim().toInt(),
        epochMs = epoch,
        inclination = l2.substring(8, 16).trim().toDouble() * DEG,
        raan = l2.substring(17, 25).trim().toDouble() * DEG,
        eccentricity = ("0." + l2.substring(26, 33).trim()).toDouble(),
        argPerigee = l2.substring(34, 42).trim().toDouble() * DEG,
        meanAnomaly = l2.substring(43, 51).trim().toDouble() * DEG,
        meanMotion = l2.substring(52, 63).trim().toDouble() * TWO_PI / 1440.0,
        bstar = bstar,
    )
} catch (_: Exception) {
    null
}

/** A position and velocity in the TEME frame, in km and km/s. */
internal data class StateVector(val x: Double, val y: Double, val z: Double, val vx: Double, val vy: Double, val vz: Double)

/** SGP4 for one satellite; [propagate] gives where it is so many minutes after its epoch. */
internal class Sgp4(private val tle: Tle) {
    private val ecco = tle.eccentricity
    private val inclo = tle.inclination
    private val nodeo = tle.raan
    private val argpo = tle.argPerigee
    private val mo = tle.meanAnomaly
    private val bstar = tle.bstar
    private val no: Double
    private val isimp: Boolean
    private val con41: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val d2: Double
    private val d3: Double
    private val d4: Double
    private val delmo: Double
    private val eta: Double
    private val argpdot: Double
    private val omgcof: Double
    private val sinmao: Double
    private val t2cof: Double
    private val t3cof: Double
    private val t4cof: Double
    private val t5cof: Double
    private val xlcof: Double
    private val aycof: Double
    private val xmcof: Double
    private val mdot: Double
    private val nodecf: Double
    private val nodedot: Double
    /** The Moon's and Sun's terms, for an orbit of 225 minutes or more. */
    private val deep: DeepSpace?

    /** False for a deep-space orbit (12 h, 24 h), placed with the Moon's and the Sun's pulls added. */
    val nearEarth: Boolean get() = deep == null

    init {
        // the mean motion without the Kozai correction, and the semi-major axis
        val eccsq = ecco * ecco
        val omeosq = 1 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (XKE / tle.meanMotion).pow(2.0 / 3.0)
        val d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1 - del * del - del * (1.0 / 3.0 + 134 * del * del / 81))
        del = d1 / (adel * adel)
        no = tle.meanMotion / (1 + del)
        val ao = (XKE / no).pow(2.0 / 3.0)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1 - 5 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1 - ecco)

        var sfour = 78 / RE + 1
        var qzms24 = ((120 - 78) / RE).pow(4)
        val deepSpace = TWO_PI / no >= 225.0
        isimp = deepSpace || rp < 220 / RE + 1
        val perige = (rp - 1) * RE
        if (perige < 156) {
            sfour = if (perige < 98) 20.0 else perige - 78
            qzms24 = ((120 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1
        }
        val pinvsq = 1 / posq
        val tsi = 1 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * no * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq)) + 0.375 * J2 * tsi / psisq * con41 * (8 + 3 * etasq * (8 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2 * coef * tsi * J3OJ2 * no * sinio / ecco else 0.0
        x1mth2 = 1 - cosio2
        cc4 = 2 * no * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq) -
            J2 * tsi / (ao * psisq) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * cos(2 * argpo)))
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * no
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * no
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) + temp3 * (3 - 36 * cosio2 + 49 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -2.0 / 3.0 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1) > 1.5e-12) -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / (1 + cosio)
        else -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / 1.5e-12
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7 * cosio2 - 1
        deep = if (!deepSpace) null else DeepSpace(
            julian(tle.epochMs) - 2433281.5, ecco, inclo, nodeo, argpo, mo, no, mdot, argpdot, nodedot, gmst(tle.epochMs), XKE,
        )
        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3
            d3 = (17 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1
            t3cof = d2 + 2 * cc1sq
            t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq))
            t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq))
        } else {
            d2 = 0.0; d3 = 0.0; d4 = 0.0; t3cof = 0.0; t4cof = 0.0; t5cof = 0.0
        }
    }

    /** Where the satellite is [t] minutes after its epoch (TEME, km and km/s), or null when the model gives up (a decayed orbit). */
    fun propagate(t: Double): StateVector? {
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (!isimp) {
            val delomg = omgcof * t
            val delm = xmcof * ((1 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = no
        var em = ecco
        var inclm = inclo
        if (deep != null) {
            val d = deep.secular(t, mm, argpm, nodem)
            em = d.em; argpm = d.argpm; inclm = d.inclm; mm = d.mm; nodem = d.nodem; nm = d.nm
        }
        if (nm <= 0) return null
        val am = (XKE / nm).pow(2.0 / 3.0) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001 || am < 0.95) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem %= TWO_PI
        argpm %= TWO_PI
        xlm %= TWO_PI
        mm = (xlm - argpm - nodem) % TWO_PI

        // the Moon's and the Sun's periodic pulls, for a deep-space orbit
        var ep = em
        var xincp = inclm
        var argpp = argpm
        var nodep = nodem
        var mp = mm
        var aycof = aycof
        var xlcof = xlcof
        if (deep != null) {
            val p = deep.periodic(t, ep, xincp, nodep, argpp, mp)
            ep = p.ep; xincp = p.inclp; nodep = p.nodep; argpp = p.argpp; mp = p.mp
            if (xincp < 0) {
                xincp = -xincp
                nodep += PI
                argpp -= PI
            }
            if (ep < 0 || ep > 1) return null
            val s = sin(xincp)
            val c = cos(xincp)
            aycof = -0.5 * J3OJ2 * s
            xlcof = -0.25 * J3OJ2 * s * (3 + 5 * c) / (if (abs(c + 1) > 1.5e-12) 1 + c else 1.5e-12)
        }

        // long-period periodics
        val sinip = sin(xincp)
        val cosip = cos(xincp)
        val axnl = ep * cos(argpp)
        var temp = 1 / (am * (1 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl

        // Kepler's equation
        val u = (xl - nodep) % TWO_PI
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        // short-period periodics
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1 - el2)
        if (pl < 0) return null
        val rl = am * (1 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1 - el2)
        temp = esine / (1 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1 - 2 * sinu * sinu
        temp = 1 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp
        var con41 = con41
        var x1mth2 = x1mth2
        var x7thm1 = x7thm1
        if (deep != null) {
            val cosisq = cosip * cosip
            con41 = 3 * cosisq - 1
            x1mth2 = 1 - cosisq
            x7thm1 = 7 * cosisq - 1
        }
        val mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE
        if (mrt < 1) return null

        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu
        val vkmpersec = RE * XKE / 60.0
        return StateVector(
            mrt * ux * RE, mrt * uy * RE, mrt * uz * RE,
            (mvt * ux + rvdot * vx) * vkmpersec, (mvt * uy + rvdot * vy) * vkmpersec, (mvt * uz + rvdot * vz) * vkmpersec,
        )
    }

    /** Where it is at [timeMs] (ms since 1970). */
    fun at(timeMs: Long): StateVector? = propagate((timeMs - tle.epochMs) / 60_000.0)
}

private fun mod2pi(a: Double): Double = a - TWO_PI * floor(a / TWO_PI)

/** Julian date of [timeMs] (ms since 1970, UTC; UT1 taken as UTC, a second at most). */
internal fun julian(timeMs: Long): Double = timeMs / 86_400_000.0 + 2440587.5

/** Greenwich mean sidereal time (IAU 1982), in radians. */
internal fun gmst(timeMs: Long): Double {
    val t = (julian(timeMs) - 2451545.0) / 36525.0
    val s = -6.2e-6 * t * t * t + 0.093104 * t * t + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841
    return mod2pi(s * DEG / 240.0)
}

/** A place on Earth: latitude and longitude in degrees, height in km. */
internal data class Observer(val latDeg: Double, val lonDeg: Double, val heightKm: Double = 0.0) {
    /** Its position in the Earth-fixed frame, in km (WGS-84). */
    fun ecef(): Triple<Double, Double, Double> {
        val a = 6378.137
        val f = 1 / 298.257223563
        val e2 = f * (2 - f)
        val lat = latDeg * DEG
        val lon = lonDeg * DEG
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        return Triple((n + heightKm) * cos(lat) * cos(lon), (n + heightKm) * cos(lat) * sin(lon), (n * (1 - e2) + heightKm) * sin(lat))
    }
}

/** How a point is seen from a place: elevation above the horizon and azimuth from north (degrees), and distance (km). */
internal data class Look(val elevationDeg: Double, val azimuthDeg: Double, val rangeKm: Double)

/** A TEME (or inertial) position at [timeMs] turned into the Earth-fixed frame. */
internal fun temeToEcef(x: Double, y: Double, z: Double, timeMs: Long): Triple<Double, Double, Double> {
    val g = gmst(timeMs)
    return Triple(cos(g) * x + sin(g) * y, -sin(g) * x + cos(g) * y, z)
}

/** How the Earth-fixed point [p] is seen from [o]. */
internal fun lookAt(o: Observer, p: Triple<Double, Double, Double>): Look {
    val (ox, oy, oz) = o.ecef()
    val rx = p.first - ox
    val ry = p.second - oy
    val rz = p.third - oz
    val lat = o.latDeg * DEG
    val lon = o.lonDeg * DEG
    val e = -sin(lon) * rx + cos(lon) * ry
    val n = -sin(lat) * cos(lon) * rx - sin(lat) * sin(lon) * ry + cos(lat) * rz
    val u = cos(lat) * cos(lon) * rx + cos(lat) * sin(lon) * ry + sin(lat) * rz
    val range = sqrt(rx * rx + ry * ry + rz * rz)
    val az = (atan2(e, n) / DEG + 360) % 360
    return Look(asin(u / range) / DEG, az, range)
}

/** The point of Earth under an Earth-fixed position: latitude and longitude (degrees) and height (km), spherical enough to say. */
internal fun subPoint(p: Triple<Double, Double, Double>): Triple<Double, Double, Double> {
    val (x, y, z) = p
    val r = sqrt(x * x + y * y + z * z)
    val lat = atan2(z, sqrt(x * x + y * y)) / DEG
    val lon = atan2(y, x) / DEG
    return Triple(lat, lon, r - 6371.0)
}

/** The Sun's direction at [timeMs], in the inertial frame, and its distance in km (the Astronomical Almanac's low-precision formula). */
internal fun sunPosition(timeMs: Long): Triple<Double, Double, Double> {
    val n = julian(timeMs) - 2451545.0
    val l = (280.460 + 0.9856474 * n) * DEG
    val g = (357.528 + 0.9856003 * n) * DEG
    val lambda = l + (1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
    val eps = (23.439 - 0.0000004 * n) * DEG
    val r = (1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2 * g)) * 149_597_870.7
    return Triple(r * cos(lambda), r * cos(eps) * sin(lambda), r * sin(eps) * sin(lambda))
}

/** Whether the Sun lights a satellite at [s] (inertial, km): not in the Earth's shadow (a cylinder, which is plenty for this). */
internal fun sunlit(s: StateVector, timeMs: Long): Boolean {
    val (sx, sy, sz) = sunPosition(timeMs)
    val d = sqrt(sx * sx + sy * sy + sz * sz)
    val ux = sx / d
    val uy = sy / d
    val uz = sz / d
    val along = s.x * ux + s.y * uy + s.z * uz
    if (along > 0) return true
    val px = s.x - along * ux
    val py = s.y - along * uy
    val pz = s.z - along * uz
    return sqrt(px * px + py * py + pz * pz) > 6378.137
}

/** How high the Sun is at [o] at [timeMs] (degrees; under -6: the sky dark enough to see a satellite). */
internal fun sunElevation(o: Observer, timeMs: Long): Double {
    val (x, y, z) = sunPosition(timeMs)
    return lookAt(o, temeToEcef(x, y, z, timeMs)).elevationDeg
}
