package com.jarvis.android.avatar

import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val CAM = 4.6f

/**
 * Draws a textured character (see [CharacterMesh]) with the animation of the heads ([HoloAvatar]): the head turns and nods on the
 * shoulders, the jaw drops with the voice, and since the eyes and the mouth are painted in the texture, the renderer draws over them what
 * moves: the inside of the open mouth between the lips, and the lids when the eyes blink or close.
 */
internal class CharacterRenderer(private val ch: CharacterMesh) {
    private val nV = ch.vertexCount
    private val nF = ch.faceCount
    private val pv = FloatArray(3 * nV)
    private val pn = FloatArray(3 * nV)
    private val xs = FloatArray(nV)
    private val ys = FloatArray(nV)
    private val col = IntArray(nV)
    private val keys = LongArray(nF)
    private val triPos = FloatArray(6 * nF)
    private val triTex = FloatArray(6 * nF)
    private val triCol = IntArray(3 * nF)
    private val texW = (ch.atlas?.width ?: 1).toFloat()
    private val texH = (ch.atlas?.height ?: 1).toFloat()
    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = true
        ch.atlas?.let { shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }
    private val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val line = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val erase = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val pt = FloatArray(3)
    private val out = FloatArray(2)

    // the pose of this frame
    private var m00 = 1f; private var m01 = 0f; private var m02 = 0f
    private var m10 = 0f; private var m11 = 1f; private var m12 = 0f
    private var m20 = 0f; private var m21 = 0f; private var m22 = 1f
    private var cr = 1f; private var sr = 0f
    private var k = 1f; private var ox = 0f; private var oy = 0f

