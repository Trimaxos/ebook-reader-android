package com.ebookreader.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class TtsClient(
    private var serverUrl: String = "https://tts.ngtri.io.vn",
    private var apiKey: String = "dCUHsBmDQJws88KGk_t1tl-fNGAORdOYdpkqPPNKGPI",
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    fun updateServerUrl(url: String) {
        serverUrl = url.trimEnd('/')
    }

    fun updateApiKey(key: String) {
        apiKey = key
    }

    fun getServerUrl(): String = serverUrl

    private fun addAuth(builder: Request.Builder) {
        builder.header("X-API-Key", apiKey)
    }

    suspend fun healthCheck(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("$serverUrl/health")
                .get()
            addAuth(builder)
            val response = client.newCall(builder.build()).execute()
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

            val builder = Request.Builder()
                .url("$serverUrl/tts")
                .post(requestBody)
            addAuth(builder)

            val response = client.newCall(builder.build()).execute()
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
            val builder = Request.Builder()
                .url("$serverUrl/voices")
                .get()
            addAuth(builder)
            val response = client.newCall(builder.build()).execute()
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
