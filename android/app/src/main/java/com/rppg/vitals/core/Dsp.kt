package com.rppg.vitals.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Signal processing for rPPG. 1:1 port of python/rppg/dsp.py (same names, same order
 * of operations) so the golden-file test can compare both implementations.
 */
object Dsp {

    class Spectrum(val freqs: DoubleArray, val power: DoubleArray)

    class Hrv(val rmssdMs: Double, val sdnnMs: Double, val nBeats: Int, val meanHrBpm: Double)

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p *= 2
        return p
    }

    /** Number of uniform samples between t[0] and t[last] at rate fs. */
    fun uniformLength(t0: Double, tLast: Double, fs: Double): Int = floor((tLast - t0) * fs).toInt() + 1

    /** numpy.interp of one channel onto tu (tu must be increasing and inside [t0, tLast]). */
    fun interp(tu: DoubleArray, t: DoubleArray, x: DoubleArray): DoubleArray {
        val out = DoubleArray(tu.size)
        var j = 0
        for (i in tu.indices) {
            val q = tu[i]
            if (q <= t[0]) { out[i] = x[0]; continue }
            if (q >= t[t.size - 1]) { out[i] = x[x.size - 1]; continue }
            while (j < t.size - 2 && t[j + 1] < q) j++
            val dt = t[j + 1] - t[j]
            out[i] = if (dt > 0) x[j] + (x[j + 1] - x[j]) * (q - t[j]) / dt else x[j]
        }
        return out
    }

    /** POS pulse extraction (Wang et al. 2017). r, g, b are uniformly sampled. */
    fun pos(r: DoubleArray, g: DoubleArray, b: DoubleArray, fs: Double, winSec: Double): DoubleArray {
        val n = r.size
        val win = (winSec * fs).roundToInt()
        val out = DoubleArray(n)
        if (n < win || win < 2) return out
        val s1 = DoubleArray(win)
        val s2 = DoubleArray(win)
        for (start in 0..n - win) {
            var mr = 0.0; var mg = 0.0; var mb = 0.0
            for (k in start until start + win) { mr += r[k]; mg += g[k]; mb += b[k] }
            mr /= win; mg /= win; mb /= win
            if (mr <= 0 || mg <= 0 || mb <= 0) continue
            for (k in 0 until win) {
                val cr = r[start + k] / mr
                val cg = g[start + k] / mg
                val cb = b[start + k] / mb
                s1[k] = cg - cb
                s2[k] = -2.0 * cr + cg + cb
            }
            val std2 = std(s2)
            val alpha = if (std2 > 1e-12) std(s1) / std2 else 0.0
            var hMean = 0.0
            for (k in 0 until win) hMean += s1[k] + alpha * s2[k]
            hMean /= win
            for (k in 0 until win) out[start + k] += s1[k] + alpha * s2[k] - hMean
        }
        return out
    }

    fun mean(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.sum() / x.size

    /** Population standard deviation (numpy default ddof=0). */
    fun std(x: DoubleArray, ddof: Int = 0): Double {
        val m = mean(x)
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / (x.size - ddof))
    }

    fun variance(x: DoubleArray): Double = std(x).let { it * it }

    fun median(x: DoubleArray): Double {
        val s = x.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    fun detrendLinear(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return DoubleArray(n) { x[it] - mean(x) }
        val tm = (n - 1) / 2.0
        val xm = mean(x)
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - tm) * (x[i] - xm)
            den += (i - tm) * (i - tm)
        }
        val slope = num / den
        return DoubleArray(n) { x[it] - (xm + slope * (it - tm)) }
    }

    fun hann(n: Int): DoubleArray =
        if (n == 1) doubleArrayOf(1.0) else DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }

    fun powerSpectrum(x: DoubleArray, fs: Double, nfft: Int): Spectrum {
        val d = detrendLinear(x)
        val w = hann(d.size)
        for (i in d.indices) d[i] *= w[i]
        val nFft = maxOf(nfft, nextPow2(d.size))
        val (re, im) = Fft.rfft(d, nFft)
        val p = DoubleArray(re.size) { re[it] * re[it] + im[it] * im[it] }
        val f = DoubleArray(re.size) { it * fs / nFft }
        return Spectrum(f, p)
    }

    fun parabolicOffset(a: Double, b: Double, c: Double): Double {
        val denom = a - 2.0 * b + c
        if (denom == 0.0) return 0.0
        return (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
    }

    /** Highest true local maximum inside band (drift rising toward a band edge is ignored). */
    fun findPeakHz(s: Spectrum, band: Band): Double? {
        val p = s.power
        var k = -1
        for (i in 1 until p.size - 1) {
            if (s.freqs[i] < band.lo || s.freqs[i] > band.hi) continue
            if (p[i] > p[i - 1] && p[i] >= p[i + 1] && (k < 0 || p[i] > p[k])) k = i
        }
        if (k < 0) return null
        return s.freqs[k] + parabolicOffset(p[k - 1], p[k], p[k + 1]) * (s.freqs[1] - s.freqs[0])
    }

    fun snrDb(s: Spectrum, f0: Double, halfwidth: Double, band: Band): Double {
        var ps = 0.0
        var pn = 0.0
        for (i in s.freqs.indices) {
            val f = s.freqs[i]
            if (f < band.lo || f > band.hi) continue
            if (abs(f - f0) <= halfwidth || abs(f - 2.0 * f0) <= halfwidth) ps += s.power[i] else pn += s.power[i]
        }
        if (ps <= 0.0) return -99.0
        if (pn <= 0.0) return 99.0
        return 10.0 * log10(ps / pn)
    }

    /** Zero-phase FFT band-pass with cosine-tapered edges. */
    fun bandpassFft(x: DoubleArray, fs: Double, band: Band, transitionHz: Double): DoubleArray {
        val d = detrendLinear(x)
        val n = d.size
        if (n < 4) return DoubleArray(n)
        val nFft = nextPow2(2 * n)
        val (re, im) = Fft.rfft(d, nFft)
        val lo = band.lo
        val hi = band.hi
        val tw = transitionHz
        for (k in re.indices) {
            val f = k * fs / nFft
            val gain = when {
                f >= lo && f <= hi -> 1.0
                f > lo - tw && f < lo -> 0.5 - 0.5 * cos(PI * (f - (lo - tw)) / tw)
                f > hi && f < hi + tw -> 0.5 + 0.5 * cos(PI * (f - hi) / tw)
                else -> 0.0
            }
            re[k] *= gain
            im[k] *= gain
        }
        return Fft.irfft(re, im, nFft).copyOf(n)
    }

    /** Beat times (s) = positive local maxima that are the max within +-0.6 beat periods. */
    fun detectBeats(y: DoubleArray, fs: Double, hrHz: Double): DoubleArray {
        val n = y.size
        if (n < 3 || hrHz <= 0) return DoubleArray(0)
        val half = maxOf(1, floor(0.6 * fs / hrHz).toInt())
        val idx = ArrayList<Int>()
        val times = ArrayList<Double>()
        for (i in 1 until n - 1) {
            if (y[i] <= 0 || y[i] < y[i - 1] || y[i] < y[i + 1]) continue
            val lo = maxOf(0, i - half)
            val hi = minOf(n, i + half + 1)
            var mx = y[lo]
            for (k in lo until hi) if (y[k] > mx) mx = y[k]
            if (y[i] < mx) continue
            if (idx.isNotEmpty() && i - idx.last() <= half) continue
            idx.add(i)
            times.add(i + parabolicOffset(y[i - 1], y[i], y[i + 1]))
        }
        return DoubleArray(times.size) { times[it] / fs }
    }

    fun hrvFromBeats(beatTimes: DoubleArray, minBeats: Int, outlierFrac: Double): Hrv? {
        if (beatTimes.size < minBeats) return null
        val ibi = DoubleArray(beatTimes.size - 1) { beatTimes[it + 1] - beatTimes[it] }
        val med = median(ibi)
        val ok = BooleanArray(ibi.size) { abs(ibi[it] - med) <= outlierFrac * med }
        if (ok.count { it } < minBeats - 1) return null
        val good = ibi.filterIndexed { i, _ -> ok[i] }.toDoubleArray()
        val diffs = ArrayList<Double>()
        for (i in 0 until ibi.size - 1) if (ok[i] && ok[i + 1]) diffs.add(ibi[i + 1] - ibi[i])
        if (diffs.size < 2) return null
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size) * 1000.0
        return Hrv(rmssd, std(good, ddof = 1) * 1000.0, beatTimes.size, 60.0 / mean(good))
    }
}
