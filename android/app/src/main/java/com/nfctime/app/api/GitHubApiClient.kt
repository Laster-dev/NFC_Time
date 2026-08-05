package com.nfctime.app.api

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var name: String = "",
    var targetDurationSeconds: Int = 0,
    var status: Int = 0, // 0: Stopped, 1: Running, 2: Paused, 3: Expired
    var startTimeUtc: String? = null,
    var savedRemainingSeconds: Int = 0,
    var remainingSeconds: Double = 0.0,
    var overdueSeconds: Double = 0.0,
    var isOverdue: Boolean = false
)

class GitHubApiClient(
    private val context: Context,
    private var gistId: String = "",
    private var token: String = ""
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val prefs = context.getSharedPreferences("offline_cards_cache", Context.MODE_PRIVATE)

    fun updateConfig(newGistId: String, newToken: String) {
        gistId = newGistId.trim()
        token = newToken.trim()
    }

    private fun getCachedCards(): MutableList<CardInfo> {
        val json = prefs.getString("cached_json", "[]")
        return try {
            gson.fromJson(json, Array<CardInfo>::class.java).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveCachedCards(cards: List<CardInfo>) {
        val json = gson.toJson(cards)
        prefs.edit().putString("cached_json", json).apply()
    }

    private suspend fun fetchRemoteCards(): MutableList<CardInfo>? = withContext(Dispatchers.IO) {
        if (gistId.isEmpty()) return@withContext null
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
                    if (files != null) {
                        val fileObj = files.getAsJsonObject("cards_data.json")
                            ?: files.getAsJsonObject("gistfile1.txt")
                            ?: if (files.keySet().isNotEmpty()) files.getAsJsonObject(files.keySet().first()) else null

                        if (fileObj != null && fileObj.has("content")) {
                            val content = fileObj.get("content").asString
                            val list = gson.fromJson(content, Array<CardInfo>::class.java).toMutableList()
                            saveCachedCards(list)
                            return@withContext list
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    private fun syncToRemoteAsync(cards: List<CardInfo>) {
        saveCachedCards(cards)
        if (gistId.isEmpty() || token.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cardsJsonStr = gson.toJson(cards)
                val fileContentObj = JsonObject().apply { addProperty("content", cardsJsonStr) }
                val filesObj = JsonObject().apply { add("cards_data.json", fileContentObj) }
                val rootObj = JsonObject().apply { add("files", filesObj) }

                val req = Request.Builder()
                    .url("https://api.github.com/gists/$gistId")
                    .patch(rootObj.toString().toRequestBody(jsonType))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(req).execute().close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getAllCards(): List<CardInfo> = withContext(Dispatchers.IO) {
        val remoteList = fetchRemoteCards()
        val list = remoteList ?: getCachedCards()
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

    fun swipeCardImmediate(cardId: String): CardInfo {
        val list = getCachedCards()
        var card = list.find { it.cardId.equals(cardId, ignoreCase = true) }
        if (card == null) {
            card = CardInfo(cardId = cardId, name = "卡片_$cardId", status = 0)
            list.add(card)
        }
        syncToRemoteAsync(list)
        return card
    }

    fun addTimeImmediate(cardId: String, addSeconds: Int): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.targetDurationSeconds += addSeconds
        card.savedRemainingSeconds += addSeconds
        syncToRemoteAsync(list)
        return card
    }

    fun setTimerImmediate(cardId: String, durationSec: Int, action: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
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
                    val now = System.currentTimeMillis()
                    val startMs = if (card.startTimeUtc != null) isoFormat.parse(card.startTimeUtc!!)?.time ?: now else now
                    val elapsedSec = (now - startMs) / 1000.0
                    val rem = Math.max(0.0, card.savedRemainingSeconds - elapsedSec)

                    card.savedRemainingSeconds = rem.toInt()
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

        syncToRemoteAsync(list)
        return card
    }

    fun renameCardImmediate(cardId: String, newName: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.name = newName
        syncToRemoteAsync(list)
        return card
    }

    fun deleteCardImmediate(cardId: String): Boolean {
        val list = getCachedCards()
        val removed = list.removeAll { it.cardId.equals(cardId, ignoreCase = true) }
        if (removed) {
            syncToRemoteAsync(list)
        }
        return removed
    }
}
