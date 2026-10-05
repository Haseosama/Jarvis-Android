package com.jarvis.android.ar

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where everything goes on the screen this frame: the face's square view ([left], [top], [side] in pixels), the shadow on the table
 * (centre, width, height), and the body under the face ([body]).
 */
internal class ArPlacement(
    val left: Float, val top: Float, val side: Float,
    val shadowX: Float, val shadowY: Float, val shadowWidth: Float, val shadowHeight: Float,
    val body: BodyDrawList,
) {
    /** The same moved by ([dx], [dy]) pixels. */
    fun shifted(dx: Float, dy: Float): ArPlacement {
        val moved = body.pos.copyOf()
        for (i in moved.indices step 2) { moved[i] += dx; moved[i + 1] += dy }
        return ArPlacement(left + dx, top + dy, side, shadowX + dx, shadowY + dy, shadowWidth, shadowHeight, BodyDrawList(moved, body.part, body.light))
    }
}

/** Haseo standing somewhere: his look (see [ArLook]) and his body (see [ArBody]), moved on and placed a frame at a time. */
internal class ArStage {
    private val look = ArLook()
    private val body = ArBody()

    /** What [com.jarvis.android.avatar.HoloAvatar.aim] takes, from the last [frame]; null before the first. */
    var aim: FloatArray? = null
        private set

    /** Someone put him down somewhere else: his body faces the camera again. */
    fun reset() = look.reset()

    /**
     * One frame, [dt] seconds after the last, the voice at [level]: Haseo's soles at ([ax], [ay], [az]) in world metres (y up), the camera
     * at ([ex], [ey], [ez]) seeing through [view] and [projection] (column-major) onto a [width] × [height] screen. Null when he is
     * behind the camera.
     */
    fun frame(
        dt: Float, level: Float, view: FloatArray, projection: FloatArray, width: Int, height: Int,
        ax: Float, ay: Float, az: Float, ex: Float, ey: Float, ez: Float,
    ): ArPlacement? {
        val headY = ay - BODY_FEET * AR_UNIT
        aim = look.step(dt, ax, headY, az, ex, ey, ez)
        body.step(dt, level)
        val facing = look.facing ?: 0f
        val drawn = bodyDrawList(body.build(), ax, ay, az, facing, AR_UNIT, view, projection, width, height) ?: return null
        val head = projectPoint(view, projection, ax, headY, az, width, height) ?: return null
        val foot = projectPoint(view, projection, ax, ay, az, width, height) ?: return null
        val square = faceSquare(head, AR_UNIT, maxOf(width, height) * 3f)
        // the shadow: a disc on the table seen from the camera's height, flatter the lower the camera
        val squash = sin(atan2(ey - ay, hypot(ex - ax, ez - az))).coerceIn(0.2f, 1f)
        val shadow = SHADOW_UNITS * AR_UNIT * foot.scale
        return ArPlacement(square[0], square[1], square[2], foot.x, foot.y, shadow, shadow * squash, drawn)
    }

    companion object {
        const val SHADOW_UNITS = 3.6f
    }
}

/** A camera at ([ex], [ey], [ez]) looking at ([tx], [ty], [tz]), y up: its view matrix, column-major as ARCore's. */
internal fun lookAt(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float): FloatArray {
    val f = normalise(floatArrayOf(tx - ex, ty - ey, tz - ez))
    val s = normalise(cross(f, floatArrayOf(0f, 1f, 0f)))
    val u = cross(s, f)
    return floatArrayOf(
        s[0], u[0], -f[0], 0f,
        s[1], u[1], -f[1], 0f,
        s[2], u[2], -f[2], 0f,
        -(s[0] * ex + s[1] * ey + s[2] * ez), -(u[0] * ex + u[1] * ey + u[2] * ez), f[0] * ex + f[1] * ey + f[2] * ez, 1f,
    )
}

/** A perspective projection with a vertical field of [fovY] radians, column-major as ARCore's. */
internal fun perspective(fovY: Float, aspect: Float, near: Float = 0.05f, far: Float = 50f): FloatArray {
    val f = 1f / tan(fovY / 2f)
    return FloatArray(16).also {
        it[0] = f / aspect; it[5] = f
        it[10] = (far + near) / (near - far); it[11] = -1f
        it[14] = 2f * far * near / (near - far)
    }
}
