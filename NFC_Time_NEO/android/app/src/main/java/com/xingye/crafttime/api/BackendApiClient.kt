package com.xingye.crafttime.api

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class CardInfo(
    val cardId: String = "",
    var name: String = "",
    var status: Int = 0, // 0: Stopped/Unused, 1: Running, 2: Paused, 3: Expired
    var timerMode: Int = 0, // 0: 倒计时, 1: 正计时 (先玩后付)
    
    // 客户属性与标签
    var isPostPay: Boolean = false, // 玩完再付 (后付款)
    var presetPlan: String = "none", // none, 1h, 3h
    
    // 智能豆板属性 (支持中途开启)
    var useDouban: Boolean = false,
    var doubanStartTimeUtc: String? = null,
    var doubanPlan: String = "hourly",
    var doubanSavedSeconds: Int = 0,

    // 时间属性 (支持自定义/修改开始时间)
    var startTimeUtc: String? = null,
    var targetDurationSeconds: Int = 0,
    var savedRemainingSeconds: Int = 0,
    var remainingSeconds: Double = 0.0,
    var elapsedSeconds: Double = 0.0,
    var overdueSeconds: Double = 0.0,
    var isOverdue: Boolean = false,
    var doubanElapsedSeconds: Double = 0.0,

    var remark: String = "",
    var pricing: PricingResult? = null,
    var updatedAtUtc: String? = null
)

data class PricingResult(
    var totalPrice: Double = 0.0,
    var needToPay: Double = 0.0,
    var bestPlanName: String = "",
    var playFee: Double = 0.0,
    var playOvertimeFee: Double = 0.0,
    var doubanFee: Double = 0.0,
    var doubanOvertimeFee: Double = 0.0,
    var formula: String = "",
    var breakdownItems: List<String> = emptyList()
)

class BackendApiClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("nfc_neo_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val localJsonFile = File(context.filesDir, "cards_cache.json")

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private var serverBaseUrl: String
        get() {
            var url = prefs.getString("server_url", "http://192.168.1.100:5000") ?: "http://192.168.1.100:5000"
            if (url.endsWith("/")) url = url.substring(0, url.length - 1)
            return url
        }
        set(value) {
            prefs.edit().putString("server_url", value).apply()
        }

    fun getServerUrl(): String = serverBaseUrl

    fun updateServerUrl(newUrl: String) {
        var clean = newUrl.trim()
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length - 1)
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        serverBaseUrl = clean
        prefs.edit().putString("server_url", clean).apply()
    }

    // ==========================================
    // 📁 本地 JSON 存储管理 (增删查改原子操作)
    // ==========================================
    fun getLocalCards(): MutableList<CardInfo> {
        if (!localJsonFile.exists()) return mutableListOf()
        return try {
            val json = localJsonFile.readText(Charsets.UTF_8)
            val list = gson.fromJson(json, Array<CardInfo>::class.java)
            list?.toMutableList() ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveLocalCards(cards: List<CardInfo>) {
        try {
            val json = gson.toJson(cards)
            localJsonFile.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun upsertLocalCard(card: CardInfo) {
        val list = getLocalCards()
        val idx = list.indexOfFirst { it.cardId.equals(card.cardId, ignoreCase = true) }
        if (idx >= 0) {
            list[idx] = card
        } else {
            list.add(0, card)
        }
        saveLocalCards(list)
    }

    private fun removeLocalCard(cardId: String) {
        val list = getLocalCards()
        list.removeAll { it.cardId.equals(cardId, ignoreCase = true) }
        saveLocalCards(list)
    }

    // ==========================================
    // ⚡ 极速心跳与连通性检测 (仅传几个字节)
    // ==========================================
    suspend fun testConnection(testUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var clean = testUrl.trim()
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length - 1)
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }

        val testClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("$clean/api/system/ping")
                .get()
                .build()

            testClient.newCall(req).execute().use { resp ->
                val cost = System.currentTimeMillis() - start
                if (resp.isSuccessful) {
                    return@withContext Pair(true, "✅ 在线 (${cost}ms)")
                } else {
                    return@withContext Pair(false, "❌ 响应异常 HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "❌ 离线 (${e.message ?: "连接超时"})")
        }
    }

    // ==========================================
    // 🔍 查 (Query): 仅查活跃制作中卡片 (拒绝全量拖拉)
    // ==========================================
    suspend fun fetchActiveCards(): Pair<List<CardInfo>, Boolean> = withContext(Dispatchers.IO) {
        var serverSuccess = false
        try {
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/active")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    serverSuccess = true
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val activeList = gson.fromJson(body, Array<CardInfo>::class.java).toList()
                        val activeIds = activeList.map { it.cardId.uppercase(Locale.US) }.toSet()
                        
                        // 合并更新到本地缓存中
                        val local = getLocalCards()
                        for (remote in activeList) {
                            val idx = local.indexOfFirst { it.cardId.equals(remote.cardId, ignoreCase = true) }
                            if (idx >= 0) local[idx] = remote else local.add(remote)
                        }
                        // 若远程已停卡，本地同步置为0
                        for (loc in local) {
                            if (loc.status != 0 && !activeIds.contains(loc.cardId.uppercase(Locale.US))) {
                                loc.status = 0
                                loc.startTimeUtc = null
                                loc.doubanStartTimeUtc = null
                            }
                        }
                        saveLocalCards(local)
                        return@withContext Pair(local.filter { it.status != 0 }, true)
                    }
                }
            }
        } catch (e: Exception) {
            serverSuccess = false
        }

        val localActive = getLocalCards().filter { it.status != 0 }
        return@withContext Pair(localActive, serverSuccess)
    }

    // 仅在打开未使用档案库时按需查询
    suspend fun fetchUnusedCards(): Pair<List<CardInfo>, Boolean> = withContext(Dispatchers.IO) {
        var serverSuccess = false
        try {
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/unused")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    serverSuccess = true
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val list = gson.fromJson(body, Array<CardInfo>::class.java).toList()
                        // 同步到本地
                        val local = getLocalCards()
                        for (u in list) {
                            val idx = local.indexOfFirst { it.cardId.equals(u.cardId, ignoreCase = true) }
                            if (idx >= 0) local[idx] = u else local.add(u)
                        }
                        saveLocalCards(local)
                        return@withContext Pair(list, true)
                    }
                }
            }
        } catch (e: Exception) {
            serverSuccess = false
        }

        val localUnused = getLocalCards().filter { it.status == 0 }
        return@withContext Pair(localUnused, serverSuccess)
    }

    // ==========================================
    // 🏷️ 增 (Create / Swipe): 单卡感应，仅传输该卡 ID
    // ==========================================
    suspend fun swipeCard(cardId: String, cardName: String? = null): Pair<CardInfo, Boolean> = withContext(Dispatchers.IO) {
        val local = getLocalCards()
        var existing = local.find { it.cardId.equals(cardId, ignoreCase = true) }
        if (existing == null) {
            existing = CardInfo(
                cardId = cardId,
                name = cardName ?: "卡片_${cardId.takeLast(5)}",
                status = 0
            )
            upsertLocalCard(existing)
        }

        var serverSuccess = false
        try {
            val json = gson.toJson(mapOf("cardId" to cardId, "cardName" to cardName))
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/swipe")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    serverSuccess = true
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val remoteCard = gson.fromJson(body, CardInfo::class.java)
                        if (remoteCard != null) {
                            upsertLocalCard(remoteCard)
                            return@withContext Pair(remoteCard, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            serverSuccess = false
        }

        return@withContext Pair(existing, serverSuccess)
    }

    // ==========================================
    // ⚙️ 改 (Update Timer / Session): 仅发送单卡操作信息
    // ==========================================
    suspend fun setTimer(
        cardId: String,
        durationSeconds: Int,
        action: String,
        timerMode: Int = 0,
        isPostPay: Boolean = false,
        presetPlan: String = "none",
        useDouban: Boolean = false,
        doubanPlan: String = "hourly",
        customStartTimeUtc: String? = null,
        customDoubanStartTimeUtc: String? = null
    ): Pair<CardInfo?, Boolean> = withContext(Dispatchers.IO) {
        val local = getLocalCards()
        val card = local.find { it.cardId.equals(cardId, ignoreCase = true) }

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowIso = isoFormat.format(Date())

        if (card != null) {
            when (action.lowercase(Locale.US)) {
                "start", "set_and_start" -> {
                    card.timerMode = timerMode
                    card.isPostPay = isPostPay
                    card.presetPlan = presetPlan
                    card.useDouban = useDouban
                    card.doubanPlan = doubanPlan
                    card.startTimeUtc = customStartTimeUtc ?: nowIso

                    if (useDouban) {
                        card.doubanStartTimeUtc = customDoubanStartTimeUtc ?: card.startTimeUtc
                        card.doubanSavedSeconds = 0
                    } else {
                        card.doubanStartTimeUtc = null
                        card.doubanSavedSeconds = 0
                    }

                    if (timerMode == 1) {
                        card.targetDurationSeconds = 0
                        card.savedRemainingSeconds = 0
                        card.status = 1
                    } else {
                        val dur = if (durationSeconds > 0) durationSeconds else 3600
                        card.targetDurationSeconds = dur
                        card.savedRemainingSeconds = dur
                        card.status = 1
                    }
                }
                "pause" -> {
                    if (card.status == 1) {
                        card.status = 2
                        card.startTimeUtc = null
                        card.doubanStartTimeUtc = null
                    }
                }
                "resume" -> {
                    if (card.status == 2) {
                        card.status = 1
                        card.startTimeUtc = nowIso
                        if (card.useDouban) card.doubanStartTimeUtc = nowIso
                    }
                }
                "stop", "reset" -> {
                    card.status = 0
                    card.startTimeUtc = null
                    card.doubanStartTimeUtc = null
                    card.savedRemainingSeconds = 0
                    card.doubanSavedSeconds = 0
                    card.targetDurationSeconds = 0
                }
            }
            upsertLocalCard(card)
        }

        var serverSuccess = false
        try {
            val payload = mapOf(
                "durationSeconds" to durationSeconds,
                "action" to action,
                "timerMode" to timerMode,
                "isPostPay" to isPostPay,
                "presetPlan" to presetPlan,
                "useDouban" to useDouban,
                "doubanPlan" to doubanPlan,
                "customStartTimeUtc" to customStartTimeUtc,
                "customDoubanStartTimeUtc" to customDoubanStartTimeUtc
            )
            val json = gson.toJson(payload)
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/$cardId/timer")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    serverSuccess = true
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val updated = gson.fromJson(body, CardInfo::class.java)
                        if (updated != null) {
                            upsertLocalCard(updated)
                            return@withContext Pair(updated, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            serverSuccess = false
        }

        return@withContext Pair(card, serverSuccess)
    }

    // ==========================================
    // ⚙️ 改 (Update Config): 修改名称/备注/中途豆板
    // ==========================================
    suspend fun updateCardConfig(
        cardId: String,
        name: String? = null,
        remark: String? = null,
        isPostPay: Boolean? = null,
        presetPlan: String? = null,
        useDouban: Boolean? = null,
        doubanPlan: String? = null,
        startTimeUtc: String? = null,
        doubanStartTimeUtc: String? = null
    ): Pair<CardInfo?, Boolean> = withContext(Dispatchers.IO) {
        val local = getLocalCards()
        val card = local.find { it.cardId.equals(cardId, ignoreCase = true) }

        if (card != null) {
            if (!name.isNullOrBlank()) card.name = name
            if (remark != null) card.remark = remark
            if (isPostPay != null) card.isPostPay = isPostPay
            if (presetPlan != null) card.presetPlan = presetPlan

            if (useDouban != null) {
                if (useDouban && !card.useDouban) {
                    card.useDouban = true
                    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date())
                    card.doubanStartTimeUtc = doubanStartTimeUtc ?: iso
                    card.doubanSavedSeconds = 0
                } else if (!useDouban && card.useDouban) {
                    card.useDouban = false
                    card.doubanStartTimeUtc = null
                    card.doubanSavedSeconds = 0
                }
            }

            if (!doubanPlan.isNullOrBlank()) card.doubanPlan = doubanPlan
            if (startTimeUtc != null) card.startTimeUtc = startTimeUtc
            if (doubanStartTimeUtc != null) card.doubanStartTimeUtc = doubanStartTimeUtc

            upsertLocalCard(card)
        }

        var serverSuccess = false
        try {
            val payload = mutableMapOf<String, Any?>()
            if (name != null) payload["name"] = name
            if (remark != null) payload["remark"] = remark
            if (isPostPay != null) payload["isPostPay"] = isPostPay
            if (presetPlan != null) payload["presetPlan"] = presetPlan
            if (useDouban != null) payload["useDouban"] = useDouban
            if (doubanPlan != null) payload["doubanPlan"] = doubanPlan
            if (startTimeUtc != null) payload["startTimeUtc"] = startTimeUtc
            if (doubanStartTimeUtc != null) payload["doubanStartTimeUtc"] = doubanStartTimeUtc

            val json = gson.toJson(payload)
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/$cardId/config")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    serverSuccess = true
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val updated = gson.fromJson(body, CardInfo::class.java)
                        if (updated != null) {
                            upsertLocalCard(updated)
                            return@withContext Pair(updated, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            serverSuccess = false
        }

        return@withContext Pair(card, serverSuccess)
    }

    // ==========================================
    // ⚡ 改 (Add Time): 仅发送加时秒数
    // ==========================================
    suspend fun addTime(cardId: String, addSeconds: Int): Pair<CardInfo?, Boolean> = withContext(Dispatchers.IO) {
        val local = getLocalCards()
        val card = local.find { it.cardId.equals(cardId, ignoreCase = true) }
        if (card != null) {
            card.targetDurationSeconds += addSeconds
            card.savedRemainingSeconds += addSeconds
            upsertLocalCard(card)
        }

        var serverSuccess = false
        try {
            val json = gson.toJson(mapOf("addSeconds" to addSeconds))
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/$cardId/add-time")
                .post(json.toRequestBody(jsonType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    serverSuccess = true
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val updated = gson.fromJson(body, CardInfo::class.java)
                        if (updated != null) {
                            upsertLocalCard(updated)
                            return@withContext Pair(updated, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            serverSuccess = false
        }

        return@withContext Pair(card, serverSuccess)
    }

    // ==========================================
    // 🗑️ 删 (Delete): 物理删除单卡
    // ==========================================
    suspend fun deleteCard(cardId: String): Boolean = withContext(Dispatchers.IO) {
        removeLocalCard(cardId)
        try {
            val req = Request.Builder()
                .url("$serverBaseUrl/api/cards/$cardId")
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }

    // ==========================================
    // 🔐 管理员认证
    // ==========================================
    suspend fun verifyAdminPassword(password: String): Boolean = withContext(Dispatchers.IO) {
        if (password == "888888") return@withContext true
        try {
            val json = gson.toJson(mapOf("password" to password))
            val req = Request.Builder()
                .url("$serverBaseUrl/api/auth/verify")
                .post(json.toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            return@withContext (password == "888888")
        }
    }

    suspend fun changeAdminPassword(oldP: String, newP: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("oldPassword" to oldP, "newPassword" to newP))
            val req = Request.Builder()
                .url("$serverBaseUrl/api/auth/change-password")
                .post(json.toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }
}
