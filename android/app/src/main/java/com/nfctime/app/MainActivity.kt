package com.nfctime.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nfctime.app.api.CardInfo
import com.nfctime.app.api.GitHubApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private lateinit var apiClient: GitHubApiClient
    private var pollJob: Job? = null

    private lateinit var tvNfcStatus: TextView
    private lateinit var rvCards: RecyclerView
    private val adapter = CardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiClient = GitHubApiClient(this)

        tvNfcStatus = findViewById(R.id.tvNfcStatus)
        rvCards = findViewById(R.id.rvCards)
        rvCards.layoutManager = LinearLayoutManager(this)
        rvCards.adapter = adapter

        // Load saved GitHub Gist credentials
        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
        val savedGistId = prefs.getString("gist_id", "") ?: ""
        val savedToken = prefs.getString("github_token", "") ?: ""
        apiClient.updateConfig(savedGistId, savedToken)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showGitHubSettingsDialog()
        }

        adapter.onManageClick = { card ->
            showCardControlDialog(card)
        }

        initNfc()
        handleIntent(intent)
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvNfcStatus.text = "⚠️ 此设备不支持 NFC"
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            tvNfcStatus.text = "⚠️ NFC 未开启，请在系统设置中启用 NFC"
        } else {
            tvNfcStatus.text = "🟢 NFC 已就绪，随时可刷卡"
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        pollJob?.cancel()
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
        tvNfcStatus.text = "✨ 已刷卡! UID: $cardId"

        // Immediate offline card creation + background GitHub sync
        val card = apiClient.swipeCardImmediate(cardId)
        Toast.makeText(this@MainActivity, "刷卡成功: ${card.name}", Toast.LENGTH_SHORT).show()
        showCardControlDialog(card)
        triggerLocalRefresh()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                refreshCards()
                delay(1000) // Smooth 1-second refresh on mobile screen
            }
        }
    }

    private fun triggerLocalRefresh() {
        lifecycleScope.launch { refreshCards() }
    }

    private suspend fun refreshCards() {
        val cards = apiClient.getAllCards()
        adapter.setCards(cards)
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

    private fun showCardControlDialog(card: CardInfo) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card_control, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val tvUid = view.findViewById<TextView>(R.id.dialogCardUid)
        val etName = view.findViewById<EditText>(R.id.etCardName)
        val npHours = view.findViewById<NumberPicker>(R.id.npHours)
        val npMinutes = view.findViewById<NumberPicker>(R.id.npMinutes)
        val btnRename = view.findViewById<Button>(R.id.btnRename)
        val btnStart = view.findViewById<Button>(R.id.btnStart)
        val btnPause = view.findViewById<Button>(R.id.btnPause)
        val btnStop = view.findViewById<Button>(R.id.btnStop)

        tvUid.text = "UID: ${card.cardId}"
        etName.setText(card.name)

        // Setup Scrollable NumberPicker for Hours (0-23) and Minutes (0-59)
        npHours.minValue = 0
        npHours.maxValue = 23
        npMinutes.minValue = 0
        npMinutes.maxValue = 59

        val initialSec = card.targetDurationSeconds
        val initialH = initialSec / 3600
        val initialM = (initialSec % 3600) / 60
        npHours.value = Math.min(23, initialH)
        npMinutes.value = initialM

        btnRename.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                apiClient.renameCardImmediate(card.cardId, newName)
                Toast.makeText(this@MainActivity, "卡片已重命名为: $newName", Toast.LENGTH_SHORT).show()
                triggerLocalRefresh()
            }
        }

        btnStart.setOnClickListener {
            val hours = npHours.value
            val minutes = npMinutes.value
            val totalSeconds = (hours * 3600) + (minutes * 60)

            apiClient.setTimerImmediate(card.cardId, totalSeconds, "start")
            Toast.makeText(this@MainActivity, "倒计时已开始!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnPause.setOnClickListener {
            val action = if (card.status == 2) "resume" else "pause"
            apiClient.setTimerImmediate(card.cardId, 0, action)
            Toast.makeText(this@MainActivity, if (action == "resume") "已恢复" else "已暂停", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        btnStop.setOnClickListener {
            apiClient.setTimerImmediate(card.cardId, 0, "stop")
            Toast.makeText(this@MainActivity, "已停止计时", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            triggerLocalRefresh()
        }

        dialog.show()
    }

    // RecyclerView Adapter for smooth 1-second UI tick
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
            private val btnManage: Button = itemView.findViewById(R.id.btnCardAction)

            fun bind(card: CardInfo, onManageClick: ((CardInfo) -> Unit)?) {
                tvName.text = card.name
                tvUid.text = "UID: ${card.cardId}"

                if (card.isOverdue || card.status == 3) {
                    tvBadge.text = "⚠️ 超时"
                    tvBadge.setBackgroundColor(0x33EF4444.toInt())
                    tvBadge.setTextColor(0xFFF87171.toInt())
                    tvTime.text = "🚨 超时: ${formatTime(card.overdueSeconds)}"
                    tvTime.setTextColor(0xFFF87171.toInt())
                } else if (card.status == 1) {
                    tvBadge.text = "进行中"
                    tvBadge.setBackgroundColor(0x3310B981.toInt())
                    tvBadge.setTextColor(0xFF10B981.toInt())
                    tvTime.text = formatTime(card.remainingSeconds)
                    tvTime.setTextColor(0xFF10B981.toInt())
                } else if (card.status == 2) {
                    tvBadge.text = "已暂停"
                    tvBadge.setBackgroundColor(0x33F59E0B.toInt())
                    tvBadge.setTextColor(0xFFF59E0B.toInt())
                    tvTime.text = formatTime(card.remainingSeconds)
                    tvTime.setTextColor(0xFFF59E0B.toInt())
                } else {
                    tvBadge.text = "未开始"
                    tvBadge.setBackgroundColor(0x3364748B.toInt())
                    tvBadge.setTextColor(0xFF94A3B8.toInt())
                    tvTime.text = formatTime(card.remainingSeconds)
                    tvTime.setTextColor(0xFF94A3B8.toInt())
                }

                btnManage.setOnClickListener { onManageClick?.invoke(card) }
            }

            private fun formatTime(sec: Double): String {
                val totalSec = Math.abs(sec.toInt())
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                return if (h > 0) {
                    String.format("%02d:%02d:%02d", h, m, s)
                } else {
                    String.format("%02d:%02d", m, s)
                }
            }
        }
    }
}
