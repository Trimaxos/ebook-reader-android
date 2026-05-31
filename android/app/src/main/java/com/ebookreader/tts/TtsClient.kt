package com.ebookreader.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class TtsClient(private var serverUrl: String = "http://221.132.21.49:8080") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateServerUrl(url: String) {
        serverUrl = url.trimEnd('/')
    }

    fun getServerUrl(): String = serverUrl

    suspend fun healthCheck(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string().orEmpty())
                Result.success(json)
            } else {
                Result.failure(Exception("Server returned ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun synthesize(
        text: String,
        voice: String = "vi-VN-HoaiMyNeural",
        rate: String = "+0%"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("rate", rate)
                put("pitch", "+0Hz")
            }

            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$serverUrl/tts")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val byteStream = ByteArrayOutputStream()
                response.body?.byteStream()?.use { input ->
                    input.copyTo(byteStream)
                }
                Result.success(byteStream.toByteArray())
            } else {
                Result.failure(Exception("TTS failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVoices(): Result<List<VoiceInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/voices")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string().orEmpty())
                val voicesArray = json.getJSONArray("voices")
                val voices = mutableListOf<VoiceInfo>()
                for (i in 0 until voicesArray.length()) {
                    val v = voicesArray.getJSONObject(i)
                    voices.add(VoiceInfo(
                        name = v.getString("name"),
                        shortName = v.getString("short_name"),
                        locale = v.getString("locale"),
                        gender = v.getString("gender"),
                        friendlyName = v.optString("friendly_name", v.getString("short_name"))
                    ))
                }
                Result.success(voices)
            } else {
                Result.failure(Exception("Failed to get voices: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class VoiceInfo(
    val name: String,
    val shortName: String,
    val locale: String,
    val gender: String,
    val friendlyName: String
)
