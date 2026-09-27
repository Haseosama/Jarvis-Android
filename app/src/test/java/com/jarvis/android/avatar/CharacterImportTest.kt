package com.jarvis.android.avatar

import com.jarvis.android.avatar.importer.CharacterBuilder
import com.jarvis.android.avatar.importer.Gltf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CharacterImportTest {
    /** A GLB of a standing "figure": a sphere for the head (radius 0.1 at y 1.6) on a tall box of a body, scaled by a node. */
    private fun figure(): ByteArray {
        val pos = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        val rows = 24; val cols = 32
        for (r in 0..rows) for (c in 0..cols) {
            val th = Math.PI * r / rows; val ph = 2 * Math.PI * c / cols
            pos += (0.1 * sin(th) * cos(ph)).toFloat(); pos += (1.6 + 0.1 * cos(th)).toFloat(); pos += (0.1 * sin(th) * sin(ph)).toFloat()
            uv += c.toFloat() / cols; uv += r.toFloat() / rows
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = r * (cols + 1) + c; val b = a + cols + 1
            idx += a; idx += b; idx += a + 1; idx += a + 1; idx += b; idx += b + 1
        }
        val base = pos.size / 3
        val box = listOf(-0.2f to 0f, 0.2f to 0f, 0.2f to 1.45f, -0.2f to 1.45f)
        for ((x, y) in box) { pos += x; pos += y; pos += 0.05f; uv += 0f; uv += 0f }
        idx += base; idx += base + 1; idx += base + 2; idx += base; idx += base + 2; idx += base + 3
        val nV = pos.size / 3
        val bin = ByteBuffer.allocate(12 * nV + 8 * nV + 4 * idx.size).order(ByteOrder.LITTLE_ENDIAN)
        pos.forEach { bin.putFloat(it) }; uv.forEach { bin.putFloat(it) }; idx.forEach { bin.putInt(it) }
        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0")).put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("mesh", 0).put("scale", JSONArray().put(2.0).put(2.0).put(2.0))))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(
                JSONObject().put("attributes", JSONObject().put("POSITION", 0).put("TEXCOORD_0", 1)).put("indices", 2).put("material", 0)))))
            .put("materials", JSONArray().put(JSONObject().put("name", "skin")
                .put("pbrMetallicRoughness", JSONObject().put("baseColorFactor", JSONArray().put(0.9).put(0.7).put(0.6).put(1.0)))))
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.capacity())))
            .put("bufferViews", JSONArray()
                .put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", 12 * nV))
                .put(JSONObject().put("buffer", 0).put("byteOffset", 12 * nV).put("byteLength", 8 * nV))
                .put(JSONObject().put("buffer", 0).put("byteOffset", 20 * nV).put("byteLength", 4 * idx.size)))
            .put("accessors", JSONArray()
                .put(JSONObject().put("bufferView", 0).put("componentType", 5126).put("count", nV).put("type", "VEC3"))
                .put(JSONObject().put("bufferView", 1).put("componentType", 5126).put("count", nV).put("type", "VEC2"))
                .put(JSONObject().put("bufferView", 2).put("componentType", 5125).put("count", idx.size).put("type", "SCALAR")))
        var js = json.toString().toByteArray()
        js += ByteArray((4 - js.size % 4) % 4) { ' '.code.toByte() }
        val body = bin.array()
        val out = ByteBuffer.allocate(12 + 8 + js.size + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("glTF".toByteArray()).putInt(2).putInt(out.capacity())
        out.putInt(js.size).put("JSON".toByteArray()).put(js)
        out.putInt(body.size).put("BIN\u0000".toByteArray()).put(body)
        return out.array()
    }

    @Test fun `a glb is read in world space and framed as the heads`() {
        val g = Gltf.read(figure())
        val sc = CharacterBuilder.gather(g, 0f)
        // the node scales by 2: the top of the head is at 2 x 1.7
        val top = (0 until sc.nV).maxOf { sc.pos[3 * it + 1] }
        assertEquals(3.4f, top, 1e-3f)
        assertEquals(0.9f, g.materials[0].factor[0], 1e-6f)

        // the markers placed on the head (eyes a third down it, the chin at its bottom), in a first framing's units
        val f0 = CharacterBuilder.guess(sc)
        val s0 = 0.95f / (f0.eyeY - f0.chin)
        fun toF0(x: Float, y: Float) = floatArrayOf((x - f0.centreX) * s0, (y - f0.chin) * s0 - 1f)
        val eyeL = toF0(-0.07f, 3.27f); val eyeR = toF0(0.07f, 3.27f); val mouth = toF0(0f, 3.12f); val chin = toF0(0f, 3.0f)
        val f1 = CharacterBuilder.reframe(f0, eyeL, eyeR, chin)
        assertEquals(3.27f, f1.eyeY, 1e-3f); assertEquals(3.0f, f1.chin, 1e-3f); assertEquals(0f, f1.centreX, 1e-3f)
        val feat = CharacterBuilder.features(eyeL, eyeR, mouth, f0, f1)
        assertEquals(-0.05f, feat.eyes[0][1], 1e-3f)
        assertTrue(feat.mouth[1] < -0.5f && feat.mouth[1] > -0.8f)

        val built = CharacterBuilder.build(sc, CharacterBuilder.normalise(sc, f1), feat)
        val (tiles, h) = CharacterBuilder.planAtlas(built, listOf(null), 256)
        val auv = CharacterBuilder.atlasUv(built, tiles, 256, h)
        val bytes = CharacterBuilder.meshBytes(built, auv)
        val meta = JSONObject().put("label", "t").put("eyes", JSONArray()).put("mouth", JSONObject().put("points", JSONArray()))
        val c = CharacterMesh.parse(bytes, meta, null)
        assertEquals(built.faces.size / 3, c.faceCount)
        // the chin at -1, the head's top above the eyes, nothing kept below the cut
        val ys = (0 until c.vertexCount).map { c.verts[3 * it + 1] }
        assertTrue(ys.all { it > -1.9f })
        assertTrue(abs(ys.max() - (3.4f - 3.0f) * (0.95f / 0.27f) + 1f) < 0.02f)
        // the jaw moves only under the mouth
        assertTrue((0 until c.vertexCount).filter { c.jaw[it] > 0.05f }.all { c.verts[3 * it + 1] < feat.mouth[1] + 0.01f })
    }

    @Test fun `a heavy bust is simplified under the budget and keeps its uvs`() {
        val g = Gltf.read(figure())
        val sc = CharacterBuilder.gather(g, 0f)
        val f = CharacterBuilder.Framing(3.27f, 3.0f, 0f)
        val feat = CharacterBuilder.features(floatArrayOf(-0.5f, -0.05f), floatArrayOf(0.5f, -0.05f), floatArrayOf(0f, -0.6f), f, f)
        val built = CharacterBuilder.build(sc, CharacterBuilder.normalise(sc, f), feat, maxTris = 600)
        assertTrue(built.faces.size / 3 in 1..600)
        assertTrue(built.srcUv.all { it in -0.001f..1.001f })
        assertTrue(built.faces.all { it in 0 until built.pos.size / 3 })
    }
}
