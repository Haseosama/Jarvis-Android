package com.jarvis.android.ar

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The head's half-height on the table, in metres, and so the unit the body is built in (see ArBody): a head about 7 cm tall on a
 * figure about 40 cm tall.
 */
internal const val AR_UNIT = 0.035f

/**
 * Where a world point falls on the screen: [x], [y] in pixels (y down), [depth] in metres in front of the camera, and [scale] the pixels
 * one metre spans at that depth.
 */
internal data class ScreenPoint(val x: Float, val y: Float, val depth: Float, val scale: Float)

/**
 * Projects the world point ([px], [py], [pz]) through ARCore's column-major [view] and [projection] matrices onto a [width] × [height]
 * screen; null when the point is behind the camera (or right on it).
 */
internal fun projectPoint(view: FloatArray, projection: FloatArray, px: Float, py: Float, pz: Float, width: Int, height: Int): ScreenPoint? {
    // eye coordinates: the camera looks down its -z
    val ex = view[0] * px + view[4] * py + view[8] * pz + view[12]
    val ey = view[1] * px + view[5] * py + view[9] * pz + view[13]
    val ez = view[2] * px + view[6] * py + view[10] * pz + view[14]
    val depth = -ez
    if (depth <= 0.01f) return null
    val cx = projection[0] * ex + projection[4] * ey + projection[8] * ez + projection[12]
    val cy = projection[1] * ex + projection[5] * ey + projection[9] * ez + projection[13]
    val cw = projection[3] * ex + projection[7] * ey + projection[11] * ez + projection[15]
    if (cw <= 0f) return null
    val x = (cx / cw + 1f) * 0.5f * width
    val y = (1f - cy / cw) * 0.5f * height
    // the projection's vertical focal (its [5]) turns a metre at that depth into a share of half the screen's height
    val scale = projection[5] * 0.5f * height / depth
    return ScreenPoint(x, y, depth, scale)
}

/** The angle from [from] to [to], wrapped into -π..π. */
internal fun angleBetween(from: Float, to: Float): Float {
    var d = (to - from) % (2f * PI.toFloat())
    if (d > PI) d -= 2f * PI.toFloat()
    if (d < -PI) d += 2f * PI.toFloat()
    return d
}

/**
 * The face standing on a table turning to whoever holds the phone. Its body faces where the camera was when it was put down, and
 * follows the camera lazily as you walk round it: so when you move, the head turns towards you a moment late (you see it turned away a
 * little, then catching up), and its eyes, quicker, stay on you. The camera above the head makes it lift its face to you.
 *
 * [step] takes the head's centre and the camera's position in world metres (y up), and gives what [com.jarvis.android.avatar.HoloAvatar.aim]
 * takes: the yaw and pitch the drawn head shows towards the viewer, and where its eyes look, each -1..1.
 */
internal class ArLook {
    /** The world angle (around the vertical, as atan2(x, z)) the body faces, or null until the first step. */
    var facing: Float? = null
        private set

    fun step(dt: Float, hx: Float, hy: Float, hz: Float, cx: Float, cy: Float, cz: Float): FloatArray {
        val dx = cx - hx; val dy = cy - hy; val dz = cz - hz
        val flat = sqrt(dx * dx + dz * dz)
        val toCamera = atan2(dx, dz)
        val f = facing ?: toCamera
        // the body catches up with the camera: slowly, so moving round it shows the head turn
        val next = f + angleBetween(f, toCamera) * (1f - exp(-dt.coerceAtLeast(0f) / BODY_TAU))
        facing = next
        // seen from the camera, a face whose front is turned by this much from it shows that much yaw (positive: to the screen's right)
        val seenYaw = angleBetween(toCamera, next)
        // the neck takes most of it at once; what is left shows
        val yaw = (seenYaw * (1f - NECK_SHARE)).coerceIn(-MAX_YAW, MAX_YAW)
        // a camera above the head sees it from above, as if it looked down (positive pitch); it lifts its face most of the way
        val elevation = atan2(dy, flat.coerceAtLeast(0.001f))
        val pitch = (elevation * (1f - LIFT_SHARE)).coerceIn(-MAX_PITCH, MAX_PITCH)
        // the eyes make up the rest, to meet the camera
        val gazeX = (-yaw / EYE_REACH_YAW).coerceIn(-1f, 1f)
        val gazeY = (-pitch / EYE_REACH_PITCH).coerceIn(-1f, 1f)
        return floatArrayOf(yaw, pitch, gazeX, gazeY)
    }

    /** Forgets where the body faced: the head was put down somewhere else. */
    fun reset() { facing = null }

    companion object {
        const val BODY_TAU = 0.9f          // seconds for the body to make up about two thirds of a turn
        const val NECK_SHARE = 0.55f       // the share of a turn the neck makes at once
        const val LIFT_SHARE = 0.6f        // the share of the camera's height the face lifts to
        const val MAX_YAW = 0.7f
        const val MAX_PITCH = 0.35f
        const val EYE_REACH_YAW = 0.35f    // the head turn the eyes' full reach makes up for
        const val EYE_REACH_PITCH = 0.25f
    }
}

/**
 * Where to lay the face's square view so its head, [half] metres in half-height, stands on [head] (the head's centre projected on the
 * screen): the square's left, top and side in pixels. The face view draws the head 0.36 of its side high (in half-heights) with its
 * centre at 0.44 of its height (see AvatarView's headRadius and headCentre); the side is kept under [maxSide].
 */
internal fun faceSquare(head: ScreenPoint, half: Float, maxSide: Float): FloatArray {
    val side = (half * head.scale / FACE_HEAD_SHARE).coerceIn(1f, maxSide)
    return floatArrayOf(head.x - side / 2f, head.y - FACE_CENTRE_SHARE * side, side)
}

internal const val FACE_HEAD_SHARE = 0.36f
internal const val FACE_CENTRE_SHARE = 0.44f
