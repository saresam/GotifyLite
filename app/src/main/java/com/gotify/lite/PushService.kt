package com.gotify.lite

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 推送前台服务：WebSocket 长连接 + 系统通知。
 *
 * 省电设计：
 * - 前台服务类型 remoteMessaging（Android 15 专为“接收远端消息”设计，无时长限制），
 *   低版本/异常时回退 dataSync；
 * - 不持 WakeLock/WifiLock，空闲时 CPU 可深度睡眠，仅收包瞬间被唤醒；
 * - 重连用内存 Handler 指数退避（1s→60s，±20% 抖动），绝不使用 setExact 精确闹钟；
 * - JobScheduler 一次性任务兜底（进程被杀后拉起服务），由系统批量调度，几乎零耗电；
 * - 客户端不发 ping（服务端每 45s 发 ping，OkHttp 自动回 pong）。
 */
class PushService : Service() {

    companion object {
        private const val TAG = "PushService"
        private const val ACTION_START = "com.gotify.lite.action.START"
        private const val ACTION_STOP = "com.gotify.lite.action.STOP"
        private const val MAX_BACKOFF_MS = 60_000L

        /** 断线补收一次拉取的消息条数上限（同时也是“重启后能补多久”的窗口）。 */
        private const val CATCHUP_LIMIT = 100

        /** 补收条数超过该值时发一条聚合通知，避免一次刷屏。 */
        private const val CATCHUP_DETAIL_MAX = 5

        @Volatile
        var status: String = "未启动"
            private set

        @Volatile
        var connected: Boolean = false
            private set

        val running = AtomicBoolean(false)

        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, PushService::class.java).setAction(ACTION_START)
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, PushService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                Log.w(TAG, "stop failed", e)
            }
        }
    }

    private lateinit var prefs: Prefs
    private var stream: StreamClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var backoffMs = 1000L
    private var reconnectAttempts = 0
    /** 已确认收到过的最大消息 id；-1 表示基准尚未确立。启动时从持久化恢复。 */
    private var lastMsgId = -1L
    /** 应用 id→名称；executor 线程会读、主线程会写，必须用并发容器。 */
    private val appNames = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private var stopping = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "network available")
            // 网络恢复时立即尝试重连（若连接已断开）
            handler.post { if (!connected) connectIfNeeded() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifier.createChannels(this)
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPush()
            return START_NOT_STICKY
        }
        if (!prefs.isConfigured) {
            status = "未配置服务器，请在应用内填写地址和令牌"
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        running.set(true)
        // 基准必须从持久化恢复：进程被杀、开机重启、JobScheduler 拉起之后，
        // 只有拿到持久化基准才能把停机期间漏掉的消息补回来。
        lastMsgId = prefs.lastMsgId
        startForegroundCompat()
        connectIfNeeded()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val n = Notifier.foreground(this, status)
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_FOREGROUND, n, type)
            } else {
                startForeground(Notifier.ID_FOREGROUND, n)
            }
        } catch (e: Exception) {
            // 个别 ROM 对特定类型不兼容时回退
            Log.w(TAG, "startForeground failed, fallback to dataSync", e)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    Notifier.ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(Notifier.ID_FOREGROUND, n)
            }
        }
    }

    private fun connectIfNeeded() {
        if (stopping || stream != null) return
        val url = GotifyApi.wsUrl(prefs.serverUrl, prefs.token)
        if (url == null) {
            setStatus("服务器地址格式不正确，请检查设置")
            return
        }
        setStatus(if (reconnectAttempts == 0) "连接中…" else "重连中（第 $reconnectAttempts 次）")
        // lateinit：对象表达式方法体里需要引用“当前连接”做陈旧回调守卫
        lateinit var client: StreamClient
        client = StreamClient(
            url,
            object : StreamClient.Listener {
                override fun onOpen() {
                    handler.post {
                        if (stream !== client) return@post
                        connected = true
                        reconnectAttempts = 0
                        backoffMs = 1000
                        setStatus("已连接")
                        ReconnectJobService.cancel(this@PushService)
                        fetchApps()
                        syncCatchUp()
                    }
                }

                override fun onMessage(text: String) {
                    val msg = runCatching { GotifyMessage.parse(text) }.getOrNull()
                    if (msg != null) {
                        handler.post {
                            if (stream === client) onGotifyMessage(msg)
                        }
                    }
                }

                override fun onClosed(code: Int, reason: String) {
                    handler.post {
                        if (stream === client) {
                            stream = null
                            connected = false
                            setStatus("连接已断开，等待重连")
                            scheduleReconnect()
                        }
                    }
                }

                override fun onFailure(t: Throwable, response: Response?) {
                    handler.post {
                        if (stream === client) {
                            stream = null
                            connected = false
                            val code = response?.code
                            if (code == 401 || code == 403) {
                                // 令牌失效：停止重试，避免无谓耗电，等用户修正设置
                                setStatus("令牌无效或已被删除，请检查设置")
                                ReconnectJobService.cancel(this@PushService)
                                return@post
                            }
                            setStatus(
                                if (code != null) "连接失败（HTTP $code）"
                                else "连接失败：${t.message ?: "网络错误"}"
                            )
                            scheduleReconnect()
                        }
                    }
                }
            }
        )
        stream = client
        client.connect()
    }

    private fun onGotifyMessage(msg: GotifyMessage) {
        if (msg.id > lastMsgId) {
            lastMsgId = msg.id
            prefs.lastMsgId = lastMsgId
        }
        val appName = appNames[msg.appId]
        // 落库放到 executor：主线程只做 O(1) 的基准更新与通知投递
        executor.execute {
            runCatching { MessageStore.get(this).insert(msg, appName) }
                .onFailure { Log.w(TAG, "insert failed: ${it.message}") }
            Notifier.postMessage(this, msg, appName ?: "应用 #${msg.appId}")
        }
    }

    /** 拉取应用 id->名称 映射（用于通知里显示应用名）。 */
    private fun fetchApps() {
        executor.execute {
            try {
                val apps = GotifyApi.applications(prefs.serverUrl, prefs.token)
                val map = HashMap<Long, String>()
                for (i in 0 until apps.length()) {
                    val o = apps.getJSONObject(i)
                    map[o.optLong("id")] = o.optString("name")
                }
                handler.post {
                    appNames.clear()
                    appNames.putAll(map)
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch apps failed: ${e.message}")
            }
        }
    }

    /**
     * 断线补收：以持久化的基准 id 为准，把断线（含进程被杀、开机重启）期间错过的消息补回来。
     *
     * 三个关键行为都由 [CatchUpPolicy] 决定并用单元测试锁住：
     * - 基准未知（-1，首次使用）：只“静默确立基准”，绝不把服务器上的历史当新消息推送；
     * - 基准已知：只补 id 大于基准的消息，按 id 升序通知；
     * - 服务器 id 空间倒退：重置基准，避免之后的消息被永久判为“已读”。
     *
     * 拉取失败时**不写入任何基准值**并等下次重连重试——旧实现在失败时把基准写成 0，
     * 会让下一次补收把最近 100 条历史全部当新消息轰炸一遍。
     */
    private fun syncCatchUp() {
        val base = lastMsgId
        val url = prefs.serverUrl
        val token = prefs.token
        executor.execute {
            val fetched = try {
                GotifyApi.messages(url, token, CATCHUP_LIMIT)
            } catch (e: Exception) {
                Log.w(TAG, "catch-up failed, keep base=$base: ${e.message}")
                return@execute
            }
            val plan = CatchUpPolicy.plan(base, fetched)
            if (stopping) return@execute
            // 落库在后台线程完成（批量写 + 一次修剪），通知与状态更新回主线程
            if (plan.missed.isNotEmpty()) {
                if (plan.missed.size > CATCHUP_DETAIL_MAX) {
                    MessageStore.get(this).insertAll(plan.missed) { appNames[it] }
                } else {
                    plan.missed.forEach { MessageStore.get(this).insert(it, appNames[it.appId]) }
                }
            }
            handler.post {
                if (stopping) return@post
                if (plan.serverReset) {
                    Log.w(TAG, "server message ids went backwards, base reset to ${plan.baseId}")
                    setStatus("服务器消息 id 已重置，已重新对齐")
                }
                if (plan.baseId != lastMsgId) {
                    lastMsgId = plan.baseId
                    prefs.lastMsgId = plan.baseId
                }
                if (plan.missed.isEmpty()) return@post
                if (plan.missed.size > CATCHUP_DETAIL_MAX) {
                    Notifier.postMissed(this@PushService, plan.missed.size)
                } else {
                    plan.missed.forEach {
                        Notifier.postMessage(
                            this@PushService, it, appNames[it.appId] ?: "应用 #${it.appId}"
                        )
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        ReconnectJobService.schedule(this)
        reconnectAttempts++
        val delay = backoffMs
        backoffMs = (minOf(backoffMs * 2, MAX_BACKOFF_MS) * (0.8 + Random.nextDouble() * 0.4))
            .toLong().coerceAtLeast(1000)
        handler.postDelayed({ connectIfNeeded() }, delay)
        setStatus("重连中（第 $reconnectAttempts 次，${delay / 1000}s 后）")
    }

    private fun setStatus(text: String) {
        status = text
        if (running.get()) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(Notifier.ID_FOREGROUND, Notifier.foreground(this, text)) }
        }
    }

    private fun stopPush() {
        stopping = true
        running.set(false)
        prefs.enabled = false
        connected = false
        status = "已停止"
        // cancel 而不是 close：服务马上要销毁了，不必等对端回 close 帧
        stream?.cancel()
        stream = null
        ReconnectJobService.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stream?.cancel()
        stream = null
        connected = false
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
        executor.shutdown()
        super.onDestroy()
    }
}
