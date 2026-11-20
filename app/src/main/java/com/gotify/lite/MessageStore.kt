package com.gotify.lite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * 本地消息历史（最多保留 [MAX_ROWS] 条），用于通知点击后查看与断线补收。
 *
 * 数据库按服务器地址分文件：消息 id 由各服务器自行编号，不同服务器的 id 会相互重复，
 * 混存会导致甲服务器的消息覆盖乙服务器的同名 id。命名与迁移规则见 [StoreDbName]。
 *
 * 三条存储约定（都是省电 + 不卡主线程的一部分）：
 * - **进程内单实例**（[get]）：不再每次 insert 都 new 一个 helper 再 close，
 *   避免反复打开/关闭数据库连接；
 * - **限流修剪**：超出上限时用 `id <= (… LIMIT 1 OFFSET 500)` 做一次索引区间删除，
 *   取代原先“每条消息都跑一次 `NOT IN (子查询全表扫描)`”；
 * - **调用方必须放到后台线程**：本类自己不做线程切换，写入统一由 PushService 的
 *   executor、读取由界面的后台线程发起。
 */
class MessageStore private constructor(context: Context, dbName: String) :
    SQLiteOpenHelper(context, dbName, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "id INTEGER PRIMARY KEY, appid INTEGER, app_name TEXT, title TEXT, body TEXT, " +
                "date INTEGER, priority INTEGER, extras TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    init {
        // WAL：写入不阻塞读取，配合限流修剪后单条插入基本是纯追加
        setWriteAheadLoggingEnabled(true)
    }

    fun insert(m: GotifyMessage, appName: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("messages", null, toValues(m, appName), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pruneIfNeeded(force = false)
        version++
    }

    /** 批量写入（服务器拉取的历史消息同步入库；REPLACE 自动去重）。 */
    fun insertAll(msgs: List<GotifyMessage>, nameOf: (Long) -> String?) {
        if (msgs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in msgs) {
                db.insertWithOnConflict(
                    "messages", null,
                    toValues(m, nameOf(m.appId) ?: m.appName),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // 一批写完必修剪一次，避免批量补收后短暂超出上限
        pruneIfNeeded(force = true)
        version++
    }

    /**
     * 只保留最新的 [MAX_ROWS] 条：先定位第 MAX_ROWS+1 条（索引倒序扫 1 行），
     * 再删除 id 不大于它的所有行（主键区间删除）。表内不足 MAX_ROWS 条时子查询为空，
     * `id <= NULL` 不匹配任何行，天然无操作。
     */
    private fun pruneIfNeeded(force: Boolean) {
        insertsSincePrune++
        if (!force && insertsSincePrune < PRUNE_EVERY) return
        insertsSincePrune = 0
        runCatching {
            writableDatabase.execSQL(
                "DELETE FROM messages WHERE id <= " +
                    "(SELECT id FROM messages ORDER BY id DESC LIMIT 1 OFFSET $MAX_ROWS)"
            )
        }
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

    /** 按 id 删除本地记录。 */
    fun delete(id: Long) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
        deleted = true
        version++
    }

    private fun toValues(m: GotifyMessage, appName: String?): ContentValues = ContentValues().apply {
        put("id", m.id)
        put("appid", m.appId)
        put("app_name", appName ?: m.appName)
        put("title", m.title)
        put("body", m.body)
        put("date", m.dateMillis)
        put("priority", m.priority)
        put("extras", m.extras?.toString())
    }

    private fun rowToMessage(c: Cursor): GotifyMessage = GotifyMessage(
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

    private var insertsSincePrune = 0

    companion object {
        const val MAX_ROWS = 500

        /** 每写入这么多条才修剪一次；配合插入批量修剪，正常使用几乎不触发全表操作。 */
        private const val PRUNE_EVERY = 20

        @Volatile
        private var instance: MessageStore? = null

        @Volatile
        private var instanceDbName: String = ""

        /**
         * 进程内单实例。库名跟随当前服务器地址：换了服务器就换一个 helper
         * （旧实例不主动 close——它可能正被别的线程持有，让它随进程自然回收，比冒崩溃风险更划算）。
         */
        fun get(context: Context): MessageStore {
            val name = dbNameFor(context)
            instance?.let { if (instanceDbName == name) return it }
            synchronized(this) {
                instance?.let { if (instanceDbName == name) return it }
                val ctx = context.applicationContext ?: context
                return MessageStore(ctx, name).also {
                    instance = it
                    instanceDbName = name
                }
            }
        }

        /** 每次插入自增，供界面以极低成本感知“有新消息”。 */
        @Volatile
        var version = 0

        /** 详情页删除消息后置位：首页恢复时重载数据库并回到第 1 页。 */
        @Volatile
        var deleted = false

        /**
         * 跟随当前服务器地址命名数据库（如 messages_5f3c8a1b9d2e4f60.db）。
         * 地址变化后自动切到另一个库，避免不同服务器的消息 id 互相覆盖；
         * 归一化与哈希规则见 [StoreDbName]（同一地址的不同写法会落到同一个库）。
         * 构造时会顺带把旧命名（String.hashCode）的库迁移到新名字，避免升级后历史“消失”。
         */
        fun dbNameFor(context: Context): String {
            val url = Prefs(context).serverUrl
            migrateLegacyDb(context, url)
            return StoreDbName.forUrl(url)
        }

        /** 把旧命名的库文件（含 -journal/-wal/-shm）整体改名为新命名；失败则保持原样。 */
        private fun migrateLegacyDb(context: Context, url: String) {
            val legacyName = StoreDbName.legacyForUrl(url)
            val newName = StoreDbName.forUrl(url)
            if (legacyName == newName) return
            runCatching {
                val legacy = context.getDatabasePath(legacyName)
                val target = context.getDatabasePath(newName)
                if (!legacy.exists() || target.exists()) return@runCatching
                target.parentFile?.mkdirs()
                legacy.renameTo(target)
                // 事务日志与 WAL 必须一起搬走，否则新库可能读到不完整的数据
                for (suffix in listOf("-journal", "-wal", "-shm")) {
                    val from = File(legacy.path + suffix)
                    if (from.exists()) from.renameTo(File(target.path + suffix))
                }
            }
        }
    }
}
