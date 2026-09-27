package com.jarvis.android.avatar.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import com.jarvis.android.avatar.CharacterCatalog
import com.jarvis.android.avatar.CharacterMesh
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Importing a character on the phone: a .glb or a Sketchfab .zip is read, shown with a first guess of where its face is, the user places
 * four markers (the eyes, the mouth, the chin), and the character is built (CharacterBuilder) and kept in files/characters/<id>.
 */
internal class ImportSession(val gltf: Gltf, val textures: List<Bitmap?>, var yaw: Float, var scene: CharacterBuilder.Scene) {
    var framing: CharacterBuilder.Framing = CharacterBuilder.guess(scene)
}

internal object CharacterImport {
    /** Larger files do not fit in a phone's memory once unpacked. */
    const val MAX_BYTES = 150L * 1024 * 1024

    class TooLarge(val bytes: Long) : Exception("file too large")

    fun open(context: Context, uri: Uri): ImportSession {
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
        if (size > MAX_BYTES) throw TooLarge(size)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the file")
        val gltf = Gltf.read(bytes)
        val textures = gltf.materials.map { m -> m.image?.let { decode(it) } }
        return ImportSession(gltf, textures, 0f, CharacterBuilder.gather(gltf, 0f))
    }

    /** The model faces another way: turn it a quarter to the left. */
    fun turn(s: ImportSession) {
        s.yaw = (s.yaw + 90f) % 360f
        s.scene = CharacterBuilder.gather(s.gltf, s.yaw)
        s.framing = CharacterBuilder.guess(s.scene)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The markers where a face usually is, in the units of the first framing. */
    fun defaultMarkers() = mapOf(
        "eyeL" to floatArrayOf(-0.33f, -0.05f), "eyeR" to floatArrayOf(0.33f, -0.05f),
        "mouth" to floatArrayOf(0f, -0.63f), "chin" to floatArrayOf(0f, -1f),
    )

    /** A quick build with the current framing, to place the markers on. */
    fun preview(s: ImportSession): CharacterMesh {
        val m = defaultMarkers()
        val feat = CharacterBuilder.features(m.getValue("eyeL"), m.getValue("eyeR"), m.getValue("mouth"), s.framing, s.framing)
        val built = CharacterBuilder.build(s.scene, CharacterBuilder.normalise(s.scene, s.framing), feat)   // as the final one: a coarser preview would show seams the result has not
        val (atlas, auv) = atlas(built, s, 1024)
        val meta = meta("…", "", built, feat, atlas, auv)
        return CharacterMesh.parse(CharacterBuilder.meshBytes(built, auv), meta, atlas)
    }

    /** The final build, from the markers placed on the preview; returns the folder of the new character. */
    fun save(context: Context, s: ImportSession, name: String, markers: Map<String, FloatArray>): String {
        val eyeL = markers.getValue("eyeL"); val eyeR = markers.getValue("eyeR")
        val f1 = CharacterBuilder.reframe(s.framing, eyeL, eyeR, markers.getValue("chin"))
        val feat = CharacterBuilder.features(eyeL, eyeR, markers.getValue("mouth"), s.framing, f1)
        val built = CharacterBuilder.build(s.scene, CharacterBuilder.normalise(s.scene, f1), feat)
        val (atlas, auv) = atlas(built, s, 2048)
        val credit = s.gltf.license?.lineSequence()?.firstOrNull { it.startsWith("This work is based on") }?.trim()
            ?: s.gltf.license?.let { l -> l.lines().filter { it.isNotBlank() }.take(3).joinToString(" ").take(300) }
            ?: "Importé sur ce téléphone"
        val dir = File(CharacterCatalog.importedDir(context), "c" + System.currentTimeMillis())
        dir.mkdirs()
        File(dir, "mesh.bin").writeBytes(CharacterBuilder.meshBytes(built, auv))
        File(dir, "atlas.webp").outputStream().use { out ->
            @Suppress("DEPRECATION")
            atlas.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 88, out)
        }
        File(dir, "meta.json").writeText(meta(name.ifBlank { "Avatar" }, credit, built, feat, atlas, auv).toString(1))
        CharacterCatalog.refresh(context)
        return CharacterMesh.FILE + dir.path
    }

