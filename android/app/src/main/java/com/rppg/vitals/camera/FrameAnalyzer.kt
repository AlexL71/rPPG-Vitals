package com.rppg.vitals.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.rppg.vitals.core.Estimate
import com.rppg.vitals.core.FaceRoi
import com.rppg.vitals.core.Polygon
import com.rppg.vitals.core.RppgConfig
import com.rppg.vitals.core.RppgProcessor
import com.rppg.vitals.core.SessionCsvWriter
import java.nio.ByteBuffer

/** Per-frame info for the UI overlay. Polygons are normalised (0..1) in the upright image. */
class FrameInfo(
    val roisUpright: Map<String, Polygon>?,
    val imageWidth: Int,       // upright image size
    val imageHeight: Int,
    val fps: Double,
    val landmarkMs: Double,
)

/**
 * CameraX analyzer: face landmarks -> skin ROIs -> mean RGB -> [RppgProcessor].
 *
 * Runs on a single background thread. Pixel colours are read straight from the
 * camera's RGBA plane (sensor orientation); only the image given to MediaPipe is
 * rotated upright. ROI polygons are mapped between the two coordinate systems.
 */
class FrameAnalyzer(
    context: Context,
    private val cfg: RppgConfig,
    private val onFrame: (FrameInfo) -> Unit,
    private val onEstimate: (Estimate) -> Unit,
    /** Run face landmarks every N frames; ROIs are reused in between. */
    private val landmarkEveryN: Int = 1,
) : ImageAnalysis.Analyzer {

    private val processor = RppgProcessor(cfg)
    private val landmarker: FaceLandmarker

    @Volatile var recorder: SessionCsvWriter? = null
    @Volatile private var resetRequested = false

    private var srcBitmap: Bitmap? = null
    private var uprightBitmap: Bitmap? = null
    private var packed: ByteArray = ByteArray(0)
    private var lastTimestampMs = -1L
    private var frameIndex = 0L
    private var nextEstimateT = 0.0

    // last face (reused when landmarks are skipped)
    private var roisSensor: Map<String, Polygon>? = null
    private var roisUprightNorm: Map<String, Polygon>? = null
    private var foreheadCx = 0.0
    private var foreheadCy = 0.0
    private var faceWidthPx = 0.0

    // fps
    private var fpsWindowStart = -1.0
    private var fpsCount = 0
    private var fps = 0.0
    private var landmarkMs = 0.0

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun requestReset() {
        resetRequested = true
    }

    fun close() {
        landmarker.close()
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val t = image.imageInfo.timestamp / 1e9
        val rotation = image.imageInfo.rotationDegrees
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val upW = if (rotation % 180 == 0) w else h
        val upH = if (rotation % 180 == 0) h else w

        if (resetRequested) {
            processor.reset()
            resetRequested = false
        }

        if (frameIndex % landmarkEveryN == 0L || roisSensor == null) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            detectFace(plane.buffer, plane.rowStride, w, h, rotation, upW, upH, t)
            landmarkMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        }

        val rois = roisSensor
        if (rois != null) {
            val mean = FaceRoi.meanRgb(plane.buffer, w, h, plane.rowStride, plane.pixelStride, rois.values)
            if (mean != null) {
                processor.add(t, mean.r, mean.g, mean.b, foreheadCx, foreheadCy, faceWidthPx)
                recorder?.write(t, mean.r, mean.g, mean.b, foreheadCx, foreheadCy, faceWidthPx)
            }
        }

        updateFps(t)
        if (t >= nextEstimateT) {
            nextEstimateT = t + 1.0
            onEstimate(processor.estimate(now = t))
        }
        onFrame(FrameInfo(roisUprightNorm, upW, upH, fps, landmarkMs))
        frameIndex++
    }

    private fun detectFace(
        buffer: ByteBuffer, rowStride: Int, w: Int, h: Int, rotation: Int, upW: Int, upH: Int, t: Double,
    ) {
        val src = copyToBitmap(buffer, rowStride, w, h)
        val uprightBmp = rotateUpright(src, rotation, upW, upH)
        val ts = maxOf((t * 1000).toLong(), lastTimestampMs + 1)   // VIDEO mode needs increasing ms
        lastTimestampMs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(uprightBmp).build(), ts)
        val faces = result.faceLandmarks()
        if (faces.isEmpty() || faces[0].size < FaceRoi.MIN_LANDMARKS) {
            roisSensor = null
            roisUprightNorm = null
            return
        }
        val lm = faces[0]
        val xs = DoubleArray(lm.size) { lm[it].x() * upW.toDouble() }
        val ys = DoubleArray(lm.size) { lm[it].y() * upH.toDouble() }
        val upright = FaceRoi.polygons(xs, ys, cfg.roiShrink)        // upright pixels
        roisUprightNorm = upright.mapValues { (_, p) -> p.map { x, y -> (x / upW) to (y / upH) } }
        roisSensor = upright.mapValues { (_, p) ->
            p.map { x, y ->
                val (su, sv) = FaceRoi.uprightToSensor(x / upW, y / upH, rotation)
                (su * w) to (sv * h)
            }
        }
        val forehead = upright.getValue("forehead")
        foreheadCx = forehead.centroidX()
        foreheadCy = forehead.centroidY()
        faceWidthPx = FaceRoi.faceWidth(xs, ys)
    }

    /** RGBA plane -> reusable ARGB_8888 bitmap (handles row padding). */
    private fun copyToBitmap(buffer: ByteBuffer, rowStride: Int, w: Int, h: Int): Bitmap {
        val bmp = srcBitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { srcBitmap = it }
        buffer.rewind()
        if (rowStride == w * 4) {
            bmp.copyPixelsFromBuffer(buffer)
        } else {
            if (packed.size != w * h * 4) packed = ByteArray(w * h * 4)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(packed, row * w * 4, w * 4)
            }
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
        }
        buffer.rewind()
        return bmp
    }

    /** Rotate clockwise by [rotation] into a reusable upright bitmap. */
    private fun rotateUpright(src: Bitmap, rotation: Int, upW: Int, upH: Int): Bitmap {
        if (rotation == 0) return src
        val dst = uprightBitmap?.takeIf { it.width == upW && it.height == upH }
            ?: Bitmap.createBitmap(upW, upH, Bitmap.Config.ARGB_8888).also { uprightBitmap = it }
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        when (rotation) {
            90 -> m.postTranslate(src.height.toFloat(), 0f)
            180 -> m.postTranslate(src.width.toFloat(), src.height.toFloat())
            270 -> m.postTranslate(0f, src.width.toFloat())
        }
        Canvas(dst).drawBitmap(src, m, null)
        return dst
    }

    private fun updateFps(t: Double) {
        if (fpsWindowStart < 0) fpsWindowStart = t
        fpsCount++
        val dt = t - fpsWindowStart
        if (dt >= 1.0) {
            fps = fpsCount / dt
            fpsCount = 0
            fpsWindowStart = t
        }
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
        const val MODEL_ASSET = "face_landmarker.task"
    }
}