    fun draw(scope: DrawScope, a: HoloAvatar, cx: Float, cy: Float, r: Float, primary: Int, bg: Int) {
        // the frame: the face as large as a head's when the bust allows it, the top of the head at the same height
        k = r * ch.scale * min(1f, 2.12f / (ch.top + 1f))
        ox = cx
        oy = cy - 1.12f * r + ch.top * k

        scope.drawCircle(
            brush = Brush.radialGradient(
                0f to Color(primary).copy(alpha = 0.22f + 0.30f * a.glow), 0.45f to Color(primary).copy(alpha = 0.10f), 1f to Color(primary).copy(alpha = 0f),
                center = Offset(cx, cy), radius = r * 1.9f,
            ),
            radius = r * 1.9f, center = Offset(cx, cy),
        )
        pose(a)
        scope.clipRect {
            // the bust melts away below the shoulders: drawn opaque in a layer (half-transparent triangles would show their overlaps),
            // then the layer is erased along a gradient
            val y0 = oy - (ch.cut + 0.55f) * k
            val y1 = oy - (ch.cut + 0.08f) * k
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.saveLayer(0f, 0f, size.width, size.height, null)
                drawMesh(nc)
                erase.shader = LinearGradient(0f, y0, 0f, y1, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
                nc.drawRect(0f, y0, size.width, size.height, erase)
                nc.restore()
            }
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                drawMouth(nc, a)
                drawLids(nc, a)
            }
        }
    }

    private fun pose(a: HoloAvatar) {
        val yaw = a.yaw; val pitch = a.pitch
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        m00 = cy; m01 = 0f; m02 = sy
        m10 = sp * sy; m11 = cp; m12 = -sp * cy
        m20 = -cp * sy; m21 = sp; m22 = cp * cy
        cr = cos(a.roll); sr = sin(a.roll)
        val jawAng = a.mouth.coerceIn(0f, 1f) * JAW_MAX
        val jy = ch.jawPivot[1]; val jz = ch.jawPivot[2]
        val px = ch.pivot[0]; val py = ch.pivot[1]; val pz = ch.pivot[2]
        val v = ch.verts; val n = ch.normals
        val lx = -0.45f; val ly = 0.50f; val lz = 0.75f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (i in 0 until nV) {
            var x = v[3 * i]; var y = v[3 * i + 1]; var z = v[3 * i + 2]
            val w = ch.jaw[i]
            if (w > 0f && jawAng > 0f) {
                val ang = w * jawAng
                val c = cos(ang); val s = sin(ang)
                val dy = y - jy; val dz = z - jz
                y = jy + dy * c - dz * s
                z = jz + dy * s + dz * c
            }
            // the head turns about the neck; the shoulders follow a little
            val h = ch.headW[i]
            val dx = x - px; val dy = y - py; val dz = z - pz
            val rx = m00 * dx + m01 * dy + m02 * dz
            val ry = m10 * dx + m11 * dy + m12 * dz
            val rz = m20 * dx + m21 * dy + m22 * dz
            val tx = rx * cr - ry * sr; val ty = rx * sr + ry * cr
            x += h * (px + tx - x); y += h * (py + ty - y); z += h * (pz + rz - z)
            pv[3 * i] = x; pv[3 * i + 1] = y; pv[3 * i + 2] = z
            val w2 = max(CAM - z, 0.35f)
            val kk = CAM / w2 * k
            xs[i] = ox + x * kk
            ys[i] = oy - y * kk
            // light: the normal turned with the head, both sides lit alike (the surfaces are drawn from both sides)
            val nx = n[3 * i]; val ny = n[3 * i + 1]; val nz = n[3 * i + 2]
            val rnx = m00 * nx + m01 * ny + m02 * nz
            val rny = m10 * nx + m11 * ny + m12 * nz
            val rnz = m20 * nx + m21 * ny + m22 * nz
            val tnx = rnx * cr - rny * sr; val tny = rnx * sr + rny * cr
            val fx = nx + h * (tnx - nx); val fy = ny + h * (tny - ny); val fz = nz + h * (rnz - nz)
            val lit = if (ch.unlit[i]) 1f else ch.ambient + (1f - ch.ambient) * abs(fx * lx + fy * ly + fz * lz) / ll
            val g = (255f * lit.coerceIn(0f, 1f)).toInt()
            col[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    private fun drawMesh(nc: Canvas) {
        val f = ch.faces
        for (t in 0 until nF) {
            val z = pv[3 * f[3 * t] + 2] + pv[3 * f[3 * t + 1] + 2] + pv[3 * f[3 * t + 2] + 2]
            val bits = java.lang.Float.floatToRawIntBits(z)
            val mapped = if (bits >= 0) bits else bits xor 0x7fffffff
            keys[t] = (mapped.toLong() shl 32) or t.toLong()
        }
        java.util.Arrays.sort(keys, 0, nF)
        var p = 0; var q = 0; var c = 0
        for (s in 0 until nF) {
            val t = (keys[s] and 0x7fffffffL).toInt()
            val a = f[3 * t]; val b = f[3 * t + 1]; val d = f[3 * t + 2]
            val gx = (xs[a] + xs[b] + xs[d]) / 3f; val gy = (ys[a] + ys[b] + ys[d]) / 3f
            for (corner in 0..2) {
                val vi = f[3 * t + corner]
                // grown by about half a pixel round the centre, so no background pixel shows where triangles meet
                val dx = xs[vi] - gx; val dy = ys[vi] - gy
                val grow = 0.6f / max(abs(dx) + abs(dy), 0.6f)
                triPos[p++] = xs[vi] + dx * grow; triPos[p++] = ys[vi] + dy * grow
                triTex[q++] = ch.uv[2 * vi] * texW; triTex[q++] = ch.uv[2 * vi + 1] * texH
                triCol[c++] = col[vi]
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && ch.atlas != null) {
            nc.drawVertices(Canvas.VertexMode.TRIANGLES, nF * 6, triPos, 0, triTex, 0, triCol, 0, null, 0, 0, paint)
        } else {
            // older phones: flat triangles in the vertex colour (no texture)
            for (s in 0 until nF) {
                path.reset()
                path.moveTo(triPos[6 * s], triPos[6 * s + 1]); path.lineTo(triPos[6 * s + 2], triPos[6 * s + 3]); path.lineTo(triPos[6 * s + 4], triPos[6 * s + 5])
                path.close()
                fill.color = triCol[3 * s]
                nc.drawPath(path, fill)
            }
        }
    }

    /** A point of the face (rest coordinates, [jawK] of the jaw's drop) posed and projected into [out]. */
    private fun project(x0: Float, y0: Float, z0: Float, jawK: Float, headK: Float = 1f) {
        var x = x0; var y = y0; var z = z0
        if (jawK > 0f) {
            val jy = ch.jawPivot[1]; val jz = ch.jawPivot[2]
            val c = cos(jawK); val s = sin(jawK)
            val dy = y - jy; val dz = z - jz
            y = jy + dy * c - dz * s; z = jz + dy * s + dz * c
        }
        val px = ch.pivot[0]; val py = ch.pivot[1]; val pz = ch.pivot[2]
        val dx = x - px; val dy = y - py; val dz = z - pz
        val rx = m00 * dx + m01 * dy + m02 * dz
        val ry = m10 * dx + m11 * dy + m12 * dz
        val rz = m20 * dx + m21 * dy + m22 * dz
        x += headK * (px + rx * cr - ry * sr - x); y += headK * (py + rx * sr + ry * cr - y); z += headK * (pz + rz - z)
        val kk = CAM / max(CAM - z, 0.35f) * k
        out[0] = ox + x * kk; out[1] = oy - y * kk
    }

    private fun drawMouth(nc: Canvas, a: HoloAvatar) {
        val open = a.mouth.coerceIn(0f, 1f)
        if (open < 0.03f) return
        val m = ch.mouth
        val n = m.size / 3
        val cx = (m[0] + m[3 * (n - 1)]) / 2f
        val hw = max((m[3 * (n - 1)] - m[0]) / 2f, 1e-3f)
        val up = FloatArray(2 * n); val lo = FloatArray(2 * n)
        for (i in 0 until n) {
            val x = m[3 * i]; val y = m[3 * i + 1]; val z = m[3 * i + 2]
            val d = abs(x - cx) / hw
            val corner = 1f - smooth(0.55f, 1.05f, d)                    // as the jaw weights: the corners stay shut
            project(x, y, z, 0f)
            up[2 * i] = out[0]; up[2 * i + 1] = out[1]
            project(x, y, z, 0.92f * corner * open * JAW_MAX)
            lo[2 * i] = out[0]; lo[2 * i + 1] = out[1]
        }
        path.reset()
        path.moveTo(up[0], up[1])
        for (i in 1 until n) path.lineTo(up[2 * i], up[2 * i + 1])
        for (i in n - 1 downTo 0) path.lineTo(lo[2 * i], lo[2 * i + 1])
        path.close()
        fill.color = ch.mouthColour
        nc.drawPath(path, fill)
        if (ch.teeth) {
            // the upper teeth: a band under the upper lip, a third of the opening at most
            path.reset()
            path.moveTo(up[2], up[3])
            for (i in 1 until n - 1) path.lineTo(up[2 * i], up[2 * i + 1])
            for (i in n - 2 downTo 1) {
                val gap = lo[2 * i + 1] - up[2 * i + 1]
                path.lineTo(up[2 * i], up[2 * i + 1] + min(gap * 0.32f, k * 0.05f))
            }
            path.close()
            fill.color = 0xFFE9E3D8.toInt()
            nc.drawPath(path, fill)
        }
    }

    private fun drawLids(nc: Canvas, a: HoloAvatar) {
        val close = (1f - (1f - a.blink) * a.lids.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        if (close < 0.04f) return
        val seg = 14
        for (e in ch.eyes) {
            val ct = cos(Math.toRadians(e.tilt.toDouble())).toFloat(); val st = sin(Math.toRadians(e.tilt.toDouble())).toFloat()
            val hw = e.hw * 1.12f; val hh = e.hh * 1.18f
            val topX = FloatArray(seg + 1); val topY = FloatArray(seg + 1)
            val edgeX = FloatArray(seg + 1); val edgeY = FloatArray(seg + 1)
            for (s in 0..seg) {
                val th = Math.PI.toFloat() * s / seg
                val lx = hw * cos(th)
                val lyTop = hh * sin(th)
                val lyEdge = hh * sin(th) * (1f - 2f * close)             // the lid's edge sweeps from the top of the eye to its bottom
                project(e.x + lx * ct - lyTop * st, e.y + lx * st + lyTop * ct, e.z, 0f)
                topX[s] = out[0]; topY[s] = out[1]
                project(e.x + lx * ct - lyEdge * st, e.y + lx * st + lyEdge * ct, e.z, 0f)
                edgeX[s] = out[0]; edgeY[s] = out[1]
            }
            path.reset()
            path.moveTo(topX[0], topY[0])
            for (s in 1..seg) path.lineTo(topX[s], topY[s])
            for (s in seg downTo 0) path.lineTo(edgeX[s], edgeY[s])
            path.close()
            // the lid in the light of the face (the texture under it is lit the same way), a shade darker in its crease
            fill.color = shade(e.lid, if (allUnlit) 1f else ch.ambient + (1f - ch.ambient) * 0.82f)
            nc.drawPath(path, fill)
            path.reset()
            path.moveTo(topX[1], topY[1])
            for (s in 2 until seg) path.lineTo(topX[s], topY[s])
            line.color = shade(e.lid, 0.72f) and 0x00FFFFFF or (0x90 shl 24)
            line.strokeWidth = max(1f, k * 0.012f)
            nc.drawPath(path, line)
            // the lashes along the lid's edge
            path.reset()
            path.moveTo(edgeX[0], edgeY[0])
            for (s in 1..seg) path.lineTo(edgeX[s], edgeY[s])
            line.color = ch.lashColour
            line.strokeWidth = max(1.2f, k * (if (allUnlit) 0.016f else 0.008f))      // a drawn lash for the anime faces, a fine one for the real ones
            nc.drawPath(path, line)
        }
    }

    private fun shade(c: Int, f: Float): Int {
        val r = (((c shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val allUnlit = ch.unlit.all { it }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
