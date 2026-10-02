package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fixed-size dense linear algebra for the error covariance.
 *
 * The filter keeps one symmetric positive-definite 15x15 matrix and never needs a general
 * solver: every measurement is one to three rows, so the only system that has to be solved is
 * the innovation covariance, which is at most 3x3. Keeping the primitives here, with the
 * dimensions passed explicitly, means no allocation of objects in the 100 Hz path and no
 * dependency on a matrix library. Everything is row-major in a flat [DoubleArray].
 */

/** `n x n` identity. */
internal fun identity(n: Int): DoubleArray {
    val out = DoubleArray(n * n)
    for (i in 0 until n) out[i * n + i] = 1.0
    return out
}

/** `a * b`, both `n x n`. */
internal fun multiplyMatrix(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray {
    val out = DoubleArray(n * n)
    for (i in 0 until n) {
        val rowOffset = i * n
        for (k in 0 until n) {
            val aik = a[rowOffset + k]
            if (aik == 0.0) continue
            val kOffset = k * n
            for (j in 0 until n) {
                out[rowOffset + j] += aik * b[kOffset + j]
            }
        }
    }
    return out
}

/** `a * b` transposed: `a * b^T`, both `n x n`. */
internal fun multiplyByTranspose(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray {
    val out = DoubleArray(n * n)
    for (i in 0 until n) {
        val rowOffset = i * n
        for (j in 0 until n) {
            val columnOffset = j * n
            var sum = 0.0
            for (k in 0 until n) sum += a[rowOffset + k] * b[columnOffset + k]
            out[rowOffset + j] = sum
        }
    }
    return out
}

/** Matrix-vector product, `n x n` times length `n`. */
internal fun multiplyVector(a: DoubleArray, v: DoubleArray, n: Int): DoubleArray {
    val out = DoubleArray(n)
    for (i in 0 until n) {
        val rowOffset = i * n
        var sum = 0.0
        for (j in 0 until n) sum += a[rowOffset + j] * v[j]
        out[i] = sum
    }
    return out
}

/** Transpose of an `n x n` matrix. */
internal fun transpose(a: DoubleArray, n: Int): DoubleArray {
    val out = DoubleArray(n * n)
    for (i in 0 until n) {
        for (j in 0 until n) out[i * n + j] = a[j * n + i]
    }
    return out
}

/** Element-wise `a + b`, both `n x n`. */
internal fun add(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(a.size)
    for (i in a.indices) out[i] = a[i] + b[i]
    return out
}

/** Scalar multiple of a row-major 3x3 matrix. */
internal fun scale3(m: DoubleArray, factor: Double): DoubleArray {
    val out = DoubleArray(9)
    for (i in 0..8) out[i] = m[i] * factor
    return out
}

/** Plain dot product of two length-`n` vectors. */
internal fun dot(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

/** `q^T * p * q`, computed as `q . (p q)` so only one matrix-vector product is needed. */
internal fun quadratic(q: DoubleArray, p: DoubleArray, n: Int): Double = dot(q, multiplyVector(p, q, n))

/** `(p + p^T) / 2`, which removes the asymmetry that repeated floating-point updates leave. */
internal fun symmetrize(p: DoubleArray, n: Int): DoubleArray {
    val out = DoubleArray(n * n)
    for (i in 0 until n) {
        for (j in i until n) {
            val value = 0.5 * (p[i * n + j] + p[j * n + i])
            out[i * n + j] = value
            out[j * n + i] = value
        }
    }
    return out
}

/** Largest `|p[i][j] - p[j][i]|`. A covariance that is not symmetric is not a covariance. */
internal fun maxAsymmetry(p: DoubleArray, n: Int): Double {
    var worst = 0.0
    for (i in 0 until n) {
        for (j in i until n) {
            worst = maxOf(worst, abs(p[i * n + j] - p[j * n + i]))
        }
    }
    return worst
}

/** Smallest diagonal entry, which must stay positive for the covariance to be usable. */
internal fun minDiagonal(p: DoubleArray, n: Int): Double {
    var smallest = Double.POSITIVE_INFINITY
    for (i in 0 until n) smallest = minOf(smallest, p[i * n + i])
    return smallest
}

/** A covariance is usable only when it is finite, symmetric, and positive definite. */
internal fun isUsableCovariance(p: DoubleArray, n: Int): Boolean {
    if (n <= 0 || p.size != n * n || p.any { !it.isFinite() }) return false
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val a = p[i * n + j]
            val b = p[j * n + i]
            val tolerance = 1e-8 * maxOf(1.0, abs(a), abs(b))
            if (abs(a - b) > tolerance) return false
        }
    }
    // Cholesky is both the positive-definiteness test and the factorization the measurement gate
    // will eventually need. Do not treat positive diagonal entries alone as a valid covariance.
    val lower = DoubleArray(n * n)
    for (i in 0 until n) {
        for (j in 0..i) {
            var sum = p[i * n + j]
            for (k in 0 until j) sum -= lower[i * n + k] * lower[j * n + k]
            if (i == j) {
                if (!(sum > 0.0) || !sum.isFinite()) return false
                lower[i * n + j] = sqrt(sum)
            } else {
                val value = sum / lower[j * n + j]
                if (!value.isFinite()) return false
                lower[i * n + j] = value
            }
        }
    }
    return true
}

/**
 * Solve `a x = b` for a small symmetric positive-definite `a` by Cholesky decomposition.
 *
 * Returns null when `a` is not positive-definite, which the caller treats as a rejection
 * rather than a reason to trust a division: an innovation covariance that failed to decompose
 * means the measurement cannot be gated meaningfully.
 */
internal fun choleskySolve(a: DoubleArray, k: Int, b: DoubleArray): DoubleArray? {
    val lower = DoubleArray(k * k)
    for (i in 0 until k) {
        for (j in 0..i) {
            var sum = a[i * k + j]
            for (m in 0 until j) sum -= lower[i * k + m] * lower[j * k + m]
            if (i == j) {
                if (!(sum > 0.0) || !sum.isFinite()) return null
                lower[i * k + j] = sqrt(sum)
            } else {
                lower[i * k + j] = sum / lower[j * k + j]
            }
        }
    }
    // Forward substitution for L y = b.
    val y = DoubleArray(k)
    for (i in 0 until k) {
        var sum = b[i]
        for (j in 0 until i) sum -= lower[i * k + j] * y[j]
        y[i] = sum / lower[i * k + i]
    }
    // Back substitution for L^T x = y.
    val x = DoubleArray(k)
    for (i in k - 1 downTo 0) {
        var sum = y[i]
        for (j in i + 1 until k) sum -= lower[j * k + i] * x[j]
        x[i] = sum / lower[i * k + i]
    }
    return x
}

/** Skew-symmetric cross-product matrix of a vector, row-major 3x3. */
internal fun skew(v: Vector3): DoubleArray =
    doubleArrayOf(0.0, -v.z, v.y, v.z, 0.0, -v.x, -v.y, v.x, 0.0)

/** Write a row-major 3x3 block into a larger `n x n` matrix at block offsets `(bi, bj)`. */
internal fun setBlock(target: DoubleArray, n: Int, bi: Int, bj: Int, block: DoubleArray) {
    for (i in 0..2) {
        for (j in 0..2) target[(bi + i) * n + (bj + j)] = block[i * 3 + j]
    }
}

/** Add a row-major 3x3 block into a larger `n x n` matrix at block offsets `(bi, bj)`. */
internal fun addBlock(target: DoubleArray, n: Int, bi: Int, bj: Int, block: DoubleArray) {
    for (i in 0..2) {
        for (j in 0..2) target[(bi + i) * n + (bj + j)] += block[i * 3 + j]
    }
}

/** Read a row-major 3x3 block out of a larger `n x n` matrix. */
internal fun block(source: DoubleArray, n: Int, bi: Int, bj: Int): DoubleArray {
    val out = DoubleArray(9)
    for (i in 0..2) {
        for (j in 0..2) out[i * 3 + j] = source[(bi + i) * n + (bj + j)]
    }
    return out
}

/** Transpose of a row-major 3x3 matrix. */
internal fun transpose3(m: DoubleArray): DoubleArray =
    doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

/** `m * v` for a row-major 3x3 matrix. */
internal fun apply3(m: DoubleArray, v: Vector3): Vector3 = Vector3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z,
)

/** `m * v` for a column vector stored as a length-3 array. */
internal fun apply3(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
    m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
    m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
    m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
)
