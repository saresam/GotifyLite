package com.gotify.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 通知：1 个前台服务状态通道 + 4 个按优先级划分的消息通道 + 分组摘要。 */
object Notifier {

    const val ID_FOREGROUND = -1
    const val ID_SUMMARY = -2
    const val ID_MISSED = -3

    private const val GROUP = "gotify_messages"
    private const val COLOR = 0xFF2563EB.toInt()

    const val CH_FOREGROUND = "foreground"
    const val CH_MIN = "messages_min"
    const val CH_LOW = "messages_low"
    const val CH_DEFAULT = "messages_default"
    const val CH_HIGH = "messages_high"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_FOREGROUND, "连接状态", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MIN, "消息（静默）", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LOW, "消息（低）", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DEFAULT, "消息（普通）", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { enableLights(true); enableVibration(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HIGH, "消息（紧急）", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableLights(true); enableVibration(true) }
        )
    }

    /** 优先级 → 颜色：静默灰 / 低蓝 / 普通琥珀 / 紧急红（列表色条、通知图标、详情标识共用）。 */
    fun priorityColor(priority: Int): Int = when {
        priority < 1 -> 0xFF9CA3AF.toInt() // 静默：灰
        priority < 4 -> 0xFF3B82F6.toInt() // 低：蓝
        priority < 8 -> 0xFFEDA463.toInt() // 普通：橙（参考用户截图）
        else -> 0xFFE74C3C.toInt() // 紧急：红（参考用户截图）
    }

    /** 列表左侧竖条专用浅色（深色竖条在白色列表里显得太浓）。 */
    fun barColor(priority: Int): Int = when {
        priority < 1 -> 0xFFC4C8CF.toInt() // 静默：浅灰
        priority < 4 -> 0xFF89B4FA.toInt() // 低：浅蓝
        priority < 8 -> 0xFFF4C8A1.toInt() // 普通：浅橙
        else -> 0xFFF1948A.toInt() // 紧急：浅红
    }

    /** 应用名胶囊字色（与分档语义一致，保证与各自浅底达到 AA 对比度）。 */
    fun chipTextColor(priority: Int): Int = when {
        priority < 1 -> 0xFF6B7280.toInt()
        priority < 4 -> 0xFF2563EB.toInt()
        priority < 8 -> 0xFFB45309.toInt()
        else -> 0xFFB91C1C.toInt()
    }

    /** 应用名胶囊底色（带透明度的同色系浅底）。 */
    fun chipBgColor(priority: Int): Int = when {
        priority < 1 -> 0x0F6B7280
        priority < 4 -> 0x0F2563EB
        priority < 8 -> 0x2EEDA463
        else -> 0x1FDC2626
    }

    /** 与原版一致：Gotify 优先级 <=0 静默、1-3 低、4-7 普通、>=8 紧急。 */
    fun channelForPriority(priority: Int): String = when {
        priority < 1 -> CH_MIN
        priority < 4 -> CH_LOW
        priority < 8 -> CH_DEFAULT
        else -> CH_HIGH
    }

    /** 前台服务常驻通知（低优先级、无声音、不可滑动）。 */
    fun foreground(ctx: Context, status: String): Notification {
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CH_FOREGROUND)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(status)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setColor(COLOR)
            .build()
    }

    /** 推送一条消息通知，并刷新分组摘要。 */
    fun postMessage(ctx: Context, m: GotifyMessage, appName: String) {
        // extras["android::action"]["onReceive"]["intentUrl"]：收到即打开（与原版行为一致）
        m.onReceiveIntentUrl?.let { url ->
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // extras["client::notification"]["click"]["url"]：点击通知打开该 URL，否则打开应用内历史
        val contentIntent: PendingIntent = if (m.clickUrl != null) {
            PendingIntent.getActivity(
                ctx, 2,
                Intent(Intent.ACTION_VIEW, Uri.parse(m.clickUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java).putExtra("message_id", m.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val title = m.title.ifBlank { appName }
        val text = m.body.ifBlank { title }

        val b = Notification.Builder(ctx, channelForPriority(m.priority))
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(appName)
            .setAutoCancel(true)
            .setWhen(m.dateMillis)
            .setContentIntent(contentIntent)
            .setGroup(GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setColor(priorityColor(m.priority))

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId(m.id), b.build())
        updateSummary(ctx, nm)
    }

    /** 断线补收的聚合通知。 */
    fun postMissed(ctx: Context, count: Int) {
        val pi = PendingIntent.getActivity(
            ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = "断线期间收到 $count 条消息"
        val b = Notification.Builder(ctx, CH_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setColor(COLOR)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_MISSED, b.build())
    }

    private fun notifId(messageId: Long): Int = 1000 + (messageId % 2_000_000_000L).toInt()

    /** 取消某条消息对应的通知（删除消息时同步清除）。 */
    fun cancelMessage(ctx: Context, messageId: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId(messageId))
    }

    private fun updateSummary(ctx: Context, nm: NotificationManager) {
        val pi = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CH_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText("查看全部消息")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(COLOR)
        nm.notify(ID_SUMMARY, b.build())
    }
}
