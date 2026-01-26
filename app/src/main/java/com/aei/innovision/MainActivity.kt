package com.aei.innovision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aei.innovision.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yuvToRgbConverter: YuvToRgbConverter
    private lateinit var postItDetector: PostItDetector

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    private val apiService = PostItApiService()

    // Track locked post-its: user tapped to confirm OCR text (trackId -> locked text)
    private val lockedPostIts = mutableMapOf<Int, String>()

    // Track which post-its have been uploaded or are in-flight
    private val uploadedTrackIds = mutableSetOf<Int>()
    private val uploadingTrackIds = mutableSetOf<Int>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            binding.statusText.text = getString(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        yuvToRgbConverter = YuvToRgbConverter(this)
        postItDetector = PostItDetector(this)

        // Tap on a detection box to lock its OCR text
        binding.overlayView.onDetectionTapped = { detection ->
            val trackId = detection.trackId
            if (trackId != null && detection.ocrText.isNotBlank()) {
                if (trackId in lockedPostIts) {
                    // Already locked - unlock it
                    lockedPostIts.remove(trackId)
                    Toast.makeText(this, "Unlocked post-it #$trackId", Toast.LENGTH_SHORT).show()
                } else {
                    lockedPostIts[trackId] = detection.ocrText
                    Toast.makeText(this, "Locked: \"${detection.ocrText}\"", Toast.LENGTH_SHORT).show()
                }
            } else if (trackId != null && detection.ocrText.isBlank()) {
                Toast.makeText(this, "No text detected yet - try again", Toast.LENGTH_SHORT).show()
            }
        }

        // Reset button: clears all locked/uploaded state
        binding.uploadButton.setOnClickListener {
            lockedPostIts.clear()
            uploadedTrackIds.clear()
            uploadingTrackIds.clear()
            Toast.makeText(this, "All states reset", Toast.LENGTH_SHORT).show()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun autoUploadNewDetections(
        detections: List<PostItDetector.Detection>,
        image: Bitmap
    ) {
        // Only upload locked detections that haven't been uploaded yet
        val toUpload = detections.filter { det ->
            det.trackId != null &&
                det.locked &&
                det.ocrText.isNotBlank() &&
                det.trackId !in uploadedTrackIds &&
                det.trackId !in uploadingTrackIds
        }

        if (toUpload.isEmpty()) return

        // Mark as in-flight
        val trackIds = toUpload.mapNotNull { it.trackId }.toSet()
        uploadingTrackIds.addAll(trackIds)

        Log.d("MainActivity", "Auto-uploading ${toUpload.size} new post-its (trackIds: $trackIds)")

        apiService.uploadPostIts(toUpload, image) { result ->
            result.fold(
                onSuccess = {
                    Log.d("MainActivity", "Upload confirmed for trackIds: $trackIds")
                    uploadedTrackIds.addAll(trackIds)
                    uploadingTrackIds.removeAll(trackIds)
                },
                onFailure = { error ->
                    Log.e("MainActivity", "Upload failed for trackIds: $trackIds", error)
                    // Remove from in-flight so retry is possible on next frame
                    uploadingTrackIds.removeAll(trackIds)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        postItDetector.close()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, PostItAnalyzer { text, detections, width, height ->
                            runOnUiThread {
                                binding.statusText.text = if (text.isBlank()) {
                                    getString(R.string.no_text_detected)
                                } else {
                                    text
                                }
                                binding.overlayView.setDetections(detections, width, height)
                            }
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer,
                )
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropDetectionRegion(bitmap: Bitmap, rect: android.graphics.RectF): Bitmap? {
        // Clamp coordinates to bitmap bounds
        val left = max(0, rect.left.toInt())
        val top = max(0, rect.top.toInt())
        val right = min(bitmap.width, rect.right.toInt())
        val bottom = min(bitmap.height, rect.bottom.toInt())

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return null

        return try {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to crop bitmap", e)
            null
        }
    }

    private inner class PostItAnalyzer(
        private val onResult: (String, List<PostItDetector.Detection>, Int, Int) -> Unit,
    ) : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class) override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            yuvToRgbConverter.yuvToRgb(mediaImage, bitmap)

            // Pass the rotation degrees to the detector
            val detections = postItDetector.detect(bitmap, rotationDegrees)

            // Calculate rotated dimensions
            val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (isRotated90or270) bitmap.height else bitmap.width
            val displayHeight = if (isRotated90or270) bitmap.width else bitmap.height

            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            // 1. Barcode Scanning for Server URL
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (rawValue != null && (rawValue.startsWith("http://") || rawValue.startsWith("https://"))) {
                            // Extract IP and Port from URL (simple parsing)
                            try {
                                val url = java.net.URL(rawValue)
                                if (PostItApiService.SERVER_IP != url.host || PostItApiService.SERVER_PORT != url.port) {
                                    PostItApiService.SERVER_IP = url.host
                                    PostItApiService.SERVER_PORT = if (url.port != -1) url.port else 80
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "Server set to: ${PostItApiService.serverUrl}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to parse URL from QR", e)
                            }
                        }
                    }
                }

            // Rotate bitmap for OCR
            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

            if (detections.isEmpty()) {
                onResult("", detections, displayWidth, displayHeight)
                imageProxy.close()
                return
            }

            // Apply locked/uploaded state
            detections.forEach { det ->
                val trackId = det.trackId ?: return@forEach
                if (trackId in lockedPostIts) {
                    det.locked = true
                    det.ocrText = lockedPostIts[trackId] ?: ""
                }
                if (trackId in uploadedTrackIds) {
                    det.uploaded = true
                }
            }

            // Run OCR on detection regions
            val ocrTasks = detections.map { detection ->
                if (detection.locked) {
                    Tasks.forResult(detection)
                } else {
                    val croppedBitmap = cropDetectionRegion(rotatedBitmap, detection.rect)
                    if (croppedBitmap != null) {
                        val cropImage = InputImage.fromBitmap(croppedBitmap, 0)
                        recognizer.process(cropImage)
                            .continueWith { task ->
                                if (task.isSuccessful) {
                                    detection.ocrText = task.result?.text?.replace("\n", " ") ?: ""
                                }
                                detection
                            }
                    } else {
                        Tasks.forResult(detection)
                    }
                }
            }

            Tasks.whenAllComplete(ocrTasks)
                .addOnCompleteListener {
                    autoUploadNewDetections(detections, rotatedBitmap)
                    val combinedText = detections
                        .filter { it.ocrText.isNotBlank() }
                        .joinToString("\n") { it.ocrText }

                    onResult(combinedText, detections, displayWidth, displayHeight)
                    imageProxy.close()
                }
        }
    }
}
