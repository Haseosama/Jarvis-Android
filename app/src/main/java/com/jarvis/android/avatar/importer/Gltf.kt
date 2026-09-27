package com.jarvis.android.avatar.importer

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * A glTF 2.0 scene read on the phone (the Kotlin twin of tools/avatar/gltf_scene.py): a .glb, or a Sketchfab download (.zip with
 * scene.gltf, scene.bin and the textures). Each primitive comes out in world space in its rest pose (the node's world matrix, or for a
 * rigged mesh the sum over its weights of joint world matrix x inverse bind matrix).
 */
internal class Gltf private constructor(private val json: JSONObject, private val bin: ByteArray, private val files: Map<String, ByteArray>) {

    class Primitive(val positions: FloatArray, val uvs: FloatArray, val indices: IntArray, val material: Int, val name: String)

    class Material(
        val name: String, val image: ByteArray?, val factor: FloatArray, val alphaMode: String, val unlit: Boolean,
        val uvOffset: FloatArray, val uvScale: FloatArray, val uvRotation: Float, val texCoord: Int,
    )

    /** The license.txt of a Sketchfab download, when there is one. */
    val license: String? = files.entries.firstOrNull { it.key.substringAfterLast('/').equals("license.txt", true) }?.value?.toString(Charsets.UTF_8)

    private val nodes = json.optJSONArray("nodes") ?: JSONArray()
    private val world = arrayOfNulls<FloatArray>(nodes.length())

    init {
        val scenes = json.optJSONArray("scenes")
        val roots = scenes?.optJSONObject(json.optInt("scene", 0))?.optJSONArray("nodes")
        if (roots != null) for (k in 0 until roots.length()) walk(roots.getInt(k), identity())
        for (i in 0 until nodes.length()) if (world[i] == null) walk(i, identity())      // nodes outside the scene: at least placed
    }

    private fun walk(i: Int, parent: FloatArray) {
        val w = mul(parent, local(nodes.getJSONObject(i)))
        world[i] = w
        val ch = nodes.getJSONObject(i).optJSONArray("children") ?: return
        for (k in 0 until ch.length()) walk(ch.getInt(k), w)
    }

    val materials: List<Material> by lazy {
        val mats = json.optJSONArray("materials") ?: JSONArray()
        (0 until mats.length()).map { material(mats.getJSONObject(it)) }
    }

