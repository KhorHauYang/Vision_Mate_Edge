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
    private var isFirstWarningGiven: Boolean = false
    private val cooldownTimer = 4000L
    private val stareTimer = 200L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        try {
            tflite = Interpreter(loadModelFile())
            Toast.makeText(this, "AI Brain Loaded Successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
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

                // 1. Prepare buffer for NCHW (Planar) data
                val byteBuffer = java.nio.ByteBuffer.allocateDirect(512 * 512 * 3 * 4)
                byteBuffer.order(java.nio.ByteOrder.nativeOrder())

                val intValues = IntArray(512 * 512)
                resizedBitmap.getPixels(intValues, 0, 512, 0, 0, 512, 512)

                // 2. 🔥 NCHW LOOP: Separates R, G, and B into their own memory blocks
                byteBuffer.rewind()
                val channelSize = 512 * 512
                for (i in 0 until 512) {
                    for (j in 0 until 512) {
                        val pixelIndex = i * 512 + j
                        val valInt = intValues[pixelIndex]

                        // Red block, then Green block, then Blue block
                        byteBuffer.putFloat(pixelIndex * 4, ((valInt shr 16) and 0xFF) / 255.0f) // Red
                        byteBuffer.putFloat((channelSize + pixelIndex) * 4, ((valInt shr 8) and 0xFF) / 255.0f) // Green
                        byteBuffer.putFloat((channelSize * 2 + pixelIndex) * 4, (valInt and 0xFF) / 255.0f) // Blue
                    }
                }

                // 7 Classes + 4 Coordinates = 11
                val outputTensor = Array(1) { Array(11) { FloatArray(5376) } }
                tflite?.run(byteBuffer, outputTensor)

                var bestScore = 0.0f
                var bestClassIndex = -1
                var bestBoxIndex = -1
                val numClasses = 7 // Set to 7 classes

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

                    // Labels matched to 7-class model (Car is index 0)
                    val labels = arrayOf("Car", "Chair", "Door", "Staircase", "Table", "Unmarked Step", "motorcycle")
                    val detectedName = labels[bestClassIndex]

                    // Get Coordinates & Size Math
                    var cx = outputTensor[0][0][bestBoxIndex]
                    var cy = outputTensor[0][1][bestBoxIndex]
                    var w = outputTensor[0][2][bestBoxIndex]
                    var h = outputTensor[0][3][bestBoxIndex]

                    if (cx > 2f) { cx /= 512f; cy /= 512f; w /= 512f; h /= 512f }

                    val screenPercentage = (w * h) * 100f
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
                        "Car" -> 0.55f
                        "Chair" -> 0.85f
                        "Staircase" -> 0.99f
                        "Unmarked Step" -> 0.65f
                        "Table" -> 0.30f
                        "motorcycle" -> 0.40f
                        else -> 0.65f            // Covers "Door" perfectly
                    }

                    if (bestScore > passingGrade) {
                        isConfident = true
                    }

                    // =========================================
                    // 2. AUDIO RULE: Is it dangerously close to the user?
                    // =========================================
                    var isCloseEnough = false
                    when (detectedName) {
                        "Car" -> if (screenPercentage > 35f) isCloseEnough = true
                        "Chair" -> if (screenPercentage > 20f) isCloseEnough = true
                        "Door" -> if (screenPercentage > 70f) isCloseEnough = true
                        "Staircase" -> if (screenPercentage > 20f) isCloseEnough = true
                        "Table" -> if (screenPercentage > 25f) isCloseEnough = true
                        "Unmarked Step" -> if (screenPercentage > 7f) isCloseEnough = true
                        "motorcycle" -> if (screenPercentage > 25f) isCloseEnough = true
                    }

                    // =========================================
                    // 3. EXECUTION LOGIC
                    // =========================================
                    if (isConfident) {
                        val percent = String.format("%.1f", bestScore * 100)
                        overlay.drawBox(android.graphics.RectF(left, top, right, bottom), dimensionsText)

                        if (isCloseEnough) {
                            val currentTime = System.currentTimeMillis()

                            if (detectedName != currentlyTrackingObject) {
                                currentlyTrackingObject = detectedName
                                trackingStartTime = currentTime
                                isFirstWarningGiven = false
                                debugText.text = "DETECTED: $detectedName $percent% (Getting closer...)"

                            } else {
                                val timeStared = currentTime - trackingStartTime

                                if (timeStared >= stareTimer) {
                                    if (currentTime - lastSpokenTime > cooldownTimer) {

                                        // 🧠 Smart Audio Logic
                                        if (isTtsReady) {
                                            if (!isFirstWarningGiven) {
                                                tts.speak("A $detectedName is within 1 meter from you, brother", TextToSpeech.QUEUE_FLUSH, null, null)
                                                isFirstWarningGiven = true
                                            } else {
                                                tts.speak("$detectedName is still in front of you, brother", TextToSpeech.QUEUE_FLUSH, null, null)
                                            }
                                        }

                                        lastSpokenTime = currentTime
                                        debugText.text = "DETECTED: $detectedName $percent% - AUDIO TRIGGERED!"
                                    } else {
                                        debugText.text = "DETECTED: $detectedName $percent% (Audio Cooldown)"
                                    }
                                } else {
                                    debugText.text = "DETECTED: $detectedName $percent% (Verifying 0.5s...)"
                                }
                            }
                        } else {
                            debugText.text = "DETECTED: $detectedName $percent% (Too far to speak)"
                            currentlyTrackingObject = ""
                            isFirstWarningGiven = false
                        }
                    } else {
                        // Not confident enough. Hide everything.
                        currentlyTrackingObject = ""
                        isFirstWarningGiven = false
                        debugText.text = "Scanning..."
                        overlay.drawBox(null, "")
                    }
                } // <--- THIS CLOSES runOnUiThread

            } catch (e: Throwable) {
                runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    debugText.text = "AI Engine Note: ${e.message ?: e.toString()}"
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