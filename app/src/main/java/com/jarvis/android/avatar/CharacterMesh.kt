package com.jarvis.android.avatar

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A textured character (format JCH2, or the older JCH1 without brows, built by tools/avatar/export_character.py): a bust in the units of the other heads (chin at y -1,
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
    /** How much each vertex lifts with the brows. */
    val brow: FloatArray,
    val unlit: BooleanArray,
    /** The vertices of an eye mesh, which follow the gaze. */
    val gazing: BooleanArray,
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
    /** How far an eye mesh slides at the gaze's full reach, and how far the brows lift at their highest. */
    val gazeReach: Float,
    val browLift: Float,
) {
    class Eye(val x: Float, val y: Float, val z: Float, val hw: Float, val hh: Float, val tilt: Float, val lid: Int)

    val vertexCount get() = verts.size / 3
    val faceCount get() = faces.size / 3

    companion object {
        /** Parses the three files of one character; [atlas] may be null (tests, or a missing file). */
        fun parse(bin: ByteArray, meta: JSONObject, atlas: Bitmap?): CharacterMesh {
            val b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(bin, 0, 4, Charsets.US_ASCII)
            require(magic == "JCH1" || magic == "JCH2") { "not a character mesh: $magic" }
            b.position(4)
            val nV = b.int
            val nF = b.int
            fun floats(n: Int) = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * n) }
            val verts = floats(3 * nV)
            val normals = floats(3 * nV)
            val uv = floats(2 * nV)
            val headW = floats(nV)
            val jaw = floats(nV)
            val brow = if (magic == "JCH2") floats(nV) else FloatArray(nV)
            val unlit = BooleanArray(nV) { (bin[b.position() + it].toInt() and 1) != 0 }
            val gazing = BooleanArray(nV) { (bin[b.position() + it].toInt() and 2) != 0 }
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
                verts = verts, normals = normals, uv = uv, headW = headW, jaw = jaw, brow = brow, unlit = unlit, gazing = gazing, faces = faces, atlas = atlas,
                eyes = eyes, mouth = mouth, mouthColour = m.optLong("inner", 0xFF2A1014).toInt(), teeth = m.optBoolean("teeth", true),
                lashColour = meta.optLong("lash", 0xFF1A1210).toInt(), ambient = meta.optDouble("ambient", 0.55).toFloat(),
                cut = meta.optDouble("cut", -1.9).toFloat(), top = meta.optDouble("top", 1.0).toFloat(), scale = meta.optDouble("scale", 1.0).toFloat(),
                pivot = vec("pivot", floatArrayOf(0f, -1f, -0.15f)), jawPivot = vec("jaw_pivot", JAW_PIVOT),
                gazeReach = meta.optDouble("gaze", 0.0).toFloat(), browLift = meta.optDouble("brow_lift", 0.06).toFloat(),
            )
        }

        /** A character of the assets, or one imported on the phone (the folder then starts with "file:"). */
        fun load(assets: AssetManager, folder: String): CharacterMesh {
            if (folder.startsWith(FILE)) {
                val dir = java.io.File(folder.removePrefix(FILE))
                val atlas = BitmapFactory.decodeFile(java.io.File(dir, "atlas.webp").path)
                return parse(java.io.File(dir, "mesh.bin").readBytes(), JSONObject(java.io.File(dir, "meta.json").readText()), atlas)
            }
            val meta = JSONObject(assets.open("$folder/meta.json").use { String(it.readBytes(), Charsets.UTF_8) })
            val bin = assets.open("$folder/mesh.bin").use { it.readBytes() }
            val atlas = assets.open("$folder/atlas.webp").use { BitmapFactory.decodeStream(it) }
            return parse(bin, meta, atlas)
        }

        const val FILE = "file:"
    }
}

/**
 * The characters: those of the assets (avatar/characters/<id>/meta.json: the public ones, and in a local debug build the others), then
 * those imported on the phone (files/characters/<id>, see importer.CharacterImport).
 */
internal object CharacterCatalog {
    private const val DIR = "avatar/characters"

    fun importedDir(context: android.content.Context) = java.io.File(context.filesDir, "characters")

    fun faces(context: android.content.Context): List<AvatarFace> {
        val assets = context.assets
        val ids = try { assets.list(DIR)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        val bundled = ids.mapNotNull { id ->
            try {
                face(JSONObject(assets.open("$DIR/$id/meta.json").use { String(it.readBytes(), Charsets.UTF_8) }), "$DIR/$id", false)
            } catch (_: Exception) {
                null
            }
        }
        val imported = importedDir(context).listFiles()?.filter { java.io.File(it, "meta.json").exists() }.orEmpty().mapNotNull { dir ->
            try { face(JSONObject(java.io.File(dir, "meta.json").readText()), CharacterMesh.FILE + dir.path, true) } catch (_: Exception) { null }
        }
        return (bundled + imported).sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }

    private fun face(meta: JSONObject, folder: String, imported: Boolean) = meta.optInt("order", if (imported) 100 else 50) to AvatarFace(
        meta.getString("label"), "avatar/head_mesh.bin", 0xFF1F1614.toInt(), fibres = false, character = folder,
        credit = meta.optString("credit"), imported = imported,
    )

    /** Reads the list again (after an import or a removal). */
    fun refresh(context: android.content.Context) {
        characterFaces = try { faces(context) } catch (_: Exception) { characterFaces }
    }
}
