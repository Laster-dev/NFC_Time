package com.nfctime.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.launch

class UnusedCardsActivity : AppCompatActivity() {

    private lateinit var apiClient: GitHubApiClient
    private lateinit var rvUnusedCards: RecyclerView
    private lateinit var tvUnusedCount: TextView
    private val adapter = UnusedCardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unused_cards)

        apiClient = GitHubApiClient(this)
        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
        val savedGistId = prefs.getString("gist_id", "") ?: ""
        val savedToken = prefs.getString("github_token", "") ?: ""
        apiClient.updateConfig(savedGistId, savedToken)

        tvUnusedCount = findViewById(R.id.tvUnusedCount)
        rvUnusedCards = findViewById(R.id.rvUnusedCards)
        rvUnusedCards.layoutManager = LinearLayoutManager(this)
        rvUnusedCards.adapter = adapter

        adapter.onEditClick = { card -> showEditUnusedCardDialog(card) }

        refreshList()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val allCards = apiClient.getAllCards()
            val unusedList = allCards.filter { it.status == 0 }
            tvUnusedCount.text = "${unusedList.size} 张"
            adapter.setCards(unusedList)
        }
    }

    private fun showEditUnusedCardDialog(card: CardInfo) {
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
        val btnDeleteCard = view.findViewById<Button>(R.id.btnDeleteCard)

        tvUid.text = "UID: ${card.cardId}"
        etName.setText(card.name)

        btnPause.visibility = View.GONE
        btnStop.visibility = View.GONE
        btnDeleteCard.visibility = View.GONE

        npHours.minValue = 0
        npHours.maxValue = 23
        npMinutes.minValue = 0
        npMinutes.maxValue = 59

        val initialSec = card.targetDurationSeconds
        npHours.value = Math.min(23, initialSec / 3600)
        npMinutes.value = (initialSec % 3600) / 60

        btnRename.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                apiClient.renameCardImmediate(card.cardId, newName)
                Toast.makeText(this, "卡片重命名为: $newName", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }

        btnStart.setOnClickListener {
            val hours = npHours.value
            val minutes = npMinutes.value
            val totalSeconds = (hours * 3600) + (minutes * 60)

            apiClient.setTimerImmediate(card.cardId, totalSeconds, "start")
            Toast.makeText(this, "倒计时已开启!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            refreshList()
        }

        dialog.show()
    }

    class UnusedCardAdapter : RecyclerView.Adapter<UnusedCardAdapter.ViewHolder>() {
        private var cards: List<CardInfo> = emptyList()
        var onEditClick: ((CardInfo) -> Unit)? = null

        fun setCards(newCards: List<CardInfo>) {
            cards = newCards
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(cards[position], onEditClick)
        }

        override fun getItemCount(): Int = cards.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvCardName)
            private val tvUid: TextView = itemView.findViewById(R.id.tvCardUid)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTimeDisplay)
            private val tvAvatarChar: TextView = itemView.findViewById(R.id.tvAvatarChar)

            fun bind(card: CardInfo, onEditClick: ((CardInfo) -> Unit)?) {
                tvName.text = card.name
                tvUid.text = if (card.remark.isNotEmpty()) "UID: ${card.cardId} | 📝 ${card.remark}" else "UID: ${card.cardId}"
                tvAvatarChar.text = if (card.name.isNotEmpty()) card.name.take(1) else "卡"
                tvBadge.text = "未使用"
                tvBadge.setTextColor(0xFF7F91A4.toInt())

                tvTime.text = "未开始"
                tvTime.setTextColor(0xFF7F91A4.toInt())

                itemView.setOnClickListener { onEditClick?.invoke(card) }
            }
        }
    }
}
