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
    var isOverdue: Boolean = false,
    var remark: String = "",
    var updatedAtMs: Long = 0L
)

data class Announcement(
    val id: String = System.currentTimeMillis().toString(),
    var title: String = "",
    var content: String = "",
    var isForce: Boolean = false,
    var publishTimeUtc: String = "",
    var updatedAtMs: Long = 0L
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

    private fun getDeletedAnnoIds(): HashSet<String> {
        val set = prefs.getStringSet("deleted_anno_ids", emptySet()) ?: emptySet()
        return HashSet(set)
    }

    private fun markAnnouncementDeleted(id: String) {
        val set = getDeletedAnnoIds()
        set.add(id)
        prefs.edit().putStringSet("deleted_anno_ids", set).apply()
    }

    private fun getLocallyModifiedCardIds(): HashSet<String> {
        val set = prefs.getStringSet("dirty_card_ids", emptySet()) ?: emptySet()
        return HashSet(set)
    }

    private fun markCardLocallyModified(cardId: String) {
        val set = getLocallyModifiedCardIds()
        set.add(cardId.lowercase())
        prefs.edit().putStringSet("dirty_card_ids", set).apply()
    }

    private fun clearLocallyModifiedCardIds(cardIds: List<String>) {
        val set = getLocallyModifiedCardIds()
        cardIds.forEach { set.remove(it.lowercase()) }
        prefs.edit().putStringSet("dirty_card_ids", set).apply()
    }

    /**
     * Smart card merge algorithm:
     * Combines local cards and remote cards item-by-item using explicit dirty tracking.
     * Overwrites remote cards ONLY if the local card was explicitly modified on THIS device and has a newer timestamp.
     */
    private fun mergeCards(localList: List<CardInfo>, remoteList: List<CardInfo>): MutableList<CardInfo> {
        val dirtySet = getLocallyModifiedCardIds()
        val map = LinkedHashMap<String, CardInfo>()

        for (remote in remoteList) {
            val key = remote.cardId.lowercase()
            map[key] = remote
        }

        for (local in localList) {
            val key = local.cardId.lowercase()
            val remote = map[key]

            if (remote == null) {
                map[key] = local
            } else {
                val isDirty = dirtySet.contains(key)
                if (isDirty && local.updatedAtMs >= remote.updatedAtMs) {
                    map[key] = local
                } else {
                    map[key] = remote
                }
            }
        }

        return map.values.toMutableList()
    }

    /**
     * Smart announcement merge algorithm:
     * Merges announcement lists by ID while checking deleted ID blacklist.
     */
    private fun mergeAnnouncements(localList: List<Announcement>, remoteList: List<Announcement>): MutableList<Announcement> {
        val deletedIds = getDeletedAnnoIds()
        val map = LinkedHashMap<String, Announcement>()

        for (remote in remoteList) {
            if (deletedIds.contains(remote.id)) continue
            map[remote.id] = remote
        }

        for (local in localList) {
            if (deletedIds.contains(local.id)) continue
            val remote = map[local.id]
            if (remote == null) {
                map[local.id] = local
            } else {
                if (local.updatedAtMs >= remote.updatedAtMs) {
                    map[local.id] = local
                } else {
                    map[local.id] = remote
                }
            }
        }

        return map.values.toMutableList()
    }

    private fun fetchRemoteCardsAndAnnosDirect(): Pair<List<CardInfo>?, List<Announcement>?> {
        if (gistId.isEmpty()) return Pair(null, null)
        try {
            val reqBuilder = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .get()

            if (token.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return Pair(null, null)
                    val jsonObj = gson.fromJson(body, JsonObject::class.java) ?: return Pair(null, null)
                    val files = jsonObj.getAsJsonObject("files")
                    if (files != null) {
                        var remoteCards: List<CardInfo>? = null
                        val fileObj = files.getAsJsonObject("cards_data.json")
                            ?: files.getAsJsonObject("gistfile1.txt")
                            ?: if (files.keySet().isNotEmpty()) files.getAsJsonObject(files.keySet().first()) else null

                        if (fileObj != null && fileObj.has("content")) {
                            val content = fileObj.get("content").asString
                            val list = gson.fromJson(content, Array<CardInfo>::class.java)
                            if (list != null) {
                                remoteCards = list.toList()
                            }
                        }

                        var remoteAnnos: List<Announcement>? = null
                        val annoFileObj = files.getAsJsonObject("announcements.json")
                        if (annoFileObj != null && annoFileObj.has("content")) {
                            val annoContent = annoFileObj.get("content").asString
                            val annoList = gson.fromJson(annoContent, Array<Announcement>::class.java)
                            if (annoList != null) {
                                remoteAnnos = annoList.toList()
                            }
                        }

                        return Pair(remoteCards, remoteAnnos)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(null, null)
    }

    private suspend fun fetchRemoteCards(): MutableList<CardInfo>? = withContext(Dispatchers.IO) {
        val (remoteCards, remoteAnnos) = fetchRemoteCardsAndAnnosDirect()
        if (remoteCards != null || remoteAnnos != null) {
            val mergedCards = if (remoteCards != null) mergeCards(getCachedCards(), remoteCards) else getCachedCards()
            val mergedAnnos = if (remoteAnnos != null) mergeAnnouncements(getCachedAnnouncements(), remoteAnnos) else getCachedAnnouncements()

            saveCachedCards(mergedCards)
            saveCachedAnnouncements(mergedAnnos)
            return@withContext mergedCards
        }
        return@withContext null
    }

    private fun syncAllToRemoteAsync(localCards: List<CardInfo>, localAnnos: List<Announcement>) {
        saveCachedCards(localCards)
        saveCachedAnnouncements(localAnnos)
        if (gistId.isEmpty() || token.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch remote first to prevent overwriting updates made on other devices
                val (remoteCards, remoteAnnos) = fetchRemoteCardsAndAnnosDirect()
                val finalCards = if (remoteCards != null) mergeCards(localCards, remoteCards) else localCards
                val finalAnnos = if (remoteAnnos != null) mergeAnnouncements(localAnnos, remoteAnnos) else localAnnos

                saveCachedCards(finalCards)
                saveCachedAnnouncements(finalAnnos)

                val cardsJsonStr = gson.toJson(finalCards)
                val annosJsonStr = gson.toJson(finalAnnos)

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

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        clearLocallyModifiedCardIds(finalCards.map { it.cardId })
                    }
                }
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
        val nowMs = System.currentTimeMillis()
        if (card == null) {
            card = CardInfo(cardId = cardId, name = "卡片_$cardId", status = 0, updatedAtMs = nowMs)
            list.add(card)
        } else {
            card.updatedAtMs = nowMs
        }
        markCardLocallyModified(cardId)
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun addTimeImmediate(cardId: String, addSeconds: Int): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.targetDurationSeconds += addSeconds
        card.savedRemainingSeconds += addSeconds
        card.updatedAtMs = System.currentTimeMillis()
        markCardLocallyModified(cardId)
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun setTimerImmediate(cardId: String, durationSec: Int, action: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        val nowIso = getIsoFormat().format(Date())
        val nowMs = System.currentTimeMillis()

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
                    val startMs = if (!card.startTimeUtc.isNull_Empty()) {
                        try { getIsoFormat().parse(card.startTimeUtc!!)?.time ?: nowMs } catch (e: Exception) { nowMs }
                    } else nowMs
                    val elapsedSec = (nowMs - startMs) / 1000.0
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
        card.updatedAtMs = nowMs
        markCardLocallyModified(cardId)

        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun renameCardImmediate(cardId: String, newName: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.name = newName
        card.updatedAtMs = System.currentTimeMillis()
        markCardLocallyModified(cardId)
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun updateCardRemarkImmediate(cardId: String, remark: String): CardInfo? {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return null
        card.remark = remark
        card.updatedAtMs = System.currentTimeMillis()
        markCardLocallyModified(cardId)
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return card
    }

    fun resetCardToUnusedImmediate(cardId: String): Boolean {
        val list = getCachedCards()
        val card = list.find { it.cardId.equals(cardId, ignoreCase = true) } ?: return false
        card.status = 0
        card.startTimeUtc = null
        card.savedRemainingSeconds = card.targetDurationSeconds
        card.updatedAtMs = System.currentTimeMillis()
        markCardLocallyModified(cardId)
        syncAllToRemoteAsync(list, getCachedAnnouncements())
        return true
    }

    fun addOrUpdateAnnouncement(anno: Announcement) {
        val annos = getCachedAnnouncements()
        val index = annos.indexOfFirst { it.id == anno.id }
        anno.updatedAtMs = System.currentTimeMillis()
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
        markAnnouncementDeleted(id)
        val annos = getCachedAnnouncements()
        annos.removeAll { it.id == id }
        syncAllToRemoteAsync(getCachedCards(), annos)
    }
}
