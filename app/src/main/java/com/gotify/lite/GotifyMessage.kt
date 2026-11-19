package com.gotify.lite

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Gotify 消息。服务端 WebSocket 帧与 REST 返回的都是裸 Message JSON：
 * {"id":..,"appid":..,"message":"..","title":"..","priority":..,"date":"RFC3339","extras":{..}}
 */
data class GotifyMessage(
    val id: Long,
    val appId: Long,
    val title: String,
    val body: String,
    val priority: Int,
    val dateMillis: Long,
    val extras: JSONObject? = null,
    val appName: String? = null
) {
    /** extras["client::notification"]["click"]["url"]：点击通知时打开该 URL。 */
    val clickUrl: String?
        get() = nestedString(extras, "client::notification", "click", "url")

    /** extras["android::action"]["onReceive"]["intentUrl"]：收到消息时立即打开该 URL。 */
    val onReceiveIntentUrl: String?
        get() = nestedString(extras, "android::action", "onReceive", "intentUrl")

    companion object {
        fun parse(text: String): GotifyMessage {
            val o = JSONObject(text)
            val extras =
                if (o.has("extras") && !o.isNull("extras")) o.getJSONObject("extras") else null
            return GotifyMessage(
                id = o.optLong("id"),
                appId = o.optLong("appid"),
                title = o.optString("title"),
                body = o.optString("message"),
                priority = o.optInt("priority", 5),
                dateMillis = parseDate(o.optString("date")),
                extras = extras
            )
        }

        private fun parseDate(s: String): Long {
            if (s.isBlank()) return System.currentTimeMillis()
            return try {
                OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (e: Exception) {
                try {
                    Instant.parse(s).toEpochMilli()
                } catch (e2: Exception) {
                    System.currentTimeMillis()
                }
            }
        }

        private fun nestedString(extras: JSONObject?, vararg keys: String): String? {
            if (extras == null) return null
            var cur: Any? = extras
            for (k in keys) {
                if (cur !is JSONObject || !cur.has(k)) return null
                cur = cur.get(k)
            }
            return (cur as? String)?.takeIf { it.isNotBlank() }
        }
    }
}
