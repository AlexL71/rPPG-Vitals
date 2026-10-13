package com.rppg.vitals.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iterative radix-2 FFT plus real-signal helpers matching numpy's rfft/irfft. */
object Fft {

    /** In-place complex FFT. Size must be a power of two. Inverse is scaled by 1/n. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "size must be a power of two" }
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        // twiddle table (exact cos/sin, no accumulated error)
        val half = n / 2
        val sign = if (inverse) 1.0 else -1.0
        val cosT = DoubleArray(half) { cos(2.0 * PI * it / n) }
        val sinT = DoubleArray(half) { sign * sin(2.0 * PI * it / n) }
        var len = 2
        while (len <= n) {
            val step = n / len
            val h = len / 2
            var start = 0
            while (start < n) {
                for (k in 0 until h) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = start + k
                    val b = a + h
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n; im[i] /= n
            }
        }
    }

    /** numpy.fft.rfft(x, n): returns (re, im) of length n/2 + 1. x is zero-padded or cut to n. */
    fun rfft(x: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        System.arraycopy(x, 0, re, 0, minOf(x.size, n))
        transform(re, im)
        return re.copyOf(n / 2 + 1) to im.copyOf(n / 2 + 1)
    }

    /** numpy.fft.irfft(X, n) for even n: Hermitian-extend, inverse FFT, real part. */
    fun irfft(specRe: DoubleArray, specIm: DoubleArray, n: Int): DoubleArray {
        val half = n / 2
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0..half) {
            re[k] = specRe[k]
            im[k] = specIm[k]
        }
        im[0] = 0.0      // numpy ignores the imaginary part of DC and Nyquist
        im[half] = 0.0
        for (k in 1 until half) {
            re[n - k] = specRe[k]
            im[n - k] = -specIm[k]
        }
        transform(re, im, inverse = true)
        return re
    }
}
