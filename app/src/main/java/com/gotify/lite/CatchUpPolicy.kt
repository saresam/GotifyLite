package com.gotify.lite

/**
 * 断线补收的纯决策逻辑（不碰网络、数据库与 Android API，便于单元测试）。
 *
 * 基准 id（baseId）语义：已经确认收到过的最大消息 id；`-1` 表示尚未确立基准。
 * 首次连接时基准未知，此时只能“静默确立基准”，绝不能把服务器上已有的历史当新消息推送；
 * 服务重启后基准从持久化中恢复，因此断线期间的漏消息仍会被补收。
 */
object CatchUpPolicy {

    /** 一次补收的结论。 */
    data class Result(
        /** 新的基准 id，需要写回持久化。 */
        val baseId: Long,
        /** 需要落库并通知的消息，按 id 升序（保证通知顺序与发生顺序一致）。 */
        val missed: List<GotifyMessage>,
        /** 服务器 id 空间倒退了（服务端被重建/换库），基准已按新值重置。 */
        val serverReset: Boolean,
        /** 本次只是静默确立基准，不做任何通知。 */
        val silentInit: Boolean
    )

    /**
     * @param knownBase 已确认的基准 id；<0 表示未知
     * @param fetched 服务器返回的消息（通常按 id 降序，`/message?limit=N` 的结果）
     */
    fun plan(knownBase: Long, fetched: List<GotifyMessage>): Result {
        val newest = fetched.maxOfOrNull { it.id }

        // 基准未知：静默确立，把服务器当前最大 id 作为基准，不补任何通知
        if (knownBase < 0) {
            return Result(
                baseId = newest ?: 0L,
                missed = emptyList(),
                serverReset = false,
                silentInit = true
            )
        }

        // 服务器最大 id 反而小于本地基准：服务端被重建（或换了数据库），旧基准永久失效，
        // 若不重置，之后所有 id 更小的消息都会被永远当成“已读”而丢掉。
        if (newest != null && newest < knownBase) {
            return Result(
                baseId = newest,
                missed = emptyList(),
                serverReset = true,
                silentInit = true
            )
        }

        val missed = fetched.filter { it.id > knownBase }.sortedBy { it.id }
        return Result(
            baseId = maxOf(knownBase, newest ?: knownBase),
            missed = missed,
            serverReset = false,
            silentInit = false
        )
    }
}
