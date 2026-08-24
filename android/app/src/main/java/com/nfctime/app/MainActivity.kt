package com.nfctime.app

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
import com.nfctime.app.api.CardInfo
import com.nfctime.app.api.GitHubApiClient
import com.nfctime.app.api.PriceCalculator
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
    private lateinit var apiClient: GitHubApiClient
    private var syncJob: Job? = null
    private var tickJob: Job? = null

    private lateinit var tvNfcStatus: TextView
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

        apiClient = GitHubApiClient(this)

        tvNfcStatus = findViewById(R.id.tvNfcStatus)
        rvCards = findViewById(R.id.rvCards)
        rvCards.layoutManager = LinearLayoutManager(this)
        rvCards.adapter = adapter

        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
        val savedGistId = prefs.getString("gist_id", "") ?: ""
        val savedToken = prefs.getString("github_token", "") ?: ""
        apiClient.updateConfig(savedGistId, savedToken)

        findViewById<ImageView>(R.id.btnNavSettings).setOnClickListener {
            showGitHubSettingsDialog()
        }

        findViewById<ImageView>(R.id.btnNavAnnounce).setOnClickListener {
            startActivity(Intent(this, AnnouncementActivity::class.java))
        }

        findViewById<ImageView>(R.id.btnNavUnused).setOnClickListener {
            startActivity(Intent(this, UnusedCardsActivity::class.java))
        }

        adapter.onManageClick = { card ->
            openCardDialogByState(card)
        }

        initNfc()
        handleIntent(intent)
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvNfcStatus.text = "⚠️ 设备不支持 NFC"
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            tvNfcStatus.text = "⚠️ NFC 未开启，请在系统设置中启用 NFC"
        } else {
            tvNfcStatus.text = "🟢 NFC 已就绪，随时可贴卡"
        }
    }

    private var isInitialSyncing = true
    private var hasPerformedInitialSync = false
    private var initialSyncDialog: AlertDialog? = null

    private fun showInitialSyncDialog() {
        if (initialSyncDialog?.isShowing == true) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_initial_syncing, null)
        initialSyncDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        initialSyncDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        initialSyncDialog?.show()
    }

    private fun dismissInitialSyncDialog() {
        try {
            initialSyncDialog?.dismiss()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        initialSyncDialog = null
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatchSafely()
        startPollingOrInitialSync()
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

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopPolling()
    }

    private fun startPollingOrInitialSync() {
        syncJob?.cancel()
        tickJob?.cancel()

        if (!hasPerformedInitialSync) {
            isInitialSyncing = true
            showInitialSyncDialog()

            lifecycleScope.launch {
                try {
                    refreshCards(forceFetch = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    hasPerformedInitialSync = true
                    isInitialSyncing = false
                    dismissInitialSyncDialog()
                    Toast.makeText(this@MainActivity, "✅ 强制同步云端数据完成", Toast.LENGTH_SHORT).show()
                    startRegularPolling()
                }
            }
        } else {
            startRegularPolling()
        }
    }

    private fun startRegularPolling() {
        syncJob?.cancel()
        tickJob?.cancel()

        // 1. Tick Job: refreshes local ticking calculations every 1 second offline without fetching Gist
        tickJob = lifecycleScope.launch {
            while (isActive) {
                refreshCards(forceFetch = false)
                delay(1000)
            }
        }

        // 2. Sync Job: fetches remote Gist every 10 seconds asynchronously to synchronize state
        syncJob = lifecycleScope.launch {
            while (isActive) {
                refreshCards(forceFetch = true)
                delay(10000)
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
        if (isInitialSyncing) {
            Toast.makeText(this, "⏳ 正在强制同步云端最新数据，请稍等...", Toast.LENGTH_SHORT).show()
            return
        }
        val uidBytes = tag.id
        val cardId = uidBytes.joinToString(":") { String.format("%02X", it) }
        tvNfcStatus.text = "✨ 贴卡成功! UID: $cardId"

        val card = apiClient.swipeCardImmediate(cardId)
        Toast.makeText(this@MainActivity, "感应成功: ${card.name}", Toast.LENGTH_SHORT).show()
        
        openCardDialogByState(card)
        triggerLocalRefresh()
    }

    private fun openCardDialogByState(card: CardInfo) {
        if (card.status == 1 || card.status == 2 || card.status == 3 || card.isOverdue) {
            showActiveRunningCardDialog(card)
        } else {
            showCardControlDialog(card)
        }
    }

    private fun triggerLocalRefresh() {
        lifecycleScope.launch { refreshCards(forceFetch = false) }
    }

    private suspend fun refreshCards(forceFetch: Boolean) {
        val allCards = apiClient.getAllCards(forceFetch)
        val activeCards = allCards.filter { it.status != 0 }.toMutableList()

        // Sort: Overdue cards first, followed by running, then paused
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

    private fun showGitHubSettingsDialog() {
        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etGistId = EditText(this).apply {
            hint = "GitHub Gist ID"
            setText(prefs.getString("gist_id", ""))
        }
        val etToken = EditText(this).apply {
            hint = "GitHub Personal Access Token (PAT)"
            setText(prefs.getString("github_token", ""))
        }

        layout.addView(TextView(this).apply { text = "Gist ID:" })
        layout.addView(etGistId)
        layout.addView(TextView(this).apply { text = "\nGitHub Token (具有 Gist 读写权限):" })
        layout.addView(etToken)

        AlertDialog.Builder(this)
            .setTitle("配置 GitHub 云端存储")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val gistId = etGistId.text.toString().trim()
                val token = etToken.text.toString().trim()

                prefs.edit()
                    .putString("gist_id", gistId)
                    .putString("github_token", token)
                    .apply()

                apiClient.updateConfig(gistId, token)
                Toast.makeText(this, "已保存 GitHub 云端配置", Toast.LENGTH_SHORT).show()
                triggerLocalRefresh()
            }
            .setNegativeButton("取消", null)
            .show()
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
        val tvActiveRemark = view.findViewById<TextView>(R.id.tvActiveRemark)
        val btnActiveQuickUnpaid = view.findViewById<Button>(R.id.btnActiveQuickUnpaid)
        val btnActiveQuickClear = view.findViewById<Button>(R.id.btnActiveQuickClear)
        val tvActiveTimeLabel = view.findViewById<TextView>(R.id.tvActiveTimeLabel)
        val tvRemaining = view.findViewById<TextView>(R.id.tvActiveRemainingBig)
        val tvOverdueAlert = view.findViewById<TextView>(R.id.tvActiveOverdueAlert)

        // Price Section
        val tvEstimatedPriceBig = view.findViewById<TextView>(R.id.tvEstimatedPriceBig)
        val tvBestPlanName = view.findViewById<TextView>(R.id.tvBestPlanName)
        val tvPriceBreakdown = view.findViewById<TextView>(R.id.tvPriceBreakdown)
        val tvDoubleModeBadge = view.findViewById<TextView>(R.id.tvDoubleModeBadge)
        val btnToggleDoubleMode = view.findViewById<Button>(R.id.btnToggleDoubleMode)

        // Timeline Details
        val tvStartTime = view.findViewById<TextView>(R.id.tvActiveStartTime)
        val tvEndTime = view.findViewById<TextView>(R.id.tvActiveEndTime)
        val tvTargetDuration = view.findViewById<TextView>(R.id.tvActiveTargetDuration)
        val llActiveEndTimeRow = view.findViewById<LinearLayout>(R.id.llActiveEndTimeRow)
        val llActiveTargetDurationRow = view.findViewById<LinearLayout>(R.id.llActiveTargetDurationRow)
        val llCountdownAddSection = view.findViewById<LinearLayout>(R.id.llCountdownAddSection)

        val btnAdd10 = view.findViewById<Button>(R.id.btnAdd10Min)
        val btnAdd30 = view.findViewById<Button>(R.id.btnAdd30Min)
        val btnAdd1H = view.findViewById<Button>(R.id.btnAdd1Hour)
        val btnPause = view.findViewById<Button>(R.id.btnActivePause)
        val btnStop = view.findViewById<Button>(R.id.btnActiveStop)

        tvTitle.text = card.name
        tvUid.text = "UID: ${card.cardId}"

        if (card.remark.isNotEmpty()) {
            tvActiveRemark.text = "📝 备注: ${card.remark}"
        } else {
            tvActiveRemark.text = "📝 备注: 无"
        }

        btnActiveQuickUnpaid.setOnClickListener {
            apiClient.updateCardRemarkImmediate(card.cardId, "未付款")
            Toast.makeText(this, "已快速标记为 [未付款]", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnActiveQuickClear.setOnClickListener {
            apiClient.updateCardRemarkImmediate(card.cardId, "")
            Toast.makeText(this, "备注已清空", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnPause.text = if (card.status == 2) "继续" else "暂停"

        val sdf = getIsoFormat()
        val startMs = try { sdf.parse(card.startTimeUtc ?: "")?.time } catch (e: Exception) { null }
        val startDate = try { sdf.parse(card.startTimeUtc ?: "") } catch (e: Exception) { null }

        if (startMs != null) {
            val clockSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvStartTime.text = clockSdf.format(Date(startMs))
            if (card.timerMode == 0 && card.targetDurationSeconds > 0) {
                val endMs = startMs + (card.targetDurationSeconds * 1000L)
                tvEndTime.text = clockSdf.format(Date(endMs))
            } else {
                tvEndTime.text = "--:--:--"
            }
        } else {
            tvStartTime.text = "--:--:--"
            tvEndTime.text = "--:--:--"
        }
        tvTargetDuration.text = formatTime(card.targetDurationSeconds.toDouble())

        if (card.timerMode == 1) {
            // 正计时模式
            tvActiveTimeLabel.text = "⏱️ 已玩时长 (正计时)"
            llActiveEndTimeRow.visibility = View.GONE
            llActiveTargetDurationRow.visibility = View.GONE
            llCountdownAddSection.visibility = View.GONE
            btnStop.text = "💰 结算并停止"
        } else {
            // 倒计时模式
            tvActiveTimeLabel.text = "⏳ 剩余时间 (倒计时)"
            llActiveEndTimeRow.visibility = View.VISIBLE
            llActiveTargetDurationRow.visibility = View.VISIBLE
            llCountdownAddSection.visibility = View.VISIBLE
            btnStop.text = "停止计时"
        }

        fun updateDoubleModeUi() {
            tvDoubleModeBadge.text = if (card.isDouble) "👥 当前: 双人计费" else "👤 当前: 单人计费"
            btnToggleDoubleMode.text = if (card.isDouble) "切为单人计费" else "切为双人计费"
        }
        updateDoubleModeUi()

        btnToggleDoubleMode.setOnClickListener {
            card.isDouble = !card.isDouble
            updateDoubleModeUi()
            val pricing = PriceCalculator.calculateBestPrice(
                if (card.timerMode == 1) card.elapsedSeconds else card.targetDurationSeconds.toDouble(),
                startDate,
                card.isDouble
            )
            card.estimatedPrice = pricing.price
            card.pricePlanName = pricing.planName
            card.priceDetails = pricing.details
            tvEstimatedPriceBig.text = "¥ ${String.format(Locale.US, "%.1f", pricing.price)}"
            tvBestPlanName.text = "💡 推荐方案: ${pricing.planName}"
            tvPriceBreakdown.text = pricing.details
        }

        var dialogTickJob: Job? = null
        dialogTickJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (card.timerMode == 1) {
                    // 正计时
                    var elapsed = card.savedRemainingSeconds.toDouble()
                    if (startMs != null && card.status == 1) {
                        val currentSegment = Math.max(0.0, (now - startMs) / 1000.0)
                        elapsed = card.savedRemainingSeconds.toDouble() + currentSegment
                    }
                    card.elapsedSeconds = elapsed

                    tvStatusBadge.text = if (card.status == 2) "🍵 已暂停" else "⏱️ 正计时中"
                    tvStatusBadge.setTextColor(if (card.status == 2) 0xFFE58032.toInt() else 0xFF0284C7.toInt())

                    tvRemaining.text = formatTime(elapsed)
                    tvRemaining.setTextColor(if (card.status == 2) 0xFFE58032.toInt() else 0xFF0284C7.toInt())
                    tvOverdueAlert.visibility = View.GONE

                    val pricing = PriceCalculator.calculateBestPrice(elapsed, startDate, card.isDouble)
                    card.estimatedPrice = pricing.price
                    card.pricePlanName = pricing.planName
                    card.priceDetails = pricing.details

                    tvEstimatedPriceBig.text = "¥ ${String.format(Locale.US, "%.1f", pricing.price)}"
                    tvBestPlanName.text = "💡 推荐方案: ${pricing.planName}"
                    tvPriceBreakdown.text = pricing.details
                } else {
                    // 倒计时
                    var remaining = card.savedRemainingSeconds.toDouble()
                    var isOverdue = false
                    var overdueSeconds = 0.0

                    if (startMs != null && (card.status == 1 || card.status == 3)) {
                        val elapsedSec = (now - startMs) / 1000.0
                        remaining = card.savedRemainingSeconds - elapsedSec
                    }

                    if (remaining < 0) {
                        isOverdue = true
                        overdueSeconds = Math.abs(remaining)
                    }

                    if (isOverdue) {
                        tvStatusBadge.text = "⚠️ 已超时"
                        tvStatusBadge.setTextColor(0xFFFF5252.toInt())

                        tvRemaining.text = "已超时"
                        tvRemaining.setTextColor(0xFFFF5252.toInt())

                        tvOverdueAlert.visibility = View.VISIBLE
                        tvOverdueAlert.text = "🚨 已超时: ${formatTime(overdueSeconds)}"
                    } else {
                        tvStatusBadge.text = if (card.status == 2) "已暂停" else "进行中"
                        tvStatusBadge.setTextColor(if (card.status == 2) 0xFFE58032.toInt() else 0xFF45B880.toInt())

                        tvRemaining.text = formatTime(remaining)
                        tvRemaining.setTextColor(if (card.status == 2) 0xFFE58032.toInt() else 0xFF45B880.toInt())

                        tvOverdueAlert.visibility = View.GONE
                    }

                    // 实时计算倒计时对应的价格
                    val totalDuration = card.targetDurationSeconds + overdueSeconds
                    val pricing = PriceCalculator.calculateBestPrice(totalDuration, startDate, card.isDouble)
                    card.estimatedPrice = pricing.price
                    card.pricePlanName = pricing.planName
                    card.priceDetails = pricing.details

                    tvEstimatedPriceBig.text = "¥ ${String.format(Locale.US, "%.1f", pricing.price)}"
                    tvBestPlanName.text = "💡 方案: ${pricing.planName}"
                    tvPriceBreakdown.text = pricing.details
                }

                delay(1000)
            }
        }

        dialog.setOnDismissListener {
            dialogTickJob?.cancel()
        }

        btnAdd10.setOnClickListener {
            apiClient.addTimeImmediate(card.cardId, 10 * 60)
            Toast.makeText(this, "已加时 10 分钟", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnAdd30.setOnClickListener {
            apiClient.addTimeImmediate(card.cardId, 30 * 60)
            Toast.makeText(this, "已加时 30 分钟", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnAdd1H.setOnClickListener {
            apiClient.addTimeImmediate(card.cardId, 60 * 60)
            Toast.makeText(this, "已加时 1 小时", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        val etCustomAddMinutes = view.findViewById<EditText>(R.id.etCustomAddMinutes)
        val btnCustomAddConfirm = view.findViewById<Button>(R.id.btnCustomAddConfirm)

        btnCustomAddConfirm.setOnClickListener {
            val minutesStr = etCustomAddMinutes.text.toString().trim()
            if (minutesStr.isNotEmpty()) {
                val mins = minutesStr.toIntOrNull()
                if (mins != null && mins > 0) {
                    apiClient.addTimeImmediate(card.cardId, mins * 60)
                    Toast.makeText(this, "已成功加时 $mins 分钟", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    triggerLocalRefresh()
                } else {
                    Toast.makeText(this, "请输入正确的正整数分钟数", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "请先输入要增加的分钟数", Toast.LENGTH_SHORT).show()
            }
        }

        btnPause.setOnClickListener {
            val action = if (card.status == 2) "resume" else "pause"
            apiClient.setTimerImmediate(card.cardId, 0, action, card.timerMode, card.isDouble)
            Toast.makeText(this, if (action == "resume") "已恢复" else "已暂停", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnStop.setOnClickListener {
            if (card.timerMode == 1) {
                // 正计时模式弹出详细结账清单
                AlertDialog.Builder(this)
                    .setTitle("💰 结账结算清单")
                    .setMessage(
                        "手作名称: ${card.name}\n" +
                        "已玩时长: ${formatTime(card.elapsedSeconds)}\n" +
                        "计费人数: ${if (card.isDouble) "双人" else "单人"}\n" +
                        "最划算方案: ${card.pricePlanName}\n" +
                        "计费明细: ${card.priceDetails}\n\n" +
                        "💵 应收总额: ¥${String.format(Locale.US, "%.1f", card.estimatedPrice)}"
                    )
                    .setPositiveButton("确认收款并结束") { _, _ ->
                        apiClient.setTimerImmediate(card.cardId, 0, "stop", 1, card.isDouble)
                        Toast.makeText(this, "结算完成，已停止计时！", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        triggerLocalRefresh()
                    }
                    .setNegativeButton("继续游玩", null)
                    .show()
            } else {
                apiClient.setTimerImmediate(card.cardId, 0, "stop", 0, card.isDouble)
                Toast.makeText(this, "已停止计时", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                triggerLocalRefresh()
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
        val etRemark = view.findViewById<EditText>(R.id.etCardRemark)
        val llTimelineDetails = view.findViewById<LinearLayout>(R.id.llTimelineDetails)

        val rgTimerMode = view.findViewById<RadioGroup>(R.id.rgTimerMode)
        val rbCountdown = view.findViewById<RadioButton>(R.id.rbCountdown)
        val rbCountup = view.findViewById<RadioButton>(R.id.rbCountup)
        val cbIsDouble = view.findViewById<CheckBox>(R.id.cbIsDouble)

        val btnQuick1H = view.findViewById<Button>(R.id.btnQuick1H)
        val btnQuick3H = view.findViewById<Button>(R.id.btnQuick3H)
        val btnQuickMorning = view.findViewById<Button>(R.id.btnQuickMorning)
        val btnQuickAfternoon = view.findViewById<Button>(R.id.btnQuickAfternoon)
        val btnQuickAllDay = view.findViewById<Button>(R.id.btnQuickAllDay)
        val btnQuickDouble3H = view.findViewById<Button>(R.id.btnQuickDouble3H)

        val tvPickerTitle = view.findViewById<TextView>(R.id.tvPickerTitle)
        val llPickerArea = view.findViewById<LinearLayout>(R.id.llPickerArea)
        val npHours = view.findViewById<NumberPicker>(R.id.npHours)
        val npMinutes = view.findViewById<NumberPicker>(R.id.npMinutes)

        val btnRename = view.findViewById<Button>(R.id.btnRename)
        val btnSaveRemark = view.findViewById<Button>(R.id.btnSaveRemark)
        val btnQuickUnpaid = view.findViewById<Button>(R.id.btnQuickUnpaid)
        val btnQuickClearRemark = view.findViewById<Button>(R.id.btnQuickClearRemark)
        val btnStart = view.findViewById<Button>(R.id.btnStart)
        val btnPause = view.findViewById<Button>(R.id.btnPause)
        val btnStop = view.findViewById<Button>(R.id.btnStop)
        val btnDeleteCard = view.findViewById<Button>(R.id.btnDeleteCard)

        tvUid.text = "UID: ${card.cardId}"
        etName.setText(card.name)
        etRemark.setText(card.remark)

        llTimelineDetails.visibility = View.GONE
        btnPause.visibility = View.GONE
        btnStop.visibility = View.GONE

        btnDeleteCard.text = "🗑️ 清理/设为未使用状态"

        npHours.minValue = 0
        npHours.maxValue = 23
        npMinutes.minValue = 0
        npMinutes.maxValue = 59

        val initialSec = if (card.targetDurationSeconds > 0) card.targetDurationSeconds else 3600
        val initialH = initialSec / 3600
        val initialM = (initialSec % 3600) / 60
        npHours.value = Math.min(23, initialH)
        npMinutes.value = initialM

        cbIsDouble.isChecked = card.isDouble
        if (card.timerMode == 1) {
            rbCountup.isChecked = true
            llPickerArea.visibility = View.GONE
            tvPickerTitle.visibility = View.GONE
            btnStart.text = "⏱️ 开始正计时 (先玩后付)"
        } else {
            rbCountdown.isChecked = true
            llPickerArea.visibility = View.VISIBLE
            tvPickerTitle.visibility = View.VISIBLE
            btnStart.text = "⏳ 开始倒计时"
        }

        rgTimerMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCountup) {
                llPickerArea.visibility = View.GONE
                tvPickerTitle.visibility = View.GONE
                btnStart.text = "⏱️ 开始正计时 (先玩后付)"
            } else {
                llPickerArea.visibility = View.VISIBLE
                tvPickerTitle.visibility = View.VISIBLE
                btnStart.text = "⏳ 开始倒计时"
            }
        }

        btnQuick1H.setOnClickListener {
            rbCountdown.isChecked = true
            cbIsDouble.isChecked = false
            npHours.value = 1
            npMinutes.value = 0
            Toast.makeText(this, "已预选: 单人1小时套餐 (¥12.9)", Toast.LENGTH_SHORT).show()
        }

        btnQuick3H.setOnClickListener {
            rbCountdown.isChecked = true
            cbIsDouble.isChecked = false
            npHours.value = 3
            npMinutes.value = 0
            Toast.makeText(this, "已预选: 单人3小时套餐 (¥29.9)", Toast.LENGTH_SHORT).show()
        }

        btnQuickMorning.setOnClickListener {
            rbCountdown.isChecked = true
            cbIsDouble.isChecked = false
            npHours.value = 4
            npMinutes.value = 0
            Toast.makeText(this, "已预选: 单人上午场套餐 (10:00-14:00, ¥36.9)", Toast.LENGTH_SHORT).show()
        }

        btnQuickAfternoon.setOnClickListener {
            rbCountdown.isChecked = true
            cbIsDouble.isChecked = false
            npHours.value = 5
            npMinutes.value = 30
            Toast.makeText(this, "已预选: 单人下午场套餐 (14:00-19:30, ¥43.9)", Toast.LENGTH_SHORT).show()
        }

        btnQuickAllDay.setOnClickListener {
            rbCountdown.isChecked = true
            cbIsDouble.isChecked = false
            npHours.value = 10
            npMinutes.value = 30
            Toast.makeText(this, "已预选: 单人全天场套餐 (10:00-20:30, ¥59.9)", Toast.LENGTH_SHORT).show()
        }

        btnQuickDouble3H.setOnClickListener {
            rbCountdown.isChecked = true
            cbIsDouble.isChecked = true
            npHours.value = 3
            npMinutes.value = 0
            Toast.makeText(this, "已预选: 双人3小时套餐 (¥56.9)", Toast.LENGTH_SHORT).show()
        }

        btnRename.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                apiClient.renameCardImmediate(card.cardId, newName)
                Toast.makeText(this@MainActivity, "卡片名称已更新: $newName", Toast.LENGTH_SHORT).show()
                triggerLocalRefresh()
            }
        }

        btnSaveRemark.setOnClickListener {
            val newRemark = etRemark.text.toString().trim()
            apiClient.updateCardRemarkImmediate(card.cardId, newRemark)
            Toast.makeText(this@MainActivity, "备注已保存", Toast.LENGTH_SHORT).show()
            triggerLocalRefresh()
        }

        btnQuickUnpaid.setOnClickListener {
            etRemark.setText("未付款")
            apiClient.updateCardRemarkImmediate(card.cardId, "未付款")
            Toast.makeText(this@MainActivity, "已标记为 [未付款]", Toast.LENGTH_SHORT).show()
            triggerLocalRefresh()
        }

        btnQuickClearRemark.setOnClickListener {
            etRemark.setText("")
            apiClient.updateCardRemarkImmediate(card.cardId, "")
            Toast.makeText(this@MainActivity, "备注已清空", Toast.LENGTH_SHORT).show()
            triggerLocalRefresh()
        }

        btnStart.setOnClickListener {
            val isCountup = rbCountup.isChecked
            val timerMode = if (isCountup) 1 else 0
            val isDouble = cbIsDouble.isChecked
            val totalSeconds = if (isCountup) 0 else (npHours.value * 3600 + npMinutes.value * 60)

            apiClient.setTimerImmediate(card.cardId, totalSeconds, "start", timerMode, isDouble)
            Toast.makeText(this@MainActivity, if (isCountup) "正计时已开始 (先玩后付)!" else "倒计时已开始!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnDeleteCard.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("设为未使用状态")
                .setMessage("保留名称「${card.name}」，将其转为未使用状态？")
                .setPositiveButton("确认") { _, _ ->
                    apiClient.resetCardToUnusedImmediate(card.cardId)
                    Toast.makeText(this@MainActivity, "已移至未使用卡片档案库", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    triggerLocalRefresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        dialog.show()
    }

    companion object {
        fun formatTime(sec: Double): String {
            val totalSec = Math.abs(sec.toInt())
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            val sb = StringBuilder()
            if (h > 0) {
                sb.append("${h}小时")
            }
            if (m > 0 || h > 0) {
                sb.append("${m}分钟")
            }
            sb.append("${s}秒")
            return sb.toString()
        }
    }

    class CardAdapter : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {
        private var cards: List<CardInfo> = emptyList()
        var onManageClick: ((CardInfo) -> Unit)? = null

        fun setCards(newCards: List<CardInfo>) {
            cards = newCards
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
            return CardViewHolder(view)
        }

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            val card = cards[position]
            holder.bind(card, onManageClick)
        }

        override fun getItemCount(): Int = cards.size

        class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvCardName)
            private val tvUid: TextView = itemView.findViewById(R.id.tvCardUid)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTimeDisplay)
            private val tvAvatarChar: TextView = itemView.findViewById(R.id.tvAvatarChar)
            private val tvRemarkBadge: TextView = itemView.findViewById(R.id.tvRemarkBadge)
            private val tvCardPriceEstimate: TextView = itemView.findViewById(R.id.tvCardPriceEstimate)

            fun bind(card: CardInfo, onManageClick: ((CardInfo) -> Unit)?) {
                tvName.text = card.name
                tvAvatarChar.text = if (card.name.isNotEmpty()) card.name.take(1) else "卡"
                tvUid.text = "UID: ${card.cardId}"

                if (card.remark.isNotEmpty()) {
                    tvRemarkBadge.visibility = View.VISIBLE
                    if (card.remark.contains("未付款")) {
                        tvRemarkBadge.text = "⚠️ 未付款"
                        tvRemarkBadge.setBackgroundColor(0xFFFFE4E6.toInt())
                        tvRemarkBadge.setTextColor(0xFFE11D48.toInt())
                    } else {
                        tvRemarkBadge.text = "📝 ${card.remark}"
                        tvRemarkBadge.setBackgroundColor(0xFFF1F5F9.toInt())
                        tvRemarkBadge.setTextColor(0xFF475569.toInt())
                    }
                } else {
                    tvRemarkBadge.visibility = View.GONE
                }

                if (card.timerMode == 1) {
                    // 正计时卡片
                    if (card.status == 1) {
                        tvBadge.text = "⏱️ 正计时"
                        tvBadge.setTextColor(0xFF0284C7.toInt())
                        tvTime.text = formatTime(card.elapsedSeconds)
                        tvTime.setTextColor(0xFF0284C7.toInt())
                    } else if (card.status == 2) {
                        tvBadge.text = "🍵 已暂停"
                        tvBadge.setTextColor(0xFFE58032.toInt())
                        tvTime.text = formatTime(card.elapsedSeconds)
                        tvTime.setTextColor(0xFFE58032.toInt())
                    } else {
                        tvBadge.text = "未使用"
                        tvBadge.setTextColor(0xFF7F91A4.toInt())
                        tvTime.text = "0秒"
                        tvTime.setTextColor(0xFF7F91A4.toInt())
                    }

                    if (card.status != 0) {
                        tvCardPriceEstimate.visibility = View.VISIBLE
                        tvCardPriceEstimate.text = "💰 预估: ¥${String.format(Locale.US, "%.1f", card.estimatedPrice)} (${card.pricePlanName})"
                    } else {
                        tvCardPriceEstimate.visibility = View.GONE
                    }
                } else {
                    // 倒计时卡片
                    if (card.isOverdue || card.status == 3) {
                        tvBadge.text = "⚠️ 已超时"
                        tvBadge.setTextColor(0xFFFF5252.toInt())
                        tvTime.text = formatTime(card.overdueSeconds)
                        tvTime.setTextColor(0xFFFF5252.toInt())
                    } else if (card.status == 1) {
                        tvBadge.text = "进行中"
                        tvBadge.setTextColor(0xFF45B880.toInt())
                        tvTime.text = formatTime(card.remainingSeconds)
                        tvTime.setTextColor(0xFF45B880.toInt())
                    } else if (card.status == 2) {
                        tvBadge.text = "已暂停"
                        tvBadge.setTextColor(0xFFE58032.toInt())
                        tvTime.text = formatTime(card.remainingSeconds)
                        tvTime.setTextColor(0xFFE58032.toInt())
                    } else {
                        tvBadge.text = "未使用"
                        tvBadge.setTextColor(0xFF7F91A4.toInt())
                        tvTime.text = "0秒"
                        tvTime.setTextColor(0xFF7F91A4.toInt())
                    }

                    if (card.status != 0 && card.estimatedPrice > 0) {
                        tvCardPriceEstimate.visibility = View.VISIBLE
                        tvCardPriceEstimate.text = "💰 计费: ¥${String.format(Locale.US, "%.1f", card.estimatedPrice)} (${card.pricePlanName})"
                    } else {
                        tvCardPriceEstimate.visibility = View.GONE
                    }
                }

                itemView.setOnClickListener { onManageClick?.invoke(card) }
            }

            private fun formatTime(sec: Double): String {
                return MainActivity.formatTime(sec)
            }
        }
    }
}
