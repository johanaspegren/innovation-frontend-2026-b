package com.aei.innovision

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
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
import androidx.core.app.ActivityCompat
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
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

    // Voice Components
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false
    private var lastAskedTrackId: Int? = null

    // Simulated suggestions from backend
    private val suggestedContents = listOf(
        "Cool Stuff",
        "MORE AI",
        "Refinement",
        "Sprint 2026",
        "Idea Board",
        "Innovation"
    )

    // Track locked post-its: user confirmed OCR text (trackId -> locked text)
    private val lockedPostIts = mutableMapOf<Int, String>()
    
    // Stabilize suggestions: trackId -> last matched suggestion
    private val matchedSuggestions = mutableMapOf<Int, String>()

    // Track which post-its have been uploaded or are in-flight
    private val uploadedTrackIds = mutableSetOf<Int>()
    private val uploadingTrackIds = mutableSetOf<Int>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            initSpeechRecognizer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        yuvToRgbConverter = YuvToRgbConverter(this)
        postItDetector = PostItDetector(this)
        tts = TextToSpeech(this, this)

        // Tap on a detection box to lock its OCR text
        binding.overlayView.onDetectionTapped = { detection ->
            toggleLock(detection)
        }

        // Reset button: clears all locked/uploaded state
        binding.uploadButton.setOnClickListener {
            resetAll()
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isEmpty()) {
            startCamera()
            initSpeechRecognizer()
        } else {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun resetAll() {
        lockedPostIts.clear()
        uploadedTrackIds.clear()
        uploadingTrackIds.clear()
        matchedSuggestions.clear()
        lastAskedTrackId = null
        Toast.makeText(this, "All states reset", Toast.LENGTH_SHORT).show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        // On some devices (like Pixel), the default SpeechRecognizer might point to an internal
        // service (like SODA) that third-party apps are not allowed to bind to, causing a SecurityException.
        // We explicitly target the standard Google Recognition Service to avoid this.
        val googleServiceComponent = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.service.GoogleRecognitionService"
        )

        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this, googleServiceComponent)
        } catch (e: Exception) {
            // Fallback to default if the explicit component is not found or fails
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    val text = matches[0].lowercase()
                    if (text.contains("yes") || text.contains("save") || text.contains("confirm") || text.contains("correct")) {
                        val focused = binding.overlayView.getFocusedDetection()
                        if (focused != null && !focused.locked) {
                            runOnUiThread { toggleLock(focused) }
                            speak("Saved")
                        }
                    }
                }
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening || !::speechRecognizer.isInitialized) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "innovision_tts")
    }

    private fun toggleLock(detection: PostItDetector.Detection) {
        // Stop assistant if it's currently speaking or listening
        if (tts.isSpeaking) tts.stop()
        if (isListening && ::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
            isListening = false
        }

        val trackId = detection.trackId
        if (trackId != null && detection.ocrText.isNotBlank()) {
            if (trackId in lockedPostIts) {
                lockedPostIts.remove(trackId)
                Toast.makeText(this, "Unlocked post-it #$trackId", Toast.LENGTH_SHORT).show()
            } else {
                lockedPostIts[trackId] = detection.ocrText
                Toast.makeText(this, "Locked: \"${detection.ocrText}\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focused = binding.overlayView.getFocusedDetection()
            if (focused != null) {
                toggleLock(focused)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun processVoiceAssistant(detections: List<PostItDetector.Detection>) {
        val focused = binding.overlayView.getFocusedDetection() ?: return
        val trackId = focused.trackId ?: return
        
        if (focused.ocrText.isNotBlank() && !focused.locked && !focused.uploaded && trackId != lastAskedTrackId) {
            if (matchedSuggestions.containsKey(trackId)) {
                lastAskedTrackId = trackId
                val suggestion = focused.ocrText
                speak("Save $suggestion?")
                
                binding.root.postDelayed({
                    // Only start listening if the user didn't manually lock it while we were talking
                    if (lastAskedTrackId == trackId && !focused.locked) {
                        startListening()
                    }
                }, 1500)
            }
        }
    }

    private fun autoUploadNewDetections(
        detections: List<PostItDetector.Detection>,
        image: Bitmap
    ) {
        val toUpload = detections.filter { det ->
            det.trackId != null &&
                det.locked &&
                det.ocrText.isNotBlank() &&
                det.trackId !in uploadedTrackIds &&
                det.trackId !in uploadingTrackIds
        }

        if (toUpload.isEmpty()) return

        val trackIds = toUpload.mapNotNull { it.trackId }.toSet()
        uploadingTrackIds.addAll(trackIds)

        apiService.uploadPostIts(toUpload, image) { result ->
            result.fold(
                onSuccess = {
                    uploadedTrackIds.addAll(trackIds)
                    uploadingTrackIds.removeAll(trackIds)
                },
                onFailure = {
                    uploadingTrackIds.removeAll(trackIds)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        postItDetector.close()
        tts.stop()
        tts.shutdown()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
                                binding.statusText.text = if (text.isBlank()) "Looking for post-its..." else text
                                binding.overlayView.setDetections(detections, width, height)
                                processVoiceAssistant(detections)
                            }
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
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

            val detections = postItDetector.detect(bitmap, rotationDegrees)
            val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (isRotated90or270) bitmap.height else bitmap.width
            val displayHeight = if (isRotated90or270) bitmap.width else bitmap.height

            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (rawValue != null && (rawValue.startsWith("http://") || rawValue.startsWith("https://"))) {
                            try {
                                val url = java.net.URL(rawValue)
                                PostItApiService.SERVER_IP = url.host
                                PostItApiService.SERVER_PORT = if (url.port != -1) url.port else 80
                            } catch (e: Exception) {}
                        }
                    }
                }

            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

            if (detections.isEmpty()) {
                onResult("", detections, displayWidth, displayHeight)
                imageProxy.close()
                return
            }

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
                                    val rawOcr = task.result?.text?.replace("\n", " ") ?: ""
                                    val bestMatch = FuzzyMatcher.findBestMatch(rawOcr, suggestedContents, 0.6f)
                                    val trackId = detection.trackId
                                    
                                    if (bestMatch != null) {
                                        val (suggestion, _) = bestMatch
                                        detection.ocrText = suggestion
                                        if (trackId != null) matchedSuggestions[trackId] = suggestion
                                    } else {
                                        val prevMatch = trackId?.let { matchedSuggestions[it] }
                                        if (prevMatch != null && FuzzyMatcher.getSimilarity(rawOcr, prevMatch) > 0.35f) {
                                            detection.ocrText = prevMatch
                                        } else {
                                            detection.ocrText = rawOcr
                                        }
                                    }
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
                    val combinedText = detections.filter { it.ocrText.isNotBlank() }.joinToString("\n") { it.ocrText }
                    onResult(combinedText, detections, displayWidth, displayHeight)
                    imageProxy.close()
                }
        }
    }
}
