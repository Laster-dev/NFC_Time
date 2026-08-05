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
    val cardId: String = "",
    var name: String = "",
    var targetDurationSeconds: Int = 0,
    var status: Int = 0, // 0: Unused/Stopped, 1: Running, 2: Paused, 3: Expired
    var startTimeUtc: String? = null,
    var savedRemainingSeconds: Int = 0,
    var remainingSeconds: Double = 0.0,
    var overdueSeconds: Double = 0.0,
    var isOverdue: Boolean = false
)

data class Announcement(
    val id: String = System.currentTimeMillis().toString(),
    var title: String = "",
    var content: String = "",
    var isForce: Boolean = false,
    var publishTimeUtc: String = ""
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

    private fun getIsoFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private val prefs = context.getSharedPreferences("offline_cards_cache", Context.MODE_PRIVATE)

    fun updateConfig(newGistId: String, newToken: String) {
        gistId = newGistId.trim()
        token = newToken.trim()
    }

    fun getCachedCards(): MutableList<CardInfo> {
        val json = prefs.getString("cached_json", "[]") ?: "[]"
        return try {
            val list = gson.fromJson(json, Array<CardInfo>::class.java)
            list?.toMutableList() ?: mutableListOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    private fun saveCachedCards(cards: List<CardInfo>) {
        try {
            val json = gson.toJson(cards)
            prefs.edit().putString("cached_json", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCachedAnnouncements(): MutableList<Announcement> {
        val json = prefs.getString("cached_anno_json", "[]") ?: "[]"
        return try {
            val list = gson.fromJson(json, Array<Announcement>::class.java)
            list?.toMutableList() ?: mutableListOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    private fun saveCachedAnnouncements(announcements: List<Announcement>) {
        try {
            val json = gson.toJson(announcements)
            prefs.edit().putString("cached_anno_json", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                    val body = resp.body?.string() ?: return@use
                    val jsonObj = gson.fromJson(body, JsonObject::class.java) ?: return@use
                    val files = jsonObj.getAsJsonObject("files")
                    if (files != null) {
                        val fileObj = files.getAsJsonObject("cards_data.json")
                            ?: files.getAsJsonObject("gistfile1.txt")
                            ?: if (files.keySet().isNotEmpty()) files.getAsJsonObject(files.keySet().first()) else null

                        if (fileObj != null && fileObj.has("content")) {
                            val content = fileObj.get("content").asString
                            val list = gson.fromJson(content, Array<CardInfo>::class.java)
                            if (list != null) {
                                saveCachedCards(list.toList())
                            }
                        }

                        val annoFileObj = files.getAsJsonObject("announcements.json")
                        if (annoFileObj != null && annoFileObj.has("content")) {
                            val annoContent = annoFileObj.get("content").asString
                            val annoList = gson.fromJson(annoContent, Array<Announcement>::class.java)
                            if (annoList != null) {
                                saveCachedAnnouncements(annoList.toList())
                            }
                        }

                        return@withContext getCachedCards()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    private fun syncAllToRemoteAsync(cards: List<CardInfo>, announcements: List<Announcement>) {
        saveCachedCards(cards)
        saveCachedAnnouncements(announcements)
        if (gistId.isEmpty() || token.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cardsJsonStr = gson.toJson(cards)
                val annosJsonStr = gson.toJson(announcements)

                val filesObj = JsonObject().apply {
                    add("cards_data.json", JsonObject().apply { addProperty("content", cardsJsonStr) })
                    add("announcements.json", JsonObject().apply { addProperty("content", annosJsonStr) })
                }
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

    suspend fun getAllCards(forceFetch: Boolean = false): List<CardInfo> = withContext(Dispatchers.IO) {
        val remoteList = if (forceFetch) fetchRemoteCards() else null
        val list = remoteList ?: getCachedCards()
        val now = System.currentTimeMillis()
        val sdf = getIsoFormat()

        list.forEach { card ->
            var remaining = card.savedRemainingSeconds.toDouble()
            if (card.status == 1 && !card.startTimeUtc.isNull_Empty()) {
                try {
                    val startMs = sdf.parse(card.startTimeUtc!!)?.time ?: now
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

    private fun String?.isNull_Empty(): Boolean = this == null || this.trim().isEmpty()

    fun swipeCardImmediate(cardId: String): CardInfo {
        val list = getCachedCards()
        var card = list.find { it.cardId.equals(cardId, ignoreCase = true) }
        if (card == null) {
            card = CardInfo(cardId = cardId, name = "卡片_$cardId", status = 0)
            list.add(card)
        }
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun addTimeImmediate(cardId: String, addSeconds: Int): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.targetDurationSeconds += addSeconds
        card.savedRemainingSeconds += addSeconds
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun setTimerImmediate(cardId: String, durationSec: Int, action: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        val nowIso = getIsoFormat().format(Date())

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
                    val startMs = if (!card.startTimeUtc.isNull_Empty()) {
                        try { getIsoFormat().parse(card.startTimeUtc!!)?.time ?: now } catch (e: Exception) { now }
                    } else now
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

        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun renameCardImmediate(cardId: String, newName: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.name = newName
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun resetCardToUnusedImmediate(cardId: String): Boolean {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return false
        card.status = 0
        card.startTimeUtc = null
        card.savedRemainingSeconds = card.targetDurationSeconds
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return true
    }

    fun addOrUpdateAnnouncement(anno: Announcement) {
        val annos = getCachedAnnouncements()
        val index = annos.indexOfFirst { it.id == anno.id }
        if (anno.publishTimeUtc.isEmpty()) {
            anno.publishTimeUtc = getIsoFormat().format(Date())
        }
        if (index >= 0) {
            annos[index] = anno
        } else {
            annos.add(0, anno)
        }
        syncAllToRemoteAsync(getCachedCards(), annos)
    }

    fun deleteAnnouncement(id: String) {
        val annos = getCachedAnnouncements()
        annos.removeAll { it.id == id }
        syncAllToRemoteAsync(getCachedCards(), annos)
    }
}
