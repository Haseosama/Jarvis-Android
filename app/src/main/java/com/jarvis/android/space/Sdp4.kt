package com.jarvis.android.space

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The deep-space part of SGP4 (once called SDP4), for orbits of 225 minutes or more: GPS and Galileo (12 hours), geostationary (24
 * hours), Molniya. Far from the Earth the Moon and the Sun pull the orbit round, and a 12 h or 24 h satellite stays in step with the
 * Earth's uneven gravity (a resonance), which is integrated numerically. After Vallado, Crawford, Hujsak and Kelso, "Revisiting
 * Spacetrack Report #3" (2006): dscom, dsinit, dspace and dpper of their code, "improved" mode.
 */

private const val TWO_PI = 2 * PI
private const val ZES = 0.01675
private const val ZEL = 0.05490
private const val ZNS = 1.19459e-5
private const val ZNL = 1.5835218e-4
/** The Earth's rotation, in radians per minute. */
private const val RPTIM = 4.37526908801129966e-3

/** The mean elements of a deep-space orbit at some time, before the Moon's and Sun's periodic pulls. */
internal class DeepMean(val em: Double, val argpm: Double, val inclm: Double, val mm: Double, val nodem: Double, val nm: Double)

/** The elements once the periodic pulls of the Moon and the Sun are added. */
internal class DeepPeriodic(val ep: Double, val inclp: Double, val nodep: Double, val argpp: Double, val mp: Double)

/**
 * The Moon and Sun terms of one deep-space orbit. [epochDays] is the element set's epoch in days since 1950 January 0; the elements
 * are the element set's, with [no] the mean motion without the Kozai correction and [mdot], [argpdot], [nodedot] the near-Earth rates.
 */
