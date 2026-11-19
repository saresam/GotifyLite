package com.gotify.lite

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** REST 调用失败（含非 2xx 与网络错误）。code=0 表示请求未发出。 */
class ApiException(val code: Int, message: String) : IOException(message)

/** Gotify 服务端 REST API（与 gotify/server v2.x 兼容）。 */
object GotifyApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 由 http(s) 地址构造 WebSocket /stream 地址。地址非法时返回 null。
     * 注意：OkHttp 的 HttpUrl 只接受 http/https scheme，ws/wss 协议升级由
     * OkHttp 在 newWebSocket 连接时自动完成（http→ws、https→wss）。
     */
    fun wsUrl(base: String, token: String): HttpUrl? {
        val u = base.trim().toHttpUrlOrNull() ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        return u.newBuilder()
            .addPathSegment("stream")
            .addQueryParameter("token", token)
            .build()
    }

    /** GET /version（免认证），用于连通性测试。 */
    fun serverVersion(base: String): String? {
        val url = base.trim().toHttpUrlOrNull()
            ?: throw ApiException(0, "服务器地址格式不正确")
        val req = Request.Builder()
            .url(url.newBuilder().addPathSegment("version").build())
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.message)
            val body = resp.body?.string() ?: return null
            return runCatching { JSONObject(body).optString("version") }.getOrNull()
        }
    }

    /** GET /application（客户端令牌认证），返回应用列表 JSON 数组。 */
    fun applications(base: String, token: String): JSONArray {
        val url = base.trim().toHttpUrlOrNull()
            ?: throw ApiException(0, "服务器地址格式不正确")
        val req = Request.Builder()
            .url(url.newBuilder().addPathSegment("application").build())
            .header("X-Gotify-Key", token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.message)
            return JSONArray(resp.body?.string() ?: "[]")
        }
    }

    /**
     * 拉取新→旧的消息列表。
     * appId 为空：GET /message（全部应用）；否则 GET /application/{id}/message（单应用）。
     * since 非空时只返回 id 小于该值的更旧消息（分页用）。
     */
    fun messages(
        base: String,
        token: String,
        limit: Int = 100,
        appId: Long? = null,
        since: Long? = null
    ): List<GotifyMessage> {
        val url = base.trim().toHttpUrlOrNull()
            ?: throw ApiException(0, "服务器地址格式不正确")
        val path = url.newBuilder()
        if (appId != null) {
            path.addPathSegment("application")
                .addPathSegment(appId.toString())
                .addPathSegment("message")
        } else {
            path.addPathSegment("message")
        }
        path.addQueryParameter("limit", limit.toString())
        if (since != null) path.addQueryParameter("since", since.toString())
        val req = Request.Builder()
            .url(path.build())
            .header("X-Gotify-Key", token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.message)
            val o = JSONObject(resp.body?.string() ?: "{}")
            val arr = o.optJSONArray("messages") ?: JSONArray()
            return (0 until arr.length()).map { GotifyMessage.parse(arr.getJSONObject(it).toString()) }
        }
    }

    /** DELETE /message/{id}：删除服务器上的一条消息（客户端令牌认证）。 */
    fun deleteMessage(base: String, token: String, id: Long) {
        val url = base.trim().toHttpUrlOrNull()
            ?: throw ApiException(0, "服务器地址格式不正确")
        val req = Request.Builder()
            .url(
                url.newBuilder()
                    .addPathSegment("message")
                    .addPathSegment(id.toString())
                    .build()
            )
            .header("X-Gotify-Key", token)
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.message)
        }
    }
}
