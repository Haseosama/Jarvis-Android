package com.jarvis.android.avatar

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The character creator of the Haseo face, ported from Jarvis 2.0 (src/avatar/FaceCustomizer.js). Each parameter is a slider in
 * [-1, 1] (0 = Haseo as he is). The sliders become one smooth displacement field applied to every vertex of the head (skin,
 * eyeballs, mouth and baked hair), so the lids, lips, brows and hairstyles keep following the face; colours never change.
 */
internal class FaceParam(val id: String, val group: String, val label: String, val low: String, val high: String)

internal object FaceCustomizer {
    /** The tabs of the creator: id to label. */
    val GROUPS = listOf("face" to "Visage", "eyes" to "Yeux", "nose" to "Nez", "mouth" to "Bouche")

    val PARAMS = listOf(
        FaceParam("faceWidth", "face", "Largeur du visage", "Étroit", "Large"),
        FaceParam("faceLength", "face", "Hauteur du visage", "Court", "Allongé"),
        FaceParam("foreheadHeight", "face", "Front", "Bas", "Haut"),
        FaceParam("browRidge", "face", "Arcades sourcilières", "Lisses", "Marquées"),
        FaceParam("browHeight", "face", "Hauteur des sourcils", "Bas", "Haut"),
        FaceParam("cheeks", "face", "Joues", "Creuses", "Pleines"),
        FaceParam("cheekbones", "face", "Pommettes", "Discrètes", "Saillantes"),
        FaceParam("jawWidth", "face", "Mâchoire", "Fine", "Carrée"),
        FaceParam("chinLength", "face", "Longueur du menton", "Court", "Long"),
        FaceParam("chinWidth", "face", "Largeur du menton", "Pointu", "Large"),
        FaceParam("eyeSize", "eyes", "Taille des yeux", "Petits", "Grands"),
        FaceParam("eyeSpacing", "eyes", "Écartement", "Rapprochés", "Écartés"),
        FaceParam("eyeHeight", "eyes", "Position verticale", "Bas", "Haut"),
        FaceParam("eyeTilt", "eyes", "Inclinaison", "Tombants", "Relevés"),
        FaceParam("noseWidth", "nose", "Largeur du nez", "Fin", "Large"),
        FaceParam("noseLength", "nose", "Longueur du nez", "Court", "Long"),
        FaceParam("noseProjection", "nose", "Saillie du nez", "Plat", "Proéminent"),
        FaceParam("mouthWidth", "mouth", "Largeur de la bouche", "Étroite", "Large"),
        FaceParam("lipFullness", "mouth", "Épaisseur des lèvres", "Fines", "Pulpeuses"),
        FaceParam("mouthHeight", "mouth", "Position de la bouche", "Basse", "Haute"),
    )
    val IDS = PARAMS.map { it.id }

    /** Ready-made faces: id, label, values. */
    val PRESETS: List<Triple<String, String, Map<String, Float>>> = listOf(
        Triple("default", "Haseo (original)", emptyMap()),
        Triple("angular", "Anguleux", mapOf("jawWidth" to 0.7f, "chinWidth" to 0.4f, "cheekbones" to 0.6f, "cheeks" to -0.5f, "browRidge" to 0.6f, "noseProjection" to 0.2f, "lipFullness" to -0.2f)),
        Triple("soft", "Doux", mapOf("faceWidth" to 0.15f, "cheeks" to 0.6f, "jawWidth" to -0.4f, "chinLength" to -0.3f, "chinWidth" to -0.2f, "eyeSize" to 0.4f, "lipFullness" to 0.5f, "noseWidth" to -0.3f, "browRidge" to -0.4f)),
        Triple("narrow", "Fin et élancé", mapOf("faceWidth" to -0.5f, "faceLength" to 0.5f, "cheeks" to -0.4f, "jawWidth" to -0.4f, "chinWidth" to -0.5f, "noseWidth" to -0.4f, "noseLength" to 0.3f, "eyeSpacing" to -0.2f)),
        Triple("round", "Rond", mapOf("faceWidth" to 0.4f, "faceLength" to -0.4f, "cheeks" to 0.8f, "jawWidth" to -0.2f, "chinLength" to -0.4f, "noseLength" to -0.3f, "eyeSize" to 0.3f, "eyeSpacing" to 0.2f)),
        Triple("expressive", "Regard expressif", mapOf("eyeSize" to 0.7f, "eyeTilt" to 0.4f, "eyeHeight" to 0.1f, "browHeight" to 0.3f, "lipFullness" to 0.2f, "cheekbones" to 0.3f)),
    )

