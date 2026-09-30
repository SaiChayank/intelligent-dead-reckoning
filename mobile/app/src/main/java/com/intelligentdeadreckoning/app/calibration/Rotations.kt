package com.intelligentdeadreckoning.app.calibration

import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rigid-rotation algebra for the calibration engine.
 *
 * Everything here is pure arithmetic on the contract's own [Vector3] and [Quaternion], so a
 * value computed here is a value the frozen codec accepts. Quaternions use the `wxyz`
 * convention and the Hamilton product, and rotations compose left-to-right in the usual
 * way: `(a * b)` applies `b` first, then `a`.
 *
 * ## Frames
 *
 * | Frame | Axes | Handedness |
 * |---|---|---|
 * | `device` | Android device frame: X right, Y toward the top of the screen, Z out of the screen | right |
 * | `vehicle` | Target vehicle frame: X forward, Y left, Z up | right |
 *
 * [Quaternion] with a `vehicle_from_device` name maps device coordinates to vehicle
 * coordinates: `v_vehicle = q.rotate(v_device)`.
 *
 * The codec deliberately does not normalize a quaternion ("never normalize a quaternion" in
 * `contracts/v1/codec.py`), so unit norm, orthogonality and `det = +1` are this module's
 * responsibility. [isProperRotation] states exactly what every value leaving the calibration
 * engine must satisfy, and the tests assert it.
 */

const val GRAVITY_STANDARD_M_S2: Double = 9.80665

/** Rotate a vector by this unit quaternion: `v' = v + 2w(u x v) + 2u x (u x v)`. */
fun Quaternion.rotate(v: Vector3): Vector3 {
    val u = Vector3(x, y, z)
    val t = u cross v
    val s = u cross t
    return Vector3(
        v.x + 2.0 * (w * t.x + s.x),
        v.y + 2.0 * (w * t.y + s.y),
        v.z + 2.0 * (w * t.z + s.z),
    )
}

/** Hamilton product. `(a * b)` applies `b` first, then `a`. */
operator fun Quaternion.times(other: Quaternion): Quaternion = Quaternion(
    w * other.w - x * other.x - y * other.y - z * other.z,
    w * other.x + x * other.w + y * other.z - z * other.y,
    w * other.y - x * other.z + y * other.w + z * other.x,
    w * other.z + x * other.y - y * other.x + z * other.w,
).normalized()

/** Inverse of a unit quaternion, which is its conjugate. */
fun Quaternion.conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

/** Unit quaternion, or [IllegalArgumentException] if the input is degenerate or non-finite. */
fun Quaternion.normalized(): Quaternion {
    require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
        "non-finite quaternion"
    }
    val n = sqrt(w * w + x * x + y * y + z * z)
    require(n > 1e-12) { "degenerate quaternion" }
    return Quaternion(w / n, x / n, y / n, z / n)
}

/** The same rotation with a non-negative `w`, so two spellings of one rotation compare equal. */
fun Quaternion.canonical(): Quaternion =
    if (w < 0.0 || (w == 0.0 && (x < 0.0 || (x == 0.0 && (y < 0.0 || (y == 0.0 && z < 0.0)))))) {
        Quaternion(-w, -x, -y, -z)
    } else {
        this
    }

/** Row-major 3x3 rotation matrix of this unit quaternion. */
fun Quaternion.toRotationMatrix(): DoubleArray {
    val ww = w * w
    val xx = x * x
    val yy = y * y
    val zz = z * z
    return doubleArrayOf(
        1 - 2 * (yy + zz), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (xx + zz), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (xx + yy),
    )
}

/**
 * The geodesic angle in radians between two orientations, in `[0, pi]`.
 *
 * A rotation and its negation are the same orientation, which the absolute value of the dot
 * product accounts for. This is the number the tests compare against a tolerance, and the
 * number remount detection compares against a threshold.
 */
fun rotationAngleBetween(a: Quaternion, b: Quaternion): Double {
    val dot = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(0.0, 1.0)
    return 2.0 * acos(dot)
}

/**
 * Vehicle-frame roll, pitch and yaw in degrees, from `R = Rz(yaw) * Ry(pitch) * Rx(roll)`.
 *
 * - `yaw` rotates about vehicle Z (up): positive is counter-clockwise seen from above.
 * - `pitch` rotates about vehicle Y (left): positive lowers vehicle X below the horizon.
 * - `roll` rotates about vehicle X (forward): positive lowers vehicle Y (right side down).
 *
 * These are reporting angles for diagnostics and tests, not the internal representation.
 */
data class YawPitchRollDeg(val yaw: Double, val pitch: Double, val roll: Double)

fun Quaternion.toYawPitchRollDeg(): YawPitchRollDeg {
    val m = toRotationMatrix()
    val pitch = kotlin.math.asin((-m[6]).coerceIn(-1.0, 1.0))
    val roll = atan2(m[7], m[8])
    val yaw = atan2(m[3], m[0])
    return YawPitchRollDeg(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll))
}

/** Rotation of [angleRad] about the given axis (normalized internally). Empty axis is rejected. */
fun quaternionFromAxisAngle(axis: Vector3, angleRad: Double): Quaternion {
    val unit = axis.normalizedOrNull() ?: throw IllegalArgumentException("zero rotation axis")
    val half = angleRad / 2.0
    val s = sin(half)
    return Quaternion(cos(half), unit.x * s, unit.y * s, unit.z * s)
}

/**
 * Rotation described by a rotation vector: the direction is the axis and the length is the
 * angle in radians.
 *
 * Unlike [quaternionFromAxisAngle] this accepts a zero vector, which is the common case rather
 * than the exception: a bias-corrected gyroscope at rest reads zero on every axis. The half-angle
 * sine is formed as `sin(|v|/2)/|v|` and the small-angle limit is taken as a series, so nothing
 * divides two quantities that both vanish.
 */
