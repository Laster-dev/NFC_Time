package com.xingye.crafttime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xingye.crafttime.api.BackendApiClient
import com.xingye.crafttime.api.CardInfo
import com.xingye.crafttime.api.PriceCalculator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var apiClient: BackendApiClient
    private var syncJob: Job? = null
    private var tickJob: Job? = null

    private lateinit var tvNfcStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var rvCards: RecyclerView
    private val adapter = CardAdapter()

    private fun getIsoFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiClient = BackendApiClient(this)

        tvNfcStatus = findViewById(R.id.tvNfcStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        rvCards = findViewById(R.id.rvCards)
        rvCards.layoutManager = LinearLayoutManager(this)
        rvCards.adapter = adapter

        updateServerStatusText()

        // Navigation & server status clicks (No password required after login)
        findViewById<ImageView>(R.id.btnNavSettings).setOnClickListener {
            showServerSettingsDialog()
        }

        findViewById<ImageView>(R.id.btnNavUnused).setOnClickListener {
            startActivity(Intent(this, UnusedCardsActivity::class.java))
        }

        findViewById<View>(R.id.llServerStatusBtn).setOnClickListener {
            showServerSettingsDialog()
        }

        adapter.onCardClick = { card ->
            openCardDialogByState(card)
        }

        initNfc()
        handleIntent(intent)
    }

    private fun updateServerStatusText() {
        val url = apiClient.getServerUrl()
        val host = try { url.substringAfter("://").substringBefore("/") } catch (e: Exception) { url }
        tvServerStatus.text = "🌐 $host"
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvNfcStatus.text = "⚠️ 设备不支持 NFC"
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            tvNfcStatus.text = "⚠️ NFC 未开启，请在系统设置中启用"
        } else {
            tvNfcStatus.text = "🟢 NFC 已就绪，随时可贴卡"
        }
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatchSafely()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopPolling()
    }

    private fun enableForegroundDispatchSafely() {
        try {
            if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

                val techList = arrayOf(
                    arrayOf(NfcA::class.java.name),
                    arrayOf(NfcB::class.java.name),
                    arrayOf(NfcF::class.java.name),
                    arrayOf(NfcV::class.java.name),
                    arrayOf(IsoDep::class.java.name),
                    arrayOf(MifareClassic::class.java.name),
                    arrayOf(MifareUltralight::class.java.name),
                    arrayOf(Ndef::class.java.name)
                )
                nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, techList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        fun parseIsoToMillis(isoStr: String?): Long? {
            if (isoStr.isNullOrEmpty()) return null
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.time.Instant.parse(isoStr).toEpochMilli()
                } else {
                    var clean = isoStr.trim()
                    if (clean.contains(".")) {
                        val dotIdx = clean.indexOf(".")
                        val zIdx = clean.indexOf("Z", dotIdx)
                        clean = if (zIdx > 0) clean.substring(0, dotIdx) + "Z" else clean.substring(0, dotIdx)
                    }
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    sdf.parse(clean)?.time
                }
            } catch (e: Exception) {
                null
            }
        }

        fun formatSeconds(seconds: Double): String {
            val totalSec = Math.max(0, Math.round(seconds).toInt())
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            val pad = { n: Int -> if (n < 10) "0$n" else "$n" }
            return if (h > 0) "${pad(h)}:${pad(m)}:${pad(s)}" else "${pad(m)}:${pad(s)}"
        }
    }

    private fun startPolling() {
        syncJob?.cancel()
        tickJob?.cancel()

        // 1. Tick local cards every 1s (每秒刷新显示)
        tickJob = lifecycleScope.launch {
            while (isActive) {
                refreshCardsLocal()
                delay(1000)
            }
        }

        // 2. Fetch active cards and check live connection every 5s (每5秒向服务器同步一次)
        syncJob = lifecycleScope.launch {
            while (isActive) {
                val currentUrl = apiClient.getServerUrl()
                val host = try { currentUrl.substringAfter("://").substringBefore("/") } catch (e: Exception) { currentUrl }
                try {
                    val (activeList, isOnline) = apiClient.fetchActiveCards()
                    if (isOnline) {
                        tvServerStatus.text = "🟢 在线 ($host)"
                        tvServerStatus.setTextColor(0xFF4ADE80.toInt())
                    } else {
                        tvServerStatus.text = "🔴 离线 (点击配置)"
                        tvServerStatus.setTextColor(0xFFEF4444.toInt())
                    }
                    refreshCardsLocal()
                } catch (e: Exception) {
                    tvServerStatus.text = "🔴 离线 (点击配置)"
                    tvServerStatus.setTextColor(0xFFEF4444.toInt())
                }
                delay(5000)
            }
        }
    }

    private fun stopPolling() {
        syncJob?.cancel()
        tickJob?.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_TAG_DISCOVERED == action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == action
        ) {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let { processNfcTag(it) }
        }
    }

    private fun processNfcTag(tag: Tag) {
        val uidBytes = tag.id
        val cardId = uidBytes.joinToString(":") { String.format("%02X", it) }
        tvNfcStatus.text = "✨ 贴卡成功! UID: $cardId"

        lifecycleScope.launch {
            val (card, synced) = apiClient.swipeCard(cardId)
            if (synced) {
                Toast.makeText(this@MainActivity, "✅ 刷卡成功，已同步至服务器: ${card.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "⚠️ 离线模式: 已本地暂存，未连接服务器", Toast.LENGTH_SHORT).show()
            }
            openCardDialogByState(card)
            refreshCardsLocal()
        }
    }

    private fun openCardDialogByState(card: CardInfo) {
        if (card.status == 1 || card.status == 2 || card.status == 3 || card.isOverdue) {
            showActiveRunningCardDialog(card)
        } else {
            showCardControlDialog(card)
        }
    }

    private fun refreshCardsLocal() {
        val allCards = apiClient.getLocalCards()
        val now = System.currentTimeMillis()
        val sdf = getIsoFormat()

        val activeCards = allCards.filter { it.status != 0 }.toMutableList()

        activeCards.forEach { card ->
            val startMs = parseIsoToMillis(card.startTimeUtc)
            val doubanStartMs = parseIsoToMillis(card.doubanStartTimeUtc)

            // Compute elapsed / remaining
            if (card.timerMode == 1) {
                // 正计时
                var elapsed = card.savedRemainingSeconds.toDouble()
                if (startMs != null && card.status == 1) {
                    val cur = Math.max(0.0, (now - startMs) / 1000.0)
                    elapsed = card.savedRemainingSeconds.toDouble() + cur
                }
                card.elapsedSeconds = elapsed
                card.remainingSeconds = elapsed
                card.isOverdue = false
                card.overdueSeconds = 0.0
            } else {
                // 倒计时
                var rem = card.savedRemainingSeconds.toDouble()
                if (startMs != null && (card.status == 1 || card.status == 3)) {
                    val spent = (now - startMs) / 1000.0
                    rem = card.savedRemainingSeconds - spent
                }
                card.remainingSeconds = rem
                card.isOverdue = rem < 0 && (card.status == 1 || card.status == 3)
                card.overdueSeconds = if (card.isOverdue) Math.abs(rem) else 0.0
                card.elapsedSeconds = Math.max(0.0, card.targetDurationSeconds - rem)
            }

            // Compute Douban elapsed
            if (card.useDouban) {
                var dElapsed = card.doubanSavedSeconds.toDouble()
                if (doubanStartMs != null && card.status == 1) {
                    val cur = Math.max(0.0, (now - doubanStartMs) / 1000.0)
                    dElapsed = card.doubanSavedSeconds.toDouble() + cur
                }
                card.doubanElapsedSeconds = dElapsed
            } else {
                card.doubanElapsedSeconds = 0.0
            }

            // Compute pricing
            card.pricing = PriceCalculator.computeCardPricing(card)
        }

        // Sort: Overdue first, then running, then paused
        activeCards.sortWith(Comparator { a, b ->
            val aOverdue = a.isOverdue || a.status == 3
            val bOverdue = b.isOverdue || b.status == 3
            if (aOverdue && !bOverdue) return@Comparator -1
            if (!aOverdue && bOverdue) return@Comparator 1
            if (a.status == 1 && b.status != 1) return@Comparator -1
            if (a.status != 1 && b.status == 1) return@Comparator 1
            return@Comparator a.cardId.compareTo(b.cardId, ignoreCase = true)
        })

        adapter.setCards(activeCards)
    }

    private fun showServerSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_server_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val etServerUrl = view.findViewById<EditText>(R.id.etServerUrl)
        val btnTest = view.findViewById<Button>(R.id.btnTestConnection)
        val tvTestStatus = view.findViewById<TextView>(R.id.tvTestStatus)
        val btnPresetLocal = view.findViewById<Button>(R.id.btnPresetLocalhost)
        val btnPresetEmu = view.findViewById<Button>(R.id.btnPresetEmulator)

        val etOldPwd = view.findViewById<EditText>(R.id.etOldPassword)
        val etNewPwd = view.findViewById<EditText>(R.id.etNewPassword)
        val btnChangePwd = view.findViewById<Button>(R.id.btnChangePassword)
        val btnCancel = view.findViewById<Button>(R.id.btnSettingsCancel)
        val btnSave = view.findViewById<Button>(R.id.btnSettingsSave)

        etServerUrl.setText(apiClient.getServerUrl())

        btnPresetLocal?.setOnClickListener {
            etServerUrl.setText("http://127.0.0.1:5000")
        }

        btnPresetEmu?.setOnClickListener {
            etServerUrl.setText("http://10.0.2.2:5000")
        }

        btnTest?.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                tvTestStatus.text = "请输入服务器地址"
                tvTestStatus.setTextColor(0xFFFF5252.toInt())
                return@setOnClickListener
            }
            tvTestStatus.text = "⏳ 正在测试连接..."
            tvTestStatus.setTextColor(0xFF2AABEE.toInt())
            lifecycleScope.launch {
                val res = apiClient.testConnection(url)
                tvTestStatus.text = res.second
                tvTestStatus.setTextColor(if (res.first) 0xFF45B880.toInt() else 0xFFFF5252.toInt())
            }
        }

        btnChangePwd.setOnClickListener {
            val oldP = etOldPwd.text.toString().trim()
            val newP = etNewPwd.text.toString().trim()
            if (oldP.isEmpty() || newP.isEmpty()) {
                Toast.makeText(this, "请输入原密码与新密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = apiClient.changeAdminPassword(oldP, newP)
                if (ok) {
                    Toast.makeText(this@MainActivity, "管理员密码修改成功", Toast.LENGTH_SHORT).show()
                    etOldPwd.setText("")
                    etNewPwd.setText("")
                } else {
                    Toast.makeText(this@MainActivity, "原密码错误或修改失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnLogout = view.findViewById<Button>(R.id.btnLogout)
        btnLogout?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🚪 确认退出登录")
                .setMessage("退出登录后，下次打开软件将需要重新输入管理员密码。")
                .setPositiveButton("退出登录") { _, _ ->
                    getSharedPreferences("nfc_neo_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_logged_in", false)
                        .apply()
                    dialog.dismiss()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newUrl = etServerUrl.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                apiClient.updateServerUrl(newUrl)
                updateServerStatusText()
                Toast.makeText(this, "已保存服务器地址: $newUrl", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    try {
                        apiClient.fetchActiveCards()
                        refreshCardsLocal()
                    } catch (e: Exception) { }
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showCardControlDialog(card: CardInfo) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_control, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvUid = view.findViewById<TextView>(R.id.dialogCardUid)
        val etName = view.findViewById<EditText>(R.id.etCardName)
        val btnRename = view.findViewById<Button>(R.id.btnRename)

        val rgPaymentType = view.findViewById<RadioGroup>(R.id.rgPaymentType)
        val rbPrepay = view.findViewById<RadioButton>(R.id.rbPrepay)
        val rbPostpay = view.findViewById<RadioButton>(R.id.rbPostpay)

        val cbUseDouban = view.findViewById<CheckBox>(R.id.cbUseDouban)
        val llDoubanPlanRow = view.findViewById<LinearLayout>(R.id.llDoubanPlanRow)
        val rbDoubanHourly = view.findViewById<RadioButton>(R.id.rbDoubanHourly)
        val rbDoubanAfternoon = view.findViewById<RadioButton>(R.id.rbDoubanAfternoon)
        val rbDoubanAllday = view.findViewById<RadioButton>(R.id.rbDoubanAllday)

        val btnStartNow = view.findViewById<Button>(R.id.btnStartNow)
        val btnStart5mAgo = view.findViewById<Button>(R.id.btnStart5mAgo)
        val btnStart15mAgo = view.findViewById<Button>(R.id.btnStart15mAgo)
        val btnStart30mAgo = view.findViewById<Button>(R.id.btnStart30mAgo)

        val btnQuick1H = view.findViewById<Button>(R.id.btnQuick1H)
        val btnQuick3H = view.findViewById<Button>(R.id.btnQuick3H)

        val rgTimerMode = view.findViewById<RadioGroup>(R.id.rgTimerMode)
        val rbCountdown = view.findViewById<RadioButton>(R.id.rbCountdown)
        val rbCountup = view.findViewById<RadioButton>(R.id.rbCountup)
        val llPickerArea = view.findViewById<LinearLayout>(R.id.llPickerArea)
        val npHours = view.findViewById<NumberPicker>(R.id.npHours)
        val npMinutes = view.findViewById<NumberPicker>(R.id.npMinutes)

        val etRemark = view.findViewById<EditText>(R.id.etCardRemark)
        val btnStart = view.findViewById<Button>(R.id.btnStart)

        tvUid.text = "UID: ${card.cardId}"
        etName.setText(card.name)
        etRemark.setText(card.remark)

        npHours.minValue = 0
        npHours.maxValue = 23
        npMinutes.minValue = 0
        npMinutes.maxValue = 59
        npHours.value = 1
        npMinutes.value = 0

        var selectedPresetPlan = "1h"
        var customStartOffsetMinutes = 0

        // 智能豆板展开/折叠
        cbUseDouban.setOnCheckedChangeListener { _, isChecked ->
            llDoubanPlanRow.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 开始时间补录选择
        fun highlightStartTimeBtn(offsetMin: Int) {
            customStartOffsetMinutes = offsetMin
            val str = if (offsetMin == 0) "现在" else "${offsetMin}分钟前"
            Toast.makeText(this, "开始时间已设为: $str", Toast.LENGTH_SHORT).show()
        }

        btnStartNow.setOnClickListener { highlightStartTimeBtn(0) }
        btnStart5mAgo.setOnClickListener { highlightStartTimeBtn(5) }
        btnStart15mAgo.setOnClickListener { highlightStartTimeBtn(15) }
        btnStart30mAgo.setOnClickListener { highlightStartTimeBtn(30) }

        // 套餐预设 (仅 1小时 / 3小时)
        btnQuick1H.setOnClickListener {
            selectedPresetPlan = "1h"
            rbCountdown.isChecked = true
            npHours.value = 1
            npMinutes.value = 0
            Toast.makeText(this, "已预设: 1小时套餐 (¥12.9)", Toast.LENGTH_SHORT).show()
        }

        btnQuick3H.setOnClickListener {
            selectedPresetPlan = "3h"
            rbCountdown.isChecked = true
            npHours.value = 3
            npMinutes.value = 0
            Toast.makeText(this, "已预设: 3小时套餐 (¥29.9)", Toast.LENGTH_SHORT).show()
        }

        rgTimerMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCountup) {
                llPickerArea.visibility = View.GONE
                selectedPresetPlan = "none"
            } else {
                llPickerArea.visibility = View.VISIBLE
            }
        }

        btnRename.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                lifecycleScope.launch {
                    val (updated, synced) = apiClient.updateCardConfig(card.cardId, name = newName)
                    if (synced) {
                        Toast.makeText(this@MainActivity, "✅ 改名成功，已同步至服务器: $newName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "⚠️ 已本地改名 (未连接服务器)", Toast.LENGTH_SHORT).show()
                    }
                    refreshCardsLocal()
                }
            }
        }

        btnStart.setOnClickListener {
            val isCountup = rbCountup.isChecked
            val timerMode = if (isCountup) 1 else 0
            val isPostPay = rbPostpay.isChecked
            val useDouban = cbUseDouban.isChecked
            val doubanPlan = when {
                rbDoubanAfternoon.isChecked -> "afternoon"
                rbDoubanAllday.isChecked -> "allday"
                else -> "hourly"
            }
            val totalSeconds = if (isCountup) 0 else (npHours.value * 3600 + npMinutes.value * 60)

            // 计算自定义开始时间
            val iso = getIsoFormat()
            val startMs = System.currentTimeMillis() - (customStartOffsetMinutes * 60 * 1000L)
            val customStartTimeIso = iso.format(Date(startMs))

            val remarkText = etRemark.text.toString().trim()

            lifecycleScope.launch {
                if (remarkText.isNotEmpty()) {
                    apiClient.updateCardConfig(card.cardId, remark = remarkText)
                }

                val (updatedCard, synced) = apiClient.setTimer(
                    cardId = card.cardId,
                    durationSeconds = totalSeconds,
                    action = "start",
                    timerMode = timerMode,
                    isPostPay = isPostPay,
                    presetPlan = selectedPresetPlan,
                    useDouban = useDouban,
                    doubanPlan = doubanPlan,
                    customStartTimeUtc = customStartTimeIso,
                    customDoubanStartTimeUtc = if (useDouban) customStartTimeIso else null
                )

                if (synced) {
                    Toast.makeText(this@MainActivity, "✅ 开启全新计时成功，已同步至服务器！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ 离线模式: 已本地开启计时", Toast.LENGTH_SHORT).show()
                }

                dialog.dismiss()
                refreshCardsLocal()
            }
        }

        dialog.show()
    }

    private fun showActiveRunningCardDialog(card: CardInfo) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_active_card_control, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvActiveTitle)
        val tvStatusBadge = view.findViewById<TextView>(R.id.tvActiveStatusBadge)
        val tvUid = view.findViewById<TextView>(R.id.tvActiveUid)
        val tvTimeLabel = view.findViewById<TextView>(R.id.tvActiveTimeLabel)
        val tvRemainingBig = view.findViewById<TextView>(R.id.tvActiveRemainingBig)
        val tvOverdueAlert = view.findViewById<TextView>(R.id.tvActiveOverdueAlert)

        // 智能豆板中途控制
        val tvDoubanStatus = view.findViewById<TextView>(R.id.tvActiveDoubanStatus)
        val btnToggleDouban = view.findViewById<Button>(R.id.btnToggleActiveDouban)
        val tvDoubanTime = view.findViewById<TextView>(R.id.tvActiveDoubanTime)

        // 修改开始时间
        val tvStartTimeDisplay = view.findViewById<TextView>(R.id.tvActiveStartTimeDisplay)
        val btnAdjustStartTime = view.findViewById<Button>(R.id.btnAdjustStartTime)

        // 价格明细
        val tvBestPlanTitle = view.findViewById<TextView>(R.id.tvActiveBestPlanTitle)
        val tvPriceBig = view.findViewById<TextView>(R.id.tvActivePriceBig)
        val tvFormula = view.findViewById<TextView>(R.id.tvActiveFormula)
        val tvBreakdown = view.findViewById<TextView>(R.id.tvActiveBreakdown)

        // 加时
        val llAddSection = view.findViewById<LinearLayout>(R.id.llAddDurationSection)
        val btnAdd10 = view.findViewById<Button>(R.id.btnAdd10Min)
        val btnAdd30 = view.findViewById<Button>(R.id.btnAdd30Min)
        val btnAdd1H = view.findViewById<Button>(R.id.btnAdd1Hour)

        val btnPause = view.findViewById<Button>(R.id.btnActivePause)
        val btnStop = view.findViewById<Button>(R.id.btnActiveStop)

        tvTitle.text = card.name
        tvUid.text = "UID: ${card.cardId}"
        btnPause.text = if (card.status == 2) "继续" else "暂停"

        if (card.timerMode == 1) {
            tvTimeLabel.text = "⏱️ 已玩时长 (正计时)"
            llAddSection.visibility = View.GONE
        } else {
            tvTimeLabel.text = "⏳ 剩余时间 (倒计时)"
            llAddSection.visibility = View.VISIBLE
        }

        fun updateDoubanUi() {
            if (card.useDouban) {
                tvDoubanStatus.text = "📟 智能豆板: 已开启 (${card.doubanPlan})"
                tvDoubanStatus.setTextColor(0xFF34D399.toInt())
                btnToggleDouban.text = "中途关闭豆板"
                tvDoubanTime.visibility = View.VISIBLE
                tvDoubanTime.text = "豆板已用时长: ${formatSeconds(card.doubanElapsedSeconds)}"
            } else {
                tvDoubanStatus.text = "📟 智能豆板: 未使用"
                tvDoubanStatus.setTextColor(0xFF7F91A4.toInt())
                btnToggleDouban.text = "中途开启豆板"
                tvDoubanTime.visibility = View.GONE
            }
        }
        updateDoubanUi()

        // 中途开启/关闭智能豆板
        btnToggleDouban.setOnClickListener {
            val nextState = !card.useDouban
            lifecycleScope.launch {
                val (updated, synced) = apiClient.updateCardConfig(card.cardId, useDouban = nextState)
                card.useDouban = nextState
                updateDoubanUi()
                if (synced) {
                    Toast.makeText(this@MainActivity, if (nextState) "✅ 智能豆板已开启，已同步至服务器" else "✅ 智能豆板已关闭，已同步至服务器", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, if (nextState) "⚠️ 智能豆板已开启 (本地离线)" else "⚠️ 智能豆板已关闭 (本地离线)", Toast.LENGTH_SHORT).show()
                }
                refreshCardsLocal()
            }
        }

        // 修改/调整开始时间
        btnAdjustStartTime.setOnClickListener {
            val options = arrayOf("推迟 5 分钟", "推迟 15 分钟", "提前 5 分钟 (补录已玩)", "提前 15 分钟 (补录已玩)", "提前 30 分钟 (补录已玩)")
            AlertDialog.Builder(this)
                .setTitle("🕒 修改卡片开始时间")
                .setItems(options) { _, which ->
                    val deltaMinutes = when (which) {
                        0 -> 5
                        1 -> 15
                        2 -> -5
                        3 -> -15
                        4 -> -30
                        else -> 0
                    }
                    val sdf = getIsoFormat()
                    val curStartMs = try { sdf.parse(card.startTimeUtc ?: "")?.time ?: System.currentTimeMillis() } catch (e: Exception) { System.currentTimeMillis() }
                    val newStartMs = curStartMs + (deltaMinutes * 60 * 1000L)
                    val newIso = sdf.format(Date(newStartMs))

                    lifecycleScope.launch {
                        val (updated, synced) = apiClient.updateCardConfig(card.cardId, startTimeUtc = newIso)
                        card.startTimeUtc = newIso
                        if (synced) {
                            Toast.makeText(this@MainActivity, "✅ 开始时间已调整，已同步至服务器", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠️ 开始时间已调整 (本地离线)", Toast.LENGTH_SHORT).show()
                        }
                        refreshCardsLocal()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        var dialogJob: Job? = null
        dialogJob = lifecycleScope.launch {
            while (isActive) {
                // Real-time update in dialog
                val clockSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val startMs = try { getIsoFormat().parse(card.startTimeUtc ?: "")?.time } catch (e: Exception) { null }
                tvStartTimeDisplay.text = "🕒 开始时间: ${if (startMs != null) clockSdf.format(Date(startMs)) else "--:--"}"

                if (card.timerMode == 1) {
                    tvRemainingBig.text = formatSeconds(card.elapsedSeconds)
                    tvRemainingBig.setTextColor(if (card.status == 2) 0xFFE58032.toInt() else 0xFF2AABEE.toInt())
                    tvOverdueAlert.visibility = View.GONE
                } else {
                    if (card.isOverdue || card.status == 3) {
                        tvRemainingBig.text = "已超时"
                        tvRemainingBig.setTextColor(0xFFFF5252.toInt())
                        tvOverdueAlert.visibility = View.VISIBLE
                        tvOverdueAlert.text = "🚨 已超时: ${formatSeconds(card.overdueSeconds)}"
                    } else {
                        tvRemainingBig.text = formatSeconds(card.remainingSeconds)
                        tvRemainingBig.setTextColor(if (card.status == 2) 0xFFE58032.toInt() else 0xFF45B880.toInt())
                        tvOverdueAlert.visibility = View.GONE
                    }
                }

                if (card.useDouban) {
                    tvDoubanTime.text = "豆板已用时长: ${formatSeconds(card.doubanElapsedSeconds)}"
                }

                // Pricing
                val p = card.pricing ?: PriceCalculator.computeCardPricing(card)
                tvBestPlanTitle.text = if (card.isPostPay) "💡 玩完再付最优方案: ${p.bestPlanName}" else "📦 当前计费: ${p.bestPlanName}"
                tvPriceBig.text = "¥ ${String.format(Locale.US, "%.1f", p.totalPrice)}"
                tvFormula.text = "📐 公式: ${p.formula}"
                tvBreakdown.text = p.breakdownItems.joinToString("\n")

                delay(1000)
            }
        }

        dialog.setOnDismissListener { dialogJob?.cancel() }

        btnAdd10.setOnClickListener {
            lifecycleScope.launch {
                val (updated, synced) = apiClient.addTime(card.cardId, 10 * 60)
                if (synced) {
                    Toast.makeText(this@MainActivity, "✅ 已加时 10 分钟并同步至服务器", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ 已加时 10 分钟 (本地离线)", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                refreshCardsLocal()
            }
        }

        btnAdd30.setOnClickListener {
            lifecycleScope.launch {
                val (updated, synced) = apiClient.addTime(card.cardId, 30 * 60)
                if (synced) {
                    Toast.makeText(this@MainActivity, "✅ 已加时 30 分钟并同步至服务器", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ 已加时 30 分钟 (本地离线)", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                refreshCardsLocal()
            }
        }

        btnAdd1H.setOnClickListener {
            lifecycleScope.launch {
                val (updated, synced) = apiClient.addTime(card.cardId, 60 * 60)
                if (synced) {
                    Toast.makeText(this@MainActivity, "✅ 已加时 1 小时并同步至服务器", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ 已加时 1 小时 (本地离线)", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                refreshCardsLocal()
            }
        }

        btnPause.setOnClickListener {
            val act = if (card.status == 2) "resume" else "pause"
            lifecycleScope.launch {
                val (updated, synced) = apiClient.setTimer(card.cardId, 0, act, card.timerMode, card.isPostPay, card.presetPlan, card.useDouban, card.doubanPlan)
                if (synced) {
                    Toast.makeText(this@MainActivity, if (act == "resume") "✅ 计时已恢复并同步至服务器" else "✅ 计时已暂停并同步至服务器", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, if (act == "resume") "⚠️ 计时已恢复 (本地离线)" else "⚠️ 计时已暂停 (本地离线)", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                refreshCardsLocal()
            }
        }

        btnStop.setOnClickListener {
            val p = card.pricing ?: PriceCalculator.computeCardPricing(card)
            val breakdownStr = if (p.breakdownItems.isNotEmpty()) {
                p.breakdownItems.joinToString("\n") + "\n\n"
            } else ""

            val title = if (!card.isPostPay && card.presetPlan.isNotEmpty() && card.presetPlan != "none") {
                if (p.needToPay > 0) "💰 结账清单 (需补收差价 ¥${String.format(Locale.US, "%.1f", p.needToPay)})" else "💰 结账清单 (已预付，无补收)"
            } else {
                "💰 结账清单 (应收 ¥${String.format(Locale.US, "%.1f", p.totalPrice)})"
            }

            val paySummary = if (!card.isPostPay && card.presetPlan.isNotEmpty() && card.presetPlan != "none") {
                "已付基价: ¥${String.format(Locale.US, "%.1f", p.playFee)}\n" +
                "💵 本次需补收: ¥${String.format(Locale.US, "%.1f", p.needToPay)}"
            } else {
                "💵 应收总额: ¥${String.format(Locale.US, "%.1f", p.totalPrice)}"
            }

            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(
                    "手作名称: ${card.name}\n" +
                    "已玩时长: ${formatSeconds(card.elapsedSeconds)}\n" +
                    (if (card.useDouban) "智能豆板: 已用 ${formatSeconds(card.doubanElapsedSeconds)}\n" else "") +
                    "结算方案: ${p.bestPlanName}\n" +
                    "计算说明: ${p.formula}\n\n" +
                    breakdownStr +
                    paySummary
                )
                .setPositiveButton("确认收款并结束") { _, _ ->
                    lifecycleScope.launch {
                        val (updated, synced) = apiClient.setTimer(card.cardId, 0, "stop")
                        if (synced) {
                            Toast.makeText(this@MainActivity, "✅ 结算重置成功，已同步至服务器！", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠️ 结算重置成功 (本地离线)", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                        refreshCardsLocal()
                    }
                }
                .setNegativeButton("继续游玩", null)
                .show()
        }

        dialog.show()
    }

    class CardAdapter : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {
        private var cards: List<CardInfo> = emptyList()
        var onCardClick: ((CardInfo) -> Unit)? = null

        fun setCards(newCards: List<CardInfo>) {
            cards = newCards
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
            return CardViewHolder(view)
        }

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            holder.bind(cards[position], onCardClick)
        }

        override fun getItemCount(): Int = cards.size

        class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvCardName)
            private val tvUid: TextView = itemView.findViewById(R.id.tvCardUid)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTimeDisplay)
            private val tvAvatarChar: TextView = itemView.findViewById(R.id.tvAvatarChar)
            private val tvTagDouban: TextView = itemView.findViewById(R.id.tvTagDouban)
            private val tvTagPostPay: TextView = itemView.findViewById(R.id.tvTagPostPay)
            private val tvTagPreset: TextView = itemView.findViewById(R.id.tvTagPreset)
            private val tvRemarkBadge: TextView = itemView.findViewById(R.id.tvRemarkBadge)
            private val tvCardPriceEstimate: TextView = itemView.findViewById(R.id.tvCardPriceEstimate)

            fun bind(card: CardInfo, onCardClick: ((CardInfo) -> Unit)?) {
                tvName.text = card.name
                tvAvatarChar.text = if (card.name.isNotEmpty()) card.name.take(1) else "卡"
                tvUid.text = "UID: ${card.cardId}"

                // 1. 智能豆板 Tag
                // 1. 智能豆板 Tag (只要用了就直接显示已用时长与应收豆板费)
                if (card.useDouban) {
                    tvTagDouban.visibility = View.VISIBLE
                    val (dFee, _) = PriceCalculator.calculateDoubanFee(card.doubanElapsedSeconds)
                    tvTagDouban.text = "📟 豆板已用: ¥${String.format(Locale.US, "%.1f", dFee)}"
                } else {
                    tvTagDouban.visibility = View.GONE
                }

                // 2. 玩完再付 (后付款) Tag
                if (card.isPostPay) {
                    tvTagPostPay.visibility = View.VISIBLE
                    tvTagPostPay.text = "⚠️ 玩完再付"
                } else {
                    tvTagPostPay.visibility = View.GONE
                }

                // 3. 预设套餐 Tag
                if (card.presetPlan.isNotEmpty() && card.presetPlan != "none") {
                    tvTagPreset.visibility = View.VISIBLE
                    val name = when (card.presetPlan) {
                        "1h" -> "📦 1h套餐"
                        "3h" -> "📦 3h套餐"
                        else -> "📦 ${card.presetPlan}"
                    }
                    tvTagPreset.text = name
                } else {
                    tvTagPreset.visibility = View.GONE
                }

                // 4. 备注 Tag
                if (card.remark.isNotEmpty()) {
                    tvRemarkBadge.visibility = View.VISIBLE
                    tvRemarkBadge.text = "📝 ${card.remark}"
                } else {
                    tvRemarkBadge.visibility = View.GONE
                }

                // 5. 状态与时间显示
                if (card.timerMode == 1) {
                    // 正计时
                    if (card.status == 1) {
                        tvBadge.text = "⏱️ 正计时"
                        tvBadge.setTextColor(0xFF2AABEE.toInt())
                        tvTime.text = formatSeconds(card.elapsedSeconds)
                        tvTime.setTextColor(0xFF2AABEE.toInt())
                    } else if (card.status == 2) {
                        tvBadge.text = "🍵 已暂停"
                        tvBadge.setTextColor(0xFFE58032.toInt())
                        tvTime.text = formatSeconds(card.elapsedSeconds)
                        tvTime.setTextColor(0xFFE58032.toInt())
                    }
                } else {
                    // 倒计时
                    if (card.isOverdue || card.status == 3) {
                        tvBadge.text = "🚨 已超时"
                        tvBadge.setTextColor(0xFFFF5252.toInt())
                        tvTime.text = formatSeconds(card.overdueSeconds)
                        tvTime.setTextColor(0xFFFF5252.toInt())
                    } else if (card.status == 1) {
                        tvBadge.text = "制作中"
                        tvBadge.setTextColor(0xFF45B880.toInt())
                        tvTime.text = formatSeconds(card.remainingSeconds)
                        tvTime.setTextColor(0xFF45B880.toInt())
                    } else if (card.status == 2) {
                        tvBadge.text = "已暂停"
                        tvBadge.setTextColor(0xFFE58032.toInt())
                        tvTime.text = formatSeconds(card.remainingSeconds)
                        tvTime.setTextColor(0xFFE58032.toInt())
                    }
                }

                // 6. 价格预估与补收提示
                val p = card.pricing ?: PriceCalculator.computeCardPricing(card)
                if (card.status != 0) {
                    tvCardPriceEstimate.visibility = View.VISIBLE
                    if (!card.isPostPay && card.presetPlan.isNotEmpty() && card.presetPlan != "none") {
                        if (p.needToPay > 0) {
                            tvCardPriceEstimate.text = "💰 需补收: ¥${String.format(Locale.US, "%.1f", p.needToPay)} (超时加时/豆板)"
                            tvCardPriceEstimate.setTextColor(0xFFFF5252.toInt())
                        } else {
                            tvCardPriceEstimate.text = "💰 已付套餐: ¥${String.format(Locale.US, "%.1f", p.playFee)} (正常未超时)"
                            tvCardPriceEstimate.setTextColor(0xFF4ADE80.toInt())
                        }
                    } else {
                        tvCardPriceEstimate.text = "💡 最优结算: ¥${String.format(Locale.US, "%.1f", p.totalPrice)} (${p.bestPlanName})"
                        tvCardPriceEstimate.setTextColor(0xFF38BDF8.toInt())
                    }
                } else {
                    tvCardPriceEstimate.visibility = View.GONE
                }

                itemView.setOnClickListener { onCardClick?.invoke(card) }
            }

            private fun formatSeconds(sec: Double): String {
                return MainActivity.formatSeconds(sec)
            }
        }
    }
}
