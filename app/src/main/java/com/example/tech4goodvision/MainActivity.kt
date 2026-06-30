package com.example.tech4goodvision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
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

    // TTS added version
    // NEW: Voice Engine Variables
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    // NEW: Spam Blocker (Debounce) Variables
    private var lastSpokenTime: Long = 0
    private val cooldownTimer = 2500L // 2.5 seconds in milliseconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 1. Try to load your teammate's model
        try {
            tflite = Interpreter(loadModelFile())
            Toast.makeText(this, "AI Brain Loaded Successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Waiting for model.tflite in assets...", Toast.LENGTH_LONG).show()
        }


        cameraExecutor = Executors.newSingleThreadExecutor()
        requestCameraPermission()

        // Initialize the Voice Engine
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Set to English (or use Locale("ms", "MY") for Malay)
                val result = tts.setLanguage(java.util.Locale.ENGLISH)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsReady = true
                }
            }
        }
    }

    // Helper function to read the file from the assets folder
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

            val preview = androidx.camera.core.Preview.Builder()
                .build()
                .also {
                    val viewFinder = findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder)
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        runInference(imageProxy)
                    }
                }

            cameraProvider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runInference(imageProxy: ImageProxy) {
        if (tflite != null) {
            try {
                // STEP 1: Get the frame, ROTATE it upright, then squish it
                val bitmap = imageProxy.toBitmap()

                // Fix the sideways camera bug!
                val matrix = android.graphics.Matrix()
                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                val uprightBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                // Now squish the upright image for the AI
                val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(uprightBitmap, 512, 512, true)

                // STEP 2: Convert the image into FLOAT32 bytes (The math the AI actually eats)
                // 512 width * 512 height * 3 colors (RGB) * 4 bytes (Float)
                val byteBuffer = java.nio.ByteBuffer.allocateDirect(512 * 512 * 3 * 4)
                byteBuffer.order(java.nio.ByteOrder.nativeOrder())

                val intValues = IntArray(512 * 512)
                resizedBitmap.getPixels(intValues, 0, 512, 0, 0, 512, 512)

                var pixel = 0
                for (i in 0 until 512) {
                    for (j in 0 until 512) {
                        val valInt = intValues[pixel++]
                        // Extract Red, Green, Blue and convert to Float (normalized 0 to 1)
                        byteBuffer.putFloat(((valInt shr 16) and 0xFF) / 255.0f)
                        byteBuffer.putFloat(((valInt shr 8) and 0xFF) / 255.0f)
                        byteBuffer.putFloat((valInt and 0xFF) / 255.0f)
                    }
                }

                // STEP 3: Create the NEW YOLO Output Bucket
// 4 box numbers + 6 class numbers = 10 data points per box!
                val outputTensor = Array(1) { Array(10) { FloatArray(5376) } }

                // STEP 4: Push the image in and catch the YOLO output!
                tflite?.run(byteBuffer, outputTensor)

                // STEP 5: Decode the YOLO Grid to find the best object!
                var bestScore = 0.0f
                var bestClassIndex = -1
                var bestBoxIndex = -1

                // UPDATE: We now have 5 classes!
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

                // STEP 6: Display the text AND draw the box!
                runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    val overlay = findViewById<BoundingBoxOverlay>(R.id.boxOverlay)

                    val labels = arrayOf("Chair", "Door", "Staircase", "Table", "Unmarked Step", "Motorcycle")
                    val detectedName = labels[bestClassIndex]

                    // ==========================================
                    // PHASE 2: DO THE MATH FIRST
                    // ==========================================
                    // We must pull the coordinates out of the AI matrix first so we can calculate the area!

                    var cx = outputTensor[0][0][bestBoxIndex]
                    var cy = outputTensor[0][1][bestBoxIndex]
                    var w = outputTensor[0][2][bestBoxIndex]
                    var h = outputTensor[0][3][bestBoxIndex]

                    // Normalize to 0.0 - 1.0 scale if necessary
                    if (cx > 2f) {
                        cx /= 512f
                        cy /= 512f
                        w /= 512f
                        h /= 512f
                    }

                    // Calculate Area Percentage for the Audio Filter
                    val screenPercentage = (w * h) * 100f

                    // Calculate Physical Screen Coordinates for the Green Box
                    val screenW = overlay.width.toFloat()
                    val screenH = overlay.height.toFloat()
                    val left = (cx - w / 2) * screenW
                    val top = (cy - h / 2) * screenH
                    val right = (cx + w / 2) * screenW
                    val bottom = (cy + h / 2) * screenH

                    // ==========================================
                    // PHASE 3: THE SAFETY FILTER & SPAM BLOCKER
                    // ==========================================
                    var shouldSpeak = false

                    // The AI must pass BOTH the Confidence grade AND the Size (Distance) grade
                    when (detectedName) {
                        "Chair" -> {
                            if (screenPercentage > 35f && bestScore > 0.75f) shouldSpeak = true
                        }
                        "Door" -> {
                            if (screenPercentage > 75f && bestScore > 0.75f) shouldSpeak = true
                        }
                        "Staircase" -> {
                            if (screenPercentage > 20f && bestScore > 0.60f) shouldSpeak = true
                        }
                        "Table" -> {
                            if (screenPercentage > 40f && bestScore > 0.60f) shouldSpeak = true
                        }
                        "Unmarked Step" -> {
                            if (screenPercentage > 20f && bestScore > 0.65f) shouldSpeak = true
                        }
                        else -> { // Motorcycle or anything else
                            if (screenPercentage > 30f && bestScore > 0.75f) shouldSpeak = true
                        }
                    }

                    // The Execution & Debounce Engine
                    if (shouldSpeak) {
                        val currentTime = System.currentTimeMillis()

                        // Check the 2.5-second cooldown timer
                        if (currentTime - lastSpokenTime > cooldownTimer) {

                            // 🎤 FIRE THE AUDIO!
                            if (isTtsReady) {
                                tts.speak(detectedName, TextToSpeech.QUEUE_FLUSH, null, null)
                            }

                            // Lock the timer
                            lastSpokenTime = currentTime

                            // Update UI to show audio fired
                            debugText.text = "DETECTED: $detectedName - AUDIO TRIGGERED!"
                            overlay.drawBox(android.graphics.RectF(left, top, right, bottom))

                        } else {
                            // Object is verified close, but we are in audio cooldown
                            debugText.text = "DETECTED: $detectedName (Audio Cooldown...)"
                            overlay.drawBox(android.graphics.RectF(left, top, right, bottom))
                        }
                    } else {
                        // The object is either too far away, or the AI isn't confident enough
                        // We do not draw the box or speak, keeping the interface clean
                        debugText.text = "Scanning (Objects clear/too far)..."
                        overlay.drawBox(null)
                    }
                }
                /* runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    val overlay = findViewById<BoundingBoxOverlay>(R.id.boxOverlay)

                    // FIX: All 6 classes perfectly synced! (Verify this exact order with your AI Lead)
                    val labels = arrayOf("Chair", "Door", "Staircase", "Table", "Unmarked Step", "Motorcycle")
                    val detectedName = labels[bestClassIndex]

                    // 🧠 CLASS-SPECIFIC THRESHOLDS: Set custom passing grades for each object!
                    val passingGrade = when (detectedName) {
                        "Chair" -> 0.85f      // STRICT: Must be 85% sure to avoid carpets and windows
                        "Staircase" -> 0.60f  // LENIENT: Only needs to be 60% sure
                        "Unmarked Step" -> 0.65f
                        "Table" -> 0.60f      // NEW: 80% is usually solid for large furniture
                        else -> 0.75f         // DEFAULT: 75% for Doors, Steps, and Motorcycles
                    }

                    // Now, test the AI's score against the custom passing grade
                    if (bestScore > passingGrade) {

                        val percent = String.format("%.1f", bestScore * 100)

                        // Get the Box Math from the AI
                        var cx = outputTensor[0][0][bestBoxIndex]
                        var cy = outputTensor[0][1][bestBoxIndex]
                        var w = outputTensor[0][2][bestBoxIndex]
                        var h = outputTensor[0][3][bestBoxIndex]

                        if (cx > 2f) {
                            cx /= 512f
                            cy /= 512f
                            w /= 512f
                            h /= 512f
                        }

                        // Map to your phone's screen
                        val screenW = overlay.width.toFloat()
                        val screenH = overlay.height.toFloat()

                        val left = (cx - w / 2) * screenW
                        val top = (cy - h / 2) * screenH
                        val right = (cx + w / 2) * screenW
                        val bottom = (cy + h / 2) * screenH

                        // Display the REAL name on the screen!
                        debugText.text = "DETECTED: $detectedName ($percent%)"

                        // Draw the Green Box!
                        overlay.drawBox(android.graphics.RectF(left, top, right, bottom))

                    } else {
                        debugText.text = "Scanning..."
                        overlay.drawBox(null)
                    }

                    // NEW! PHASE 3: The Safety Filter Logic Switch
                    var shouldSpeak = false

                    when (detectedName) {
                        "Chair" -> {
                            if (screenPercentage > 35f && bestScore > 0.75f) {
                                shouldSpeak = true
                            }
                        }
                        "Door" -> {
                            if (screenPercentage > 75f && bestScore > 0.75f) {
                                shouldSpeak = true
                            }
                        }
                        "Staircase" -> {
                            // Example: Stairs might be dangerous further away, so lower percentage needed
                            if (screenPercentage > 20f && bestScore > 0.60f) {
                                shouldSpeak = true
                            }
                        }
                        // Add other cases (Table, Unmarked Step, Motorcycle) as needed
                    }

                //NEW! PHASE 3: The Spam Blocker (Debounce Execution)
                    if (shouldSpeak) {
                        val currentTime = System.currentTimeMillis()

                        // Check if 2.5 seconds have passed since the last announcement
                        if (currentTime - lastSpokenTime > cooldownTimer) {

                            // Trigger the audio!
                            if (isTtsReady) {
                                tts.speak(detectedName, TextToSpeech.QUEUE_FLUSH, null, null)
                            }

                            // Lock the timer
                            lastSpokenTime = currentTime

                            // Update UI
                            debugText.text = "DETECTED: $detectedName - AUDIO TRIGGERED!"
                            overlay.drawBox(android.graphics.RectF(left, top, right, bottom))
                        } else {
                            // Object is close, but we are in cooldown so we don't spam the user
                            debugText.text = "DETECTED: $detectedName (Audio Cooldown...)"
                            overlay.drawBox(android.graphics.RectF(left, top, right, bottom))
                        }
                    } else {
                        // Object detected but too far away/low confidence
                        debugText.text = "Scanning (Object too far)..."
                        overlay.drawBox(null)
                    }
                } */

            } catch (e: Exception) {
                // If the AI outputs a different shape (e.g., it's a YOLO model instead of SSD), we will catch the error here so the app doesn't crash.
                runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    debugText.text = "AI Engine Note: ${e.message}"
                }
            }
        }

        imageProxy.close() // ALWAYS close the frame so the camera doesn't freeze!
    }

    override fun onDestroy() {
        super.onDestroy()
        tflite?.close()
        cameraExecutor.shutdown()
        // NEW!
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}