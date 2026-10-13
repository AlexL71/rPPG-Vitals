package com.rppg.vitals.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Replays golden_trace.csv through the Kotlin processor and compares with the numbers
 * produced by the Python reference (python/tools/make_golden.py).
 */
class GoldenTest {

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader()

    private fun loadExpected(): List<Map<String, String>> {
        val lines = resource("golden_expected.csv").readLines().filter { it.isNotBlank() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    private fun Map<String, String>.num(key: String): Double? =
        this.getValue(key).let { if (it == "nan") null else it.toDouble() }

    private fun assertClose(label: String, expected: Double?, actual: Double?, tol: Double) {
        if (expected == null) {
            assertNull("$label should be absent", actual)
        } else {
            assertNotNull("$label missing", actual)
            assertEquals(label, expected, actual!!, tol)
        }
    }

    @Test
    fun kotlinMatchesPythonReference() {
        val data = readSessionCsv(resource("golden_trace.csv"))
        val t = data.getValue("t_sec")
        val expected = loadExpected()
        val proc = RppgProcessor()
        var k = 0
        val results = ArrayList<Estimate>()
        for (i in t.indices) {
            while (k < expected.size && t[i] > expected[k].num("check_t")!!) {
                results.add(proc.estimate()); k++
            }
            proc.add(t[i], data.getValue("r")[i], data.getValue("g")[i], data.getValue("b")[i],
                data.getValue("cx")[i], data.getValue("cy")[i], data.getValue("size")[i])
        }
        while (k < expected.size) { results.add(proc.estimate()); k++ }

        for ((e, a) in expected.zip(results)) {
            val at = "t=${e["check_t"]}"
            assertEquals("$at quality", e.getValue("quality").uppercase(), a.quality.name)
            assertClose("$at data_sec", e.num("data_sec"), a.dataSec, 1e-6)
            assertClose("$at hr_bpm", e.num("hr_bpm"), a.hrBpm, 1e-3)
            assertClose("$at hr_raw_bpm", e.num("hr_raw_bpm"), a.hrRawBpm, 1e-3)
            assertClose("$at snr_db", e.num("snr_db"), a.snrDb, 1e-3)
            assertClose("$at motion", e.num("motion"), a.motion, 1e-6)
            assertClose("$at rr_bpm", e.num("rr_bpm"), a.rrBpm, 1e-3)
            assertClose("$at rr_snr_db", e.num("rr_snr_db"), a.rrSnrDb, 1e-3)
            assertClose("$at rmssd_ms", e.num("rmssd_ms"), a.rmssdMs, 0.05)
            assertClose("$at sdnn_ms", e.num("sdnn_ms"), a.sdnnMs, 0.05)
            assertClose("$at n_beats", e.num("n_beats"), a.nBeats?.toDouble(), 0.0)
            assertEquals("$at rr_source", e.getValue("rr_source").takeIf { it != "nan" }, a.rrSource)
            assertEquals("$at waveform_len", e.num("waveform_len")!!.toInt(), a.waveform.size)
            for (j in 0 until minOf(5, a.waveform.size)) {
                assertClose("$at w$j", e.num("w$j"), a.waveform[j], 1e-6)
            }
        }
    }
}
