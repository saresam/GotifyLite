package com.gotify.lite

/**
 * 通知权限（Android 13 / API 33 起的 `POST_NOTIFICATIONS` 运行时权限）判定逻辑。
 *
 * 只做纯判断，不申请、不跳转，方便单元测试；实际的 `requestPermissions()` 与
 * “跳系统设置”由界面层完成。没有这个判定的后果是：用户在 Android 13+ 上装完应用，
 * 系统默认不弹通知，而应用以为自己一切正常。
 */
object NotifPermission {

    /** 从该 API 级别起通知权限是运行时权限。 */
    const val MIN_SDK_RUNTIME = 33

    /** requestPermissions() 的请求码。 */
    const val REQ_CODE = 4001

    fun needsRuntimePermission(sdkInt: Int): Boolean = sdkInt >= MIN_SDK_RUNTIME

    /**
     * 是否应当主动弹出系统申请框。
     *
     * @param granted 当前是否已授权
     * @param alreadyAsked 本机是否已经弹过一次（避免每次回到前台都骚扰用户；
     *   若用户拒绝过，后续引导他走系统设置而不是反复弹框）
     */
    fun shouldPrompt(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
        needsRuntimePermission(sdkInt) && !granted && !alreadyAsked

    /** 未授权时状态栏的提示文案；已授权返回 null。 */
    fun statusHint(sdkInt: Int, granted: Boolean): String? = when {
        granted -> null
        needsRuntimePermission(sdkInt) -> "通知权限未开启，收不到新消息（点此开启）"
        // API 33 以下没有运行时权限，只可能是用户在系统设置里关了通知
        else -> "通知已被关闭，收不到新消息（点此开启）"
    }
}
