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
    private var lastMsgId = -1L
    private var missedInit = false
    private val appNames = HashMap<Long, String>()
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
        startForegroundCompat()
        if (prefs.lastMsgId < 0) initLastMsgId()
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
                        fetchMissed()
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
        MessageStore(this).insert(msg, appName)
        Notifier.postMessage(this, msg, appName ?: "应用 #${msg.appId}")
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

    /** 首次启动：记录服务器上最新消息 id，作为断线补收的基准。 */
    private fun initLastMsgId() {
        executor.execute {
            var id = 0L
            try {
                id = GotifyApi.messages(prefs.serverUrl, prefs.token, 1).firstOrNull()?.id ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "init lastMsgId failed: ${e.message}")
            }
            prefs.lastMsgId = id
            handler.post {
                lastMsgId = id
                missedInit = true
            }
        }
    }

    /** 重连成功后，补通知断线期间错过的消息。 */
    private fun fetchMissed() {
        if (!missedInit) return
        executor.execute {
            try {
                val msgs = GotifyApi.messages(prefs.serverUrl, prefs.token, 100)
                val missed = msgs.filter { it.id > lastMsgId }.sortedBy { it.id }
                if (missed.isNotEmpty()) {
                    handler.post {
                        if (missed.size > 5) {
                            missed.forEach {
                                if (it.id > lastMsgId) {
                                    lastMsgId = it.id
                                    prefs.lastMsgId = it.id
                                }
                                MessageStore(this).insert(it, appNames[it.appId])
                            }
                            Notifier.postMissed(this, missed.size)
                        } else {
                            missed.forEach { onGotifyMessage(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch missed failed: ${e.message}")
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
        stream?.close()
        stream = null
        ReconnectJobService.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stream?.close()
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
