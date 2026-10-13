package com.rppg.vitals.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class DspTest {
    private val fs = 30.0
    private val pbv = doubleArrayOf(0.33, 0.77, 0.53)

    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val x = DoubleArray(n) { sin(0.3 * it) + 0.5 * cos(1.7 * it) + it * 0.01 }
        val (re, im) = Fft.rfft(x, n)
        for (k in 0..n / 2) {
            var sr = 0.0; var si = 0.0
            for (j in 0 until n) { sr += x[j] * cos(2 * PI * k * j / n); si -= x[j] * sin(2 * PI * k * j / n) }
            assertEquals(sr, re[k], 1e-9)
            assertEquals(si, im[k], 1e-9)
        }
        assertArrayEquals(x, Fft.irfft(re, im, n), 1e-9)
    }

    @Test
    fun posRecoversPulseUnderLightingFlicker() {
        val n = 300
        val r = DoubleArray(n); val g = DoubleArray(n); val b = DoubleArray(n)
        for (i in 0 until n) {
            val t = i / fs
            val light = 1.0 + 0.05 * sin(2 * PI * 0.9 * t)
            val p = sin(2 * PI * 1.5 * t)
            r[i] = 170 * light * (1 + 0.004 * p * pbv[0])
            g[i] = 120 * light * (1 + 0.004 * p * pbv[1])
            b[i] = 100 * light * (1 + 0.004 * p * pbv[2])
        }
        val green = Dsp.findPeakHz(Dsp.powerSpectrum(g, fs, 4096), Band(0.7, 3.0))!!
        assertEquals(0.9, green, 0.02)   // green channel alone is fooled by the light
        val pos = Dsp.findPeakHz(Dsp.powerSpectrum(Dsp.pos(r, g, b, fs, 1.6), fs, 4096), Band(0.7, 3.0))!!
        assertEquals(1.5, pos, 0.02)     // POS finds the pulse
    }

    @Test
    fun peakIgnoresDriftAtBandEdge() {
        val f = DoubleArray(2049) { it * 15.0 / 2048 }
        val p = DoubleArray(f.size) {
            100.0 / (1.0 + (f[it] / 0.2).let { x -> x * x }) + 2.0 * exp(-0.5 * ((f[it] - 1.2) / 0.05).let { x -> x * x })
        }
        assertEquals(1.2, Dsp.findPeakHz(Dsp.Spectrum(f, p), Band(0.7, 3.0))!!, 0.01)
    }

    @Test
    fun beatsAndHrvOnKnownRhythm() {
        val pattern = doubleArrayOf(0.80, 0.84, 0.78, 0.86)
        val ibi = DoubleArray(40) { pattern[it % 4] }
        val beats = DoubleArray(41)
        beats[0] = 0.5
        for (i in ibi.indices) beats[i + 1] = beats[i] + ibi[i]
        val n = ((beats.last() + 0.5) * fs).toInt()
        val y = DoubleArray(n) { i -> beats.sumOf { b -> exp(-0.5 * ((i / fs - b) / 0.08).let { it * it }) } }
        val found = Dsp.detectBeats(Dsp.bandpassFft(y, fs, Band(0.7, 3.0), 0.2), fs, 1 / ibi.average())
        assertEquals(beats.size, found.size)
        for (i in beats.indices) assertEquals(beats[i], found[i], 0.02)
        val hrv = Dsp.hrvFromBeats(found, 15, 0.25)!!
        var sq = 0.0
        for (i in 0 until ibi.size - 1) sq += (ibi[i + 1] - ibi[i]).let { it * it }
        val trueRmssd = kotlin.math.sqrt(sq / (ibi.size - 1)) * 1000
        assertEquals(trueRmssd, hrv.rmssdMs, 0.2 * trueRmssd)
    }

    @Test
    fun rotationMappingRoundTrip() {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val (su, sv) = FaceRoi.uprightToSensor(0.2, 0.7, rot)
            val (u, v) = FaceRoi.sensorToUpright(su, sv, rot)
            assertEquals(0.2, u, 1e-12)
            assertEquals(0.7, v, 1e-12)
        }
        // 90 deg clockwise: sensor top-left corner becomes upright top-right corner
        assertEquals(1.0 to 0.0, FaceRoi.sensorToUpright(0.0, 0.0, 90))
    }

    @Test
    fun meanRgbInsidePolygonOnly() {
        val w = 20; val h = 10; val stride = w * 4 + 8   // padded rows like a real camera plane
        val buf = ByteBuffer.allocate(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val off = y * stride + x * 4
            val inside = x in 5..9 && y in 2..6
            buf.put(off, (if (inside) 200 else 10).toByte())
            buf.put(off + 1, (if (inside) 100 else 10).toByte())
            buf.put(off + 2, (if (inside) 50 else 10).toByte())
        }
        val square = Polygon(doubleArrayOf(5.0, 10.0, 10.0, 5.0), doubleArrayOf(2.0, 2.0, 7.0, 7.0))
        val m = FaceRoi.meanRgb(buf, w, h, stride, 4, listOf(square))!!
        assertEquals(25, m.pixels)
        assertEquals(200.0, m.r, 1e-9); assertEquals(100.0, m.g, 1e-9); assertEquals(50.0, m.b, 1e-9)
    }

    @Test
    fun convexHullDropsInteriorPoints() {
        val hull = FaceRoi.convexHull(doubleArrayOf(0.0, 4.0, 4.0, 0.0, 2.0), doubleArrayOf(0.0, 0.0, 4.0, 4.0, 2.0))
        assertEquals(4, hull.size)
        assertTrue(hull.contains(2.0, 2.0))
        assertTrue(!hull.contains(5.0, 2.0))
    }
}
