package com.example.tech4goodvision

import android.Manifest
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var tflite: Interpreter? = null

    // Voice Engine Variables
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    // ⏱️ TIMERS: The 1-Second Stare & The 2.5-Second Cooldown
    private var currentlyTrackingObject: String = ""
    private var trackingStartTime: Long = 0
    private var lastSpokenTime: Long = 0
    private val cooldownTimer = 2500L
    private val stareTimer = 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        try {
            tflite = Interpreter(loadModelFile())
            Toast.makeText(this, "AI Brain Loaded Successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Waiting for model.tflite in assets...", Toast.LENGTH_LONG).show()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        requestCameraPermission()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(java.util.Locale.ENGLISH)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsReady = true
                }
            }
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun requestCameraPermission() {
        val launcher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            if (it) startCamera()
        }
        launcher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                val viewFinder = findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder)
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> runInference(imageProxy) }
                }

            cameraProvider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runInference(imageProxy: ImageProxy) {
        if (tflite != null) {
            try {
                val bitmap = imageProxy.toBitmap()
                val matrix = android.graphics.Matrix()
                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                val uprightBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(uprightBitmap, 512, 512, true)

                val byteBuffer = java.nio.ByteBuffer.allocateDirect(512 * 512 * 3 * 4)
                byteBuffer.order(java.nio.ByteOrder.nativeOrder())

                val intValues = IntArray(512 * 512)
                resizedBitmap.getPixels(intValues, 0, 512, 0, 0, 512, 512)

                var pixel = 0
                for (i in 0 until 512) {
                    for (j in 0 until 512) {
                        val valInt = intValues[pixel++]
                        byteBuffer.putFloat(((valInt shr 16) and 0xFF) / 255.0f)
                        byteBuffer.putFloat(((valInt shr 8) and 0xFF) / 255.0f)
                        byteBuffer.putFloat((valInt and 0xFF) / 255.0f)
                    }
                }

                val outputTensor = Array(1) { Array(10) { FloatArray(5376) } }
                tflite?.run(byteBuffer, outputTensor)

                var bestScore = 0.0f
                var bestClassIndex = -1
                var bestBoxIndex = -1
                val numClasses = 6

                for (i in 0 until 5376) {
                    for (c in 0 until numClasses) {
                        val score = outputTensor[0][4 + c][i]
                        if (score > bestScore) {
                            bestScore = score
                            bestClassIndex = c
                            bestBoxIndex = i
                        }
                    }
                }

                runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    val overlay = findViewById<BoundingBoxOverlay>(R.id.boxOverlay)

                    val labels = arrayOf("Chair", "Door", "Staircase", "Table", "Unmarked Step", "Motorcycle")
                    val detectedName = labels[bestClassIndex]

                    // Get Coordinates & Size Math
                    var cx = outputTensor[0][0][bestBoxIndex]
                    var cy = outputTensor[0][1][bestBoxIndex]
                    var w = outputTensor[0][2][bestBoxIndex]
                    var h = outputTensor[0][3][bestBoxIndex]

                    if (cx > 2f) { cx /= 512f; cy /= 512f; w /= 512f; h /= 512f }

                    val screenPercentage = (w * h) * 100f

                    // NEW: Create the floating label for Width and Height
                    val wPercentStr = String.format("%.1f", w * 100f)
                    val hPercentStr = String.format("%.1f", h * 100f)
                    val dimensionsText = "W: $wPercentStr% | H: $hPercentStr%"

                    val screenW = overlay.width.toFloat()
                    val screenH = overlay.height.toFloat()
                    val left = (cx - w / 2) * screenW
                    val top = (cy - h / 2) * screenH
                    val right = (cx + w / 2) * screenW
                    val bottom = (cy + h / 2) * screenH

                    // =========================================
                    // 1. VISUAL RULE: Does it pass the Confidence test?
                    // =========================================
                    var isConfident = false
                    val passingGrade = when (detectedName) {
                        "Chair" -> 0.85f
                        "Staircase" -> 0.70f
                        "Unmarked Step" -> 0.65f
                        "Table" -> 0.60f
                        "Motorcycle" -> 0.40f
                        else -> 0.80f
                    }

                    if (bestScore > passingGrade) {
                        isConfident = true
                    }

                    // =========================================
                    // 2. AUDIO RULE: Is it dangerously close to the user?
                    // NOTE: Thresholds updated based on your recent photo sizes!
                    // =========================================
                    var isCloseEnough = false
                    when (detectedName) {
                        "Chair" -> if (screenPercentage > 20f) isCloseEnough = true
                        "Door" -> if (screenPercentage > 75f) isCloseEnough = true
                        "Staircase" -> if (screenPercentage > 15f) isCloseEnough = true
                        "Table" -> if (screenPercentage > 25f) isCloseEnough = true
                        "Unmarked Step" -> if (screenPercentage > 8f) isCloseEnough = true
                        "Motorcycle" -> if (screenPercentage > 25f) isCloseEnough = true
                    }

                    // =========================================
                    // 3. EXECUTION LOGIC
                    // =========================================
                    if (isConfident) {
                        // INSTANTLY DRAW THE GREEN BOX AND THE W/H TEXT!
                        val percent = String.format("%.1f", bestScore * 100)
                        overlay.drawBox(android.graphics.RectF(left, top, right, bottom), dimensionsText)

                        if (isCloseEnough) {
                            val currentTime = System.currentTimeMillis()

                            if (detectedName != currentlyTrackingObject) {
                                currentlyTrackingObject = detectedName
                                trackingStartTime = currentTime
                                debugText.text = "DETECTED: $detectedName $percent% (Getting closer...)"

                            } else {
                                val timeStared = currentTime - trackingStartTime

                                if (timeStared >= stareTimer) {
                                    if (currentTime - lastSpokenTime > cooldownTimer) {
                                        if (isTtsReady) tts.speak("$detectedName ahead", TextToSpeech.QUEUE_FLUSH, null, null)
                                        lastSpokenTime = currentTime
                                        debugText.text = "DETECTED: $detectedName $percent% - AUDIO TRIGGERED!"
                                    } else {
                                        debugText.text = "DETECTED: $detectedName $percent% (Audio Cooldown)"
                                    }
                                } else {
                                    debugText.text = "DETECTED: $detectedName $percent% (Verifying 1s...)"
                                }
                            }
                        } else {
                            debugText.text = "DETECTED: $detectedName $percent% (Too far to speak)"
                            currentlyTrackingObject = ""
                        }
                    } else {
                        // Not confident enough. Hide everything.
                        currentlyTrackingObject = ""
                        debugText.text = "Scanning..."
                        overlay.drawBox(null, "") // Pass empty text when clearing the box
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    debugText.text = "AI Engine Note: ${e.message}"
                }
            }
        }
        imageProxy.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        tflite?.close()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}