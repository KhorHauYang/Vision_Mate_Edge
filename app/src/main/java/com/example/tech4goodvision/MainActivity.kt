package com.example.tech4goodvision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

                // STEP 3: Create the YOLO Output Bucket
                // Your teammate's YOLO model outputs one giant grid: [1 batch, 7 data points, 5376 boxes]
                val outputTensor = Array(1) { Array(7) { FloatArray(5376) } }

                // STEP 4: Push the image in and catch the YOLO output!
                tflite?.run(byteBuffer, outputTensor)

                // STEP 5: Decode the YOLO Grid to find the best object!
                var bestScore = 0.0f
                var bestClassIndex = -1
                var bestBoxIndex = -1 // <-- We need to track WHICH box won!

                val numClasses = 3

                for (i in 0 until 5376) {
                    for (c in 0 until numClasses) {
                        val score = outputTensor[0][4 + c][i]
                        if (score > bestScore) {
                            bestScore = score
                            bestClassIndex = c
                            bestBoxIndex = i // Remember the box's ID number!
                        }
                    }
                }

                // STEP 6: Display the text AND draw the box!
                runOnUiThread {
                    val debugText = findViewById<TextView>(R.id.tv_debug_log)
                    val overlay = findViewById<BoundingBoxOverlay>(R.id.boxOverlay)

                    if (bestScore > 0.75f) {
                        val labels = arrayOf("Class 0", "Class 1", "Class 2")
                        val detectedName = labels[bestClassIndex]
                        val percent = String.format("%.1f", bestScore * 100)

                        // 2. Get the Box Math from the AI
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

                        // 3. Map to your phone's screen
                        val screenW = overlay.width.toFloat()
                        val screenH = overlay.height.toFloat()

                        val left = (cx - w / 2) * screenW
                        val top = (cy - h / 2) * screenH
                        val right = (cx + w / 2) * screenW
                        val bottom = (cy + h / 2) * screenH

                        // THE DEBUGGER: Print the actual coordinates to the screen!
                        debugText.text = "DETECTED: $detectedName ($percent%)\nBox: [L:${left.toInt()}, T:${top.toInt()}, R:${right.toInt()}, B:${bottom.toInt()}]"

                        // 4. Draw the Green Box!
                        overlay.drawBox(android.graphics.RectF(left, top, right, bottom))

                    } else {
                        // FIX THE FROZEN TEXT! Clear it out when nothing is found.
                        debugText.text = "Scanning..."
                        overlay.drawBox(null)
                    }
                }

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
    }
}