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
import androidx.appcompat.app.AlertDialog
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
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min


private const val STRONG_MATCH = 0.78f
private const val STICKY_FLOOR = 0.40f
private const val CANDIDATE_FLOOR = 0.25f

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
    private var isServerFound = false
    private var hasReceivedSuggestions = false
    private var activeSessionId: String? = null
    private var isCapturingSceneImage = false
    private var isSceneUploadRequested = false
    private var lastJoinPromptSessionId: String? = null
    private var isJoinPromptShowing = false

    // Suggestions from backend (initialized with defaults)
    @Volatile
    private var suggestedContents: List<String> = listOf(
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
    private val isSpeechEnabled = false

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

        updateSuggestionsIndicator()
        initWebSocket()
        checkPermissions()
    }

    private fun initWebSocket() {
        apiService.onConnectionStatusChanged = { connected ->
            runOnUiThread {
                binding.connectionStatusIcon.setImageResource(
                    if (connected) android.R.drawable.presence_online 
                    else android.R.drawable.presence_offline
                )
                binding.statusText.text = when {
                    isSessionActive && !activeSessionId.isNullOrBlank() ->
                        if (connected) "Session: $activeSessionId" else "Session offline"
                    connected -> "Connected to Backend"
                    else -> "Backend Offline"
                }
            }
        }

        apiService.onSuggestionsReceived = { newSuggestions ->
            runOnUiThread {
                suggestedContents = newSuggestions.toList()
                matchedSuggestions.clear()
                if (!hasReceivedSuggestions) {
                    hasReceivedSuggestions = true
                    Toast.makeText(this, "Suggestions updated from backend", Toast.LENGTH_SHORT).show()
                }
                updateSuggestionsIndicator()
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
        hasReceivedSuggestions = false
        activeSessionId = null
        isCapturingSceneImage = false
        isSceneUploadRequested = false
        lastJoinPromptSessionId = null
        isJoinPromptShowing = false
        updateSuggestionsIndicator()
        apiService.stopWebSocket()
        Toast.makeText(this, "All states reset", Toast.LENGTH_SHORT).show()
    }

    private fun promptJoinSession(sessionId: String?) {
        val resolvedSessionId = sessionId ?: return
        if (isJoinPromptShowing || isSessionActive) return
        isJoinPromptShowing = true
        lastJoinPromptSessionId = resolvedSessionId
        AlertDialog.Builder(this)
            .setTitle("Join session?")
            .setMessage("Join session $resolvedSessionId on ${PostItApiService.SERVER_IP}?")
            .setPositiveButton("Join") { _, _ ->
                joinSession(resolvedSessionId)
            }
            .setNegativeButton("Not now") { _, _ ->
                isJoinPromptShowing = false
            }
            .setOnCancelListener { isJoinPromptShowing = false }
            .show()
    }

    private fun joinSession(sessionId: String) {
        apiService.joinSession(sessionId) { result ->
            runOnUiThread {
                isJoinPromptShowing = false
                result.fold(
                    onSuccess = { response ->
                        if (response.success) {
                            val resolvedSessionId = response.session_id ?: sessionId
                            isSessionActive = true
                            activeSessionId = resolvedSessionId
                            apiService.startWebSocket(resolvedSessionId)
                            response.suggestions?.let { suggestions ->
                                suggestedContents = suggestions.toList()
                                matchedSuggestions.clear()
                                hasReceivedSuggestions = true
                                updateSuggestionsIndicator()
                            }
                            binding.statusText.text = "Session: $resolvedSessionId"
                            Toast.makeText(this, "Joined session", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Join failed: ${response.message ?: "Server reported failure"}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = {
                        Toast.makeText(this, "Join failed, check server", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun updateSuggestionsIndicator() {
        val suggestionsCount = if (hasReceivedSuggestions) suggestedContents.size else 0
        val hasSuggestions = suggestionsCount > 0

        binding.suggestionsCountText.text = suggestionsCount.toString()
        binding.suggestionsCountText.visibility = if (hasReceivedSuggestions) View.VISIBLE else View.GONE

        binding.suggestionsCountText.contentDescription = if (hasSuggestions) {
            "Suggestions available: $suggestionsCount"
        } else {
            "No suggestions available"
        }
    }

    private fun extractSessionIdFromUrl(rawValue: String): String? {
        return try {
            val uri = URI(rawValue)
            val query = uri.query.orEmpty()
            val queryParams = query.split("&")
                .filter { it.contains("=") }
                .associate {
                    val parts = it.split("=")
                    parts[0] to parts.getOrElse(1) { "" }
                }
            val sessionFromQuery = queryParams["session_id"] ?: queryParams["sessionId"]
            if (!sessionFromQuery.isNullOrBlank() && looksLikeSessionId(sessionFromQuery)) {
                return sessionFromQuery
            }
            val pathSegments = uri.path?.trim('/')?.split('/')?.filter { it.isNotBlank() }.orEmpty()
            val sessionIndex = pathSegments.indexOf("sessions")
            val fromSessions = if (sessionIndex != -1) pathSegments.getOrNull(sessionIndex + 1) else null
            if (!fromSessions.isNullOrBlank() && looksLikeSessionId(fromSessions)) {
                return fromSessions
            }
            val lastSegment = pathSegments.lastOrNull()
            if (!lastSegment.isNullOrBlank() && looksLikeSessionId(lastSegment)) {
                return lastSegment
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse session ID from QR: $rawValue", e)
            null
        }
    }

    private fun looksLikeSessionId(value: String): Boolean {
        val uuidRegex = Regex("^[0-9a-fA-F-]{32,36}$")
        return uuidRegex.matches(value)
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
        if (!isSpeechEnabled) return
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

    private fun requestSceneUpload() {
        if (!isSessionActive) {
            Toast.makeText(this, "Join a session first", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSceneUploadRequested) {
            Toast.makeText(this, "Scene upload already requested", Toast.LENGTH_SHORT).show()
            return
        }
        isSceneUploadRequested = true
        isCapturingSceneImage = true
        Toast.makeText(this, "Capturing scene image...", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focused = binding.overlayView.getFocusedDetection()
            if (focused != null) {
                toggleLock(focused)
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            requestSceneUpload()
            return true
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
                    it.setAnalyzer(cameraExecutor, PostItAnalyzer { text, detections, width, height, rotationDegrees ->
                        runOnUiThread {
                            val currentStatus = binding.statusText.text.toString()
                            val voiceStates = listOf("Heard", "Listening...", "Recording...", "Processing...", "Voice Error", "Mic starting...", "Connected", "Offline")
                            if (!voiceStates.any { currentStatus.startsWith(it) }) {
                                binding.statusText.text = if (text.isBlank()) "Looking for post-its..." else text
                            }
                        binding.overlayView.setDetections(detections, width, height, rotationDegrees)
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
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Skipping crop with invalid size w=$width h=$height rect=$rect")
            return null
        }
        return try { Bitmap.createBitmap(bitmap, left, top, width, height) } catch (e: Exception) {
            Log.w(TAG, "Crop failed for rect=$rect size=$width x $height", e)
            null
        }
    }

    private inner class PostItAnalyzer(
        private val onResult: (String, List<PostItDetector.Detection>, Int, Int, Int) -> Unit,
        ) : ImageAnalysis.Analyzer {
        private var lastQrScanTime = 0L

        @OptIn(ExperimentalGetImage::class) override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            
            // 1. QR Scanning - until session joins
            if (!isSessionActive) {
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
                                        val sessionId = extractSessionIdFromUrl(rawValue)
                                        val shouldPrompt = sessionId != null && sessionId != lastJoinPromptSessionId
                                        if (!isServerFound) {
                                            isServerFound = true
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity, "Server Connected: ${PostItApiService.SERVER_IP}", Toast.LENGTH_LONG).show()
                                                binding.statusText.text = "Server: ${PostItApiService.SERVER_IP}"
                                            }
                                        }
                                        if (shouldPrompt) {
                                            sessionId?.let { nonNullSessionId ->
                                                runOnUiThread { promptJoinSession(nonNullSessionId) }
                                            }
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

            // 2. Skip expensive processing if session not active
            if (!isSessionActive) {
                onResult("", emptyList(), imageProxy.width, imageProxy.height, rotationDegrees)
                imageProxy.close()
                return
            }

            // 3. Perform expensive YUV->RGB conversion ONLY when needed
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            yuvToRgbConverter.yuvToRgb(mediaImage, bitmap)
            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

            if (isCapturingSceneImage && isSceneUploadRequested) {
                isCapturingSceneImage = false
                isSceneUploadRequested = false
                apiService.uploadSceneImage(rotatedBitmap) { result ->
                    runOnUiThread {
                        result.fold(
                            onSuccess = {
                                Toast.makeText(this@MainActivity, "Scene image uploaded", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = {
                                Toast.makeText(this@MainActivity, "Scene upload failed", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // Hold detection until session is active
            if (!isSessionActive) {
                onResult("", emptyList(), rotatedBitmap.width, rotatedBitmap.height, rotationDegrees)
                imageProxy.close()
                return
            }

            val detections = postItDetector.detect(rotatedBitmap)
            val displayWidth = rotatedBitmap.width
            val displayHeight = rotatedBitmap.height

            if (detections.isEmpty()) {
                onResult("", detections, displayWidth, displayHeight, rotationDegrees)
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

                                    val trackId = detection.trackId ?: return@continueWith detection
                                    val previous = matchedSuggestions[trackId]

                                    val suggestionsSnapshot = suggestedContents
                                    val best = FuzzyMatcher.findBestMatch(rawOcr, suggestionsSnapshot, 0.6f)
                                    val bestCandidate = FuzzyMatcher.findBestCandidate(rawOcr, suggestionsSnapshot)
                                    val bestScore = best?.second ?: 0f
                                    val candidateScore = bestCandidate?.second ?: 0f
                                    val previousScore = if (previous != null) FuzzyMatcher.getSimilarity(rawOcr, previous) else 0f

                                    Log.d(
                                        TAG,
                                        "OCR track=$trackId raw='$rawOcr' best='${best?.first ?: ""}' score=$bestScore candidate='${bestCandidate?.first ?: ""}' candidateScore=$candidateScore prev='${previous ?: ""}' prevScore=$previousScore suggestions=${suggestionsSnapshot.size}"
                                    )

                                    when {
                                        // 1. Strong new match → override & stick
                                        best != null && best.second >= STRONG_MATCH -> {
                                            detection.ocrText = best.first
                                            matchedSuggestions[trackId] = best.first
                                        }

                                        // 2. Weak OCR but previous match still plausible → stay sticky
                                        previous != null && FuzzyMatcher.getSimilarity(rawOcr, previous) >= STICKY_FLOOR -> {
                                            detection.ocrText = previous
                                        }

                                        // 3. Use best candidate when it clears the floor and beats prior similarity
                                        bestCandidate != null &&
                                            candidateScore >= CANDIDATE_FLOOR &&
                                            candidateScore >= previousScore -> {
                                            detection.ocrText = bestCandidate.first
                                            matchedSuggestions[trackId] = bestCandidate.first
                                        }

                                        // 4. Otherwise show raw OCR
                                        else -> {
                                            detection.ocrText = rawOcr
                                            matchedSuggestions.remove(trackId)
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
                onResult(
                    detections.filter { it.ocrText.isNotBlank() }.joinToString("\n") { it.ocrText!! },
                    detections,
                    displayWidth,
                    displayHeight,
                    rotationDegrees
                )
                imageProxy.close()
            }
        }
    }
}