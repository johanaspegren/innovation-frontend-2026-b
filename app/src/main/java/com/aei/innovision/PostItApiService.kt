package com.aei.innovision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class PostItApiService {

    // Store the active session ID
    private var activeSessionId: String? = null

    companion object {
        private const val TAG = "PostItApiService"
        var SERVER_IP = "192.168.1.100"
        var SERVER_PORT = 8080

        val postItsUrl: String get() = "http://$SERVER_IP:$SERVER_PORT/api/postits"
        val startSessionUrl: String get() = "http://$SERVER_IP:$SERVER_PORT/api/sessions/start"
        // WebSocket URL will be constructed with the session ID dynamically
        fun wsUrl(sessionId: String) = "ws://$SERVER_IP:$SERVER_PORT/ws/$sessionId"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    // Callbacks for WebSocket events
    var onSuggestionsReceived: ((List<String>) -> Unit)? = null
    var onSpeechRequested: ((String) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null

    data class BoundsDto(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class PostItDto(val trackId: Int?, val text: String, val confidence: Float, val bounds: BoundsDto)

    // New request/response DTOs based on SPEC.md
    data class StartSessionRequest(val timestamp: Long, val imageBase64: String)
    data class StartSessionResponse(val success: Boolean, val session_id: String? = null, val message: String? = null)
    
    data class UploadRequest(val timestamp: Long, val session_id: String, val postits: List<PostItDto>)
    data class UploadResponse(val success: Boolean, val message: String? = null, val id: String? = null)

    // WebSocket Message Format - Remains largely the same
    data class WsMessage(val type: String, val data: List<String>? = null, val text: String? = null)

    fun startSession(image: Bitmap, callback: (Result<StartSessionResponse>) -> Unit) {
        val request = StartSessionRequest(System.currentTimeMillis(), bitmapToBase64(image))
        val httpRequest = Request.Builder()
            .url(startSessionUrl)
            .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val result = gson.fromJson(body, StartSessionResponse::class.java)
                        // Store session ID upon successful start
                        activeSessionId = result.session_id
                        callback(Result.success(result))
                    } else {
                        callback(Result.failure(IOException("Start session failed: Code ${response.code}")))
                    }
                }
            }
        })
    }

    fun uploadPostIts(detections: List<PostItDetector.Detection>, image: Bitmap? = null, callback: (Result<UploadResponse>) -> Unit) {
        val sessionId = activeSessionId
        if (sessionId == null) {
            callback(Result.failure(IllegalStateException("Cannot upload post-its. Session ID is missing.")))
            return
        }

        val postItDtos = detections.map { detection ->
            PostItDto(
                trackId = detection.trackId,
                text = detection.ocrText,
                confidence = detection.score,
                bounds = BoundsDto(detection.rect.left, detection.rect.top, detection.rect.right, detection.rect.bottom)
            )
        }
        
        // Remove imageBase64 from UploadRequest as per new spec
        val request = UploadRequest(System.currentTimeMillis(), sessionId, postItDtos)
        val httpRequest = Request.Builder()
            .url(postItsUrl)
            .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) callback(Result.success(gson.fromJson(response.body?.string(), UploadResponse::class.java)))
                    else callback(Result.failure(IOException("Upload post-its failed: Code ${response.code}")))
                }
            }
        })
    }

    fun startWebSocket(sessionId: String) {
        stopWebSocket() // Close any existing connection first
        activeSessionId = sessionId
        val url = wsUrl(sessionId)
        Log.d(TAG, "Connecting to WebSocket: $url")
        val request = Request.Builder().url(url).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected to session $sessionId")
                onConnectionStatusChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Received: $text")
                try {
                    val msg = gson.fromJson(text, WsMessage::class.java)
                    when (msg.type) {
                        "suggestions" -> msg.data?.let { onSuggestionsReceived?.invoke(it) }
                        "speech" -> msg.text?.let { onSpeechRequested?.invoke(it) }
                        "connected" -> Log.d(TAG, "WS Handshake: ${msg.text}")
                        "error" -> Log.e(TAG, "WS Error: ${msg.text}")
                        // Ignore "postits_received" and "graph_updated" events for Android app
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closing: $code / $reason")
                onConnectionStatusChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Error", t)
                onConnectionStatusChanged?.invoke(false)
                // Reconnect only if we have an active session ID
                if (activeSessionId != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Attempt to reconnect to the stored session
                        startWebSocket(activeSessionId!!) 
                    }, 5000)
                }
            }
        })
    }

    fun stopWebSocket() {
        webSocket?.close(1000, "App closing")
        webSocket = null
        activeSessionId = null
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        // Use 80% compression for smaller size
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}