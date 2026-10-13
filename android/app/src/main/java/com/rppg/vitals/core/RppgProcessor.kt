package com.rppg.vitals.core

import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Quality { NO_FACE, WARMING_UP, MOVING, POOR, FAIR, GOOD }

/** One analysis result. Nullable fields are absent when they cannot be computed. */
class Estimate(
    val t: Double,
    val dataSec: Double,
    var quality: Quality,
) {
    var hrBpm: Double? = null          // smoothed (median of accepted values); null when POOR
    var hrRawBpm: Double? = null       // spectral peak of this window
    var snrDb: Double? = null
    var motion: Double? = null
    var rrBpm: Double? = null          // respiration (experimental)
    var rrSnrDb: Double? = null
    var rrSource: String? = null       // "motion" or "color"
    var rmssdMs: Double? = null        // HRV (experimental)
    var sdnnMs: Double? = null
    var nBeats: Int? = null
    var waveform: DoubleArray = DoubleArray(0)
}

/**
 * Collects per-frame samples and produces estimates. Port of python/rppg/processor.py.
 *
 * Not thread-safe: call [add] and [estimate] from the same thread (the camera analyzer).
 * Call [estimate] once per second — every accepted value goes into the smoothing history.
 */
class RppgProcessor(val cfg: RppgConfig = RppgConfig()) {

    private class Sample(
        val t: Double, val r: Double, val g: Double, val b: Double,
        val cx: Double, val cy: Double, val size: Double,
    )

    private val buf = ArrayDeque<Sample>()
    private val history = ArrayDeque<Double>()

    fun reset() {
        buf.clear()
        history.clear()
    }

    fun add(t: Double, r: Double, g: Double, b: Double, cx: Double, cy: Double, size: Double) {
        if (buf.isNotEmpty() && t <= buf.last().t) return
        if (buf.isNotEmpty() && t - buf.last().t > cfg.maxGapSec) reset()
        buf.addLast(Sample(t, r, g, b, cx, cy, size))
        while (buf.isNotEmpty() && buf.first().t < t - cfg.bufferSec) buf.removeFirst()
    }

    val dataSec: Double
        get() = if (buf.size > 1) buf.last().t - buf.first().t else 0.0

    fun estimate(now: Double? = null): Estimate {
        if (buf.isEmpty() || (now != null && now - buf.last().t > cfg.maxGapSec)) {
            return Estimate(now ?: 0.0, 0.0, Quality.NO_FACE)
        }
        val tLast = buf.last().t
        val dataSec = dataSec
        if (dataSec < cfg.minWindowSec) return Estimate(tLast, dataSec, Quality.WARMING_UP)

        // resample all channels onto a uniform grid t0 + k/fs
        val t = DoubleArray(buf.size) { buf[it].t }
        val t0 = t[0]
        val n = Dsp.uniformLength(t0, tLast, cfg.fs)
        val tu = DoubleArray(n) { t0 + it / cfg.fs }
        val r = Dsp.interp(tu, t, DoubleArray(buf.size) { buf[it].r })
        val g = Dsp.interp(tu, t, DoubleArray(buf.size) { buf[it].g })
        val b = Dsp.interp(tu, t, DoubleArray(buf.size) { buf[it].b })
        val cx = Dsp.interp(tu, t, DoubleArray(buf.size) { buf[it].cx })
        val cy = Dsp.interp(tu, t, DoubleArray(buf.size) { buf[it].cy })
        val size = Dsp.interp(tu, t, DoubleArray(buf.size) { buf[it].size })

        val pulse = Dsp.pos(r, g, b, cfg.fs, cfg.posWindowSec)
        val nWin = minOf(pulse.size, (cfg.windowSec * cfg.fs).roundToInt())
        val seg = pulse.copyOfRange(pulse.size - nWin, pulse.size)

        val spec = Dsp.powerSpectrum(seg, cfg.fs, cfg.nfft)
        val f0 = Dsp.findPeakHz(spec, cfg.hrBand)
        val est = Estimate(tLast, dataSec, Quality.POOR)
        if (f0 == null) return est
        val halfwidth = 2.0 * cfg.fs / nWin
        est.hrRawBpm = f0 * 60.0
        val snr = Dsp.snrDb(spec, f0, halfwidth, cfg.snrBand)
        est.snrDb = snr

        val nMot = maxOf(2, (cfg.motionWindowSec * cfg.fs).roundToInt())
        val cxT = cx.copyOfRange(cx.size - minOf(nMot, cx.size), cx.size)
        val cyT = cy.copyOfRange(cy.size - minOf(nMot, cy.size), cy.size)
        val meanSize = Dsp.mean(size.copyOfRange(size.size - minOf(nMot, size.size), size.size))
        val motion = if (meanSize > 0) sqrt(Dsp.variance(cxT) + Dsp.variance(cyT)) / meanSize else 0.0
        est.motion = motion

        est.quality = when {
            motion > cfg.motionMax -> Quality.MOVING
            snr >= cfg.snrGoodDb -> Quality.GOOD
            snr >= cfg.snrMinDb -> Quality.FAIR
            else -> Quality.POOR
        }

        if (est.quality == Quality.GOOD || est.quality == Quality.FAIR) {
            history.addLast(f0 * 60.0)
            while (history.size > cfg.smoothN) history.removeFirst()
        }
        if (history.isNotEmpty() && est.quality != Quality.POOR) {
            est.hrBpm = Dsp.median(history.toDoubleArray())
        }

        val wave = Dsp.bandpassFft(seg, cfg.fs, cfg.hrBand, cfg.filterTransitionHz)
        val sd = Dsp.std(wave)
        est.waveform = if (sd > 0) DoubleArray(wave.size) { wave[it] / sd } else wave

        respiration(est, n, g, cy)
        if (est.quality == Quality.GOOD || est.quality == Quality.FAIR) hrv(est, pulse, f0)
        return est
    }

