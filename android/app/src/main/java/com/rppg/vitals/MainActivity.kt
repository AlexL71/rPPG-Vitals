package com.rppg.vitals

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.rppg.vitals.camera.FrameAnalyzer
import com.rppg.vitals.camera.FrameInfo
import com.rppg.vitals.core.Estimate
import com.rppg.vitals.core.Quality
import com.rppg.vitals.core.RppgConfig
import com.rppg.vitals.core.SessionCsvWriter
import com.rppg.vitals.ui.OverlayView
import com.rppg.vitals.ui.WaveformView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val cfg = RppgConfig()

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var waveform: WaveformView
    private lateinit var hrText: TextView
    private lateinit var qualityText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var fpsText: TextView
    private lateinit var lockButton: Button
    private lateinit var recordButton: Button
    private lateinit var shareButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: FrameAnalyzer? = null
    private var camera: Camera? = null
    private var fpsRange: Range<Int>? = null

    private var autoLock = true
    private var locked = false
    private var recorder: SessionCsvWriter? = null
    private var lastSession: File? = null
    private var lastFps = 0.0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = getString(R.string.permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        waveform = findViewById(R.id.waveform)
        hrText = findViewById(R.id.hrText)
        qualityText = findViewById(R.id.qualityText)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        fpsText = findViewById(R.id.fpsText)
        lockButton = findViewById(R.id.lockButton)
        recordButton = findViewById(R.id.recordButton)
        shareButton = findViewById(R.id.shareButton)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            analyzer?.requestReset()
            if (autoLock) setAeAwbLock(false)
        }
        lockButton.setOnClickListener {
            autoLock = !autoLock
            if (!autoLock) setAeAwbLock(false)
            analyzer?.requestReset()
            updateButtons()
        }
        recordButton.setOnClickListener { toggleRecording() }
        shareButton.setOnClickListener { shareLastSession() }
        updateButtons()

        cameraExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val a = analyzer
        val rec = recorder
        analyzer?.recorder = null
        cameraExecutor.execute {
            rec?.close()
            a?.close()
        }
        cameraExecutor.shutdown()
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindCamera(future.get())
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                statusText.text = getString(R.string.camera_failed, e.message ?: e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bindCamera(provider: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA
        if (!provider.hasCamera(selector)) {
            statusText.text = getString(R.string.no_front_camera)
            return
        }
        fpsRange = chooseFpsRange(provider, selector)

        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(previewSelector)
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        fpsRange?.let { range ->
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }

        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val frameAnalyzer = analyzer ?: FrameAnalyzer(
            context = this,
            cfg = cfg,
            onFrame = { info -> runOnUiThread { onFrame(info) } },
            onEstimate = { est -> runOnUiThread { onEstimate(est) } },
        ).also { analyzer = it }
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(cameraExecutor, frameAnalyzer)

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, selector, preview, analysis)
        locked = false
        Log.i(TAG, "camera bound, fps range=$fpsRange, analysis=${analysis.resolutionInfo?.resolution}")
    }

    /** Prefer a fixed 30 fps; otherwise the range with the highest minimum up to 30. */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun chooseFpsRange(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        return ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
    }

    /** Lock (or release) auto-exposure and auto-white-balance: brightness jumps destroy the signal. */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun setAeAwbLock(lock: Boolean) {
        val cam = camera ?: return
        if (locked == lock) return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
            .build()
        Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(options)
        locked = lock
        if (lock) analyzer?.requestReset()   // start a clean window with fixed exposure
        updateButtons()
    }

    // ---------------------------------------------------------------------- UI

    private fun onFrame(info: FrameInfo) {
        overlay.update(info)
        lastFps = info.fps
        fpsText.text = String.format(Locale.US, "%.1f fps · face %.0f ms", info.fps, info.landmarkMs)
    }

    private fun onEstimate(est: Estimate) {
        if (autoLock) {
            if (est.quality == Quality.NO_FACE) setAeAwbLock(false)
            else if (!locked && est.dataSec >= LOCK_AFTER_SEC) setAeAwbLock(true)
        }
        val color = qualityColor(est.quality)
        hrText.text = est.hrBpm?.roundToInt()?.toString() ?: "--"
        hrText.setTextColor(color)
        qualityText.text = est.quality.name.replace('_', ' ')
        qualityText.setTextColor(color)
        statusText.text = getString(statusRes(est.quality))
        waveform.setData(est.waveform, color)

        val lines = ArrayList<String>()
        if (est.snrDb != null) {
            lines.add(String.format(Locale.US, "SNR %+.1f dB · raw %.1f BPM · motion %.3f",
                est.snrDb, est.hrRawBpm, est.motion ?: 0.0))
        }
        lines.add(if (est.rrBpm != null)
            String.format(Locale.US, "Respiration %.0f /min (%s) · experimental", est.rrBpm, est.rrSource)
        else "Respiration -- (needs 15 s of good data)")
        lines.add(if (est.rmssdMs != null)
            String.format(Locale.US, "HRV RMSSD %.0f ms · SDNN %.0f ms · %d beats · experimental",
                est.rmssdMs, est.sdnnMs, est.nBeats)
        else "HRV -- (needs 30 s of good data)")
        lines.add(String.format(Locale.US, "data %.0f s · exposure %s%s",
            est.dataSec, if (locked) "locked" else "auto", if (recorder != null) " · REC" else ""))
        detailText.text = lines.joinToString("\n")
    }

    private fun updateButtons() {
        lockButton.text = getString(if (autoLock) R.string.lock_auto else R.string.lock_off)
        recordButton.text = getString(if (recorder != null) R.string.record_stop else R.string.record_start)
        shareButton.isEnabled = lastSession != null && recorder == null
    }

    private fun qualityColor(q: Quality): Int = when (q) {
        Quality.GOOD -> Color.rgb(80, 200, 120)
        Quality.FAIR -> Color.rgb(255, 200, 60)
        Quality.POOR -> Color.rgb(240, 90, 80)
        Quality.MOVING -> Color.rgb(255, 150, 50)
        else -> Color.rgb(170, 170, 170)
    }

    private fun statusRes(q: Quality): Int = when (q) {
        Quality.NO_FACE -> R.string.status_no_face
        Quality.WARMING_UP -> R.string.status_warming_up
        Quality.MOVING -> R.string.status_moving
        Quality.POOR -> R.string.status_poor
        Quality.FAIR -> R.string.status_fair
        Quality.GOOD -> R.string.status_good
    }

    // --------------------------------------------------------------- recording

    private fun toggleRecording() {
        val a = analyzer ?: return
        val current = recorder
        if (current == null) {
            val dir = getExternalFilesDir("sessions") ?: File(filesDir, "sessions")
            val name = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            val meta = linkedMapOf(
                "source" to "android",
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to Build.VERSION.RELEASE,
                "app_version" to BuildConfigCompat.versionName(this),
                "fps_range" to (fpsRange?.toString() ?: "default"),
                "exposure" to if (autoLock) "auto-lock" else "auto",
            )
            val writer = SessionCsvWriter(File(dir, name), meta)
            recorder = writer
            a.recorder = writer
        } else {
            a.recorder = null
            recorder = null
            lastSession = current.file
            cameraExecutor.execute { current.close() }   // after the frame being written
            Toast.makeText(this, getString(R.string.saved_to, current.file.name), Toast.LENGTH_LONG).show()
        }
        updateButtons()
    }

    private fun shareLastSession() {
        val file = lastSession ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val LOCK_AFTER_SEC = 2.0
    }
}

/** versionName without enabling the BuildConfig feature. */
private object BuildConfigCompat {
    fun versionName(activity: ComponentActivity): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }
}
