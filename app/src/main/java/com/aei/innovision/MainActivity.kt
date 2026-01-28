package com.aei.innovision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
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
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastAskedTrackId: Int? = null

    // Session state
    private var isSessionActive = false
    private var isCapturingForStart = false
    private var isServerFound = false

    // Suggestions from backend (initialized with defaults)
    private var suggestedContents = mutableListOf(
        "Cool Stuff",
        "MORE AI",
        "Refinement",
        "Sprint 2026",
        "Idea Board",
        "Innovation"
    )

    private val lockedPostIts = mutableMapOf<Int, String>()
    private val matchedSuggestions = mutableMapOf<Int, String>()
    private val uploadedTrackIds = mutableSetOf<Int>()
    private val uploadingTrackIds = mutableSetOf<Int>()

    private val TAG = "InnovisionMain"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            initSpeechRecognizer()
        } else {
            Toast.makeText(this, "Microphone permission required for voice assistant", Toast.LENGTH_LONG).show()
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

        binding.overlayView.onDetectionTapped = { detection ->
            toggleLock(detection)
        }

        binding.uploadButton.setOnClickListener {
            resetAll()
        }

        binding.startButton.setOnClickListener {
            startSessionWithImage()
        }

        initWebSocket()
        checkPermissions()
    }

    private fun startSessionWithImage() {
        isCapturingForStart = true
        binding.startButton.isEnabled = false
        binding.startButton.text = "CAPTURING..."
        Toast.makeText(this, "Capturing whiteboard...", Toast.LENGTH_SHORT).show()
    }

    private fun initWebSocket() {
        apiService.onConnectionStatusChanged = { connected ->
            runOnUiThread {
                binding.connectionStatusIcon.setImageResource(
                    if (connected) android.R.drawable.presence_online 
                    else android.R.drawable.presence_offline
                )
                binding.statusText.text = if (connected) "Connected to Backend" else "Backend Offline"
            }
        }

        apiService.onSuggestionsReceived = { newSuggestions ->
            runOnUiThread {
                suggestedContents.clear()
                suggestedContents.addAll(newSuggestions)
                Log.d(TAG, "Updated suggestions: $newSuggestions")
            }
        }

        apiService.onSpeechRequested = { text ->
            runOnUiThread {
                speak(text, "backend_request")
            }
        }
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
        isSessionActive = false
        isServerFound = false
        binding.startButton.visibility = View.VISIBLE
        binding.startButton.isEnabled = true
        binding.startButton.text = "START SESSION"
        apiService.stopWebSocket()
        Toast.makeText(this, "All states reset", Toast.LENGTH_SHORT).show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "ask_confirmation") {
                        runOnUiThread { startListening() }
                    }
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == "ask_confirmation") {
                        runOnUiThread { startListening() }
                    }
                }
            })
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech Recognition not available")
            runOnUiThread { binding.statusText.text = "Voice Assistant: N/A" }
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { 
                isListening = true
                runOnUiThread { 
                    binding.statusText.text = "Listening..."
                    binding.micIcon.visibility = View.VISIBLE
                }
            }
            override fun onBeginningOfSpeech() {
                runOnUiThread { binding.statusText.text = "Recording..." }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { 
                isListening = false
                runOnUiThread { 
                    binding.statusText.text = "Processing..."
                    binding.micIcon.visibility = View.GONE
                }
            }
            override fun onError(error: Int) { 
                isListening = false
                runOnUiThread { 
                    binding.statusText.text = "Voice Error: $error"
                    binding.micIcon.visibility = View.GONE
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0].lowercase()
                    if (text.contains("yes") || text.contains("save") || text.contains("confirm") || text.contains("correct") || text.contains("yeah")) {
                        val focused = binding.overlayView.getFocusedDetection()
                        if (focused != null && !focused.locked) {
                            runOnUiThread { toggleLock(focused) }
                            speak("Saved", "confirm_done")
                        }
                    }
                }
                isListening = false
                runOnUiThread { binding.micIcon.visibility = View.GONE }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening || speechRecognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_STEADY_SILENCE_IN_MILLIS", 2000L)
        }
        runOnUiThread { 
            try {
                speechRecognizer?.startListening(intent) 
            } catch (e: Exception) {
                binding.micIcon.visibility = View.GONE
            }
        }
    }

    private fun speak(text: String, utteranceId: String = "innovision_tts") {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun toggleLock(detection: PostItDetector.Detection) {
        if (tts.isSpeaking) tts.stop()
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            runOnUiThread { binding.micIcon.visibility = View.GONE }
        }

        val trackId = detection.trackId
        if (trackId != null && detection.ocrText.isNotBlank()) {
            if (trackId in lockedPostIts) {
                lockedPostIts.remove(trackId)
            } else {
                lockedPostIts[trackId] = detection.ocrText
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
            lastAskedTrackId = trackId
            speak("Save ${focused.ocrText}?", "ask_confirmation")
        }
    }

    private fun autoUploadNewDetections(detections: List<PostItDetector.Detection>, image: Bitmap) {
        val toUpload = detections.filter { det ->
            det.trackId != null && det.locked && det.ocrText.isNotBlank() &&
            det.trackId !in uploadedTrackIds && det.trackId !in uploadingTrackIds
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
                onFailure = { uploadingTrackIds.removeAll(trackIds) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        postItDetector.close()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
        apiService.stopWebSocket()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
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
                            val currentStatus = binding.statusText.text.toString()
                            val voiceStates = listOf("Heard", "Listening...", "Recording...", "Processing...", "Voice Error", "Mic starting...", "Connected", "Offline")
                            if (!voiceStates.any { currentStatus.startsWith(it) }) {
                                binding.statusText.text = if (text.isBlank()) "Looking for post-its..." else text
                            }
                            binding.overlayView.setDetections(detections, width, height)
                            processVoiceAssistant(detections)
                        }
                    })
                }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropDetectionRegion(bitmap: Bitmap, rect: android.graphics.RectF): Bitmap? {
        val left = max(0, rect.left.toInt()); val top = max(0, rect.top.toInt())
        val right = min(bitmap.width, rect.right.toInt()); val bottom = min(bitmap.height, rect.bottom.toInt())
        val width = right - left; val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return try { Bitmap.createBitmap(bitmap, left, top, width, height) } catch (e: Exception) { null }
    }

    private inner class PostItAnalyzer(
        private val onResult: (String, List<PostItDetector.Detection>, Int, Int) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private var lastQrScanTime = 0L

        @OptIn(ExperimentalGetImage::class) override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            
            // 1. QR Scanning - Only if server not yet found
            if (!isServerFound) {
                val now = System.currentTimeMillis()
                if (now - lastQrScanTime > 1000) { // Scan once per second
                    lastQrScanTime = now
                    Log.d(TAG, "Scanning for QR/Server IP...")
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                val rawValue = barcode.rawValue
                                Log.i(TAG, "QR Code Detected: $rawValue")
                                if (rawValue != null && (rawValue.startsWith("http://") || rawValue.startsWith("https://"))) {
                                    try {
                                        val url = java.net.URL(rawValue)
                                        PostItApiService.SERVER_IP = url.host
                                        PostItApiService.SERVER_PORT = if (url.port != -1) url.port else 80
                                        isServerFound = true
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Server Connected: ${PostItApiService.SERVER_IP}", Toast.LENGTH_LONG).show()
                                            binding.statusText.text = "Server: ${PostItApiService.SERVER_IP}"
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Invalid URL in QR: $rawValue")
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                    return 
                } else {
                    imageProxy.close()
                    return
                }
            }

            // 2. Skip expensive processing if session not active and no capture requested
            if (!isSessionActive && !isCapturingForStart) {
                onResult("", emptyList(), imageProxy.width, imageProxy.height)
                imageProxy.close()
                return
            }

            // 3. Perform expensive YUV->RGB conversion ONLY when needed
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            yuvToRgbConverter.yuvToRgb(mediaImage, bitmap)
            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)
            
            // Capture for start session
            if (isCapturingForStart) {
                isCapturingForStart = false
                apiService.startSession(rotatedBitmap) { result ->
                    runOnUiThread {
                        result.fold(
                            onSuccess = { response ->
                                if (response.success && response.session_id != null) {
                                    val sessionId = response.session_id
                                    Toast.makeText(this@MainActivity, "Session Started! ID: $sessionId", Toast.LENGTH_LONG).show()
                                    binding.startButton.visibility = View.GONE
                                    isSessionActive = true
                                    apiService.startWebSocket(sessionId)
                                } else {
                                    binding.startButton.isEnabled = true
                                    binding.startButton.text = "START SESSION"
                                    Toast.makeText(this@MainActivity, "Session failed: ${response.message ?: "Server reported failure"}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onFailure = { 
                                binding.startButton.isEnabled = true
                                binding.startButton.text = "START SESSION"
                                Toast.makeText(this@MainActivity, "Capture failed, try again", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // Hold detection until session is active
            if (!isSessionActive) {
                onResult("", emptyList(), rotatedBitmap.width, rotatedBitmap.height)
                imageProxy.close()
                return
            }

            val detections = postItDetector.detect(bitmap, rotationDegrees)
            val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (isRotated90or270) bitmap.height else bitmap.width
            val displayHeight = if (isRotated90or270) bitmap.width else bitmap.height

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
                if (trackId in uploadedTrackIds) det.uploaded = true
            }

            val ocrTasks = detections.map { detection ->
                if (detection.locked) {
                    Tasks.forResult(detection)
                } else {
                    val croppedBitmap = cropDetectionRegion(rotatedBitmap, detection.rect)
                    if (croppedBitmap != null) {
                        recognizer.process(InputImage.fromBitmap(croppedBitmap, 0))
                            .continueWith { task ->
                                if (task.isSuccessful) {
                                    val rawOcr = task.result?.text?.replace("\n", " ") ?: ""
                                    val bestMatch = FuzzyMatcher.findBestMatch(rawOcr, suggestedContents, 0.6f)
                                    if (bestMatch != null) {
                                        detection.ocrText = bestMatch.first
                                        detection.trackId?.let { matchedSuggestions[it] = bestMatch.first }
                                    } else {
                                        val prevMatch = detection.trackId?.let { matchedSuggestions[it] }
                                        if (prevMatch != null && FuzzyMatcher.getSimilarity(rawOcr, prevMatch) > 0.35f) {
                                            detection.ocrText = prevMatch
                                        } else {
                                            detection.ocrText = rawOcr
                                        }
                                    }
                                }
                                detection
                            }
                    } else { Tasks.forResult(detection) }
                }
            }

            Tasks.whenAllComplete(ocrTasks).addOnCompleteListener {
                autoUploadNewDetections(detections, rotatedBitmap)
                onResult(detections.filter { it.ocrText.isNotBlank() }.joinToString("\n") { it.ocrText }, detections, displayWidth, displayHeight)
                imageProxy.close()
            }
        }
    }
}