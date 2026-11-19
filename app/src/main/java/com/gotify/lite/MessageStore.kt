package com.gotify.lite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 本地消息历史（最多保留 500 条），用于通知点击后查看与断线补收。
 *
 * 数据库按服务器地址分文件：消息 id 由各服务器自行编号，不同服务器的 id 会相互重复，
 * 混存会导致甲服务器的消息覆盖乙服务器的同名 id。 */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context, dbNameFor(context), null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "id INTEGER PRIMARY KEY, appid INTEGER, app_name TEXT, title TEXT, body TEXT, " +
                "date INTEGER, priority INTEGER, extras TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(m: GotifyMessage, appName: String?) {
        val cv = ContentValues().apply {
            put("id", m.id)
            put("appid", m.appId)
            put("app_name", appName ?: m.appName)
            put("title", m.title)
            put("body", m.body)
            put("date", m.dateMillis)
            put("priority", m.priority)
            put("extras", m.extras?.toString())
        }
        val db = writableDatabase
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM messages WHERE id NOT IN " +
                "(SELECT id FROM messages ORDER BY id DESC LIMIT 500)"
        )
        db.close()
        version++
    }

    /** 批量写入（服务器拉取的历史消息同步入库；REPLACE 自动去重）。 */
    fun insertAll(msgs: List<GotifyMessage>, nameOf: (Long) -> String?) {
        if (msgs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in msgs) {
                val cv = ContentValues().apply {
                    put("id", m.id)
                    put("appid", m.appId)
                    put("app_name", nameOf(m.appId) ?: m.appName)
                    put("title", m.title)
                    put("body", m.body)
                    put("date", m.dateMillis)
                    put("priority", m.priority)
                    put("extras", m.extras?.toString())
                }
                db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL(
                "DELETE FROM messages WHERE id NOT IN " +
                    "(SELECT id FROM messages ORDER BY id DESC LIMIT 500)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.close()
        version++
    }

    /** 新→旧；appId 非空时只返回该应用的消息。 */
    fun latest(limit: Int, appId: Long? = null): List<GotifyMessage> {
        val out = ArrayList<GotifyMessage>()
        val base = "SELECT id, appid, app_name, title, body, date, priority, extras FROM messages "
        val sql: String
        val args: Array<String>
        if (appId != null) {
            sql = base + "WHERE appid = ? ORDER BY id DESC LIMIT ?"
            args = arrayOf(appId.toString(), limit.toString())
        } else {
            sql = base + "ORDER BY id DESC LIMIT ?"
            args = arrayOf(limit.toString())
        }
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(rowToMessage(c))
            }
        }
        return out
    }

    /** 本地消息中出现过的应用列表（离线兜底，按最近消息时间倒序）。 */
    fun apps(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT appid, app_name, MAX(date) AS d FROM messages GROUP BY appid ORDER BY d DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(id to (c.getString(1) ?: "应用 #$id"))
            }
        }
        return out
    }

    /** 按消息 id 查询（详情页使用）。 */
    fun getById(id: Long): GotifyMessage? {
        readableDatabase.rawQuery(
            "SELECT id, appid, app_name, title, body, date, priority, extras " +
                "FROM messages WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) return rowToMessage(c)
        }
        return null
    }

    private fun rowToMessage(c: android.database.Cursor): GotifyMessage =
        GotifyMessage(
            id = c.getLong(0),
            appId = c.getLong(1),
            title = c.getString(3) ?: "",
            body = c.getString(4) ?: "",
            dateMillis = c.getLong(5),
            priority = c.getInt(6),
            extras = c.getString(7)?.let {
                runCatching { org.json.JSONObject(it) }.getOrNull()
            },
            appName = c.getString(2)
        )

    /** 按 id 删除本地记录。 */
    fun delete(id: Long) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
        deleted = true
        version++
    }

    companion object {
        /** 每次插入自增，供界面以极低成本感知“有新消息”。 */
        @Volatile
        var version = 0

        /** 详情页删除消息后置位：首页恢复时重载数据库并回到第 1 页。 */
        @Volatile
        var deleted = false

        /**
         * 跟随当前服务器地址命名数据库（如 messages_b8f3a1c4.db）。
         * 地址变化后自动切到另一个库，避免不同服务器的消息 id 互相覆盖。
         */
        fun dbNameFor(context: Context): String {
            val url = Prefs(context).serverUrl
            if (url.isBlank()) return "messages_default.db"
            val h = url.hashCode().toUInt().toString(16)
            return "messages_$h.db"
        }
    }
}
