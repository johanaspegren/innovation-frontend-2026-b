package com.aei.innovision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service for uploading detected post-its to the backend server.
 *
 * API Endpoint: POST http://<SERVER_IP>:<PORT>/api/postits
 *
 * Request body (JSON):
 * {
 *   "timestamp": 1706198400000,
 *   "postits": [
 *     {
 *       "trackId": 1,
 *       "text": "Cool Stuff",
 *       "confidence": 0.85,
 *       "bounds": { "left": 100.0, "top": 200.0, "right": 300.0, "bottom": 400.0 }
 *     }
 *   ],
 *   "imageBase64": "..." // optional JPEG image
 * }
 */
class PostItApiService {

    companion object {
        private const val TAG = "PostItApiService"

        // TODO: Configure this to your backend server's IP and port
        var SERVER_IP = "192.168.1.100"
        var SERVER_PORT = 8080

        val serverUrl: String
            get() = "http://$SERVER_IP:$SERVER_PORT/api/postits"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Data classes for JSON serialization
    data class BoundsDto(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    data class PostItDto(
        val trackId: Int?,
        val text: String,
        val confidence: Float,
        val bounds: BoundsDto
    )

    data class UploadRequest(
        val timestamp: Long,
        val postits: List<PostItDto>,
        val imageBase64: String? = null
    )

    data class UploadResponse(
        val success: Boolean,
        val message: String? = null,
        val id: String? = null
    )

    /**
     * Upload detected post-its to the backend.
     *
     * @param detections List of detected post-its
     * @param image Optional bitmap image to include (will be JPEG compressed)
     * @param callback Called with result (success/failure)
     */
    fun uploadPostIts(
        detections: List<PostItDetector.Detection>,
        image: Bitmap? = null,
        callback: (Result<UploadResponse>) -> Unit
    ) {
        // Convert detections to DTOs
        val postItDtos = detections.map { detection ->
            PostItDto(
                trackId = detection.trackId,
                text = detection.ocrText,
                confidence = detection.score,
                bounds = BoundsDto(
                    left = detection.rect.left,
                    top = detection.rect.top,
                    right = detection.rect.right,
                    bottom = detection.rect.bottom
                )
            )
        }

        // Encode image if provided
        val imageBase64 = image?.let { bitmapToBase64(it) }

        val request = UploadRequest(
            timestamp = System.currentTimeMillis(),
            postits = postItDtos,
            imageBase64 = imageBase64
        )

        val json = gson.toJson(request)
        Log.d(TAG, "Uploading ${detections.size} post-its to $serverUrl")

        val httpRequest = Request.Builder()
            .url(serverUrl)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload failed", e)
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val uploadResponse = try {
                            gson.fromJson(body, UploadResponse::class.java)
                        } catch (e: Exception) {
                            UploadResponse(success = true, message = "Uploaded successfully")
                        }
                        Log.d(TAG, "Upload successful: ${uploadResponse.message}")
                        callback(Result.success(uploadResponse))
                    } else {
                        val error = IOException("Upload failed: ${response.code} ${response.message}")
                        Log.e(TAG, "Upload failed: ${response.code}")
                        callback(Result.failure(error))
                    }
                }
            }
        })
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
