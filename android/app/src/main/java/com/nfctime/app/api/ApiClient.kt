package com.nfctime.app.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class CardInfo(
    val cardId: String,
    val name: String,
    val targetDurationSeconds: Int,
    val status: Int, // 0: Stopped, 1: Running, 2: Paused, 3: Expired
    val remainingSeconds: Double,
    val overdueSeconds: Double,
    val isOverdue: Boolean
)

data class SwipeRequest(val cardId: String, val cardName: String? = null)
data class SetTimerRequest(val durationSeconds: Int, val action: String)
data class RenameRequest(val newName: String)

class ApiClient(private var baseUrl: String = "http://192.168.1.100:5000") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun updateBaseUrl(url: String) {
        baseUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "http://$url"
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.dropLast(1)
    }

    suspend fun swipeCard(cardId: String): CardInfo? = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(SwipeRequest(cardId))
            val req = Request.Builder()
                .url("$baseUrl/api/cards/swipe")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    return@withContext gson.fromJson(body, CardInfo::class.java)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun setTimer(cardId: String, durationSec: Int, action: String): CardInfo? = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(SetTimerRequest(durationSec, action))
            val req = Request.Builder()
                .url("$baseUrl/api/cards/$cardId/timer")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    return@withContext gson.fromJson(body, CardInfo::class.java)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun renameCard(cardId: String, newName: String): CardInfo? = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(RenameRequest(newName))
            val req = Request.Builder()
                .url("$baseUrl/api/cards/$cardId/rename")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    return@withContext gson.fromJson(body, CardInfo::class.java)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun getAllCards(): List<CardInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/cards")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    val array = gson.fromJson(body, Array<CardInfo>::class.java)
                    return@withContext array.toList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }
}
