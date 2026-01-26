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
    private val apiService = PostItApiService()

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

        // Reset button: clears uploaded state so post-its can be re-sent
        binding.uploadButton.setOnClickListener {
            uploadedTrackIds.clear()
            uploadingTrackIds.clear()
            Toast.makeText(this, "Upload state reset", Toast.LENGTH_SHORT).show()
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
        // Find detections with OCR text that haven't been uploaded yet
        val toUpload = detections.filter { det ->
            det.trackId != null &&
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

            // Calculate rotated dimensions - matches how PreviewView displays the image
            // and how PostItDetector now returns coordinates (in rotated/display space)
            val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (isRotated90or270) bitmap.height else bitmap.width
            val displayHeight = if (isRotated90or270) bitmap.width else bitmap.height

            // Rotate bitmap to match detection coordinates (upright orientation)
            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

            if (detections.isEmpty()) {
                // No detections - just report empty
                onResult("", detections, displayWidth, displayHeight)
                imageProxy.close()
                return
            }

            // Run OCR on each detected post-it region
            val ocrTasks = detections.map { detection ->
                val croppedBitmap = cropDetectionRegion(rotatedBitmap, detection.rect)
                if (croppedBitmap != null) {
                    val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
                    recognizer.process(inputImage)
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

            // Wait for all OCR tasks to complete
            Tasks.whenAllComplete(ocrTasks)
                .addOnCompleteListener {
                    // Mark detections that have already been uploaded
                    detections.forEach { det ->
                        if (det.trackId != null && det.trackId in uploadedTrackIds) {
                            det.uploaded = true
                        }
                    }

                    // Auto-upload any new detections with OCR text
                    autoUploadNewDetections(detections, rotatedBitmap)

                    // Combine all OCR text for the status display
                    val combinedText = detections
                        .filter { it.ocrText.isNotBlank() }
                        .joinToString("\n") { it.ocrText }

                    onResult(combinedText, detections, displayWidth, displayHeight)
                    imageProxy.close()
                }
        }
    }
}
