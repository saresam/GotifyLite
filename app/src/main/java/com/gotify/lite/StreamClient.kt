package com.gotify.lite

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 极省电 WebSocket 客户端：
 * - 不设 pingInterval（服务端每 45s 发 ping，OkHttp 自动回 pong，客户端不必额外发包）；
 * - readTimeout=0（长连接不超时）；
 * - 不持有任何 WakeLock / WifiLock。
 */
class StreamClient(
    private val wsUrl: HttpUrl,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable, response: Response?)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ws: WebSocket? = null

    fun connect() {
        if (ws != null) return
        val req = Request.Builder().url(wsUrl).get().build()
        ws = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ws = null
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ws = null
                    listener.onFailure(t, response)
                }
            }
        )
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }
}
