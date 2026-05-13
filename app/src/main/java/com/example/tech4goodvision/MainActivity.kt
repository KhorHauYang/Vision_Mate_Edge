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

        // Test Buttons
        findViewById<Button>(R.id.btn_test_audio).setOnClickListener {
            Toast.makeText(this, "Audio Test: 'Obstacle Ahead'", Toast.LENGTH_SHORT).show()
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
        // This is where the magic happens!
        // The camera frame is inside 'imageProxy'.
        // The AI is inside 'tflite'.

        if (tflite != null) {
            // STEP 1: Turn the camera image into math the AI understands
            // (We will add the 'Bitmap' conversion code once your teammate
            // tells us the exact input size of their model!)

            runOnUiThread {
                val debugText = findViewById<TextView>(R.id.tv_debug_log)
                debugText.text = "AI System: Active & Scanning..."
            }
        }

        imageProxy.close() // Don't forget to close!
    }

    override fun onDestroy() {
        super.onDestroy()
        tflite?.close()
        cameraExecutor.shutdown()
    }
}