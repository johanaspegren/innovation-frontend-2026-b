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

    companion object {
        private const val TAG = "PostItApiService"
        var SERVER_IP = "192.168.1.100"
        var SERVER_PORT = 8080

        val serverUrl: String get() = "http://$SERVER_IP:$SERVER_PORT/api/postits"
        val wsUrl: String get() = "ws://$SERVER_IP:$SERVER_PORT/ws"
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
    data class UploadRequest(val timestamp: Long, val postits: List<PostItDto>, val imageBase64: String? = null)
    data class UploadResponse(val success: Boolean, val message: String? = null)
    
    // WebSocket Message Format
    data class WsMessage(val type: String, val data: List<String>? = null, val text: String? = null)

    fun uploadPostIts(detections: List<PostItDetector.Detection>, image: Bitmap? = null, callback: (Result<UploadResponse>) -> Unit) {
        val postItDtos = detections.map { detection ->
            PostItDto(
                trackId = detection.trackId,
                text = detection.ocrText,
                confidence = detection.score,
                bounds = BoundsDto(detection.rect.left, detection.rect.top, detection.rect.right, detection.rect.bottom)
            )
        }

        val request = UploadRequest(System.currentTimeMillis(), postItDtos, image?.let { bitmapToBase64(it) })
        val httpRequest = Request.Builder()
            .url(serverUrl)
            .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) callback(Result.success(gson.fromJson(response.body?.string(), UploadResponse::class.java)))
                    else callback(Result.failure(IOException("Code ${response.code}")))
                }
            }
        })
    }

    fun startWebSocket() {
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected")
                onConnectionStatusChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Received: $text")
                try {
                    val msg = gson.fromJson(text, WsMessage::class.java)
                    when (msg.type) {
                        "suggestions" -> msg.data?.let { onSuggestionsReceived?.invoke(it) }
                        "speech" -> msg.text?.let { onSpeechRequested?.invoke(it) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onConnectionStatusChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Error", t)
                onConnectionStatusChanged?.invoke(false)
                // Reconnect after 5 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startWebSocket()
                }, 5000)
            }
        })
    }

    fun stopWebSocket() {
        webSocket?.close(1000, "App closing")
        webSocket = null
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