    private fun respiration(est: Estimate, nTotal: Int, green: DoubleArray, cy: DoubleArray) {
        val n = minOf(nTotal, (cfg.rrWindowSec * cfg.fs).roundToInt())
        if (n / cfg.fs < cfg.rrMinWindowSec) return
        val halfwidth = 2.0 * cfg.fs / n
        val g = green.copyOfRange(green.size - n, green.size)
        val gMean = Dsp.mean(g)
        val candidates = ArrayList<Pair<String, DoubleArray>>()
        candidates.add("motion" to cy.copyOfRange(cy.size - n, cy.size))
        if (gMean > 0) candidates.add("color" to DoubleArray(n) { g[it] / gMean })
        var bestRate = 0.0
        var bestSnr = 0.0
        var bestName: String? = null
        for ((name, sig) in candidates) {
            val spec = Dsp.powerSpectrum(sig, cfg.fs, cfg.nfft)
            val f0 = Dsp.findPeakHz(spec, cfg.rrBand) ?: continue
            val snr = Dsp.snrDb(spec, f0, halfwidth, cfg.rrBand)
            if (bestName == null || snr > bestSnr) {
                bestRate = f0 * 60.0
                bestSnr = snr
                bestName = name
            }
        }
        if (bestName != null && bestSnr >= cfg.rrSnrMinDb) {
            est.rrBpm = bestRate
            est.rrSnrDb = bestSnr
            est.rrSource = bestName
        }
    }

    private fun hrv(est: Estimate, pulse: DoubleArray, f0: Double) {
        val n = (cfg.hrvWindowSec * cfg.fs).roundToInt()
        if (pulse.size < n) return
        // narrow band around the current heart rate: removes noise that creates extra/missed beats
        val band = Band(maxOf(cfg.hrBand.lo, f0 - cfg.hrvBandHalfwidthHz), minOf(cfg.hrBand.hi, f0 + cfg.hrvBandHalfwidthHz))
        val y = Dsp.bandpassFft(pulse.copyOfRange(pulse.size - n, pulse.size), cfg.fs, band, cfg.filterTransitionHz)
        val beats = Dsp.detectBeats(y, cfg.fs, f0)
        val res = Dsp.hrvFromBeats(beats, cfg.hrvMinBeats, cfg.hrvOutlierFrac) ?: return
        est.rmssdMs = res.rmssdMs
        est.sdnnMs = res.sdnnMs
        est.nBeats = res.nBeats
    }
}
