package com.aei.innovision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
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

        val postitsUrl: String get() = "http://$SERVER_IP:$SERVER_PORT/api/postits"
        val sessionStartUrl: String get() = "http://$SERVER_IP:$SERVER_PORT/api/sessions/start"
        fun wsUrl(sessionId: String): String = "ws://$SERVER_IP:$SERVER_PORT/ws/$sessionId"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    /** The active session ID returned by /api/sessions/start */
    var sessionId: String? = null
        private set

    // Callbacks for WebSocket events
    var onSuggestionsReceived: ((List<String>) -> Unit)? = null
    var onSpeechRequested: ((String) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onPostitsReceived: ((Int, List<String>) -> Unit)? = null
    var onGraphUpdated: ((JsonObject) -> Unit)? = null

    // --- DTOs ---

    data class BoundsDto(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class PostItDto(val trackId: Int?, val text: String, val confidence: Float, val bounds: BoundsDto)
    data class UploadRequest(val timestamp: Long, val postits: List<PostItDto>, val imageBase64: String? = null)
    data class UploadResponse(val success: Boolean, val message: String? = null, val id: String? = null)

    data class StartSessionRequest(val timestamp: Long, val imageBase64: String)
    data class StartSessionResponse(val success: Boolean, val session_id: String? = null, val message: String? = null)

    // --- HTTP: Start Session ---

    fun startSession(sceneImage: Bitmap, callback: (Result<StartSessionResponse>) -> Unit) {
        val body = StartSessionRequest(
            timestamp = System.currentTimeMillis(),
            imageBase64 = bitmapToBase64(sceneImage)
        )
        val httpRequest = Request.Builder()
            .url(sessionStartUrl)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val resp = gson.fromJson(response.body?.string(), StartSessionResponse::class.java)
                        if (resp.success && resp.session_id != null) {
                            sessionId = resp.session_id
                        }
                        callback(Result.success(resp))
                    } else {
                        callback(Result.failure(IOException("Session start failed: ${response.code}")))
                    }
                }
            }
        })
    }

    // --- HTTP: Upload Post-Its ---

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
            .url(postitsUrl)
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

    // --- WebSocket ---

    fun startWebSocket(forSessionId: String? = null) {
        val sid = forSessionId ?: sessionId
        if (sid == null) {
            Log.w(TAG, "Cannot start WebSocket without a session ID")
            return
        }
        val url = wsUrl(sid)
        Log.d(TAG, "Connecting to WebSocket: $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected to session $sid")
                onConnectionStatusChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Received: $text")
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)
                    // Handle initial connection message (uses "type" field)
                    val type = json.get("type")?.asString
                    if (type == "connected") {
                        Log.d(TAG, "WS connected ack: ${json.get("message")?.asString}")
                        return
                    }
                    if (type == "pong") {
                        Log.d(TAG, "WS pong received")
                        return
                    }

                    // All other server-pushed messages use "event" field
                    val event = json.get("event")?.asString ?: return
                    val data = json.getAsJsonObject("data")

                    when (event) {
                        "suggestions" -> {
                            val list: List<String> = gson.fromJson(
                                data.getAsJsonArray("data"),
                                object : TypeToken<List<String>>() {}.type
                            )
                            onSuggestionsReceived?.invoke(list)
                        }
                        "speech" -> {
                            val speechText = data.get("text")?.asString
                            speechText?.let { onSpeechRequested?.invoke(it) }
                        }
                        "postits_received" -> {
                            val count = data.get("count")?.asInt ?: 0
                            val texts: List<String> = gson.fromJson(
                                data.getAsJsonArray("texts"),
                                object : TypeToken<List<String>>() {}.type
                            )
                            onPostitsReceived?.invoke(count, texts)
                        }
                        "graph_updated" -> {
                            onGraphUpdated?.invoke(data)
                        }
                        else -> Log.d(TAG, "Unknown WS event: $event")
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
                    startWebSocket(sid)
                }, 5000)
            }
        })
    }

    fun stopWebSocket() {
        webSocket?.close(1000, "App closing")
        webSocket = null
    }

    // --- Client → Server messages ---

    fun sendPing() {
        val msg = JsonObject().apply {
            addProperty("type", "ping")
            addProperty("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendAck(data: JsonObject = JsonObject()) {
        val msg = JsonObject().apply {
            addProperty("type", "ack")
            add("data", data)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendFeedback(data: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("type", "feedback")
            add("data", data)
        }
        webSocket?.send(gson.toJson(msg))
    }

    // --- Utilities ---

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
