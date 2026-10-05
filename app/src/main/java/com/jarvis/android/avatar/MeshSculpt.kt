package com.jarvis.android.avatar

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Hand-made retouches of a face: vertex index → offset (dx, dy, dz), applied after the creator's sliders. */
internal typealias Sculpt = Map<Int, FloatArray>

/**
 * The polygon editor's geometry, ported from Jarvis 2.0 (src/avatar/MeshSculpt.js): retouches are sparse offsets per vertex, so the
 * original mesh is never changed. Vertices that sit on the same point (the mesh's seams) are welded: moving one moves its twins, so
 * nothing cracks open. The hair cannot be edited.
 */
internal object MeshSculpt {
    const val MAX_ENTRIES = 12_000
    const val MAX_OFFSET = 0.6f
    private const val WELD_SCALE = 1e4f

    fun normalize(raw: Map<Int, FloatArray>): Map<Int, FloatArray> {
        val out = LinkedHashMap<Int, FloatArray>()
        for ((index, value) in raw) {
            if (index < 0 || index > 500_000 || value.size < 3) continue
            val v = FloatArray(3) { i ->
                val x = value[i]
                if (!x.isFinite()) 0f else ((x * 1e4f).roundToInt() / 1e4f).coerceIn(-MAX_OFFSET, MAX_OFFSET) + 0f
            }
            if (v[0] == 0f && v[1] == 0f && v[2] == 0f) continue
            out[index] = v
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    /** "12:0.01,0,-0.002|40:…", as stored in a file. */
    fun encode(s: Sculpt): String = normalize(s).entries.joinToString("|") { (k, v) -> "$k:${v[0]},${v[1]},${v[2]}" }

    fun decode(text: String): Map<Int, FloatArray> {
        val out = HashMap<Int, FloatArray>()
        for (part in text.split('|')) {
            val (k, v) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
            val i = k.trim().toIntOrNull() ?: continue
            val xyz = v.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (xyz.size == 3) out[i] = xyz.toFloatArray()
        }
        return normalize(out)
    }

    /** A short fingerprint of the retouches, to know when the head must be rebuilt. */
    fun key(s: Sculpt): String = if (s.isEmpty()) "" else "${s.size}:${encode(s).hashCode()}"

    /** How many vertices can be edited: all those before the hair (the first vertex of a hair triangle). */
    fun sculptableLimit(mesh: HeadMesh): Int {
        var limit = Int.MAX_VALUE
        for (t in 0 until mesh.faceCount) if (mesh.faceGroup[t] > 1.5f) {
            limit = minOf(limit, mesh.faces[3 * t], mesh.faces[3 * t + 1], mesh.faces[3 * t + 2])
        }
        return if (limit == Int.MAX_VALUE) minOf(if (mesh.nHead > 0) mesh.nHead else mesh.vertexCount, mesh.vertexCount) else limit
    }

    /** A new head with the retouches (the one given is not modified); the iris centres follow a moved eyeball. */
    fun apply(mesh: HeadMesh, sculpt: Sculpt): HeadMesh {
        val offsets = normalize(sculpt)
        if (offsets.isEmpty()) return mesh
        val limit = sculptableLimit(mesh)
        val verts = mesh.verts.copyOf()
        var moved = false
        for ((i, o) in offsets) {
            if (i >= limit) continue
            verts[3 * i] += o[0]; verts[3 * i + 1] += o[1]; verts[3 * i + 2] += o[2]
            moved = true
        }
        if (!moved) return mesh
        val headVertices = minOf(if (mesh.nHead > 0) mesh.nHead else mesh.vertexCount, mesh.vertexCount)
        val normals = refitHeadNormals(mesh, verts, headVertices)
        val eyeCentre = mesh.eyeCentre.copyOf()
        for (e in mesh.eyeFirst.indices) {
            val first = mesh.eyeFirst[e]
            val end = minOf(first + mesh.eyeCount[e], limit)
            if (end <= first) continue
            var dx = 0f; var dy = 0f; var dz = 0f
            for (i in first until end) {
                dx += verts[3 * i] - mesh.verts[3 * i]; dy += verts[3 * i + 1] - mesh.verts[3 * i + 1]; dz += verts[3 * i + 2] - mesh.verts[3 * i + 2]
            }
            val n = end - first
            eyeCentre[3 * e] += dx / n; eyeCentre[3 * e + 1] += dy / n; eyeCentre[3 * e + 2] += dz / n
        }
        return mesh.copy(verts = verts, normals = normals, eyeCentre = eyeCentre)
    }

    // ── editing topology ────────────────────────────────────────────────────

    /** The editable part of a head: [rep] is the representative of each welded point, [members] its twins. */
    class Topology(val limit: Int, val rep: IntArray, val members: Map<Int, IntArray>, val tris: IntArray, val neighbours: Map<Int, IntArray>) {
        /** The representatives that belong to at least one triangle. */
        val reps: IntArray = members.keys.filter { neighbours.containsKey(it) }.toIntArray()
    }

    fun topology(mesh: HeadMesh): Topology {
        val limit = sculptableLimit(mesh)
        val rep = IntArray(limit)
        val members = LinkedHashMap<Int, MutableList<Int>>()
        val seen = HashMap<Triple<Int, Int, Int>, Int>()
        for (i in 0 until limit) {
            val key = Triple((mesh.verts[3 * i] * WELD_SCALE).roundToInt(), (mesh.verts[3 * i + 1] * WELD_SCALE).roundToInt(), (mesh.verts[3 * i + 2] * WELD_SCALE).roundToInt())
            val r = seen.getOrPut(key) { i }
            members.getOrPut(r) { ArrayList() } += i
            rep[i] = r
        }
        val tris = ArrayList<Int>()
        val nbr = HashMap<Int, LinkedHashSet<Int>>()
        for (t in 0 until mesh.faceCount) {
            if (mesh.faceGroup[t] > 1.5f) continue
            val a = mesh.faces[3 * t]; val b = mesh.faces[3 * t + 1]; val c = mesh.faces[3 * t + 2]
            if (a >= limit || b >= limit || c >= limit) continue
            tris += a; tris += b; tris += c
            for ((u, w) in listOf(rep[a] to rep[b], rep[b] to rep[c], rep[c] to rep[a])) {
                if (u == w) continue
                nbr.getOrPut(u) { LinkedHashSet() } += w
                nbr.getOrPut(w) { LinkedHashSet() } += u
            }
        }
        return Topology(limit, rep, members.mapValues { it.value.toIntArray() }, tris.toIntArray(), nbr.mapValues { it.value.toIntArray() })
    }

    fun grow(topo: Topology, selection: Set<Int>): Set<Int> {
        val out = LinkedHashSet(selection)
        for (r in selection) topo.neighbours[r]?.let { for (n in it) out += n }
        return out
    }

    fun shrink(topo: Topology, selection: Set<Int>): Set<Int> =
        selection.filterTo(LinkedHashSet()) { r -> (topo.neighbours[r] ?: IntArray(0)).all { it in selection } }

    /**
     * A soft influence round the selection, by the distance along the SURFACE (the mesh's edges): the upper lid does not pull the
     * lower one across the eye's opening. Returns representative → weight 0..1.
     */
    fun softWeights(topo: Topology, verts: FloatArray, selection: Set<Int>, radius: Float): Map<Int, Float> {
        val weights = LinkedHashMap<Int, Float>()
        if (radius <= 1e-6f) { for (r in selection) weights[r] = 1f; return weights }
        val dist = HashMap<Int, Float>()
        val heap = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        for (r in selection) { dist[r] = 0f; heap += 0f to r }
        while (heap.isNotEmpty()) {
            val (d, r) = heap.poll()
            if (d > (dist[r] ?: Float.MAX_VALUE)) continue
            for (n in topo.neighbours[r] ?: continue) {
                val dx = verts[3 * n] - verts[3 * r]; val dy = verts[3 * n + 1] - verts[3 * r + 1]; val dz = verts[3 * n + 2] - verts[3 * r + 2]
                val nd = d + sqrt(dx * dx + dy * dy + dz * dz)
                if (nd >= radius || nd >= (dist[n] ?: Float.MAX_VALUE)) continue
                dist[n] = nd
                heap += nd to n
            }
        }
        for ((r, d) in dist) { val t = d / radius; weights[r] = 1 - t * t * (3 - 2 * t) }
        return weights
    }

    private fun cellKey(x: Float, y: Float, z: Float, cell: Float) = Triple(floor(x / cell).toInt(), floor(y / cell).toInt(), floor(z / cell).toInt())

    /** The mirror point (about the plane x = [cx]) of each selected point, when there is one. */
    fun mirrorMap(topo: Topology, verts: FloatArray, selection: Set<Int>, cx: Float, tolerance: Float = 0.03f): Map<Int, Int> {
        val cell = tolerance * 2
        val grid = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        for (r in topo.reps) grid.getOrPut(cellKey(verts[3 * r], verts[3 * r + 1], verts[3 * r + 2], cell)) { ArrayList() } += r
        val out = LinkedHashMap<Int, Int>()
        for (r in selection) {
            val x = 2 * cx - verts[3 * r]; val y = verts[3 * r + 1]; val z = verts[3 * r + 2]
            val (ix, iy, iz) = cellKey(x, y, z, cell)
            var best = -1; var bestD = tolerance
            for (a in -1..1) for (b in -1..1) for (c in -1..1) {
                for (o in grid[Triple(ix + a, iy + b, iz + c)] ?: continue) {
                    val dx = verts[3 * o] - x; val dy = verts[3 * o + 1] - y; val dz = verts[3 * o + 2] - z
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (d < bestD) { bestD = d; best = o }
                }
            }
            if (best >= 0) out[r] = best
        }
        return out
    }

    /** The weights of the mirror pass: the mirror points of the selection (not already selected), with the same soft influence. */
    fun mirrorWeights(topo: Topology, verts: FloatArray, selection: Set<Int>, radius: Float, cx: Float): Map<Int, Float> {
        val mirrored = mirrorMap(topo, verts, selection, cx).values.filterTo(LinkedHashSet()) { it !in selection }
        return softWeights(topo, verts, mirrored, radius)
    }

    private fun addTo(next: MutableMap<Int, FloatArray>, i: Int, dx: Float, dy: Float, dz: Float) {
        val cur = next[i]
        next[i] = if (cur == null) floatArrayOf(dx, dy, dz) else floatArrayOf(cur[0] + dx, cur[1] + dy, cur[2] + dz)
    }

    /** Adds `delta × weight` to the points (and their twins); the [mirror] weights get the move mirrored in x. */
    fun addDelta(offsets: Sculpt, topo: Topology, weights: Map<Int, Float>, delta: FloatArray, mirror: Map<Int, Float>? = null): Sculpt {
        val next = HashMap(offsets)
        for ((r, w) in weights) for (i in topo.members[r] ?: continue) addTo(next, i, delta[0] * w, delta[1] * w, delta[2] * w)
        if (mirror != null) for ((r, w) in mirror) for (i in topo.members[r] ?: continue) addTo(next, i, -delta[0] * w, delta[1] * w, delta[2] * w)
        return next
    }

    /** The given points back where they were. */
    fun clear(offsets: Sculpt, topo: Topology, reps: Set<Int>): Sculpt {
        val next = HashMap(offsets)
        for (r in reps) for (i in topo.members[r] ?: continue) next.remove(i)
        return next
    }

    /** Laplacian smoothing: each point moves towards the mean of its neighbours (wipes out folds and steps). */
    fun smooth(offsets: Sculpt, topo: Topology, base: FloatArray, weights: Map<Int, Float>, strength: Float = 0.5f): Sculpt {
        fun cur(r: Int, j: Int) = base[3 * r + j] + (offsets[r]?.get(j) ?: 0f)
        val deltas = LinkedHashMap<Int, FloatArray>()
        for ((r, w) in weights) {
            val nb = topo.neighbours[r] ?: continue
            if (nb.isEmpty()) continue
            val d = FloatArray(3)
            for (j in 0..2) {
                var avg = 0f
                for (n in nb) avg += cur(n, j)
                d[j] = (avg / nb.size - cur(r, j)) * strength * w
            }
            deltas[r] = d
        }
        val next = HashMap(offsets)
        for ((r, d) in deltas) for (i in topo.members[r] ?: continue) addTo(next, i, d[0], d[1], d[2])
        return next
    }

    /** The positions shown: the base plus the offsets. */
    fun displayVerts(base: FloatArray, offsets: Sculpt, limit: Int): FloatArray {
        val out = base.copyOf()
        for ((i, o) in offsets) {
            if (i >= limit) continue
            out[3 * i] += o[0]; out[3 * i + 1] += o[1]; out[3 * i + 2] += o[2]
        }
        return out
    }

    // ── orthographic camera and picking on screen ───────────────────────────

    class Camera(
        val yaw: Float = 0f, val pitch: Float = 0f, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f,
        val width: Float = 600f, val height: Float = 600f, val centre: FloatArray = floatArrayOf(0f, -0.1f, 0f), val extent: Float = 1.45f,
    ) {
        private val cy = cos(yaw); private val sy = sin(yaw); private val cp = cos(pitch); private val sp = sin(pitch)
        val r0 = floatArrayOf(cy, 0f, sy)
        val r1 = floatArrayOf(sp * sy, cp, -sp * cy)
        val r2 = floatArrayOf(-cp * sy, sp, cp * cy)
        val scale = zoom * min(width, height) / (2 * extent)

        fun with(yaw: Float = this.yaw, pitch: Float = this.pitch, zoom: Float = this.zoom, panX: Float = this.panX, panY: Float = this.panY,
                 width: Float = this.width, height: Float = this.height, centre: FloatArray = this.centre) =
            Camera(yaw, pitch, zoom, panX, panY, width, height, centre, extent)

        /** A move on screen (pixels) → a move of the object, in the plane of the view. */
        fun screenToWorld(dxPx: Float, dyPx: Float): FloatArray {
            val dx = dxPx / scale; val dy = -dyPx / scale
            return floatArrayOf(r0[0] * dx + r1[0] * dy, r0[1] * dx + r1[1] * dy, r0[2] * dx + r1[2] * dy)
        }
    }

    fun project(cam: Camera, verts: FloatArray, count: Int): FloatArray {
        val out = FloatArray(3 * count)
        for (i in 0 until count) {
            val px = verts[3 * i] - cam.centre[0]; val py = verts[3 * i + 1] - cam.centre[1]; val pz = verts[3 * i + 2] - cam.centre[2]
            out[3 * i] = cam.width / 2 + cam.panX + (cam.r0[0] * px + cam.r0[1] * py + cam.r0[2] * pz) * cam.scale
            out[3 * i + 1] = cam.height / 2 + cam.panY - (cam.r1[0] * px + cam.r1[1] * py + cam.r1[2] * pz) * cam.scale
            out[3 * i + 2] = cam.r2[0] * px + cam.r2[1] * py + cam.r2[2] * pz
        }
        return out
    }

    /** The triangles facing the camera, their light and depth, and a half-resolution depth buffer (only what is seen can be picked). */
    class Scene(val cam: Camera, val proj: FloatArray, val front: BooleanArray, val shade: FloatArray, val depth: FloatArray,
                val zbuf: FloatArray, val dw: Int, val dh: Int, val tris: IntArray)

    fun scene(cam: Camera, verts: FloatArray, topo: Topology, refNormals: FloatArray): Scene {
        val proj = project(cam, verts, topo.limit)
        val tris = topo.tris
        val nTri = tris.size / 3
        val front = BooleanArray(nTri); val shade = FloatArray(nTri); val depth = FloatArray(nTri)
        val lx = -0.35f; val ly = 0.45f; val lz = 0.82f
        for (t in 0 until nTri) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val abx = verts[3 * b] - verts[3 * a]; val aby = verts[3 * b + 1] - verts[3 * a + 1]; val abz = verts[3 * b + 2] - verts[3 * a + 2]
            val acx = verts[3 * c] - verts[3 * a]; val acy = verts[3 * c + 1] - verts[3 * a + 1]; val acz = verts[3 * c + 2] - verts[3 * a + 2]
            var nx = aby * acz - abz * acy; var ny = abz * acx - abx * acz; var nz = abx * acy - aby * acx
            val len = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
            nx /= len; ny /= len; nz /= len
            val rx = refNormals[3 * a] + refNormals[3 * b] + refNormals[3 * c]
            val ry = refNormals[3 * a + 1] + refNormals[3 * b + 1] + refNormals[3 * c + 1]
            val rz = refNormals[3 * a + 2] + refNormals[3 * b + 2] + refNormals[3 * c + 2]
            if (nx * rx + ny * ry + nz * rz < 0) { nx = -nx; ny = -ny; nz = -nz }
            val cz = cam.r2[0] * nx + cam.r2[1] * ny + cam.r2[2] * nz
            val cxn = cam.r0[0] * nx + cam.r0[1] * ny + cam.r0[2] * nz
            val cyn = cam.r1[0] * nx + cam.r1[1] * ny + cam.r1[2] * nz
            front[t] = cz > 0.02f
            shade[t] = max(0f, cxn * lx + cyn * ly + cz * lz)
            depth[t] = (proj[3 * a + 2] + proj[3 * b + 2] + proj[3 * c + 2]) / 3
        }
        val dw = max(1, ceil(cam.width / 2).toInt()); val dh = max(1, ceil(cam.height / 2).toInt())
        val zbuf = FloatArray(dw * dh) { Float.NEGATIVE_INFINITY }
        for (t in 0 until nTri) {
            if (!front[t]) continue
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val x0 = proj[3 * a] / 2; val y0 = proj[3 * a + 1] / 2; val z0 = proj[3 * a + 2]
            val x1 = proj[3 * b] / 2; val y1 = proj[3 * b + 1] / 2; val z1 = proj[3 * b + 2]
            val x2 = proj[3 * c] / 2; val y2 = proj[3 * c + 1] / 2; val z2 = proj[3 * c + 2]
            val minX = max(0, floor(min(x0, min(x1, x2))).toInt()); val maxX = min(dw - 1, ceil(max(x0, max(x1, x2))).toInt())
            val minY = max(0, floor(min(y0, min(y1, y2))).toInt()); val maxY = min(dh - 1, ceil(max(y0, max(y1, y2))).toInt())
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area) < 1e-6f) continue
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
                val w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
                val w2 = 1 - w0 - w1
                if (w0 < -0.02f || w1 < -0.02f || w2 < -0.02f) continue
                val z = w0 * z0 + w1 * z1 + w2 * z2
                val idx = y * dw + x
                if (z > zbuf[idx]) zbuf[idx] = z
            }
        }
        return Scene(cam, proj, front, shade, depth, zbuf, dw, dh, tris)
    }

    private const val VISIBILITY_EPS = 0.014f

    fun visible(s: Scene, i: Int): Boolean {
        val x = floor(s.proj[3 * i] / 2).toInt(); val y = floor(s.proj[3 * i + 1] / 2).toInt()
        if (x < 0 || y < 0 || x >= s.dw || y >= s.dh) return false
        var best = Float.NEGATIVE_INFINITY
        for (oy in -1..1) for (ox in -1..1) {
            val xx = x + ox; val yy = y + oy
            if (xx < 0 || yy < 0 || xx >= s.dw || yy >= s.dh) continue
            best = max(best, s.zbuf[yy * s.dw + xx])
        }
        return s.proj[3 * i + 2] >= best - VISIBILITY_EPS
    }

    /** The visible point nearest to ([px], [py]) within [radiusPx], or -1. */
    fun pickVertex(s: Scene, topo: Topology, px: Float, py: Float, radiusPx: Float): Int {
        var best = -1; var bestScore = Float.MAX_VALUE
        for (r in topo.reps) {
            val dx = s.proj[3 * r] - px; val dy = s.proj[3 * r + 1] - py
            val d2 = dx * dx + dy * dy
            if (d2 > radiusPx * radiusPx || !visible(s, r)) continue
            val score = d2 - s.proj[3 * r + 2] * 4
            if (score < bestScore) { bestScore = score; best = r }
        }
        return best
    }

    /** The front triangle under ([px], [py]), or -1. */
    fun pickTriangle(s: Scene, px: Float, py: Float): Int {
        val tris = s.tris; val proj = s.proj
        var best = -1; var bestDepth = Float.NEGATIVE_INFINITY
        for (t in 0 until tris.size / 3) {
            if (!s.front[t]) continue
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val x0 = proj[3 * a]; val y0 = proj[3 * a + 1]; val x1 = proj[3 * b]; val y1 = proj[3 * b + 1]; val x2 = proj[3 * c]; val y2 = proj[3 * c + 1]
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area) < 1e-9f) continue
            val w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
            val w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
            val w2 = 1 - w0 - w1
            if (w0 < 0 || w1 < 0 || w2 < 0) continue
            if (s.depth[t] > bestDepth) { bestDepth = s.depth[t]; best = t }
        }
        return best
    }

    fun trianglePoints(topo: Topology, t: Int) = listOf(topo.rep[topo.tris[3 * t]], topo.rep[topo.tris[3 * t + 1]], topo.rep[topo.tris[3 * t + 2]])

    fun boxSelect(s: Scene, topo: Topology, x0: Float, y0: Float, x1: Float, y1: Float): List<Int> {
        val minX = min(x0, x1); val maxX = max(x0, x1); val minY = min(y0, y1); val maxY = max(y0, y1)
        return topo.reps.filter { r -> s.proj[3 * r] in minX..maxX && s.proj[3 * r + 1] in minY..maxY && visible(s, r) }
    }

    /** The lids of one eye ([left]: as seen on screen): the rim of its opening, grown by [rings] rings of neighbours. */
    fun selectEyelids(mesh: HeadMesh, topo: Topology, left: Boolean, rings: Int = 2): Set<Int> {
        val out = LinkedHashSet<Int>()
        val rim = mesh.eyelidRim; val ec = mesh.eyeCentre
        if (rim.isEmpty() || ec.size < 6) return out
        val xs = (0 until ec.size / 3).map { ec[3 * it] }
        val target = if (left) xs.min() else xs.max()
        for (k in 0 until rim.size / 3) for (vi in intArrayOf(rim[3 * k], rim[3 * k + 1])) {
            if (vi >= topo.limit) continue
            val x = mesh.verts[3 * vi]
            if (xs.minBy { abs(it - x) } == target) out += topo.rep[vi]
        }
        var selection: Set<Int> = out
        repeat(rings) { selection = grow(topo, selection) }
        return selection
    }

    // ── symmetry ────────────────────────────────────────────────────────────

    /** The plane x = cx that best fits the head (median distance from a mirrored point to the nearest point). */
    fun symmetryPlane(mesh: HeadMesh, topo: Topology, guess: Float): Float {
        val v = mesh.verts
        val headLimit = min(if (mesh.nHead > 0) mesh.nHead else topo.limit, topo.limit)
        val reps = topo.reps.filter { it < headLimit }
        val cell = 0.03f
        val grid = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        for (r in reps) grid.getOrPut(cellKey(v[3 * r], v[3 * r + 1], v[3 * r + 2], cell)) { ArrayList() } += r
        fun nearest(x: Float, y: Float, z: Float): Float {
            var best = Float.MAX_VALUE
            val (ix, iy, iz) = cellKey(x, y, z, cell)
            for (a in -2..2) for (b in -2..2) for (c in -2..2) for (r in grid[Triple(ix + a, iy + b, iz + c)] ?: continue) {
                val dx = v[3 * r] - x; val dy = v[3 * r + 1] - y; val dz = v[3 * r + 2] - z
                best = min(best, sqrt(dx * dx + dy * dy + dz * dz))
            }
            return best
        }
        val sample = reps.filterIndexed { i, _ -> i % 12 == 0 }
        if (sample.isEmpty()) return guess
        fun score(cx: Float): Float = sample.map { nearest(2 * cx - v[3 * it], v[3 * it + 1], v[3 * it + 2]) }.sorted()[sample.size / 2]
        var best = guess; var bestScore = score(guess)
        for ((span, step) in listOf(0.08f to 0.01f, 0.012f to 0.002f)) {
            val centre = best
            var cx = centre - span
            while (cx <= centre + span + 1e-6f) {
                val s = score(cx)
                if (s < bestScore) { bestScore = s; best = cx }
                cx += step
            }
        }
        return best
    }

    private fun closestOnTriangle(p: FloatArray, a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        // Ericson, "Real-Time Collision Detection": the closest point of a triangle
        fun sub(u: FloatArray, w: FloatArray) = floatArrayOf(u[0] - w[0], u[1] - w[1], u[2] - w[2])
        fun dot(u: FloatArray, w: FloatArray) = u[0] * w[0] + u[1] * w[1] + u[2] * w[2]
        val ab = sub(b, a); val ac = sub(c, a); val ap = sub(p, a)
        val d1 = dot(ab, ap); val d2 = dot(ac, ap)
        if (d1 <= 0 && d2 <= 0) return a
        val bp = sub(p, b)
        val d3 = dot(ab, bp); val d4 = dot(ac, bp)
        if (d3 >= 0 && d4 <= d3) return b
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0 && d1 >= 0 && d3 <= 0) { val t = d1 / (d1 - d3); return floatArrayOf(a[0] + t * ab[0], a[1] + t * ab[1], a[2] + t * ab[2]) }
        val cp = sub(p, c)
        val d5 = dot(ab, cp); val d6 = dot(ac, cp)
        if (d6 >= 0 && d5 <= d6) return c
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0 && d2 >= 0 && d6 <= 0) { val t = d2 / (d2 - d6); return floatArrayOf(a[0] + t * ac[0], a[1] + t * ac[1], a[2] + t * ac[2]) }
        val va = d3 * d6 - d5 * d4
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            val t = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            return floatArrayOf(b[0] + t * (c[0] - b[0]), b[1] + t * (c[1] - b[1]), b[2] + t * (c[2] - b[2]))
        }
        val denom = 1 / (va + vb + vc)
        val s = vb * denom; val t = vc * denom
        return floatArrayOf(a[0] + ab[0] * s + ac[0] * t, a[1] + ab[1] * s + ac[1] * t, a[2] + ab[2] * s + ac[2] * t)
    }

    private const val SYMMETRY_MAX_DISTANCE = 0.06f

    class Symmetrized(val offsets: Sculpt, val moved: Int, val skipped: Int)

    /**
     * Makes the face symmetric by copying one side on the other: each point of the other side lands on the mirror of the source side's
     * SURFACE (the nearest, facing the same way), fading out near the middle. The source side never changes. [fromLeft]: the side on
     * screen at x < cx. [only] limits it to the given points. The eyeballs are moved rigidly, so both keep their size.
     */
    fun symmetrize(mesh: HeadMesh, topo: Topology, offsets: Sculpt, fromLeft: Boolean, plane: Float, only: Set<Int>? = null): Symmetrized {
        val limit = topo.limit
        val side = if (fromLeft) -1f else 1f
        val cur = displayVerts(mesh.verts, offsets, limit)
        val n = mesh.normals
        val cell = 0.05f
        val grid = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        val srcA = ArrayList<Int>(); val srcB = ArrayList<Int>(); val srcC = ArrayList<Int>(); val srcN = ArrayList<FloatArray>()
        val tris = topo.tris
        for (t in 0 until tris.size / 3) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            val gx = (cur[3 * a] + cur[3 * b] + cur[3 * c]) / 3
            if (side * (gx - plane) < -0.012f) continue
            val abx = cur[3 * b] - cur[3 * a]; val aby = cur[3 * b + 1] - cur[3 * a + 1]; val abz = cur[3 * b + 2] - cur[3 * a + 2]
            val acx = cur[3 * c] - cur[3 * a]; val acy = cur[3 * c + 1] - cur[3 * a + 1]; val acz = cur[3 * c + 2] - cur[3 * a + 2]
            var nx = aby * acz - abz * acy; var ny = abz * acx - abx * acz; var nz = abx * acy - aby * acx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-12f) continue
            nx /= len; ny /= len; nz /= len
            if (nx * (n[3 * a] + n[3 * b] + n[3 * c]) + ny * (n[3 * a + 1] + n[3 * b + 1] + n[3 * c + 1]) + nz * (n[3 * a + 2] + n[3 * b + 2] + n[3 * c + 2]) < 0) { nx = -nx; ny = -ny; nz = -nz }
            val id = srcA.size
            srcA += a; srcB += b; srcC += c; srcN += floatArrayOf(nx, ny, nz)
            val lo = IntArray(3) { k -> floor(min(cur[3 * a + k], min(cur[3 * b + k], cur[3 * c + k])) / cell).toInt() }
            val hi = IntArray(3) { k -> floor(max(cur[3 * a + k], max(cur[3 * b + k], cur[3 * c + k])) / cell).toInt() }
            for (x in lo[0]..hi[0]) for (y in lo[1]..hi[1]) for (z in lo[2]..hi[2]) grid.getOrPut(Triple(x, y, z)) { ArrayList() } += id
        }
        fun point(i: Int) = floatArrayOf(cur[3 * i], cur[3 * i + 1], cur[3 * i + 2])
        val next = HashMap(offsets)
        var moved = 0; var skipped = 0
        val globes = mesh.eyeFirst.indices.map { e -> Triple(e, mesh.eyeFirst[e], min(limit, mesh.eyeFirst[e] + mesh.eyeCount[e])) }
        fun isGlobe(i: Int) = globes.any { (_, first, end) -> i in first until end }
        fun smooth01(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        for (r in topo.reps) {
            if (isGlobe(r)) continue
            val dx = cur[3 * r] - plane
            if (side * dx >= 0) continue                    // the source side stays as it is
            if (only != null && r !in only) continue
            val w = smooth01((abs(dx) - 0.004f) / 0.04f)
            if (w <= 0f) continue
            val q = floatArrayOf(2 * plane - cur[3 * r], cur[3 * r + 1], cur[3 * r + 2])
            val want = floatArrayOf(-n[3 * r], n[3 * r + 1], n[3 * r + 2])
            val (ix, iy, iz) = cellKey(q[0], q[1], q[2], cell)
            var best: FloatArray? = null; var bestD = SYMMETRY_MAX_DISTANCE
            val seen = HashSet<Int>()
            for (a in -1..1) for (b in -1..1) for (c in -1..1) for (id in grid[Triple(ix + a, iy + b, iz + c)] ?: continue) {
                if (!seen.add(id)) continue
                val tn = srcN[id]
                if (tn[0] * want[0] + tn[1] * want[1] + tn[2] * want[2] < 0.1f) continue
                val cp = closestOnTriangle(q, point(srcA[id]), point(srcB[id]), point(srcC[id]))
                val d = sqrt((cp[0] - q[0]) * (cp[0] - q[0]) + (cp[1] - q[1]) * (cp[1] - q[1]) + (cp[2] - q[2]) * (cp[2] - q[2]))
                if (d < bestD) { bestD = d; best = cp }
            }
            val found = best ?: run { skipped++; null } ?: continue
            val target = floatArrayOf(2 * plane - found[0], found[1], found[2])
            val fin = FloatArray(3) { k -> cur[3 * r + k] + (target[k] - cur[3 * r + k]) * w }
            for (i in topo.members[r] ?: continue) {
                val off = FloatArray(3) { k -> fin[k] - mesh.verts[3 * i + k] }
                if (sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]) < 1e-5f) next.remove(i) else next[i] = off
            }
            moved++
        }
        // the eyeballs: the target one moved rigidly onto the mirror of the source one
        val centres = globes.map { (e, first, end) ->
            var dx = 0f; var dy = 0f; var dz = 0f
            for (i in first until end) { dx += cur[3 * i] - mesh.verts[3 * i]; dy += cur[3 * i + 1] - mesh.verts[3 * i + 1]; dz += cur[3 * i + 2] - mesh.verts[3 * i + 2] }
            val k = max(1, end - first)
            floatArrayOf(mesh.eyeCentre[3 * e] + dx / k, mesh.eyeCentre[3 * e + 1] + dy / k, mesh.eyeCentre[3 * e + 2] + dz / k)
        }
        for ((gi, g) in globes.withIndex()) {
            val c = centres[gi]
            if (side * (c[0] - plane) >= 0) continue
            if (only != null && (g.second until g.third).none { topo.rep[it] in only }) continue
            val source = centres.firstOrNull { side * (it[0] - plane) > 0 } ?: continue
            val move = floatArrayOf(2 * plane - source[0] - c[0], source[1] - c[1], source[2] - c[2])
            for (i in g.second until g.third) {
                val o = next[i] ?: FloatArray(3)
                val off = FloatArray(3) { k -> o[k] + move[k] }
                if (sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]) < 1e-5f) next.remove(i) else next[i] = off
            }
            moved += g.third - g.second
        }
        return Symmetrized(next, moved, skipped)
    }
}