    /** Only known parameters, clamped to [-1, 1], rounded to 2 decimals, zeros left out. */
    fun normalize(raw: Map<String, Float>): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        for (id in IDS) {
            val v = raw[id] ?: continue
            if (!v.isFinite()) continue
            val r = (v.coerceIn(-1f, 1f) * 100f).roundToInt() / 100f
            if (r != 0f) out[id] = r
        }
        return out
    }

    fun preset(id: String): Map<String, Float> = normalize(PRESETS.firstOrNull { it.first == id }?.third ?: emptyMap())

    /** A reproducible random face, of moderate amplitude so it stays natural (the same generator as Jarvis 2.0). */
    fun random(seed: Long): Map<String, Float> {
        var state = (seed.toInt().toLong() and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }
        fun rnd(): Float {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return (state / 4294967296.0).toFloat()
        }
        return normalize(IDS.associateWith { (((rnd() * 2f - 1f) * 0.65f) * 100f).roundToInt() / 100f })
    }

    /** "faceWidth=0.3;eyeSize=-0.2", as stored in the settings. */
    fun encode(values: Map<String, Float>): String = normalize(values).entries.joinToString(";") { (k, v) -> "$k=$v" }

    fun decode(s: String): Map<String, Float> =
        normalize(s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> v.toFloatOrNull()?.let { k to it } } }.toMap())

    private class Ctx(val cx: Float, val eyes: List<FloatArray>, val eyeY: Float, val lipY: Float)

    private fun smooth(e0: Float, e1: Float, x: Float) = HaseoFace.smooth(e0, e1, x)

    /** The displacement of one point; [eyeball] (0 or 1, else -1) marks an eyeball, which follows its eye rigidly. */
    private fun displacement(x: Float, y: Float, z: Float, k: Map<String, Float>, ctx: Ctx, eyeball: Int = -1, out: FloatArray) {
        fun p(id: String) = k[id] ?: 0f
        val u = x - ctx.cx
        val au = abs(u)
        val side = if (u < 0) -1f else 1f
        var dx = 0f; var dy = 0f; var dz = 0f
        fun front(a: Float, b: Float) = smooth(a, b, z)

        // the face
        val headBand = smooth(-1.05f, -0.8f, y) * (1 - smooth(0.9f, 1.12f, y))
        dx += u * 0.14f * p("faceWidth") * headBand
        val yRel = y - ctx.eyeY
        val lowerFace = smooth(0.1f, -0.1f, yRel) * smooth(-1.25f, -0.95f, y)
        dy += 0.16f * p("faceLength") * yRel * lowerFace
        dy += 0.14f * p("foreheadHeight") * maxOf(0f, y - 0.25f) * (1 - smooth(0.85f, 1.1f, y))
        val ridge = smooth(0.05f, 0.14f, y) * (1 - smooth(0.3f, 0.42f, y)) * front(-0.08f, 0.24f) * (1 - smooth(0.5f, 0.8f, au))
        dz += 0.06f * p("browRidge") * ridge
        val browBand = smooth(0.12f, 0.2f, y) * (1 - smooth(0.28f, 0.36f, y)) * front(0.2f, 0.3f) * (1 - smooth(0.55f, 0.75f, au))
        dy += 0.05f * p("browHeight") * browBand
        val cheekBand = smooth(-0.45f, -0.2f, y) * (1 - smooth(0.12f, 0.35f, y)) * smooth(0.1f, 0.3f, au) * (1 - smooth(0.6f, 0.85f, au)) * front(0f, 0.25f)
        dx += side * 0.07f * p("cheeks") * cheekBand
        dz += 0.04f * p("cheeks") * cheekBand
        val boneBand = smooth(-0.2f, -0.05f, y) * (1 - smooth(0.08f, 0.2f, y)) * smooth(0.25f, 0.4f, au) * (1 - smooth(0.62f, 0.8f, au)) * front(0f, 0.25f)
        dx += side * 0.05f * p("cheekbones") * boneBand
        dz += 0.05f * p("cheekbones") * boneBand
        val jawBand = smooth(-1.0f, -0.8f, y) * (1 - smooth(-0.5f, -0.3f, y)) * front(-0.2f, 0.25f)
        dx += u * 0.18f * p("jawWidth") * jawBand
        val chinBand = smooth(-0.6f, -0.85f, y) * smooth(-1.15f, -0.95f, y) * front(0f, 0.3f)
        dy += -0.12f * p("chinLength") * chinBand * (1 - smooth(0.2f, 0.45f, au))
        dz += 0.05f * p("chinLength") * chinBand * (1 - smooth(0.2f, 0.45f, au))
        dx += u * 0.3f * p("chinWidth") * chinBand * (1 - smooth(0.4f, 0.6f, au))

        // the eyes
        val eyeIndex = if (eyeball >= 0) eyeball else if (u < 0) 0 else 1
        val eye = ctx.eyes.getOrNull(eyeIndex) ?: ctx.eyes.firstOrNull()
        if (eye != null) {
            val rx = x - eye[0]; val ry = y - eye[1]
            val r = hypot(rx, ry)
            val depth = if (eyeball >= 0) 1f else smooth(0f, 0.3f, z)
            val core = (if (eyeball >= 0) 1f else 1 - smooth(0.1f, 0.28f, r)) * depth
            val move = (if (eyeball >= 0) 1f else 1 - smooth(0.22f, 0.5f, r)) * depth
            val eyeSide = if (eye[0] < ctx.cx) -1f else 1f
            val scale = 0.3f * p("eyeSize") * core
            dx += rx * scale; dy += ry * scale
            if (eyeball >= 0) dz += (z - eye[2]) * 0.3f * p("eyeSize")
            // the bridge of the nose between the eyes is pulled by neither eye (it would tear when they come closer)
            dx += eyeSide * 0.045f * p("eyeSpacing") * move * (if (eyeball >= 0) 1f else smooth(0.02f, 0.14f, au))
            dy += 0.08f * p("eyeHeight") * move
            dy += 0.3f * p("eyeTilt") * (rx * eyeSide) * move
        }

        // the nose
        val alar = (1 - smooth(0.16f, 0.34f, au)) * smooth(-0.46f, -0.36f, y) * (1 - smooth(-0.16f, -0.04f, y)) * front(0.3f, 0.48f)
        dx += u * 0.35f * p("noseWidth") * alar
        val noseLen = (1 - smooth(0.14f, 0.3f, au)) * smooth(-0.55f, -0.4f, y) * (1 - smooth(0f, 0.15f, y)) * front(0.3f, 0.48f)
        dy += 0.18f * p("noseLength") * y * noseLen
        val proj = (1 - smooth(0.07f, 0.2f, au)) * smooth(-0.45f, -0.25f, y) * (1 - smooth(-0.15f, 0.1f, y)) * front(0.3f, 0.5f)
        dz += 0.1f * p("noseProjection") * proj

        // the mouth
        val mouthFront = front(0.25f, 0.45f)
        val ly = y - ctx.lipY
        val wMouth = 1 - smooth(0.8f, 1.6f, hypot(u / 0.38f, ly / 0.14f))
        val wLips = 1 - smooth(0.7f, 1.4f, hypot(u / 0.26f, ly / 0.075f))
        val wArea = 1 - smooth(0.7f, 1.5f, hypot(u / 0.55f, ly / 0.22f))
        dx += u * 0.25f * p("mouthWidth") * wMouth * mouthFront
        dy += ly * 0.5f * p("lipFullness") * wLips * mouthFront
        dz += 0.035f * p("lipFullness") * wLips * mouthFront
        dy += 0.07f * p("mouthHeight") * wArea * mouthFront
        out[0] = dx; out[1] = dy; out[2] = dz
    }

    /** A new head with the sliders' proportions; with every slider at 0, the head given. */
    fun apply(mesh: HeadMesh, raw: Map<String, Float>): HeadMesh {
        val k = normalize(raw)
        if (k.isEmpty()) return mesh
        val ec = mesh.eyeCentre
        val eyes = (0 until ec.size / 3).map { floatArrayOf(ec[3 * it], ec[3 * it + 1], ec[3 * it + 2]) }.sortedBy { it[0] }
        val cx = if (eyes.size >= 2) 0.5f * (eyes.first()[0] + eyes.last()[0]) else 0f
        val ctx = Ctx(cx, eyes, eyes.firstOrNull()?.get(1) ?: 0f, if (mesh.lipCentre.size >= 2) mesh.lipCentre[1] else -0.47f)
        val eyeOf = IntArray(mesh.vertexCount) { -1 }
        for (e in mesh.eyeFirst.indices) {
            val target = if (ec[3 * e] < cx) 0 else 1
            for (i in mesh.eyeFirst[e] until minOf(mesh.vertexCount, mesh.eyeFirst[e] + mesh.eyeCount[e])) eyeOf[i] = target
        }
        val verts = mesh.verts.copyOf()
        val d = FloatArray(3)
        for (i in 0 until mesh.vertexCount) {
            displacement(verts[3 * i], verts[3 * i + 1], verts[3 * i + 2], k, ctx, eyeOf[i], d)
            verts[3 * i] += d[0]; verts[3 * i + 1] += d[1]; verts[3 * i + 2] += d[2]
        }
        val eyeCentre = ec.copyOf()
        for (e in 0 until ec.size / 3) {
            displacement(ec[3 * e], ec[3 * e + 1], ec[3 * e + 2], k, ctx, if (ec[3 * e] < cx) 0 else 1, d)
            for (j in 0..2) eyeCentre[3 * e + j] += d[j]
        }
        val lc = mesh.lipCentre
        displacement(lc[0], lc[1], lc[2], k, ctx, -1, d)
        val lipCentre = floatArrayOf(lc[0] + d[0], lc[1] + d[1], lc[2] + d[2])
        val headVertices = minOf(if (mesh.nHead > 0) mesh.nHead else mesh.vertexCount, mesh.vertexCount)
        val normals = refitHeadNormals(mesh, verts, headVertices)
        displacement(cx, mesh.crown, 0f, k, ctx, -1, d)
        val crown = mesh.crown + d[1]
        displacement(cx, mesh.bottom, 0f, k, ctx, -1, d)
        val bottom = mesh.bottom + d[1]
        return mesh.copy(verts = verts, normals = normals, eyeCentre = eyeCentre, lipCentre = lipCentre, crown = crown, bottom = bottom)
    }
}
