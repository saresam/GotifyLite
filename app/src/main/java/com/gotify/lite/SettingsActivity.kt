package com.gotify.lite

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 设置页：把原先分散在菜单里的「服务器设置」「省电设置」「外观模式」合并成一页。
 *
 * 四张卡片：连接 → 外观 → 省电与保活 → 关于。改动即时生效：
 * 外观选择立刻重建界面，服务器保存后立刻重连。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var serverEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var btnTest: Button
    private lateinit var resultText: TextView
    private lateinit var themeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = getString(R.string.menu_settings)

        serverEdit = findViewById(R.id.server_url)
        tokenEdit = findViewById(R.id.token)
        btnTest = findViewById(R.id.btn_test)
        resultText = findViewById(R.id.test_result)
        themeGroup = findViewById(R.id.theme_group)

        serverEdit.setText(prefs.serverUrl)
        tokenEdit.setText(prefs.token)

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        btnTest.setOnClickListener { test() }
        setupThemeChooser()
        setupBatteryButtons()
        setupAbout()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- 外观 ----------

    private fun setupThemeChooser() {
        val checkedId = when (prefs.themeMode) {
            ThemeMode.LIGHT -> R.id.theme_light
            ThemeMode.DARK -> R.id.theme_dark
            else -> R.id.theme_system
        }
        themeGroup.check(checkedId)
        themeGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.theme_light -> ThemeMode.LIGHT
                R.id.theme_dark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            if (mode != prefs.themeMode) {
                prefs.themeMode = mode
                // 立即重建本页；返回首页时首页也会检测到变化并重建
                applyThemeChange()
            }
        }
    }

    // ---------- 连接 ----------

    private fun save() {
        val url = serverEdit.text.toString().trim()
        val token = tokenEdit.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            toast("请填写服务器地址和客户端令牌")
            return
        }
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
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

        val hint = NotifPermission.statusHint(Build.VERSION.SDK_INT, notificationsEnabled())
        val insecure = TransportSecurity.warning(url)
        showResult(
            buildString {
                append("已保存并开始连接，返回首页可查看状态")
                if (hint != null) append("\n但$hint")
                if (insecure != null) append("\n$insecure")
            }
        )
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
            val insecure = TransportSecurity.warning(url)
            runOnUiThread {
                btnTest.isEnabled = true
                btnTest.text = getString(R.string.btn_test)
                showResult(if (insecure == null) result else "$result\n$insecure")
            }
        }.start()
    }

    private fun showResult(text: String) {
        resultText.text = text
        resultText.visibility = TextView.VISIBLE
    }

    // ---------- 省电与保活 ----------

    private fun setupBatteryButtons() {
        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                "无法打开通知设置"
            )
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            // 不直接用 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（免检白名单入口，商店政策受限）
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "无法打开电池优化设置")
        }
        val appDetails = {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        }
        // 厂商自启动页没有标准 API，统一落到应用详情页
        findViewById<Button>(R.id.btn_autostart).setOnClickListener {
            open(appDetails(), "无法打开应用详情")
        }
        findViewById<Button>(R.id.btn_app_details).setOnClickListener {
            open(appDetails(), "无法打开应用详情")
        }
    }

    private fun open(intent: Intent, failureHint: String) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, failureHint, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 关于 ----------

    private fun setupAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.app_name) + "  v" + version
    }

    // ---------- 工具 ----------

    private fun notificationsEnabled(): Boolean {
        if (NotifPermission.needsRuntimePermission(Build.VERSION.SDK_INT) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(serverEdit.windowToken, 0)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
