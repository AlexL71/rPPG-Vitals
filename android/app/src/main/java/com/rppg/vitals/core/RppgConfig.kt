package com.rppg.vitals.core

/** Frequency band in Hz (inclusive). */
data class Band(val lo: Double, val hi: Double)

/**
 * All tunable parameters. Mirrors python/rppg/config.py — keep the defaults in sync.
 */
data class RppgConfig(
    // sampling
    val fs: Double = 30.0,
    val bufferSec: Double = 35.0,
    val maxGapSec: Double = 0.5,
    // heart rate
    val windowSec: Double = 10.0,
    val minWindowSec: Double = 6.0,
    val posWindowSec: Double = 1.6,
    val hrBand: Band = Band(0.7, 3.0),
    val snrBand: Band = Band(0.7, 4.0),
    val nfft: Int = 4096,
    val snrMinDb: Double = 0.0,
    val snrGoodDb: Double = 3.0,
    val smoothN: Int = 5,
    val filterTransitionHz: Double = 0.2,
    // motion
    val motionWindowSec: Double = 1.0,
    val motionMax: Double = 0.04,
    // respiration (experimental)
    val rrBand: Band = Band(0.1, 0.5),
    val rrWindowSec: Double = 30.0,
    val rrMinWindowSec: Double = 15.0,
    val rrSnrMinDb: Double = 0.0,
    // HRV (experimental)
    val hrvWindowSec: Double = 30.0,
    val hrvMinBeats: Int = 15,
    val hrvOutlierFrac: Double = 0.25,
    val hrvBandHalfwidthHz: Double = 0.6,
    // face ROI
    val roiShrink: Double = 0.85,
) {
    init {
        require(nfft > 0 && (nfft and (nfft - 1)) == 0) { "nfft must be a power of two" }
        require(bufferSec >= maxOf(windowSec, rrWindowSec, hrvWindowSec)) {
            "bufferSec must cover the longest analysis window"
        }
    }
}