internal class DeepSpace(
    epochDays: Double, ecco: Double, inclo: Double, nodeo: Double, argpo: Double, mo: Double,
    private val no: Double, mdot: Double, argpdot: Double, nodedot: Double, private val gsto: Double, xke: Double,
) {
    // dpper's coefficients (the long-period Sun and Moon terms)
    private val e3: Double; private val ee2: Double
    private val se2: Double; private val se3: Double
    private val sgh2: Double; private val sgh3: Double; private val sgh4: Double
    private val sh2: Double; private val sh3: Double
    private val si2: Double; private val si3: Double
    private val sl2: Double; private val sl3: Double; private val sl4: Double
    private val xgh2: Double; private val xgh3: Double; private val xgh4: Double
    private val xh2: Double; private val xh3: Double
    private val xi2: Double; private val xi3: Double
    private val xl2: Double; private val xl3: Double; private val xl4: Double
    private val zmol: Double; private val zmos: Double

    // dsinit's: the secular rates, and the resonance (0 none, 1 a day, 2 half a day)
    private val ecco0 = ecco
    private val inclo0 = inclo
    private val nodeo0 = nodeo
    private val argpo0 = argpo
    private val mo0 = mo
    private val argpdot0 = argpdot
    private var dedt = 0.0; private var didt = 0.0; private var dmdt = 0.0; private var dnodt = 0.0; private var domdt = 0.0
    private var irez = 0
    private var d2201 = 0.0; private var d2211 = 0.0; private var d3210 = 0.0; private var d3222 = 0.0; private var d4410 = 0.0
    private var d4422 = 0.0; private var d5220 = 0.0; private var d5232 = 0.0; private var d5421 = 0.0; private var d5433 = 0.0
    private var del1 = 0.0; private var del2 = 0.0; private var del3 = 0.0
    private var xfact = 0.0
    private var xlamo = 0.0

    init {
        // ---- dscom: where the Moon and the Sun are at epoch, and their pull on this orbit
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458
        val nm = no
        val em = ecco
        val snodm = sin(nodeo)
        val cnodm = cos(nodeo)
        val sinomm = sin(argpo)
        val cosomm = cos(argpo)
        val sinim = sin(inclo)
        val cosim = cos(inclo)
        val emsq = em * em
        val betasq = 1 - emsq
        val rtemsq = sqrt(betasq)
        val day = epochDays + 18261.5
        val xnodce = (4.5236020 - 9.2422029e-4 * day) % TWO_PI
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1 - zsinhl * zsinhl)
        val gam = 5.8351514 + 0.0019443680 * day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = gam + zx - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)

        var zcosg = zcosgs; var zsing = zsings; var zcosi = zcosis; var zsini = zsinis; var zcosh = cnodm; var zsinh = snodm
        var cc = c1ss
        val xnoi = 1 / nm
        // the Sun's terms on the first round (kept as ss*, sz*), the Moon's on the second
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0; var s6 = 0.0; var s7 = 0.0
        var z1 = 0.0; var z2 = 0.0; var z3 = 0.0; var z11 = 0.0; var z12 = 0.0; var z13 = 0.0
        var z21 = 0.0; var z22 = 0.0; var z23 = 0.0; var z31 = 0.0; var z32 = 0.0; var z33 = 0.0
        var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0; var ss6 = 0.0; var ss7 = 0.0
        var sz1 = 0.0; var sz2 = 0.0; var sz3 = 0.0; var sz11 = 0.0; var sz12 = 0.0; var sz13 = 0.0
        var sz21 = 0.0; var sz22 = 0.0; var sz23 = 0.0; var sz31 = 0.0; var sz32 = 0.0; var sz33 = 0.0
        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10
            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm
            z31 = 12 * x1 * x1 - 3 * x3 * x3
            z32 = 24 * x1 * x2 - 6 * x3 * x4
            z33 = 12 * x2 * x2 - 3 * x4 * x4
            z1 = 3 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3 * (a3 * a3 + a4 * a4) + z33 * emsq
            z11 = -6 * a1 * a5 + emsq * (-24 * x1 * x7 - 6 * x3 * x5)
            z12 = -6 * (a1 * a6 + a3 * a5) + emsq * (-24 * (x2 * x7 + x1 * x8) - 6 * (x3 * x6 + x4 * x5))
            z13 = -6 * a3 * a6 + emsq * (-24 * x2 * x8 - 6 * x4 * x6)
            z21 = 6 * a2 * a5 + emsq * (24 * x1 * x5 - 6 * x3 * x7)
            z22 = 6 * (a4 * a5 + a2 * a6) + emsq * (24 * (x2 * x5 + x1 * x6) - 6 * (x4 * x7 + x3 * x8))
            z23 = 6 * a4 * a6 + emsq * (24 * x2 * x6 - 6 * x4 * x8)
            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = -0.5 * s3 / rtemsq
            s4 = s3 * rtemsq
            s1 = -15 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3
            if (lsflg == 1) {
                ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7
                sz1 = z1; sz2 = z2; sz3 = z3; sz11 = z11; sz12 = z12; sz13 = z13
                sz21 = z21; sz22 = z22; sz23 = z23; sz31 = z31; sz32 = z32; sz33 = z33
                zcosg = zcosgl; zsing = zsingl; zcosi = zcosil; zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }
        zmol = (4.7199672 + 0.22997150 * day - gam) % TWO_PI
        zmos = (6.2565837 + 0.017201977 * day) % TWO_PI
        se2 = 2 * ss1 * ss6
        se3 = 2 * ss1 * ss7
        si2 = 2 * ss2 * sz12
        si3 = 2 * ss2 * (sz13 - sz11)
        sl2 = -2 * ss3 * sz2
        sl3 = -2 * ss3 * (sz3 - sz1)
        sl4 = -2 * ss3 * (-21 - 9 * emsq) * ZES
        sgh2 = 2 * ss4 * sz32
        sgh3 = 2 * ss4 * (sz33 - sz31)
        sgh4 = -18 * ss4 * ZES
        sh2 = -2 * ss2 * sz22
        sh3 = -2 * ss2 * (sz23 - sz21)
        ee2 = 2 * s1 * s6
        e3 = 2 * s1 * s7
        xi2 = 2 * s2 * z12
        xi3 = 2 * s2 * (z13 - z11)
        xl2 = -2 * s3 * z2
        xl3 = -2 * s3 * (z3 - z1)
        xl4 = -2 * s3 * (-21 - 9 * emsq) * ZEL
        xgh2 = 2 * s4 * z32
        xgh3 = 2 * s4 * (z33 - z31)
        xgh4 = -18 * s4 * ZEL
        xh2 = -2 * s2 * z22
        xh3 = -2 * s2 * (z23 - z21)

        // ---- dsinit: the secular rates, and the resonance terms
        if (nm > 0.0034906585 && nm < 0.0052359877) irez = 1
        if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2
        val ses = ss1 * ZNS * ss5
        val sis = ss2 * ZNS * (sz11 + sz13)
        val sls = -ZNS * ss3 * (sz1 + sz3 - 14 - 6 * emsq)
        val sghs = ss4 * ZNS * (sz31 + sz33 - 6)
        var shs = -ZNS * ss2 * (sz21 + sz23)
        val equatorial = inclo < 5.2359877e-2 || inclo > PI - 5.2359877e-2
        if (equatorial) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs
        dedt = ses + s1 * ZNL * s5
        didt = sis + s2 * ZNL * (z11 + z13)
        dmdt = sls - ZNL * s3 * (z1 + z3 - 14 - 6 * emsq)
        val sghl = s4 * ZNL * (z31 + z33 - 6)
        var shll = -ZNL * s2 * (z21 + z23)
        if (equatorial) shll = 0.0
        domdt = sgs + sghl
        dnodt = shs
        if (sinim != 0.0) {
            domdt -= cosim / sinim * shll
            dnodt += shll / sinim
        }
        val theta = gsto % TWO_PI
        if (irez != 0) {
            val aonv = (nm / xke).pow(2.0 / 3.0)
            if (irez == 2) {
                // half a day, eccentric (Molniya, some GPS): the eccentricity at epoch, not the averaged one
                val cosisq = cosim * cosim
                val e = ecco
                val esq = ecco * ecco
                val eoc = e * esq
                val g201 = -0.306 - (e - 0.64) * 0.440
                val g211: Double; val g310: Double; val g322: Double; val g410: Double; val g422: Double; val g520: Double
                if (e <= 0.65) {
                    g211 = 3.616 - 13.2470 * e + 16.2900 * esq
                    g310 = -19.302 + 117.3900 * e - 228.4190 * esq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * e - 214.6334 * esq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * e - 471.0940 * esq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * e - 1629.014 * esq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * e - 5740.032 * esq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * e - 508.738 * esq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * e - 2415.925 * esq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * e - 2366.899 * esq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * e - 7193.992 * esq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * e - 24462.770 * esq + 12422.520 * eoc
                    g520 = if (e > 0.715) -5149.66 + 29936.92 * e - 54087.36 * esq + 31324.56 * eoc
                    else 1464.74 - 4664.75 * e + 3763.64 * esq
                }
                val g533: Double; val g521: Double; val g532: Double
                if (e < 0.7) {
                    g533 = -919.22770 + 4988.6100 * e - 9064.7700 * esq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * e - 8491.4146 * esq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * e - 8624.7700 * esq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * e - 229838.20 * esq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * e - 309468.16 * esq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * e - 242699.48 * esq + 115605.82 * eoc
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1 + 2 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1 - 2 * cosim - 3 * cosisq)
                val f322 = -1.875 * sinim * (1 + 2 * cosim - 3 * cosisq)
                val f441 = 35 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim * (sini2 * (1 - 2 * cosim - 5 * cosisq) + 0.33333333 * (-2 + 4 * cosim + 6 * cosisq))
                val f523 = sinim * (4.92187512 * sini2 * (-2 - 4 * cosim + 10 * cosisq) + 6.56250012 * (1 + 2 * cosim - 3 * cosisq))
                val f542 = 29.53125 * sinim * (2 - 8 * cosim + cosisq * (-12 + 8 * cosim + 10 * cosisq))
                val f543 = 29.53125 * sinim * (-2 - 8 * cosim + cosisq * (12 + 8 * cosim - 10 * cosisq))
                val xno2 = nm * nm
                val ainv2 = aonv * aonv
                var temp1 = 3 * xno2 * ainv2
                var temp = temp1 * 1.7891679e-6
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * 3.7393792e-7
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2 * temp1 * 7.3636953e-9
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * 1.1428639e-7
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2 * temp1 * 2.1765803e-9
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - theta - theta) % TWO_PI
                xfact = mdot + dmdt + 2 * (nodedot + dnodt - RPTIM) - no
            }
            if (irez == 1) {
                // a day (geostationary and geosynchronous)
                val g200 = 1 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1 + 2 * emsq
                val g300 = 1 + emsq * (-6 + 6.60937 * emsq)
                val f220 = 0.75 * (1 + cosim) * (1 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1 + 3 * cosim) - 0.75 * (1 + cosim)
                val f330 = 1.875 * (1 + cosim).pow(3)
                del1 = 3 * nm * nm * aonv * aonv
                del2 = 2 * del1 * f220 * g200 * 1.7891679e-6
                del3 = 3 * del1 * f330 * g300 * 2.2123015e-7 * aonv
                del1 = del1 * f311 * g310 * 2.1460748e-6 * aonv
                xlamo = (mo + nodeo + argpo - theta) % TWO_PI
                xfact = mdot + (argpdot + nodedot) - RPTIM + dmdt + domdt + dnodt - no
            }
        }
    }

    /** dspace: the mean elements [t] minutes after epoch, [mm], [argpm], [nodem] being the near-Earth secular ones at [t]. */
    fun secular(t: Double, mm: Double, argpm: Double, nodem: Double): DeepMean {
        val theta = (gsto + t * RPTIM) % TWO_PI
        val em = ecco0 + dedt * t
        val inclm = inclo0 + didt * t
        val argp = argpm + domdt * t
        val node = nodem + dnodt * t
        var m = mm + dmdt * t
        var nm = no
        if (irez != 0) {
            // the resonance, integrated from epoch in 720-minute steps (Euler-Maclaurin), then to [t]
            val delt = if (t > 0) 720.0 else -720.0
            var atime = 0.0
            var xni = no
            var xli = xlamo
            var ft = 0.0
            var xndt = 0.0
            var xldot = 0.0
            var xnddt = 0.0
            while (true) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - 0.13130908) + del2 * sin(2 * (xli - 2.8843198)) + del3 * sin(3 * (xli - 0.37448087))
                    xldot = xni + xfact
                    xnddt = del1 * cos(xli - 0.13130908) + 2 * del2 * cos(2 * (xli - 2.8843198)) + 3 * del3 * cos(3 * (xli - 0.37448087))
                    xnddt *= xldot
                } else {
                    val xomi = argpo0 + argpdot0 * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = d2201 * sin(x2omi + xli - G22) + d2211 * sin(xli - G22) + d3210 * sin(xomi + xli - G32) +
                        d3222 * sin(-xomi + xli - G32) + d4410 * sin(x2omi + x2li - G44) + d4422 * sin(x2li - G44) +
                        d5220 * sin(xomi + xli - G52) + d5232 * sin(-xomi + xli - G52) + d5421 * sin(xomi + x2li - G54) +
                        d5433 * sin(-xomi + x2li - G54)
                    xldot = xni + xfact
                    xnddt = d2201 * cos(x2omi + xli - G22) + d2211 * cos(xli - G22) + d3210 * cos(xomi + xli - G32) +
                        d3222 * cos(-xomi + xli - G32) + d5220 * cos(xomi + xli - G52) + d5232 * cos(-xomi + xli - G52) +
                        2 * (d4410 * cos(x2omi + x2li - G44) + d4422 * cos(x2li - G44) + d5421 * cos(xomi + x2li - G54) +
                            d5433 * cos(-xomi + x2li - G54))
                    xnddt *= xldot
                }
                if (abs(t - atime) >= 720.0) {
                    xli += xldot * delt + xndt * 259200.0
                    xni += xndt * delt + xnddt * 259200.0
                    atime += delt
                } else {
                    ft = t - atime
                    break
                }
            }
            nm = xni + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xli + xldot * ft + xndt * ft * ft * 0.5
            m = if (irez != 1) xl - 2 * node + 2 * theta else xl - node - argp + theta
        }
        return DeepMean(em, argp, inclm, m, node, nm)
    }

    /** dpper: the Moon's and the Sun's periodic pulls added to the elements [t] minutes after epoch. */
    fun periodic(t: Double, ep: Double, inclp: Double, nodep: Double, argpp: Double, mp: Double): DeepPeriodic {
        fun terms(zm: Double, ecc: Double): DoubleArray {
            val zf = zm + 2 * ecc * sin(zm)
            val sinzf = sin(zf)
            return doubleArrayOf(0.5 * sinzf * sinzf - 0.25, -0.5 * sinzf * cos(zf), sinzf)
        }
        val (f2s, f3s, szs) = terms(zmos + ZNS * t, ZES)
        val (f2l, f3l, szl) = terms(zmol + ZNL * t, ZEL)
        val pe = se2 * f2s + se3 * f3s + ee2 * f2l + e3 * f3l
        val pinc = si2 * f2s + si3 * f3s + xi2 * f2l + xi3 * f3l
        val pl = sl2 * f2s + sl3 * f3s + sl4 * szs + xl2 * f2l + xl3 * f3l + xl4 * szl
        var pgh = sgh2 * f2s + sgh3 * f3s + sgh4 * szs + xgh2 * f2l + xgh3 * f3l + xgh4 * szl
        var ph = sh2 * f2s + sh3 * f3s + xh2 * f2l + xh3 * f3l
        val incl = inclp + pinc
        val e = ep + pe
        val sinip = sin(incl)
        val cosip = cos(incl)
        return if (incl >= 0.2) {
            ph /= sinip
            pgh -= cosip * ph
            DeepPeriodic(e, incl, nodep + ph, argpp + pgh, mp + pl)
        } else {
            // near the equator, Lyddane's form, which does not divide by the inclination's sine
            val sinop = sin(nodep)
            val cosop = cos(nodep)
            val alfdp = sinip * sinop + ph * cosop + pinc * cosip * sinop
            val betdp = sinip * cosop - ph * sinop + pinc * cosip * cosop
            val node0 = nodep % TWO_PI
            val xls = mp + argpp + pl + pgh + (cosip - pinc * sinip) * node0
            var node = atan2(alfdp, betdp)
            if (abs(node0 - node) > PI) node += if (node < node0) TWO_PI else -TWO_PI
            val m = mp + pl
            DeepPeriodic(e, incl, node, xls - m - cosip * node, m)
        }
    }

    private companion object {
        const val G22 = 5.7686396
        const val G32 = 0.95240898
        const val G44 = 1.8014998
        const val G52 = 1.0508330
        const val G54 = 4.4108898
    }
}
