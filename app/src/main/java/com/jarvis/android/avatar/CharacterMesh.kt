package com.jarvis.android.avatar

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A textured character (format JCH1, built by tools/avatar/export_character.py): a bust in the units of the other heads (chin at y -1,
 * eyes near 0, nose tip at z 0.68), its texture atlas, and where its eyes and mouth are, for the lids and the open mouth the renderer
 * draws over the painted face.
 */
internal class CharacterMesh(
    val label: String,
    val credit: String,
    val verts: FloatArray,
    val normals: FloatArray,
    val uv: FloatArray,
    val headW: FloatArray,
    val jaw: FloatArray,
    val unlit: BooleanArray,
    val faces: IntArray,
    val atlas: Bitmap?,
    val eyes: List<Eye>,
    /** The mouth line, left to right: x, y, z per point. */
    val mouth: FloatArray,
    val mouthColour: Int,
    val teeth: Boolean,
    val lashColour: Int,
    val ambient: Float,
    val cut: Float,
    val top: Float,
    val scale: Float,
    val pivot: FloatArray,
    val jawPivot: FloatArray,
) {
    class Eye(val x: Float, val y: Float, val z: Float, val hw: Float, val hh: Float, val tilt: Float, val lid: Int)

    val vertexCount get() = verts.size / 3
    val faceCount get() = faces.size / 3

    companion object {
        /** Parses the three files of one character; [atlas] may be null (tests, or a missing file). */
        fun parse(bin: ByteArray, meta: JSONObject, atlas: Bitmap?): CharacterMesh {
            val b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(bin, 0, 4, Charsets.US_ASCII)
            require(magic == "JCH1") { "not a character mesh: $magic" }
            b.position(4)
            val nV = b.int
            val nF = b.int
            fun floats(n: Int) = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * n) }
            val verts = floats(3 * nV)
            val normals = floats(3 * nV)
            val uv = floats(2 * nV)
            val headW = floats(nV)
            val jaw = floats(nV)
            val unlit = BooleanArray(nV) { bin[b.position() + it].toInt() != 0 }
            b.position(b.position() + nV + (4 - nV % 4) % 4)
            val faces = IntArray(3 * nF).also { b.asIntBuffer().get(it) }
            val eyesJ = meta.getJSONArray("eyes")
            val eyes = (0 until eyesJ.length()).map { k ->
                val e = eyesJ.getJSONObject(k)
                Eye(e.getDouble("x").toFloat(), e.getDouble("y").toFloat(), e.getDouble("z").toFloat(), e.getDouble("hw").toFloat(),
                    e.getDouble("hh").toFloat(), e.getDouble("tilt").toFloat(), e.getLong("lid").toInt())
            }
            val m = meta.getJSONObject("mouth")
            val pts = m.getJSONArray("points")
            val mouth = FloatArray(3 * pts.length())
            for (k in 0 until pts.length()) {
                val p = pts.getJSONArray(k)
                for (c in 0..2) mouth[3 * k + c] = p.getDouble(c).toFloat()
            }
            fun vec(name: String, def: FloatArray): FloatArray {
                val a = meta.optJSONArray(name) ?: return def
                return FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            return CharacterMesh(
                label = meta.getString("label"), credit = meta.optString("credit"),
                verts = verts, normals = normals, uv = uv, headW = headW, jaw = jaw, unlit = unlit, faces = faces, atlas = atlas,
                eyes = eyes, mouth = mouth, mouthColour = m.optLong("inner", 0xFF2A1014).toInt(), teeth = m.optBoolean("teeth", true),
                lashColour = meta.optLong("lash", 0xFF1A1210).toInt(), ambient = meta.optDouble("ambient", 0.55).toFloat(),
                cut = meta.optDouble("cut", -1.9).toFloat(), top = meta.optDouble("top", 1.0).toFloat(), scale = meta.optDouble("scale", 1.0).toFloat(),
                pivot = vec("pivot", floatArrayOf(0f, -1f, -0.15f)), jawPivot = vec("jaw_pivot", JAW_PIVOT),
            )
        }

        fun load(assets: AssetManager, folder: String): CharacterMesh {
            val meta = JSONObject(assets.open("$folder/meta.json").use { String(it.readBytes(), Charsets.UTF_8) })
            val bin = assets.open("$folder/mesh.bin").use { it.readBytes() }
            val atlas = assets.open("$folder/atlas.webp").use { BitmapFactory.decodeStream(it) }
            return parse(bin, meta, atlas)
        }
    }
}

/** The characters found in the assets (avatar/characters/<id>/meta.json): the public ones, and in a local debug build the others. */
internal object CharacterCatalog {
    private const val DIR = "avatar/characters"

    fun faces(assets: AssetManager): List<AvatarFace> {
        val ids = try { assets.list(DIR)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        return ids.mapNotNull { id ->
            try {
                val meta = JSONObject(assets.open("$DIR/$id/meta.json").use { String(it.readBytes(), Charsets.UTF_8) })
                meta.optInt("order", 50) to AvatarFace(
                    meta.getString("label"), "avatar/head_mesh.bin", 0xFF1F1614.toInt(), fibres = false, character = "$DIR/$id",
                    credit = meta.optString("credit"),
                )
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }
}
