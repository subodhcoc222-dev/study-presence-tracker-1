package com.example.studypresence

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvStudyTime: TextView
    private lateinit var tvBreakTime: TextView
    private lateinit var tvLogs: TextView

    private lateinit var cameraExecutor: ExecutorService
    private var isPresent = false
    private var lastSeenTimestamp = System.currentTimeMillis()
    private var leaveTimestamp: Long = 0

    private var totalStudyMillis: Long = 0
    private var totalBreakMillis: Long = 0
    private var lastTickTimestamp = System.currentTimeMillis()

    private val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        tvStatus = findViewById(R.id.tvStatus)
        tvStudyTime = findViewById(R.id.tvStudyTime)
        tvBreakTime = findViewById(R.id.tvBreakTime)
        tvLogs = findViewById(R.id.tvLogs)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 101)
        }

        startLiveTimerLoop()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val detectorOptions = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            val detector = FaceDetection.getClient(detectorOptions)

            var lastProcessedTime = 0L
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastProcessedTime >= 1000) {
                            lastProcessedTime = now
                            processFrame(imageProxy, detector)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy, detector: com.google.mlkit.vision.face.FaceDetector) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val now = System.currentTimeMillis()
                    if (faces.isNotEmpty()) {
                        lastSeenTimestamp = now
                        if (!isPresent) {
                            onReturnDesk(now)
                        }
                    } else {
                        if (isPresent && (now - lastSeenTimestamp > 6000)) {
                            onLeaveDesk(lastSeenTimestamp)
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun onLeaveDesk(time: Long) {
        isPresent = false
        leaveTimestamp = time
        mainHandler.post {
            tvStatus.text = "Status: AWAY (On Break)"
            tvStatus.setTextColor(0xFFFF5252.toInt())
            addLog("Left Desk at: ${timeFormat.format(Date(time))}")
        }
    }

    private fun onReturnDesk(time: Long) {
        isPresent = true
        mainHandler.post {
            tvStatus.text = "Status: STUDYING (Present)"
            tvStatus.setTextColor(0xFF00E676.toInt())
            if (leaveTimestamp > 0) {
                val breakDurationSec = (time - leaveTimestamp) / 1000
                addLog("Returned at: ${timeFormat.format(Date(time))} (Break: ${formatDuration(breakDurationSec)})")
            } else {
                addLog("Session Started at: ${timeFormat.format(Date(time))}")
            }
        }
    }

    private fun addLog(message: String) {
        val oldLogs = tvLogs.text.toString()
        tvLogs.text = "$message\n$oldLogs"
    }

    private fun startLiveTimerLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val delta = now - lastTickTimestamp
                lastTickTimestamp = now

                if (isPresent) {
                    totalStudyMillis += delta
                } else if (leaveTimestamp > 0) {
                    totalBreakMillis += delta
                }

                val studySec = totalStudyMillis / 1000
                val breakSec = totalBreakMillis / 1000

                tvStudyTime.text = formatDuration(studySec)
                tvBreakTime.text = formatDuration(breakSec)

                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && allPermissionsGranted()) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
