package com.nfctime.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nfctime.app.api.Announcement
import com.nfctime.app.api.GitHubApiClient

class AnnouncementActivity : AppCompatActivity() {

    private lateinit var apiClient: GitHubApiClient
    private lateinit var rvAnnouncements: RecyclerView
    private val adapter = AnnouncementAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_announcement)

        apiClient = GitHubApiClient(this)
        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
        val savedGistId = prefs.getString("gist_id", "") ?: ""
        val savedToken = prefs.getString("github_token", "") ?: ""
        apiClient.updateConfig(savedGistId, savedToken)

        rvAnnouncements = findViewById(R.id.rvAnnouncements)
        rvAnnouncements.layoutManager = LinearLayoutManager(this)
        rvAnnouncements.adapter = adapter

        findViewById<Button>(R.id.btnAddAnnouncement).setOnClickListener {
            showEditDialog(null)
        }

        adapter.onEditClick = { anno -> showEditDialog(anno) }
        adapter.onDeleteClick = { anno -> showDeleteDialog(anno) }

        refreshList()
    }

    private fun refreshList() {
        val list = apiClient.getCachedAnnouncements()
        adapter.setItems(list)
    }

    private fun showEditDialog(existing: Announcement?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etTitle = EditText(this).apply {
            hint = "公告标题"
            setText(existing?.title ?: "")
        }
        val etContent = EditText(this).apply {
            hint = "公告正文内容"
            setText(existing?.content ?: "")
            minLines = 3
        }
        val cbForce = CheckBox(this).apply {
            text = "🚨 强制提醒（打开网页后强制弹窗且需等3秒关闭）"
            isChecked = existing?.isForce ?: false
        }

        layout.addView(TextView(this).apply { text = "标题:" })
        layout.addView(etTitle)
        layout.addView(TextView(this).apply { text = "\n内容:" })
        layout.addView(etContent)
        layout.addView(cbForce)

        val dialogTitle = if (existing == null) "发布新公告" else "编辑公告"

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(layout)
            .setPositiveButton("保存并发布") { _, _ ->
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()
                val isForce = cbForce.isChecked

                if (title.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, "标题和内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val anno = existing?.copy(title = title, content = content, isForce = isForce)
                    ?: Announcement(title = title, content = content, isForce = isForce)

                apiClient.addOrUpdateAnnouncement(anno)
                Toast.makeText(this, "公告已更新并上传云端", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(anno: Announcement) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除公告「${anno.title}」吗？")
            .setPositiveButton("删除") { _, _ ->
                apiClient.deleteAnnouncement(anno.id)
                Toast.makeText(this, "公告已删除", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class AnnouncementAdapter : RecyclerView.Adapter<AnnouncementAdapter.AnnoViewHolder>() {
        private var items: List<Announcement> = emptyList()
        var onEditClick: ((Announcement) -> Unit)? = null
        var onDeleteClick: ((Announcement) -> Unit)? = null

        fun setItems(newItems: List<Announcement>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnnoViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_announcement, parent, false)
            return AnnoViewHolder(view)
        }

        override fun onBindViewHolder(holder: AnnoViewHolder, position: Int) {
            val anno = items[position]
            holder.bind(anno, onEditClick, onDeleteClick)
        }

        override fun getItemCount(): Int = items.size

        class AnnoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tvAnnoTitle)
            private val tvContent: TextView = itemView.findViewById(R.id.tvAnnoContent)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvAnnoForceBadge)
            private val tvTime: TextView = itemView.findViewById(R.id.tvAnnoTime)
            private val btnEdit: Button = itemView.findViewById(R.id.btnEditAnno)
            private val btnDelete: Button = itemView.findViewById(R.id.btnDeleteAnno)

            fun bind(anno: Announcement, onEdit: ((Announcement) -> Unit)?, onDelete: ((Announcement) -> Unit)?) {
                tvTitle.text = anno.title
                tvContent.text = anno.content
                tvTime.text = anno.publishTimeUtc.replace("T", " ").replace("Z", "")

                if (anno.isForce) {
                    tvBadge.visibility = View.VISIBLE
                } else {
                    tvBadge.visibility = View.GONE
                }

                btnEdit.setOnClickListener { onEdit?.invoke(anno) }
                btnDelete.setOnClickListener { onDelete?.invoke(anno) }
            }
        }
    }
}
