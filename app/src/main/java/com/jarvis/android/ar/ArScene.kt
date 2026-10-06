package com.jarvis.android.ar

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where everything goes on the screen this frame: the shadow on the table (centre, width, height) and the robot over it ([body]).
 */
internal class ArPlacement(
    val shadowX: Float, val shadowY: Float, val shadowWidth: Float, val shadowHeight: Float,
    val body: FigureDrawList,
) {
    /** The same moved by ([dx], [dy]) pixels. */
    fun shifted(dx: Float, dy: Float) = ArPlacement(shadowX + dx, shadowY + dy, shadowWidth, shadowHeight, body.shifted(dx, dy))
}

/**
 * The robot standing somewhere: where it faces (see [ArLook]: its body follows the camera lazily as you walk round it) and how it moves
 * (see [ArRobot]: its head turns to you at once, and it steps while its body turns), placed a frame at a time.
 */
internal class ArStage(model: RobotModel) {
    private val look = ArLook()
    private val robot = ArRobot(model)
    private var lastFacing: Float? = null

    /** Someone put it down somewhere else: its body faces the camera again, and it waves. */
    fun reset() { look.reset(); lastFacing = null; robot.greet() }

    /**
     * One frame, [dt] seconds after the last, the voice at [level]: the robot's soles at ([ax], [ay], [az]) in world metres (y up),
     * the camera at ([ex], [ey], [ez]) seeing through [view] and [projection] (column-major) onto a [width] × [height] screen, drawn
     * in [style]. Null when it is behind the camera.
     */
    fun frame(
        dt: Float, level: Float, view: FloatArray, projection: FloatArray, width: Int, height: Int,
        ax: Float, ay: Float, az: Float, ex: Float, ey: Float, ez: Float, style: RobotLook = RobotLook.DEFAULT,
    ): ArPlacement? {
        val headY = ay + (HEAD_Y - ROBOT_FEET) * ROBOT_UNIT
        look.step(dt, ax, headY, az, ex, ey, ez)
        val facing = look.facing ?: 0f
        val turn = lastFacing?.let { if (dt > 0f) angleBetween(it, facing) / dt else 0f } ?: 0f
        lastFacing = facing
        // the head turns to the camera from where the body faces, and lifts to it when it is above
        val toCamera = atan2(ex - ax, ez - az)
        val elevation = atan2(ey - headY, hypot(ex - ax, ez - az))
        robot.step(dt, level, turn, angleBetween(facing, toCamera), elevation * 0.8f)
        val drawn = robotDrawList(robot.build(style.level), ax, ay, az, facing, ROBOT_UNIT, view, projection, width, height, style)
            ?: return null
        val foot = projectPoint(view, projection, ax, ay, az, width, height) ?: return null
        // the shadow: a disc on the table seen from the camera's height, flatter the lower the camera
        val squash = sin(atan2(ey - ay, hypot(ex - ax, ez - az))).coerceIn(0.2f, 1f)
        val shadow = SHADOW_UNITS * ROBOT_UNIT * foot.scale
        return ArPlacement(foot.x, foot.y, shadow, shadow * squash, drawn)
    }

    companion object {
        const val SHADOW_UNITS = 1.5f
        /** The middle of its head, in its units. */
        const val HEAD_Y = 0.45f
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
