package com.gotify.lite

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** 消息详情：完整标题、时间、优先级、正文全文，可复制 / 打开附带链接。 */
class MessageDetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_detail)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = getString(R.string.detail_title)

        val id = intent.getLongExtra("message_id", -1L)
        val m = parseIntentMessage() ?: if (id > 0) MessageStore(this).getById(id) else null

        val titleView = findViewById<TextView>(R.id.detail_title)
        val metaView = findViewById<TextView>(R.id.detail_meta)
        val bodyView = findViewById<TextView>(R.id.detail_body)
        val headCard = findViewById<View>(R.id.detail_head_card)
        val bodyCard = findViewById<View>(R.id.detail_body_card)
        val actions = findViewById<View>(R.id.detail_actions)
        val missing = findViewById<View>(R.id.detail_missing)
        val btnCopy = findViewById<Button>(R.id.btn_copy)
        val btnDelete = findViewById<Button>(R.id.btn_delete)
        val btnLink = findViewById<Button>(R.id.btn_open_link)

        if (m == null) {
            headCard.visibility = View.GONE
            bodyCard.visibility = View.GONE
            actions.visibility = View.GONE
            missing.visibility = View.VISIBLE
            return
        }

        titleView.text = m.title.ifBlank { "（无标题）" }
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        metaView.text =
            "${m.appName ?: "应用 #${m.appId}"} · ${fmt.format(Date(m.dateMillis))}"
        findViewById<View>(R.id.detail_priority_dot).backgroundTintList =
            ColorStateList.valueOf(Notifier.priorityColor(m.priority))
        // 只有标题的消息不显示空正文卡片
        if (m.body.isBlank()) {
            bodyCard.visibility = View.GONE
        } else {
            bodyView.text = m.body
        }

        btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("gotify", m.body.ifBlank { m.title }))
            Toast.makeText(this, "已复制消息内容", Toast.LENGTH_SHORT).show()
        }

        btnDelete.setOnClickListener { confirmDelete(m) }

        val url = m.clickUrl
        if (url != null) {
            btnLink.visibility = View.VISIBLE
            btnLink.setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
    }

    /** 优先使用列表页直接传入的消息 JSON；通知点击等入口回退到本地库。 */
    private fun parseIntentMessage(): GotifyMessage? {
        val json = intent.getStringExtra("message_json") ?: return null
        return runCatching {
            val o = JSONObject(json)
            GotifyMessage(
                id = o.optLong("id"),
                appId = o.optLong("appid"),
                title = o.optString("title"),
                body = o.optString("message"),
                priority = o.optInt("priority", 5),
                dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                extras = if (o.has("extras") && !o.isNull("extras")) {
                    o.getJSONObject("extras")
                } else {
                    null
                },
                appName = if (o.isNull("appName")) null else o.optString("appName")
            )
        }.getOrNull()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 删除确认：不可恢复，需用户二次确认。 */
    private fun confirmDelete(m: GotifyMessage) {
        AlertDialog.Builder(this)
            .setTitle("删除消息")
            .setMessage("将从服务器删除该消息，且无法恢复。确定删除吗？")
            .setPositiveButton("删除") { _, _ -> doDelete(m) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除服务器消息 + 本地记录 + 通知，完成后返回首页（列表自动刷新）。 */
    private fun doDelete(m: GotifyMessage) {
        val prefs = Prefs(this)
        Thread {
            try {
                GotifyApi.deleteMessage(prefs.serverUrl, prefs.token, m.id)
                runOnUiThread {
                    MessageStore(this).delete(m.id)
                    Notifier.cancelMessage(this, m.id)
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
