package com.rppg.vitals.core

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Closed polygon in pixel coordinates. */
class Polygon(val xs: DoubleArray, val ys: DoubleArray) {
    val size: Int get() = xs.size
    fun centroidX(): Double = xs.average()
    fun centroidY(): Double = ys.average()

    /** Even-odd ray casting. */
    fun contains(x: Double, y: Double): Boolean {
        var inside = false
        var j = size - 1
        for (i in 0 until size) {
            if ((ys[i] > y) != (ys[j] > y) &&
                x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]
            ) inside = !inside
            j = i
        }
        return inside
    }

    fun map(f: (Double, Double) -> Pair<Double, Double>): Polygon {
        val nx = DoubleArray(size)
        val ny = DoubleArray(size)
        for (i in 0 until size) {
            val (a, b) = f(xs[i], ys[i])
            nx[i] = a; ny[i] = b
        }
        return Polygon(nx, ny)
    }
}

class MeanRgb(val r: Double, val g: Double, val b: Double, val pixels: Int)

/**
 * Face landmarks -> skin ROIs -> mean colour. Same landmark indices as
 * python/rppg/face_roi.py, so both versions measure the same skin regions.
 */
object FaceRoi {
    /** "left"/"right" = image left/right of the un-mirrored camera image. */
    val ROI_LANDMARKS: Map<String, IntArray> = linkedMapOf(
        "forehead" to intArrayOf(109, 10, 338, 297, 332, 334, 296, 336, 9, 107, 66, 105, 103, 67),
        "left_cheek" to intArrayOf(116, 117, 118, 119, 100, 142, 203, 206, 205, 50, 123),
        "right_cheek" to intArrayOf(345, 346, 347, 348, 329, 371, 423, 426, 425, 280, 352),
    )
    const val FACE_LEFT = 234
    const val FACE_RIGHT = 454
    const val MIN_LANDMARKS = 455

    /** Andrew's monotone chain convex hull (counter-clockwise). */
    fun convexHull(xs: DoubleArray, ys: DoubleArray): Polygon {
        val idx = xs.indices.sortedWith(compareBy({ xs[it] }, { ys[it] }))
        if (idx.size <= 2) return Polygon(DoubleArray(idx.size) { xs[idx[it]] }, DoubleArray(idx.size) { ys[idx[it]] })
        fun cross(o: Int, a: Int, b: Int) = (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o])
        val lower = ArrayList<Int>()
        for (p in idx) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Int>()
        for (p in idx.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        val hull = lower.dropLast(1) + upper.dropLast(1)
        return Polygon(DoubleArray(hull.size) { xs[hull[it]] }, DoubleArray(hull.size) { ys[hull[it]] })
    }

    /** ROI polygons (convex hull of each ROI's landmarks, shrunk toward the centroid). */
    fun polygons(lmX: DoubleArray, lmY: DoubleArray, shrink: Double): Map<String, Polygon> {
        val out = LinkedHashMap<String, Polygon>()
        for ((name, ids) in ROI_LANDMARKS) {
            val hull = convexHull(DoubleArray(ids.size) { lmX[ids[it]] }, DoubleArray(ids.size) { lmY[ids[it]] })
            val cx = hull.centroidX()
            val cy = hull.centroidY()
            out[name] = hull.map { x, y -> (cx + (x - cx) * shrink) to (cy + (y - cy) * shrink) }
        }
        return out
    }

    fun faceWidth(lmX: DoubleArray, lmY: DoubleArray): Double =
        hypot(lmX[FACE_RIGHT] - lmX[FACE_LEFT], lmY[FACE_RIGHT] - lmY[FACE_LEFT])

    /**
     * Mean R, G, B over all polygons, read directly from an RGBA_8888 image plane
     * (bytes R, G, B, A at offset y * rowStride + x * pixelStride). Pixel centres are tested.
     */
    fun meanRgb(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        polys: Collection<Polygon>,
    ): MeanRgb? {
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var count = 0
        for (poly in polys) {
            val x0 = maxOf(0, floor(poly.xs.min()).toInt())
            val x1 = minOf(width - 1, ceil(poly.xs.max()).toInt())
            val y0 = maxOf(0, floor(poly.ys.min()).toInt())
            val y1 = minOf(height - 1, ceil(poly.ys.max()).toInt())
            for (y in y0..y1) {
                val row = y * rowStride
                for (x in x0..x1) {
                    if (!poly.contains(x + 0.5, y + 0.5)) continue
                    val off = row + x * pixelStride
                    sr += buffer.get(off).toInt() and 0xFF
                    sg += buffer.get(off + 1).toInt() and 0xFF
                    sb += buffer.get(off + 2).toInt() and 0xFF
                    count++
                }
            }
        }
        if (count == 0) return null
        return MeanRgb(sr.toDouble() / count, sg.toDouble() / count, sb.toDouble() / count, count)
    }

    /**
     * Maps a normalised point in the upright (rotated) image back to the normalised
     * sensor image. [rotation] is the clockwise rotation (0/90/180/270) that makes the
     * sensor image upright (CameraX ImageInfo.rotationDegrees).
     */
    fun uprightToSensor(u: Double, v: Double, rotation: Int): Pair<Double, Double> = when (rotation) {
        90 -> v to (1.0 - u)
        180 -> (1.0 - u) to (1.0 - v)
        270 -> (1.0 - v) to u
        else -> u to v
    }

    /** Inverse of [uprightToSensor]. */
    fun sensorToUpright(su: Double, sv: Double, rotation: Int): Pair<Double, Double> = when (rotation) {
        90 -> (1.0 - sv) to su
        180 -> (1.0 - su) to (1.0 - sv)
        270 -> sv to (1.0 - su)
        else -> su to sv
    }
}
