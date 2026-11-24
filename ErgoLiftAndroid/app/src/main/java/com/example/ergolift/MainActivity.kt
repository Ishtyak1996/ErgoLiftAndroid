package com.example.ergolift

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ErgoLift proof of concept: CameraX RGBA frames -> MediaPipe -> annotated video +
 * 2D posture metrics. Analysis runs on a worker thread, with no network upload.
 *
 * IMPORTANT: Angle limits are demonstrative and are NOT validated lifting-risk limits.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var videoView: ImageView
    private lateinit var postureText: TextView
    private lateinit var anglesText: TextView
    private lateinit var phaseText: TextView
    private lateinit var detailsText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: PoseLandmarker? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    @Volatile private var neutralNeck: Double? = null
    @Volatile private var lastNeckRaw: Double? = null
    @Volatile private var lastSideView = false

    private var smoothTrunk: Double? = null
    private var previousTrunk: Double? = null
    private var previousTime = 0L
    private var reachedDown = false
    private var lastRenderTime = 0L

    private val green = Color.rgb(48, 218, 129)
    private val teal = Color.rgb(54, 227, 235)
    private val magenta = Color.rgb(247, 81, 221)
    private val red = Color.rgb(255, 82, 82)

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            setStatus("CAMERA PERMISSION REQUIRED", "Allow camera access to analyse posture.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView = findViewById(R.id.liveImage)
        postureText = findViewById(R.id.postureText)
        anglesText = findViewById(R.id.anglesText)
        phaseText = findViewById(R.id.phaseText)
        detailsText = findViewById(R.id.detailsText)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            resetTracking()
            startCamera()
        }

        findViewById<Button>(R.id.calibrateButton).setOnClickListener {
            val raw = lastNeckRaw
            if (raw != null && lastSideView) {
                neutralNeck = raw
                Toast.makeText(this, "Neutral neck reference saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this, "Stand side-on with your ear, shoulder and hip visible",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .build()
            val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .build()
            landmarker = PoseLandmarker.createFromOptions(this, opts)
        } catch (e: Exception) {
            setStatus(
                "MODEL NOT FOUND",
                "Copy pose_landmarker_lite.task into app/src/main/assets and rebuild. ${e.message}"
            )
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (landmarker == null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                if (!provider.hasCamera(selector)) {
                    setStatus("CAMERA UNAVAILABLE", "This phone has no matching camera.")
                    return@addListener
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image -> analyseFrame(image) }
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                setStatus("CAMERA ERROR", e.message ?: "Could not start camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyseFrame(proxy: ImageProxy) {
        val source: Bitmap
        try {
            // CameraX RGBA_8888: plane[0] is an RGBA buffer. Copy immediately,
            // because ImageProxy must be closed after the analyzer finishes.
            val raw = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            val plane = proxy.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            if (plane.rowStride == proxy.width * 4) {
                raw.copyPixelsFromBuffer(buffer)
            } else {
                // Remove per-row padding, if the camera provides padded rows.
                val bytes = ByteArray(proxy.width * proxy.height * 4)
                val row = ByteArray(proxy.width * 4)
                for (y in 0 until proxy.height) {
                    buffer.position(y * plane.rowStride)
                    buffer.get(row)
                    System.arraycopy(row, 0, bytes, y * row.size, row.size)
                }
                raw.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            }

            val matrix = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f)
            }
            source = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } catch (e: Exception) {
            android.util.Log.e("ErgoLift", "Camera frame conversion failed", e)
            return
        } finally {
            proxy.close()
        }

        val detector = landmarker ?: return
        try {
            val result = detector.detectForVideo(
                BitmapImageBuilder(source).build(),
                SystemClock.uptimeMillis()
            )
            val now = SystemClock.uptimeMillis()
            if (now - lastRenderTime < 85L) return
            lastRenderTime = now

            val drawn = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(drawn)
            val p = result.landmarks().firstOrNull()
            val measurements = if (p == null) {
                resetTracking()
                Metrics(null, null, null, null, "NOT DETECTED",
                    "NOT ASSESSED", "Keep head, shoulders and hips in view.", Color.LTGRAY)
            } else analysePose(p, drawn.width, drawn.height, canvas, now)

            runOnUiThread {
                videoView.setImageBitmap(drawn)
                postureText.text = measurements.posture
                postureText.setTextColor(measurements.color)
                anglesText.text = "Trunk: ${fmt(measurements.trunk)}    Neck: ${fmt(measurements.neck)}\n" +
                    "Left elbow: ${fmt(measurements.leftElbow)}    Right elbow: ${fmt(measurements.rightElbow)}"
                phaseText.text = "Phase: ${measurements.phase}"
                detailsText.text = measurements.details
            }
        } catch (e: Exception) {
            android.util.Log.e("ErgoLift", "Pose detection error", e)
            runOnUiThread { setStatus("ANALYSIS ERROR", e.message ?: "Check Android logcat") }
        }
    }

    private data class Pt(val x: Float, val y: Float)
    private data class Metrics(
        val trunk: Double?, val neck: Double?,
        val leftElbow: Double?, val rightElbow: Double?,
        val phase: String, val posture: String,
        val details: String, val color: Int
    )

    private fun visible(p: List<NormalizedLandmark>, vararg ids: Int, threshold: Float = 0.45f) =
        ids.all { p[it].visibility().orElse(0f) >= threshold }

    private fun pt(p: List<NormalizedLandmark>, id: Int, w: Int, h: Int) =
        Pt(p[id].x() * w, p[id].y() * h)

    private fun midpoint(a: Pt, b: Pt) = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun vec(a: Pt, b: Pt) = Pt(a.x - b.x, a.y - b.y)

    private fun angle(a: Pt, b: Pt): Double? {
        val magnitude = hypot(a.x.toDouble(), a.y.toDouble()) *
            hypot(b.x.toDouble(), b.y.toDouble())
        if (magnitude < 1e-6) return null
        val cosine = ((a.x * b.x + a.y * b.y) / magnitude).coerceIn(-1.0, 1.0)
        return acos(cosine) * 180.0 / PI
    }

    private fun jointAngle(a: Pt, b: Pt, c: Pt) = angle(vec(a, b), vec(c, b))

    private fun drawLine(canvas: Canvas, a: Pt, b: Pt, color: Int, width: Float = 5f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }

    private fun dot(canvas: Canvas, a: Pt) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
        canvas.drawCircle(a.x, a.y, 6f, paint)
    }

    private fun analysePose(
        p: List<NormalizedLandmark>, w: Int, h: Int,
        canvas: Canvas, now: Long
    ): Metrics {
        val links = arrayOf(
            11 to 12, 11 to 13, 13 to 15,
            12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24
        )
        links.forEach { (a, b) ->
            if (visible(p, a, b)) drawLine(canvas, pt(p, a, w, h), pt(p, b, w, h), green)
        }
        intArrayOf(0, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24).forEach { id ->
            if (visible(p, id)) dot(canvas, pt(p, id, w, h))
        }

        val leftElbow = if (visible(p, 11, 13, 15))
            jointAngle(pt(p, 11, w, h), pt(p, 13, w, h), pt(p, 15, w, h)) else null
        val rightElbow = if (visible(p, 12, 14, 16))
            jointAngle(pt(p, 12, w, h), pt(p, 14, w, h), pt(p, 16, w, h)) else null

        if (!visible(p, 11, 12, 23, 24)) {
            resetTracking()
            return Metrics(null, null, leftElbow, rightElbow, "NOT DETECTED",
                "NOT ASSESSED", "Move back until shoulders and hips are visible.", Color.LTGRAY)
        }

        val shoulders = midpoint(pt(p, 11, w, h), pt(p, 12, w, h))
        val hips = midpoint(pt(p, 23, w, h), pt(p, 24, w, h))
        val torso = vec(shoulders, hips)
        drawLine(canvas, shoulders, hips, teal, 6f)
        val measuredTrunk = atan2(abs(torso.x.toDouble()), abs(torso.y.toDouble())) * 180 / PI
        smoothTrunk = smoothTrunk?.let { it * 0.7 + measuredTrunk * 0.3 } ?: measuredTrunk
        val trunk = smoothTrunk!!

        // Frontal postures cannot provide reliable sagittal-plane bending estimates.
        val shoulderWidth = hypot(
            (p[11].x() - p[12].x()).toDouble() * w,
            (p[11].y() - p[12].y()).toDouble() * h
        )
        val torsoLength = hypot(torso.x.toDouble(), torso.y.toDouble())
        val side = torsoLength > 25 && shoulderWidth < 0.75 * torsoLength
        lastSideView = side

        // Draw a head-to-neck line even in frontal view, but only estimate neck
        // inclination when a clear side-on ear/shoulder/hip alignment exists.
        var neckRaw: Double? = null
        if (side) {
            val pairs = arrayOf(Triple(7, 11, 23), Triple(8, 12, 24))
            val best = pairs.filter { (ear, sh, hip) ->
                visible(p, ear, sh, hip, threshold = 0.30f)
            }.maxByOrNull { (ear, sh, hip) ->
                minOf(
                    p[ear].visibility().orElse(0f),
                    p[sh].visibility().orElse(0f),
                    p[hip].visibility().orElse(0f)
                )
            }
            if (best != null) {
                val ear = pt(p, best.first, w, h)
                val shoulder = pt(p, best.second, w, h)
                val hip = pt(p, best.third, w, h)
                drawLine(canvas, shoulder, ear, magenta, 5f)
                neckRaw = angle(vec(ear, shoulder), vec(shoulder, hip))
            }
        } else if (visible(p, 7, 8, threshold = 0.30f)) {
            drawLine(canvas, shoulders, midpoint(pt(p, 7, w, h), pt(p, 8, w, h)), magenta, 5f)
        } else if (visible(p, 0)) {
            drawLine(canvas, shoulders, pt(p, 0, w, h), magenta, 5f)
        }
        lastNeckRaw = neckRaw
        val neckChange = if (neckRaw != null && neutralNeck != null)
            abs(neckRaw - neutralNeck!!) else null

        val speed = if (previousTrunk == null || previousTime == 0L) 0.0 else
            (trunk - previousTrunk!!) / ((now - previousTime).coerceAtLeast(1) / 1000.0)
        previousTrunk = trunk
        previousTime = now
        val handsLow = (visible(p, 15, 23) && p[15].y() > p[23].y()) ||
            (visible(p, 16, 24) && p[16].y() > p[24].y())
        if (handsLow && trunk >= 20.0) reachedDown = true
        val phase = when {
            trunk < 15.0 -> { reachedDown = false; "UPRIGHT" }
            reachedDown && speed < -8 -> "RISING / POSSIBLE LIFT"
            handsLow && trunk >= 20.0 -> "REACHING"
            speed > 8 -> "BENDING"
            else -> "HOLD / TRANSITION"
        }

        val flags = mutableListOf<String>()
        if (trunk > 45.0) flags.add("large trunk inclination")
        if (neckChange != null && neckChange > 25.0) flags.add("neck deviation")
        val posture: String
        val explanation: String
        val color: Int
        when {
            !side -> {
                posture = "TURN SIDEWAYS"
                explanation = "A side-on view is needed for meaningful bending measurement."
                color = Color.rgb(255, 198, 73)
            }
            flags.isNotEmpty() -> {
                posture = "BAD POSTURE (DEMO)"
                explanation = "Demo angle flag: ${flags.joinToString()}. Not a safety verdict."
                color = red
            }
            else -> {
                posture = "GOOD POSTURE (DEMO)"
                explanation = if (neckChange != null)
                    "No demo thresholds exceeded. Load, distance and repetition not assessed."
                else "Trunk angle only; tap Calibrate neck to include neck deviation."
                color = green
            }
        }
        return Metrics(trunk, neckChange ?: neckRaw, leftElbow, rightElbow,
            phase, posture, explanation, color)
    }

    private fun fmt(value: Double?): String =
        if (value == null) "--" else String.format(java.util.Locale.US, "%.1f°", value)

    private fun setStatus(message: String, details: String) {
        postureText.text = message
        postureText.setTextColor(Color.LTGRAY)
        detailsText.text = details
    }

    private fun resetTracking() {
        smoothTrunk = null
        previousTrunk = null
        previousTime = 0L
        reachedDown = false
        lastNeckRaw = null
        lastSideView = false
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.execute { landmarker?.close(); landmarker = null }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
