package com.gotify.lite

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 首页：连接状态条 + 全屏消息列表。
 * - 点击消息进入详情页；长按复制
 * - 右上角菜单：刷新消息 / 服务器设置 / 省电设置 / 开始推送 / 停止推送
 * - 收到新消息时列表自动更新（无需手动刷新）
 */
class MainActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var historyList: ListView
    private lateinit var historyEmpty: View
    private lateinit var historyEmptyText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var filterSpinner: Spinner
    private lateinit var pagerBar: View
    private lateinit var pageInfo: TextView
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private var lastUpdateMs = 0L
    private var appList: List<Pair<Long, String>> = emptyList()
    private var filterAppId = -1L
    private var barWidthPx = 0
    private var barLeftPx = 0
    private var barInsetPx = 0
    private var cardRadiusPx = 0
    private var chipRadiusPx = 0
    private var strokePx = 0
    private var normalCardColor = 0
    private var pressedCardColor = 0

    /** 列表时间戳格式化器（只在 UI 线程用，缓存一份避免 getView 里反复 new）。 */
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    private val handler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private var history: List<GotifyMessage> = emptyList()
    private var lastStoreVersion = -1
    private var historyLoaded = false
    /** 后台查库的重入保护：catch-up 一次写入上百条时，避免每个 tick 都排队查库。 */
    private var storeReloading = false
    /** 当前界面所使用的外观模式；与 prefs 不一致说明在设置页改过，需要重建。 */
    private var appliedThemeMode = -1

    // 分页：每页 30 条，已加载的页缓存在 pageCache；向后翻页用 since 增量拉取
    private val pageSize = 30
    private val pageCache = ArrayList<List<GotifyMessage>>()
    private var currentPage = 0
    private var hasMore = false
    private var loadingPage = false
    /** 已知总页数（-1 未知）；用于页码选择器。换服务器/筛选/刷新后需重置。 */
    private var totalPagesHint = -1
    private var totalPagesKey = ""
    /** 页码选择器打开时统计总数用的请求条数（也是本地库保存上限）。 */
    private val countLimit = 500
    /** 上次加载数据时对应的服务器+令牌；变化则丢弃旧列表，避免显示上一台服务器的消息。 */
    private var loadedConfigKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        appliedThemeMode = prefs.themeMode

        statusText = findViewById(R.id.status_text)
        statusDot = findViewById(R.id.status_dot)
        historyList = findViewById(R.id.history_list)
        historyEmpty = findViewById(R.id.history_empty)
        historyEmptyText = findViewById(R.id.history_empty_text)
        lastUpdateText = findViewById(R.id.last_update)
        filterSpinner = findViewById(R.id.app_filter)
        pagerBar = findViewById(R.id.pager_bar)
        pageInfo = findViewById(R.id.page_info)
        btnPrevPage = findViewById(R.id.btn_prev_page)
        btnNextPage = findViewById(R.id.btn_next_page)
        btnPrevPage.setOnClickListener { prevPage() }
        btnNextPage.setOnClickListener { nextPage() }
        pageInfo.setOnClickListener { showPagePicker() }
        // 标题栏副标题显示版本号，便于确认安装版本
        actionBar?.subtitle = "v" + runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        val density = resources.displayMetrics.density
        barWidthPx = (3 * density).toInt().coerceAtLeast(2)
        barLeftPx = (13 * density).toInt()
        barInsetPx = (11 * density).toInt()
        cardRadiusPx = (14 * density).toInt()
        chipRadiusPx = (6 * density).toInt()
        strokePx = (density).toInt().coerceAtLeast(1)
        normalCardColor = getColor(R.color.card)
        pressedCardColor = getColor(R.color.card_pressed)

        historyList.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, pos, _ ->
                val idx = pos - historyList.headerViewsCount
                if (idx in history.indices) {
                    val m = history[idx]
                    startActivity(
                        Intent(this, MessageDetailActivity::class.java)
                            .putExtra("message_id", m.id)
                            .putExtra("message_json", messageToJson(m))
                    )
                }
            }
        historyList.onItemLongClickListener =
            AdapterView.OnItemLongClickListener { _, _, pos, _ ->
                val idx = pos - historyList.headerViewsCount
                if (idx in history.indices) {
                    copyToClipboard(history[idx].body.ifBlank { history[idx].title })
                    toast("已复制消息内容")
                }
                true
            }

        // 应用筛选：先读持久化的选择 + 本地兜底列表（后台查），再异步刷新服务器应用列表
        filterAppId = prefs.filterAppId
        setupFilter()
        loadLocalApps()
        loadApps()

        // 通知权限：Android 13+ 是运行时权限，不申请就完全收不到通知（系统默认静默拒绝）
        statusText.setOnClickListener { openNotificationSettings() }
        ensureNotificationPermission()

        handleMessageIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 在设置页改过外观模式：重建首页让新配色生效
        if (appliedThemeMode != prefs.themeMode) {
            appliedThemeMode = prefs.themeMode
            applyThemeChange()
            return
        }
        refreshStatus()
        // 从「服务器设置」返回时地址或令牌可能已变：已加载的旧列表属于旧服务器，必须重载
        val cfg = prefs.configKey
        if (cfg != loadedConfigKey) {
            loadedConfigKey = cfg
            historyLoaded = false
            pageCache.clear()
            currentPage = 0
            hasMore = false
            appList = emptyList()
            setupFilter()
            loadLocalApps()
            loadApps()
        }
        lastStoreVersion = MessageStore.version
        handler.post(statusTicker)
        // 详情页删了消息：重载数据库并回到第 1 页（否则当前页可能残留已删条目）
        if (MessageStore.deleted) {
            MessageStore.deleted = false
            reloadFirstPage()
        } else if (!historyLoaded) {
            reloadFirstPage()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMessageIntent(intent)
    }

    // ---------- 通知权限 ----------

    /** 系统层面（运行时权限）与应用内开关（系统设置里关掉通知）都要检查。 */
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

    /** 首次进入时弹一次系统申请框；用户拒绝过就只在状态条上提示，点状态条去系统设置。 */
    private fun ensureNotificationPermission() {
        if (NotifPermission.shouldPrompt(
                Build.VERSION.SDK_INT, notificationsEnabled(), prefs.notifPermissionAsked
            )
        ) {
            prefs.notifPermissionAsked = true
            runCatching {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NotifPermission.REQ_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NotifPermission.REQ_CODE) {
            if (!notificationsEnabled()) {
                toast("未获得通知权限，收不到新消息通知；点状态条可去系统设置开启")
            }
            refreshStatus()
        }
    }

    /** 跳到本应用的通知设置页，让用户自己打开（系统设置里还能看到“已拒绝”记录）。 */
    private fun openNotificationSettings() {
        if (notificationsEnabled()) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    /** 通知点击带 message_id 时，直达消息详情。 */
    private fun handleMessageIntent(intent: Intent?) {
        val id = intent?.getLongExtra("message_id", -1L) ?: -1L
        if (id > 0) {
            intent?.removeExtra("message_id")
            startActivity(
                Intent(this, MessageDetailActivity::class.java).putExtra("message_id", id)
            )
        }
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_start).isVisible = !PushService.running.get()
        menu.findItem(R.id.menu_stop).isVisible = PushService.running.get()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> {
                toast("正在刷新…")
                reloadFirstPage()
            }
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_start -> {
                if (!prefs.isConfigured) {
                    toast("请先在「服务器设置」里填写地址和令牌")
                } else {
                    prefs.enabled = true
                    PushService.start(this)
                    toast("已开启推送")
                }
            }
            R.id.menu_stop -> {
                prefs.enabled = false
                PushService.stop(this)
                toast("已停止推送")
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- 应用筛选 ----------

    /** 构建筛选下拉框：全部消息 + 应用列表；保持当前选择。 */
    private fun setupFilter() {
        val names = ArrayList<String>()
        names.add(getString(R.string.filter_all))
        appList.forEach { names.add(it.second) }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, names)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        filterSpinner.adapter = adapter
        // 弹窗宽度用布局里的 android:dropDownWidth 声明式固定（原先靠反射 AbsSpinner.mPopup
        // 调 setWidth，属于非 SDK 接口，随时可能在高版本上失效）
        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val newId = if (position == 0) {
                    -1L
                } else {
                    appList.getOrNull(position - 1)?.first ?: -1L
                }
                // 只有“筛选条件真的变了”才重载：重建 adapter 与 setSelection 都会回调到这里，
                // 但解析出的 appId 与当前一致，按值比较即可跳过——不再需要易卡住的初始化标记
                if (newId == filterAppId) return
                filterAppId = newId
                prefs.filterAppId = newId
                reloadFirstPage()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val pos = appList.indexOfFirst { it.first == filterAppId }
        filterSpinner.setSelection(if (pos >= 0) pos + 1 else 0)
    }

    /** 本地库里出现过的应用（离线兜底）：后台查，避免 GROUP BY 在主线程跑。 */
    private fun loadLocalApps() {
        Thread {
            val list = MessageStore.get(this).apps()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                appList = list
                setupFilter()
            }
        }.start()
    }

    /** 从服务器拉取应用列表；失败时保留本地兜底列表，不提示。 */
    private fun loadApps() {
        if (!prefs.isConfigured) return
        Thread {
            try {
                val apps = GotifyApi.applications(prefs.serverUrl, prefs.token)
                val list = (0 until apps.length()).map {
                    val o = apps.getJSONObject(it)
                    o.optLong("id") to o.optString("name")
                }
                runOnUiThread {
                    appList = list
                    setupFilter()
                }
            } catch (e: Exception) {
                // 网络不可用：保留本地完底列表即可
            }
        }.start()
    }

    // ---------- 数据 ----------

    /** 拉取第一页（最新 30 条）：首次进入 / 手动刷新 / 切换筛选。 */
    private fun reloadFirstPage() {
        totalPagesHint = -1
        totalPagesKey = ""
        if (!prefs.isConfigured) {
            pageCache.clear()
            currentPage = 0
            hasMore = false
            historyList.visibility = View.GONE
            pagerBar.visibility = View.GONE
            historyEmpty.visibility = View.VISIBLE
            historyEmptyText.text = getString(R.string.empty_not_configured)
            return
        }
        loadingPage = true
        updatePager()
        val appId = if (filterAppId > 0) filterAppId else null
        Thread {
            try {
                val msgs =
                    fillAppNames(GotifyApi.messages(prefs.serverUrl, prefs.token, pageSize, appId))
                syncToStore(msgs)
                runOnUiThread {
                    loadingPage = false
                    historyLoaded = true
                    lastUpdateMs = System.currentTimeMillis()
                    pageCache.clear()
                    pageCache.add(msgs)
                    currentPage = 0
                    hasMore = msgs.size >= pageSize
                    lastStoreVersion = MessageStore.version
                    showCurrentPage()
                }
            } catch (e: Exception) {
                val local = MessageStore.get(this).latest(pageSize, appId)
                runOnUiThread {
                    loadingPage = false
                    historyLoaded = true
                    pageCache.clear()
                    pageCache.add(local)
                    currentPage = 0
                    hasMore = local.size >= pageSize
                    lastStoreVersion = MessageStore.version
                    showCurrentPage()
                    toast("刷新失败，已显示本地缓存")
                }
            }
        }.start()
    }

    /** 向后翻页：以已加载最后一页的最后一条 id 为 since，拉取更旧的一页。 */
    private fun loadNextPage() {
        if (loadingPage || !hasMore) return
        val since = pageCache.lastOrNull()?.lastOrNull()?.id ?: return
        loadingPage = true
        updatePager()
        val appId = if (filterAppId > 0) filterAppId else null
        Thread {
            try {
                val msgs =
                    fillAppNames(
                        GotifyApi.messages(prefs.serverUrl, prefs.token, pageSize, appId, since)
                    )
                syncToStore(msgs)
                runOnUiThread {
                    loadingPage = false
                    if (msgs.isNotEmpty()) {
                        pageCache.add(msgs)
                        currentPage = pageCache.size - 1
                        hasMore = msgs.size >= pageSize
                        lastStoreVersion = MessageStore.version
                        showCurrentPage()
                    } else {
                        hasMore = false
                        updatePager()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingPage = false
                    updatePager()
                    toast("加载失败，请检查网络")
                }
            }
        }.start()
    }

    /** 历史消息同步入库：详情页可查、离线可看、新消息到达时列表不丢历史。 */
    private fun syncToStore(msgs: List<GotifyMessage>) {
        if (msgs.isEmpty()) return
        val nameOf: (Long) -> String? = { id -> appList.firstOrNull { it.first == id }?.second }
        MessageStore.get(this).insertAll(msgs, nameOf)
    }

    /** 服务器返回的消息补齐应用名（来自应用列表映射，取不到时保持 null）。 */
    private fun fillAppNames(msgs: List<GotifyMessage>): List<GotifyMessage> =
        msgs.map { m ->
            if (m.appName != null) {
                m
            } else {
                m.copy(appName = appList.firstOrNull { it.first == m.appId }?.second)
            }
        }

    private fun prevPage() {
        if (currentPage > 0) {
            currentPage--
            showCurrentPage()
        }
    }

    private fun nextPage() {
        if (currentPage < pageCache.size - 1) {
            currentPage++
            showCurrentPage()
        } else if (hasMore) {
            loadNextPage()
        }
    }

    // ---------- 页码选择器 ----------

    /** 点「第 N 页」弹出页码列表；首次打开先统计总条数（结果按配置+筛选缓存）。 */
    @Suppress("DEPRECATION") // ProgressDialog 适合这种一次性的阻塞等待，不为此引入 androidx
    private fun showPagePicker() {
        if (pageCache.isEmpty()) return
        if (loadingPage) {
            toast("正在加载，请稍候")
            return
        }
        val key = prefs.configKey + "|" + filterAppId
        if (totalPagesHint > 0 && totalPagesKey == key) {
            showPageList(maxOf(totalPagesHint, pageCache.size))
            return
        }
        if (!prefs.isConfigured) {
            showPageList(pageCache.size)
            return
        }

        val progress = android.app.ProgressDialog(this)
        progress.setMessage("正在统计页数…")
        progress.setCancelable(true)
        progress.show()

        val appId = if (filterAppId > 0) filterAppId else null
        Thread {
            val count = runCatching {
                GotifyApi.messages(prefs.serverUrl, prefs.token, countLimit, appId).size
            }.getOrDefault(-1)
            runOnUiThread {
                runCatching { progress.dismiss() }
                val pages = when {
                    // 统计失败：只列已加载的页，至少能回看已有内容
                    count < 0 -> pageCache.size
                    // 命中上限：无法确认还有多少，额外留一页供继续加载
                    count >= countLimit -> countLimit / pageSize + 1
                    else -> (count + pageSize - 1) / pageSize
                }.coerceAtLeast(1)
                totalPagesHint = pages
                totalPagesKey = key
                showPageList(maxOf(pages, pageCache.size))
            }
        }.start()
    }

    private fun showPageList(pages: Int) {
        val n = pages.coerceAtLeast(1)
        val items = Array(n) { "第 ${it + 1} 页" }
        android.app.AlertDialog.Builder(this)
            .setTitle("跳转到页面（共 $n 页）")
            .setItems(items) { _, which -> jumpToPage(which + 1) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 跳到指定页：已加载的直接切；未加载的按 since 链式补拉到该页。 */
    private fun jumpToPage(page: Int) {
        val idx = (page - 1).coerceAtLeast(0)
        if (idx < pageCache.size) {
            currentPage = idx
            showCurrentPage()
            return
        }
        loadPagesUntil(idx)
    }

    /**
     * 顺序补页直到第 targetIdx 页（0 基）可用。后台线程只往局部列表里追加，
     * 全部完成后再整体交给 UI，避免后台线程改动 pageCache 与适配器竞争。
     */
    private fun loadPagesUntil(targetIdx: Int) {
        if (loadingPage) return
        val since = pageCache.lastOrNull()?.lastOrNull()?.id ?: return
        // 提前快照已加载页数，后台线程不再读 UI 线程维护的 pageCache
        val baseCount = pageCache.size
        loadingPage = true
        updatePager()
        val appId = if (filterAppId > 0) filterAppId else null
        Thread {
            val added = ArrayList<List<GotifyMessage>>()
            var cursor = since
            var more = true
            try {
                // 一次最多补 20 页（600 条），避免极端情况下长时间阻塞
                while (baseCount + added.size <= targetIdx && more && added.size < 20) {
                    val msgs = fillAppNames(
                        GotifyApi.messages(prefs.serverUrl, prefs.token, pageSize, appId, cursor)
                    )
                    if (msgs.isEmpty()) {
                        more = false
                        break
                    }
                    syncToStore(msgs)
                    added.add(msgs)
                    cursor = msgs.last().id
                    more = msgs.size >= pageSize
                }
                runOnUiThread {
                    loadingPage = false
                    if (added.isNotEmpty()) {
                        pageCache.addAll(added)
                        hasMore = more
                        currentPage = minOf(targetIdx, pageCache.size - 1)
                        lastStoreVersion = MessageStore.version
                        showCurrentPage()
                        if (currentPage < targetIdx) toast("已到最后一页")
                    } else {
                        hasMore = false
                        currentPage = pageCache.size - 1
                        showCurrentPage()
                        toast("已到最后一页")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingPage = false
                    updatePager()
                    toast("加载失败，请检查网络")
                }
            }
        }.start()
    }

    /** 新消息到达（本地库版本变化）：后台查库后更新第一页缓存；停留第一页时无感刷新。 */
    private fun reloadFromStore() {
        if (storeReloading) return
        storeReloading = true
        val appId = if (filterAppId > 0) filterAppId else null
        Thread {
            val first = MessageStore.get(this).latest(pageSize, appId)
            runOnUiThread {
                storeReloading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (pageCache.isEmpty()) {
                    pageCache.add(first)
                    currentPage = 0
                } else {
                    pageCache[0] = first
                }
                if (currentPage == 0) showCurrentPage()
            }
        }.start()
    }

    private fun showCurrentPage() {
        if (pageCache.isEmpty()) {
            pageCache.add(emptyList())
            currentPage = 0
        }
        showHistory(pageCache.getOrElse(currentPage) { emptyList() })
    }

    private fun showHistory(msgs: List<GotifyMessage>) {
        history = msgs
        updatePager()
        if (history.isEmpty()) {
            historyList.visibility = View.GONE
            pagerBar.visibility = View.GONE
            historyEmpty.visibility = View.VISIBLE
            historyEmptyText.text = getString(R.string.empty_no_msg)
            return
        }
        historyEmpty.visibility = View.GONE
        historyList.visibility = View.VISIBLE
        pagerBar.visibility = View.VISIBLE
        @Suppress("UNCHECKED_CAST")
        val adapter = historyList.adapter as? MessageAdapter
        if (adapter == null) {
            historyList.adapter = MessageAdapter(history)
        } else {
            // 复用 adapter，保留滚动位置
            adapter.clear()
            adapter.addAll(history)
            adapter.notifyDataSetChanged()
        }
        historyList.setSelection(0)
    }

    private fun updatePager() {
        pageInfo.text = "第 ${currentPage + 1} 页 ▾"
        btnPrevPage.isEnabled = currentPage > 0 && !loadingPage
        btnNextPage.isEnabled = !loadingPage && (currentPage < pageCache.size - 1 || hasMore)
    }

    /** 消息序列化为 JSON 传给详情页，避免详情页依赖本地库。 */
    private fun messageToJson(m: GotifyMessage): String {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("appid", m.appId)
        o.put("appName", m.appName ?: JSONObject.NULL)
        o.put("title", m.title)
        o.put("message", m.body)
        o.put("priority", m.priority)
        o.put("dateMillis", m.dateMillis)
        o.put("extras", m.extras ?: JSONObject.NULL)
        return o.toString()
    }

    private fun refreshStatus() {
        val base = if (!prefs.isConfigured) {
            "未配置服务器（右上角菜单 → 设置）"
        } else {
            PushService.status
        }
        // 没有通知权限时这条推送链路的产出等于 0，必须让用户看见
        val permHint = NotifPermission.statusHint(Build.VERSION.SDK_INT, notificationsEnabled())
        statusText.text = if (permHint != null) "$base · $permHint" else base
        val color = when {
            permHint != null -> getColor(R.color.err)
            PushService.connected -> getColor(R.color.ok)
            PushService.status.contains("令牌") || PushService.status.contains("错误") ->
                getColor(R.color.err)
            else -> getColor(R.color.warn)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        updateLastUpdateText()

        // 前台服务收到新消息时，本地缓存版本号变化 → 无网络开销地刷新列表
        if (!storeReloading && MessageStore.version != lastStoreVersion) {
            lastStoreVersion = MessageStore.version
            reloadFromStore()
        }
    }

    private fun updateLastUpdateText() {
        if (lastUpdateMs <= 0) {
            lastUpdateText.text = ""
            return
        }
        val diff = System.currentTimeMillis() - lastUpdateMs
        lastUpdateText.text = if (diff < 60_000) {
            "· 刚刚"
        } else {
            "· " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(lastUpdateMs))
        }
    }

    // ---------- 工具 ----------

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("gotify", text))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun fmtTime(ms: Long): String {
        val now = System.currentTimeMillis()
        return when {
            now - ms < 60_000 -> "刚刚"
            now - ms < 3_600_000 -> "${(now - ms) / 60_000} 分钟前"
            else -> timeFormat.format(Date(ms))
        }
    }

    private inner class MessageAdapter(items: List<GotifyMessage>) :
        ArrayAdapter<GotifyMessage>(this, 0, items) {

        override fun getView(
            position: Int,
            convertView: View?,
            parent: android.view.ViewGroup
        ): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_message, parent, false)
            val m = getItem(position) ?: return v
            v.findViewById<TextView>(R.id.msg_title).text = m.title.ifBlank { "（无标题）" }
            v.findViewById<TextView>(R.id.msg_time).text = fmtTime(m.dateMillis)

            // 正文：空正文（仅标题）时整行隐藏，卡片更紧凑
            val bodyView = v.findViewById<TextView>(R.id.msg_body)
            if (m.body.isBlank()) {
                bodyView.visibility = View.GONE
            } else {
                bodyView.visibility = View.VISIBLE
                bodyView.text = m.body
            }

            // 应用名胶囊：按优先级取字色/底色（替代纯文字里的「应用 #N · 优先级 N」）
            val chip = v.findViewById<TextView>(R.id.msg_chip)
            chip.text = m.appName ?: "应用 #${m.appId}"
            // 颜色按当前主题（浅/深）从资源取，深色模式下有另一套值
            val txtColor = getColor(Palette.chipTextRes(m.priority))
            chip.setTextColor(txtColor)
            val chipBg = GradientDrawable().apply {
                setColor(getColor(Palette.chipBgRes(m.priority)))
                cornerRadius = chipRadiusPx.toFloat()
            }
            chip.background = chipBg

            // 优先级仅在高档位才有信息量，普通消息不占位
            val prioView = v.findViewById<TextView>(R.id.msg_priority)
            prioView.text = when {
                m.priority >= 8 -> "紧急 · ${m.priority}"
                m.priority in 4..7 -> "优先级 ${m.priority}"
                m.priority in 1..3 -> "低 · ${m.priority}"
                else -> "静默"
            }
            prioView.setTextColor(txtColor)

            // 卡片底色 + 左侧优先级色条（色条不越过卡片圆角，用 inset 预留内边距）
            val barColor = getColor(Palette.barRes(m.priority))
            fun cardLayer(cardColor: Int): LayerDrawable {
                val card = GradientDrawable().apply {
                    setColor(cardColor)
                    cornerRadius = cardRadiusPx.toFloat()
                    setStroke(strokePx, getColor(R.color.border))
                }
                val bar = GradientDrawable().apply {
                    setColor(barColor)
                    cornerRadius = (barWidthPx / 2f)
                }
                val ld = LayerDrawable(arrayOf(card, bar))
                ld.setLayerWidth(1, barWidthPx)
                ld.setLayerInset(1, barLeftPx, barInsetPx, 0, barInsetPx)
                return ld
            }
            val sld = StateListDrawable()
            sld.addState(intArrayOf(android.R.attr.state_pressed), cardLayer(pressedCardColor))
            sld.addState(intArrayOf(), cardLayer(normalCardColor))
            v.background = sld
            return v
        }
    }
}