    /** The atlas image: the used part of each texture drawn into its tile (with a little bleed round it), and the atlas uvs. */
    private fun atlas(b: CharacterBuilder.Built, s: ImportSession, size: Int): Pair<Bitmap, FloatArray> {
        val sizes = s.textures.map { t -> t?.let { intArrayOf(it.width, it.height) } }
        val (tiles, h) = CharacterBuilder.planAtlas(b, sizes, size)
        val bmp = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val pad = 4
        for (t in tiles) {
            val m = s.gltf.materials.getOrNull(t.mat)
            val f = m?.factor ?: floatArrayOf(1f, 1f, 1f, 1f)
            val tint = (((f[3] * 255).toInt().coerceIn(0, 255)) shl 24) or ((f[0] * 255).toInt().coerceIn(0, 255) shl 16) or
                ((f[1] * 255).toInt().coerceIn(0, 255) shl 8) or (f[2] * 255).toInt().coerceIn(0, 255)
            val tex = s.textures.getOrNull(t.mat)
            if (t.plain || tex == null) {
                paint.colorFilter = null
                paint.color = tint
                c.drawRect((t.x - pad).toFloat(), (t.y - pad).toFloat(), (t.x + t.w + pad).toFloat(), (t.y + t.h + pad).toFloat(), paint)
                continue
            }
            paint.color = -0x1
            paint.colorFilter = if (f.all { it >= 0.999f }) null else PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
            val sx = (t.u1 - t.u0) * tex.width / t.w; val sy = (t.v1 - t.v0) * tex.height / t.h
            val src = Rect(
                ((t.u0 * tex.width) - pad * sx).toInt().coerceIn(0, tex.width - 1), ((t.v0 * tex.height) - pad * sy).toInt().coerceIn(0, tex.height - 1),
                ((t.u1 * tex.width) + pad * sx).toInt().coerceIn(1, tex.width), ((t.v1 * tex.height) + pad * sy).toInt().coerceIn(1, tex.height),
            )
            c.drawBitmap(tex, src, RectF((t.x - pad).toFloat(), (t.y - pad).toFloat(), (t.x + t.w + pad).toFloat(), (t.y + t.h + pad).toFloat()), paint)
        }
        return bmp to CharacterBuilder.atlasUv(b, tiles, size, h)
    }

    private fun meta(label: String, credit: String, b: CharacterBuilder.Built, feat: CharacterBuilder.Features, atlas: Bitmap, auv: FloatArray): JSONObject {
        fun colourNear(x: Float, y: Float): Int {
            var best = -1; var bd = Float.MAX_VALUE
            for (v in 0 until b.pos.size / 3) {
                if (b.pos[3 * v + 2] < 0.2f) continue
                val d = (b.pos[3 * v] - x) * (b.pos[3 * v] - x) + (b.pos[3 * v + 1] - y) * (b.pos[3 * v + 1] - y)
                if (d < bd) { bd = d; best = v }
            }
            if (best < 0) return 0xFFC8A088.toInt()
            val px = (auv[2 * best] * atlas.width).toInt().coerceIn(0, atlas.width - 1)
            val py = (auv[2 * best + 1] * atlas.height).toInt().coerceIn(0, atlas.height - 1)
            return atlas.getPixel(px, py) or (0xFF shl 24)
        }
        val eyes = JSONArray()
        feat.eyes.forEachIndexed { i, e ->
            // the lid in the colour of the cheek under the eye (above it, hair or a brow often is)
            eyes.put(JSONObject().put("x", e[0]).put("y", e[1]).put("z", b.eyeZ[i]).put("hw", e[2]).put("hh", e[3]).put("tilt", e[4])
                .put("lid", colourNear(e[0], e[1] - e[3] * 1.8f).toLong() and 0xFFFFFFFFL))
        }
        val pts = JSONArray()
        for (i in 0 until b.mouthPts.size / 3) pts.put(JSONArray().put(b.mouthPts[3 * i]).put(b.mouthPts[3 * i + 1]).put(b.mouthPts[3 * i + 2]))
        return JSONObject().put("label", label).put("order", 100).put("credit", credit).put("public", false).put("scale", 0.86)
            .put("cut", b.cut.toDouble()).put("top", b.top.toDouble())
            .put("pivot", JSONArray().put(0).put(-1.0).put(-0.15)).put("jaw_pivot", JSONArray().put(0).put(0.06).put(-0.34))
            .put("eyes", eyes).put("mouth", JSONObject().put("points", pts).put("inner", 0xFF2A1014L).put("teeth", true))
            .put("lash", 0xFF1A1210L).put("ambient", if (b.unlit.all { it }) 1.0 else 0.62).put("gaze", 0.0).put("brow_lift", 0.05)
    }

    fun remove(context: Context, folder: String) {
        if (!folder.startsWith(CharacterMesh.FILE)) return
        File(folder.removePrefix(CharacterMesh.FILE)).deleteRecursively()
        CharacterCatalog.refresh(context)
    }

}