    private fun material(m: JSONObject): Material {
        val pbr = m.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
        val ext = m.optJSONObject("extensions")
        val sg = ext?.optJSONObject("KHR_materials_pbrSpecularGlossiness")
        var info = pbr.optJSONObject("baseColorTexture")
        var factorJ = pbr.optJSONArray("baseColorFactor")
        if (info == null && sg != null) { info = sg.optJSONObject("diffuseTexture"); factorJ = sg.optJSONArray("diffuseFactor") }
        val factor = FloatArray(4) { k -> factorJ?.optDouble(k, 1.0)?.toFloat() ?: 1f }
        var image: ByteArray? = null
        var off = floatArrayOf(0f, 0f); var scale = floatArrayOf(1f, 1f); var rot = 0f; var tc = 0
        if (info != null) {
            image = image(info.getInt("index"))
            tc = info.optInt("texCoord", 0)
            info.optJSONObject("extensions")?.optJSONObject("KHR_texture_transform")?.let { t ->
                t.optJSONArray("offset")?.let { off = floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                t.optJSONArray("scale")?.let { scale = floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                rot = t.optDouble("rotation", 0.0).toFloat()
            }
        }
        return Material(m.optString("name"), image, factor, m.optString("alphaMode", "OPAQUE"), ext?.has("KHR_materials_unlit") == true, off, scale, rot, tc)
    }

    private fun image(textureIndex: Int): ByteArray? {
        val t = json.optJSONArray("textures")?.optJSONObject(textureIndex) ?: return null
        var src = if (t.has("source")) t.getInt("source") else -1
        if (src < 0) src = t.optJSONObject("extensions")?.optJSONObject("KHR_texture_webp")?.optInt("source", -1) ?: -1
        val img = json.optJSONArray("images")?.optJSONObject(src) ?: return null
        if (img.has("bufferView")) return view(img.getInt("bufferView"))
        val uri = java.net.URLDecoder.decode(img.optString("uri"), "UTF-8")
        return files[uri] ?: files.entries.firstOrNull { it.key.endsWith("/$uri") || it.key.substringAfterLast('/') == uri.substringAfterLast('/') }?.value
    }

    private fun view(i: Int): ByteArray {
        val bv = json.getJSONArray("bufferViews").getJSONObject(i)
        val start = bv.optInt("byteOffset", 0)
        return bin.copyOfRange(start, start + bv.getInt("byteLength"))
    }

    /** An accessor as floats (normalised integers scaled to 0..1), [n] components per element. */
    private fun floats(i: Int): Pair<FloatArray, Int> {
        val a = json.getJSONArray("accessors").getJSONObject(i)
        val n = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1 }
        val count = a.getInt("count")
        val out = FloatArray(count * n)
        if (!a.has("bufferView")) return out to n
        val bv = json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val ct = a.getInt("componentType")
        val size = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
        val stride = bv.optInt("byteStride", 0).let { if (it == 0) size * n else it }
        val start = bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
        val norm = a.optBoolean("normalized", false)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        for (e in 0 until count) for (c in 0 until n) {
            val p = start + e * stride + c * size
            out[e * n + c] = when (ct) {
                5126 -> bb.getFloat(p)
                5125 -> bb.getInt(p).toLong().and(0xFFFFFFFFL).toFloat()
                5123 -> (bb.getShort(p).toInt() and 0xFFFF).toFloat().let { if (norm) it / 65535f else it }
                5122 -> bb.getShort(p).toFloat().let { if (norm) it / 32767f else it }
                5121 -> (bin[p].toInt() and 0xFF).toFloat().let { if (norm) it / 255f else it }
                else -> bin[p].toFloat().let { if (norm) it / 127f else it }
            }
        }
        return out to n
    }

    private fun ints(i: Int): IntArray {
        val a = json.getJSONArray("accessors").getJSONObject(i)
        val count = a.getInt("count")
        val bv = json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val start = bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        return when (a.getInt("componentType")) {
            5125 -> IntArray(count) { bb.getInt(start + 4 * it) }
            5123 -> IntArray(count) { bb.getShort(start + 2 * it).toInt() and 0xFFFF }
            else -> IntArray(count) { bin[start + it].toInt() and 0xFF }
        }
    }

    fun primitives(): List<Primitive> {
        val out = ArrayList<Primitive>()
        val meshes = json.optJSONArray("meshes") ?: return out
        for (ni in 0 until nodes.length()) {
            val node = nodes.getJSONObject(ni)
            if (!node.has("mesh")) continue
            val mesh = meshes.getJSONObject(node.getInt("mesh"))
            val prims = mesh.getJSONArray("primitives")
            for (pi in 0 until prims.length()) {
                val p = prims.getJSONObject(pi)
                if (p.optInt("mode", 4) != 4) continue
                val attrs = p.getJSONObject("attributes")
                val (pos, _) = floats(attrs.getInt("POSITION"))
                val nV = pos.size / 3
                val w = FloatArray(3 * nV)
                if (node.has("skin") && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
                    val skin = json.getJSONArray("skins").getJSONObject(node.getInt("skin"))
                    val joints = skin.getJSONArray("joints")
                    val ibm = if (skin.has("inverseBindMatrices")) floats(skin.getInt("inverseBindMatrices")).first else null
                    val mats = Array(joints.length()) { k ->
                        val inv = if (ibm != null) FloatArray(16) { c -> ibm[16 * k + c] } else identity()
                        mul(world[joints.getInt(k)] ?: identity(), inv)
                    }
                    val (jn, jc) = floats(attrs.getInt("JOINTS_0"))
                    val (wt, _) = floats(attrs.getInt("WEIGHTS_0"))
                    for (v in 0 until nV) {
                        var sum = 0f
                        for (c in 0 until jc) sum += wt[v * jc + c]
                        if (sum <= 0f) sum = 1f
                        for (c in 0 until jc) {
                            val ww = wt[v * jc + c] / sum
                            if (ww == 0f) continue
                            val m = mats[jn[v * jc + c].toInt().coerceIn(0, mats.size - 1)]
                            transformAdd(m, pos, v, w, ww)
                        }
                    }
                } else {
                    val m = world[ni] ?: identity()
                    for (v in 0 until nV) transformAdd(m, pos, v, w, 1f)
                }
                val matIndex = p.optInt("material", -1)
                val mat = materials.getOrNull(matIndex)
                val key = "TEXCOORD_" + (mat?.texCoord ?: 0)
                val uv = if (attrs.has(key)) floats(attrs.getInt(key)).first else FloatArray(2 * nV)
                if (mat != null && (mat.uvOffset[0] != 0f || mat.uvOffset[1] != 0f || mat.uvScale[0] != 1f || mat.uvScale[1] != 1f || mat.uvRotation != 0f)) {
                    val c = cos(mat.uvRotation); val s = sin(mat.uvRotation)
                    for (v in 0 until nV) {
                        val u = uv[2 * v] * mat.uvScale[0]; val t = uv[2 * v + 1] * mat.uvScale[1]
                        uv[2 * v] = mat.uvOffset[0] + c * u + s * t
                        uv[2 * v + 1] = mat.uvOffset[1] - s * u + c * t
                    }
                }
                val idx = if (p.has("indices")) ints(p.getInt("indices")) else IntArray(nV) { it }
                out += Primitive(w, uv, idx, matIndex, node.optString("name") + "/" + mesh.optString("name"))
            }
        }
        return out
    }

    companion object {
        /** A .glb, or a .zip holding a .gltf (and its .bin and textures) or a .glb. */
        fun read(bytes: ByteArray): Gltf {
            if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "glTF") return glb(bytes, emptyMap())
            if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                val files = HashMap<String, ByteArray>()
                ZipInputStream(bytes.inputStream()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (!e.isDirectory) files[e.name] = z.readBytes()
                    }
                }
                files.entries.firstOrNull { it.key.endsWith(".glb", true) }?.let { return glb(it.value, files) }
                val gltf = files.entries.firstOrNull { it.key.endsWith(".gltf", true) } ?: error("no .gltf or .glb in the archive")
                val dir = gltf.key.substringBeforeLast('/', "")
                val rel = files.mapKeys { (k, _) -> if (dir.isNotEmpty() && k.startsWith("$dir/")) k.removePrefix("$dir/") else k }
                val json = JSONObject(gltf.value.toString(Charsets.UTF_8))
                val uri = json.getJSONArray("buffers").getJSONObject(0).optString("uri", "scene.bin")
                val bin = rel[java.net.URLDecoder.decode(uri, "UTF-8")] ?: error("missing $uri")
                return Gltf(json, bin, rel)
            }
            if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) error("a .gltf alone has no data: choose the .zip or a .glb")
            error("not a glTF file")
        }

        private fun glb(b: ByteArray, files: Map<String, ByteArray>): Gltf {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val jlen = bb.getInt(12)
            val json = JSONObject(String(b, 20, jlen, Charsets.UTF_8))
            val off = 20 + jlen
            val bin = if (off + 8 <= b.size) b.copyOfRange(off + 8, off + 8 + bb.getInt(off)) else ByteArray(0)
            return Gltf(json, bin, files)
        }

        fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        /** Column-major 4x4 product a x b (as glTF stores them). */
        fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(16)
            for (c in 0..3) for (row in 0..3) {
                var s = 0f
                for (k in 0..3) s += a[k * 4 + row] * b[c * 4 + k]
                r[c * 4 + row] = s
            }
            return r
        }

        private fun transformAdd(m: FloatArray, p: FloatArray, v: Int, out: FloatArray, w: Float) {
            val x = p[3 * v]; val y = p[3 * v + 1]; val z = p[3 * v + 2]
            out[3 * v] += w * (m[0] * x + m[4] * y + m[8] * z + m[12])
            out[3 * v + 1] += w * (m[1] * x + m[5] * y + m[9] * z + m[13])
            out[3 * v + 2] += w * (m[2] * x + m[6] * y + m[10] * z + m[14])
        }

        private fun local(n: JSONObject): FloatArray {
            n.optJSONArray("matrix")?.let { a -> return FloatArray(16) { a.getDouble(it).toFloat() } }
            var m = identity()
            n.optJSONArray("scale")?.let { s ->
                m = mul(floatArrayOf(s.getDouble(0).toFloat(), 0f, 0f, 0f, 0f, s.getDouble(1).toFloat(), 0f, 0f, 0f, 0f, s.getDouble(2).toFloat(), 0f, 0f, 0f, 0f, 1f), m)
            }
            n.optJSONArray("rotation")?.let { q ->
                val x = q.getDouble(0).toFloat(); val y = q.getDouble(1).toFloat(); val z = q.getDouble(2).toFloat(); val w = q.getDouble(3).toFloat()
                val r = floatArrayOf(
                    1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
                    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f,
                    0f, 0f, 0f, 1f,
                )
                m = mul(r, m)
            }
            n.optJSONArray("translation")?.let { t ->
                m = mul(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat(), 1f), m)
            }
            return m
        }
    }
}
