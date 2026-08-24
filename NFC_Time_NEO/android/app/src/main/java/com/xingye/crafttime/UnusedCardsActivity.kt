package com.xingye.crafttime

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xingye.crafttime.api.BackendApiClient
import com.xingye.crafttime.api.CardInfo
import kotlinx.coroutines.launch

class UnusedCardsActivity : AppCompatActivity() {

    private lateinit var apiClient: BackendApiClient
    private lateinit var rvUnusedCards: RecyclerView
    private lateinit var tvUnusedCount: TextView
    private val adapter = UnusedCardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unused_cards)

        apiClient = BackendApiClient(this)

        tvUnusedCount = findViewById(R.id.tvUnusedCount)
        rvUnusedCards = findViewById(R.id.rvUnusedCards)
        rvUnusedCards.layoutManager = LinearLayoutManager(this)
        rvUnusedCards.adapter = adapter

        adapter.onEditClick = { card -> showEditUnusedCardDialog(card) }

        refreshList()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val (unusedList, isOnline) = apiClient.fetchUnusedCards()
            tvUnusedCount.text = "${unusedList.size} 张"
            adapter.setCards(unusedList)
        }
    }

    private fun showEditUnusedCardDialog(card: CardInfo) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 20)
        }

        val tvUid = TextView(this).apply {
            text = "UID: ${card.cardId}"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 13f
        }
        val etName = EditText(this).apply {
            setText(card.name)
            hint = "卡片名称"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.edit_text_bg)
            setPadding(30, 20, 30, 20)
        }

        layout.addView(tvUid)
        layout.addView(etName)

        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ 管理未使用卡片")
            .setView(layout)
            .setPositiveButton("保存名称") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        val (updated, synced) = apiClient.updateCardConfig(card.cardId, name = newName)
                        if (synced) {
                            Toast.makeText(this@UnusedCardsActivity, "✅ 名称已更新并同步至服务器: $newName", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@UnusedCardsActivity, "⚠️ 名称已本地更新 (未连接服务器)", Toast.LENGTH_SHORT).show()
                        }
                        refreshList()
                    }
                }
            }
            .setNeutralButton("🗑️ 彻底删除卡片") { _, _ ->
                confirmDeleteCard(card)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
    }

    private fun confirmDeleteCard(card: CardInfo) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ 确认彻底删除卡片")
            .setMessage("确定要从数据库中彻底删除卡片「${card.name}」(UID: ${card.cardId}) 吗？\n删除后不可恢复。")
            .setPositiveButton("确认删除") { _, _ ->
                lifecycleScope.launch {
                    val synced = apiClient.deleteCard(card.cardId)
                    if (synced) {
                        Toast.makeText(this@UnusedCardsActivity, "✅ 卡片已彻底从服务器删除！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@UnusedCardsActivity, "⚠️ 已从本地缓存删除 (未连接服务器)", Toast.LENGTH_SHORT).show()
                    }
                    refreshList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            private val tvTagDouban: TextView = itemView.findViewById(R.id.tvTagDouban)
            private val tvTagPostPay: TextView = itemView.findViewById(R.id.tvTagPostPay)
            private val tvTagPreset: TextView = itemView.findViewById(R.id.tvTagPreset)
            private val tvRemarkBadge: TextView = itemView.findViewById(R.id.tvRemarkBadge)
            private val tvCardPriceEstimate: TextView = itemView.findViewById(R.id.tvCardPriceEstimate)

            fun bind(card: CardInfo, onEditClick: ((CardInfo) -> Unit)?) {
                tvName.text = card.name
                tvAvatarChar.text = if (card.name.isNotEmpty()) card.name.take(1) else "卡"
                tvUid.text = "UID: ${card.cardId}"

                tvTagDouban.visibility = View.GONE
                tvTagPostPay.visibility = View.GONE
                tvTagPreset.visibility = View.GONE
                tvRemarkBadge.visibility = View.GONE
                tvCardPriceEstimate.visibility = View.GONE

                tvBadge.text = "未使用"
                tvBadge.setTextColor(0xFF94A3B8.toInt())

                tvTime.text = "空闲待用"
                tvTime.setTextColor(0xFF94A3B8.toInt())

                itemView.setOnClickListener { onEditClick?.invoke(card) }
            }
        }
    }
}
