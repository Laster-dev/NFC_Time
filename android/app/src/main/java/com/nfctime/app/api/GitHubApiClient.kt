package com.nfctime.app.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class CardInfo(
    val cardId: String,
    var name: String,
    var targetDurationSeconds: Int = 0,
    var status: Int = 0, // 0: Stopped, 1: Running, 2: Paused, 3: Expired
    var startTimeUtc: String? = null,
    var savedRemainingSeconds: Int = 0,
    var remainingSeconds: Double = 0.0,
    var overdueSeconds: Double = 0.0,
    var isOverdue: Boolean = false
)

class GitHubApiClient(
    private var gistId: String = "",
    private var token: String = ""
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun updateConfig(newGistId: String, newToken: String) {
        gistId = newGistId.trim()
        token = newToken.trim()
    }

    private suspend fun fetchRawCards(): MutableList<CardInfo> = withContext(Dispatchers.IO) {
        if (gistId.isEmpty()) return@withContext mutableListOf()
        try {
            val reqBuilder = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .get()

            if (token.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    val jsonObj = gson.fromJson(body, JsonObject::class.java)
                    val files = jsonObj.getAsJsonObject("files")
                    if (files != null && files.has("cards_data.json")) {
                        val content = files.getAsJsonObject("cards_data.json").get("content").asString
                        val list = gson.fromJson(content, Array<CardInfo>::class.java).toMutableList()
                        return@withContext list
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext mutableListOf()
    }

    private suspend fun saveCardsToGist(cards: List<CardInfo>): Boolean = withContext(Dispatchers.IO) {
        if (gistId.isEmpty() || token.isEmpty()) return@withContext false
        try {
            val cardsJsonStr = gson.toJson(cards)

            val fileContentObj = JsonObject().apply {
                addProperty("content", cardsJsonStr)
            }
            val filesObj = JsonObject().apply {
                add("cards_data.json", fileContentObj)
            }
            val rootObj = JsonObject().apply {
                add("files", filesObj)
            }

            val req = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .patch(rootObj.toString().toRequestBody(jsonType))
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(req).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun getAllCards(): List<CardInfo> = withContext(Dispatchers.IO) {
        val list = fetchRawCards()
        val now = System.currentTimeMillis()
        list.forEach { card ->
            var remaining = card.savedRemainingSeconds.toDouble()
            if (card.status == 1 && card.startTimeUtc != null) {
                try {
                    val startMs = isoFormat.parse(card.startTimeUtc!!)?.time ?: now
                    val elapsedSec = (now - startMs) / 1000.0
                    remaining = card.savedRemainingSeconds - elapsedSec
                    if (remaining <= 0) {
                        card.status = 3 // Expired
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            card.isOverdue = remaining < 0 && (card.status == 1 || card.status == 3)
            card.remainingSeconds = remaining
            card.overdueSeconds = if (card.isOverdue) Math.abs(remaining) else 0.0
        }
        return@withContext list
    }

    suspend fun swipeCard(cardId: String): CardInfo? = withContext(Dispatchers.IO) {
        val list = fetchRawCards()
        var card = list.find { it.cardId.equals(cardId, ignoreCase = true) }
        if (card == null) {
            card = CardInfo(cardId = cardId, name = "卡片_$cardId", status = 0)
            list.add(card)
            saveCardsToGist(list)
        }
        return@withContext card
    }

    suspend fun setTimer(cardId: String, durationSec: Int, action: String): CardInfo? = withContext(Dispatchers.IO) {
        val list = fetchRawCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return@withContext null

        val nowIso = isoFormat.format(Date())

        when (action.lowercase()) {
            "start" -> {
                if (durationSec > 0) {
                    card.targetDurationSeconds = durationSec
                    card.savedRemainingSeconds = durationSec
                }
                if (card.savedRemainingSeconds > 0) {
                    card.startTimeUtc = nowIso
                    card.status = 1 // Running
                }
            }
            "pause" -> {
                if (card.status == 1) {
                    val computed = getAllCards().find { it.cardId.equals(cardId, ignoreCase = true) }
                    card.savedRemainingSeconds = Math.max(0, computed?.remainingSeconds?.toInt() ?: 0)
                    card.status = 2 // Paused
                    card.startTimeUtc = null
                }
            }
            "resume" -> {
                if (card.status == 2 && card.savedRemainingSeconds > 0) {
                    card.startTimeUtc = nowIso
                    card.status = 1
                }
            }
            "stop" -> {
                card.status = 0 // Stopped
                card.savedRemainingSeconds = card.targetDurationSeconds
                card.startTimeUtc = null
            }
        }

        saveCardsToGist(list)
        return@withContext card
    }

    suspend fun renameCard(cardId: String, newName: String): CardInfo? = withContext(Dispatchers.IO) {
        val list = fetchRawCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return@withContext null
        card.name = newName
        saveCardsToGist(list)
        return@withContext card
    }
}
