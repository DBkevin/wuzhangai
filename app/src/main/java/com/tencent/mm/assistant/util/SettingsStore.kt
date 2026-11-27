package com.tencent.mm.assistant.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设置持久化。
 *
 * 只保存"配置"，不保存任何聊天内容：
 *  中控地址 / 客服账号别名 / N / OCR 开关 / 本地日志开关 / device_id 派生值。
 *  当前会话消息永远只在内存中，会话切换即清空。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 中控

    /** 上报接口地址，例如 http://10.0.0.5:8080/ingest 。 */
    var centerUrl: String
        get() = prefs.getString(KEY_CENTER_URL, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_CENTER_URL, value.trim()).apply()

    /**
     * 反馈接口地址。
     * 默认从中控地址派生：同源 + /feedback；也允许显式覆盖。
     */
    var feedbackUrlOverride: String
        get() = prefs.getString(KEY_FEEDBACK_URL, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_FEEDBACK_URL, value.trim()).apply()

    val feedbackUrl: String
        get() {
            val override = feedbackUrlOverride
            if (override.isNotBlank()) return override
            return deriveFeedbackUrl(centerUrl)
        }

    // ------------------------------------------------------------ 客服账号

    /**
     * 客服账号别名（设备上手动填写一次）。
     * 微信号可从微信「我」页读取并缓存，见 [cachedWechatId]。
     */
    var serviceAlias: String
        get() = prefs.getString(KEY_SERVICE_ALIAS, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_SERVICE_ALIAS, value.trim()).apply()

    /** 从微信「我」页读取到的微信号缓存（仅此一个标识，非聊天内容）。 */
    var cachedWechatId: String
        get() = prefs.getString(KEY_CACHED_WECHAT_ID, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_CACHED_WECHAT_ID, value.trim()).apply()

    /**
     * 回传给中控的 service_account_id。
     * 优先级：微信「我」页微信号 > 设备上配置的别名。
     */
    val serviceAccountId: String
        get() = cachedWechatId.ifBlank { serviceAlias }

    // ------------------------------------------------------------ 采集参数

    /** 最近 N 条可见消息，默认 5。 */
    var maxMessages: Int
        get() = prefs.getInt(KEY_MAX_MESSAGES, DEFAULT_MAX_MESSAGES).coerceIn(MIN_MESSAGES, MAX_MESSAGES)
        set(value) = prefs.edit()
            .putInt(KEY_MAX_MESSAGES, value.coerceIn(MIN_MESSAGES, MAX_MESSAGES))
            .apply()

    /** OCR 降级开关：默认关闭。 */
    var ocrFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_OCR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OCR_ENABLED, value).apply()

    /** 本地日志开关。开启后也只记录时间戳/包名/read_status/HTTP 状态码。 */
    var localLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_LOG, value).apply()

    /**
     * 个人资料页微信号读取开关（默认开启）。
     *
     * 说明：这是对原始"只回传四类字段"边界的一次**显式扩展**，
     * 仅在人工打开某个人的资料页时读取其微信号，用于中控做跨客服同一人匹配。
     * 关闭后完全不读取资料页。
     */
    var profileReadEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_READ, true)
        set(value) = prefs.edit().putBoolean(KEY_PROFILE_READ, value).apply()

    /**
     * 悬浮窗背景不透明度（百分比，30~100，默认 78）。
     *
     * 100 = 完全不透明；数值越小越透，越能看清下面的微信界面。
     */
    var overlayOpacityPercent: Int
        get() = prefs.getInt(KEY_OVERLAY_OPACITY, DEFAULT_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY)
        set(value) = prefs.edit()
            .putInt(KEY_OVERLAY_OPACITY, value.coerceIn(MIN_OPACITY, MAX_OPACITY))
            .apply()

    // ------------------------------------------------------------ WP-27 悬浮球记忆（零聊天隐私）

    /** 悬浮球停靠的 Y 坐标（px）；-1 表示尚未拖动过，使用默认 35% 高度。 */
    var floatingBallY: Int
        get() = prefs.getInt(KEY_FLOATING_BALL_Y, -1)
        set(value) = prefs.edit().putInt(KEY_FLOATING_BALL_Y, value).apply()

    /** 上次所选渠道（`sourceOne\u0001sourceTwo`，只记渠道名，不含任何聊天内容）。 */
    var lastChannelRaw: String
        get() = prefs.getString(KEY_LAST_CHANNEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL, value).apply()

    /** 上次所选引流运营（`id\u0001name`，id 可为空表示无运营）。 */
    var lastOperatorRaw: String
        get() = prefs.getString(KEY_LAST_OPERATOR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_OPERATOR, value).apply()

    // ------------------------------------------------------------ device_id

    /**
     * device_id = android_id 的 SHA-256 哈希（不持久化明文 android_id）。
     */
    val deviceId: String by lazy { hashAndroidId() }

    private fun hashAndroidId(): String {
        val raw = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        } ?: return UNKNOWN_DEVICE_ID
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        digest.joinToString(separator = "") { b -> "%02x".format(b) }
    } catch (_: Exception) {
        UNKNOWN_DEVICE_ID
    }

    companion object {
        const val PREFS_NAME = "kf_settings"

        private const val KEY_CENTER_URL = "center_url"
        private const val KEY_FEEDBACK_URL = "feedback_url"
        private const val KEY_SERVICE_ALIAS = "service_alias"
        private const val KEY_CACHED_WECHAT_ID = "cached_wechat_id"
        private const val KEY_MAX_MESSAGES = "max_messages"
        private const val KEY_OCR_ENABLED = "ocr_fallback_enabled"
        private const val KEY_LOCAL_LOG = "local_log_enabled"
        private const val KEY_PROFILE_READ = "profile_read_enabled"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity_percent"
        private const val KEY_FLOATING_BALL_Y = "floating_ball_y"
        private const val KEY_LAST_CHANNEL = "last_channel_choice"
        private const val KEY_LAST_OPERATOR = "last_operator_choice"

        /** 悬浮窗不透明度默认值（百分比）。65% 兼顾可读性与"能看清背后微信"的需求。 */
        const val DEFAULT_OPACITY = 65
        const val MIN_OPACITY = 30
        const val MAX_OPACITY = 100

        const val DEFAULT_MAX_MESSAGES = 5
        const val MIN_MESSAGES = 1
        const val MAX_MESSAGES = 20

        const val UNKNOWN_DEVICE_ID = "unknown_device"

        /**
         * 由上报地址派生反馈地址：同 scheme+authority，路径替换为 /feedback（或追加）。
         */
        fun deriveFeedbackUrl(centerUrl: String): String {
            val url = centerUrl.trim()
            if (url.isBlank()) return ""
            return try {
                val parsed = java.net.URI(url)
                val scheme = parsed.scheme ?: return ""
                val authority = parsed.authority ?: return ""
                val path = parsed.path.orEmpty()
                val base = path.substringBeforeLast('/', "")
                val feedbackPath = if (base.isEmpty() || base == "/") "/feedback" else "$base/feedback"
                "$scheme://$authority$feedbackPath"
            } catch (_: Exception) {
                ""
            }
        }
    }
}

/**
 * 上次所选渠道 / 引流运营的纯函数编解码（WP-27），便于 JVM 单测。
 *
 * 只保存渠道与运营名称，**绝不**保存聊天内容、会话标题或对方微信号明文（本地零隐私落盘铁律）。
 */
object LastChoiceMemory {
    private const val SEP = "\u0001"

    data class Channel(val sourceOne: String, val sourceTwo: String)

    data class Operator(val id: String, val name: String)

    fun encodeChannel(sourceOne: String, sourceTwo: String): String =
        sourceOne.trim() + SEP + sourceTwo.trim()

    fun decodeChannel(raw: String?): Channel? {
        val parts = split(raw) ?: return null
        if (parts[0].isEmpty()) return null
        return Channel(parts[0], parts[1])
    }

    fun encodeOperator(id: String?, name: String?): String =
        (id?.trim().orEmpty()) + SEP + (name?.trim().orEmpty())

    fun decodeOperator(raw: String?): Operator? {
        val parts = split(raw) ?: return null
        if (parts[1].isEmpty()) return null
        return Operator(parts[0], parts[1])
    }

    private fun split(raw: String?): List<String>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(SEP)
        if (parts.size != 2) return null
        return parts
    }
}