fun quaternionFromRotationVector(v: Vector3): Quaternion {
    val angle = v.norm()
    if (angle < 1e-9) return Quaternion(1.0, v.x * 0.5, v.y * 0.5, v.z * 0.5).normalized()
    val half = angle / 2.0
    val scale = sin(half) / angle
    return Quaternion(cos(half), v.x * scale, v.y * scale, v.z * scale)
}

/** Rotation of [angleRad] about vehicle up (+Z). */
fun quaternionAboutZ(angleRad: Double): Quaternion =
    Quaternion(cos(angleRad / 2.0), 0.0, 0.0, sin(angleRad / 2.0))

/**
 * The shortest rotation taking [from] to [to]. Both must be non-zero.
 *
 * Antiparallel inputs have no unique shortest rotation, so a deterministic perpendicular axis
 * is chosen: the smallest-magnitude coordinate axis crossed with `from`. Determinism matters
 * because the same window must always produce the same calibration.
 */
fun rotationFromTo(from: Vector3, to: Vector3): Quaternion {
    val u = from.normalizedOrNull() ?: throw IllegalArgumentException("zero 'from' vector")
    val v = to.normalizedOrNull() ?: throw IllegalArgumentException("zero 'to' vector")
    val cosine = (u dot v).coerceIn(-1.0, 1.0)
    val axis = u cross v
    return if (axis.norm() > 1e-9) {
        quaternionFromAxisAngle(axis, atan2(axis.norm(), cosine))
    } else if (cosine > 0.0) {
        Quaternion(1.0, 0.0, 0.0, 0.0)
    } else {
        val perpendicular = when {
            abs(u.x) <= abs(u.y) && abs(u.x) <= abs(u.z) -> Vector3(1.0, 0.0, 0.0)
            abs(u.y) <= abs(u.z) -> Vector3(0.0, 1.0, 0.0)
            else -> Vector3(0.0, 0.0, 1.0)
        }
        quaternionFromAxisAngle(u cross perpendicular, Math.PI)
    }
}

/**
 * Whether these components describe a proper rotation: unit norm, orthonormal rows, `det = +1`.
 *
 * `det = +1` is the part that matters most here: a reflection has `det = -1`, is not a
 * rotation, and would silently mirror every position the engine later reports.
 */
fun isProperRotation(q: Quaternion, tolerance: Double = 1e-9): Boolean {
    if (!(q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite())) return false
    val unit = q.canonical()
    if (abs(unit.norm() - 1.0) > tolerance) return false
    val m = unit.toRotationMatrix()
    for (row in 0..2) {
        if (abs(m[row * 3] * m[row * 3] + m[row * 3 + 1] * m[row * 3 + 1] +
                    m[row * 3 + 2] * m[row * 3 + 2] - 1.0) > tolerance) return false
    }
    for (a in 0..2) {
        for (b in a + 1..2) {
            val dot = m[a * 3] * m[b * 3] + m[a * 3 + 1] * m[b * 3 + 1] + m[a * 3 + 2] * m[b * 3 + 2]
            if (abs(dot) > tolerance) return false
        }
    }
    val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6])
    return abs(determinant - 1.0) <= tolerance
}

/** Euclidean norm of this quaternion's four components. */
fun Quaternion.norm(): Double = sqrt(w * w + x * x + y * y + z * z)

/** Euclidean norm of a vector. */
fun Vector3.norm(): Double = sqrt(x * x + y * y + z * z)

infix fun Vector3.dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

infix fun Vector3.cross(other: Vector3): Vector3 = Vector3(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x,
)

operator fun Vector3.plus(other: Vector3): Vector3 =
    Vector3(x + other.x, y + other.y, z + other.z)

operator fun Vector3.minus(other: Vector3): Vector3 =
    Vector3(x - other.x, y - other.y, z - other.z)

operator fun Vector3.times(scale: Double): Vector3 = Vector3(x * scale, y * scale, z * scale)

/** Unit vector in the same direction, or null when the input is zero or non-finite. */
fun Vector3.normalizedOrNull(): Vector3? {
    val n = norm()
    if (!n.isFinite() || n < 1e-12) return null
    return Vector3(x / n, y / n, z / n)
}

/** The component of this vector along [axis], where [axis] is expected to be a unit vector. */
infix fun Vector3.componentAlong(axis: Vector3): Double = this dot axis

/** This vector with its component along [axis] removed. */
fun Vector3.removeComponentAlong(axis: Vector3): Vector3 = this - axis * (this dot axis)

/** Angle in radians between two non-zero vectors, in `[0, pi]`. */
fun angleBetweenVectors(a: Vector3, b: Vector3): Double {
    val ua = a.normalizedOrNull() ?: return 0.0
    val ub = b.normalizedOrNull() ?: return 0.0
    return acos((ua dot ub).coerceIn(-1.0, 1.0))
}

/** Wraps an angle in degrees into `[0, 360)`, the range the frozen codec requires. */
fun normalizeDegrees360(degrees: Double): Double {
    val wrapped = degrees % 360.0
    return if (wrapped < 0.0) wrapped + 360.0 else wrapped
}

/** Smallest signed difference `b - a` in degrees, in `(-180, 180]`. */
fun angularDifferenceDegrees(a: Double, b: Double): Double {
    var difference = (b - a) % 360.0
    if (difference <= -180.0) difference += 360.0
    if (difference > 180.0) difference -= 360.0
    return difference
}
