package com.gotify.lite

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 服务器设置：地址 + 客户端令牌，保存即连接，可测试连通性。 */
class ServerSettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var serverEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var btnTest: Button
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_settings)
        prefs = Prefs(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = getString(R.string.menu_server)

        serverEdit = findViewById(R.id.server_url)
        tokenEdit = findViewById(R.id.token)
        val btnSave = findViewById<Button>(R.id.btn_save)
        btnTest = findViewById(R.id.btn_test)
        resultText = findViewById(R.id.test_result)

        serverEdit.setText(prefs.serverUrl)
        tokenEdit.setText(prefs.token)

        btnSave.setOnClickListener { save() }
        btnTest.setOnClickListener { test() }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save() {
        val url = serverEdit.text.toString().trim()
        val token = tokenEdit.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            toast("请填写服务器地址和客户端令牌")
            return
        }
        val u = url.toHttpUrlOrNull()
        if (u == null || (u.scheme != "http" && u.scheme != "https")) {
            toast("服务器地址格式不正确，示例：https://push.example.com")
            return
        }
        // 换了服务器或令牌：本地历史与「已收到最大 id」都属于旧源，必须丢掉重来
        val changed = prefs.configKey != (url + "\u0000" + token)
        prefs.serverUrl = url
        prefs.token = token
        if (changed) {
            prefs.lastMsgId = -1L
            MessageStore.deleted = true // 让首页下次恢复时重载，而不是继续显示旧服务器的消息
        }
        prefs.enabled = true
        PushService.start(this)
        resultText.text = "已保存并开始连接，返回首页可查看状态"
        hideKeyboard()
    }

    private fun test() {
        val url = serverEdit.text.toString().trim()
        val token = tokenEdit.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            toast("请先填写服务器地址和令牌")
            return
        }
        btnTest.isEnabled = false
        btnTest.text = "测试中…"
        Thread {
            val result = try {
                val version = GotifyApi.serverVersion(url)
                val apps = GotifyApi.applications(url, token)
                val names = (0 until apps.length()).map { apps.getJSONObject(it).optString("name") }
                if (version != null) {
                    "连接成功：服务器 v$version，令牌有效，共 ${names.size} 个应用" +
                        if (names.isNotEmpty()) "：${names.joinToString("、")}" else ""
                } else {
                    "连接成功，令牌有效，共 ${names.size} 个应用"
                }
            } catch (e: ApiException) {
                when {
                    e.code == 401 || e.code == 403 -> "令牌无效（HTTP ${e.code}），请检查令牌"
                    e.code == 404 -> "地址似乎不对（HTTP 404），请确认服务器地址"
                    else -> "连接失败：${e.message}"
                }
            } catch (e: Exception) {
                "连接失败：${e.message}"
            }
            runOnUiThread {
                btnTest.isEnabled = true
                btnTest.text = "测试连接"
                resultText.text = result
            }
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(serverEdit.windowToken, 0)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
