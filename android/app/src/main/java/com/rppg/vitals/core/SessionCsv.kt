package com.rppg.vitals.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Session CSV shared with the Python tools (python/rppg/session_io.py):
 * `# key=value` metadata lines, then header `t_sec,r,g,b,cx,cy,size`.
 */
class SessionCsvWriter(val file: File, meta: Map<String, String>) {
    private val out: BufferedWriter

    init {
        file.parentFile?.mkdirs()
        out = BufferedWriter(FileWriter(file))
        for ((k, v) in meta) out.write("# $k=$v\n")
        out.write(COLUMNS.joinToString(",") + "\n")
    }

    fun write(t: Double, r: Double, g: Double, b: Double, cx: Double, cy: Double, size: Double) {
        out.write(String.format(Locale.US, "%.6f,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f\n", t, r, g, b, cx, cy, size))
    }

    fun close() = out.close()

    companion object {
        val COLUMNS = listOf("t_sec", "r", "g", "b", "cx", "cy", "size")
    }
}

/** Rows of a session CSV as column arrays (used by unit tests). */
fun readSessionCsv(reader: BufferedReader): Map<String, DoubleArray> {
    var header: List<String>? = null
    val rows = ArrayList<DoubleArray>()
    reader.forEachLine { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
        if (header == null) {
            header = line.split(",")
        } else {
            rows.add(line.split(",").map { it.toDouble() }.toDoubleArray())
        }
    }
    val h = requireNotNull(header) { "no header" }
    return h.withIndex().associate { (i, name) -> name to DoubleArray(rows.size) { rows[it][i] } }
}
