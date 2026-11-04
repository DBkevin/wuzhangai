package com.tencent.mm.assistant.core

/**
 * 回传节流 / 去重状态机（纯逻辑，可单测）。
 *
 * 规则（PRD 模块 1.3 去抖动 + 模块 2.5 频率限制）：
 *  * 同一会话 2 秒内的多次 CONTENT_CHANGED 只触发一次提取 —— 由调用方做时间窗聚合，
 *    本类负责"这一份快照该不该发"。
 *  * 标题未变化且消息文本未变化 -> 不重复回传。
 *  * 同一会话两次回传间隔 >= 5 秒，除非标题发生变化。
 *  * 会话切换（sessionKey 变化）立即清空旧会话缓存。
 */
class ReportGate(
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 最近一次实际发出的快照指纹（仅在内存）。 */
    private val lastFingerprint = HashMap<String, String>()

    /** 最近一次实际发出的时间。 */
    private val lastSentAt = HashMap<String, Long>()

    /** 最近一次发出的标题，用于判断"标题是否变化"。 */
    private val lastTitle = HashMap<String, String?>()

    /** 当前活跃会话键。切换时清空旧会话缓存。 */
    var activeSessionKey: String? = null
        private set

    /**
     * 会话切换：清空上一会话的内存缓存（PRD：会话切换即清空）。
     *
     * @return true 表示确实发生了切换
     */
    fun onSessionChanged(newSessionKey: String?): Boolean {
        val old = activeSessionKey
        if (old == newSessionKey) return false
        if (old != null) {
            lastFingerprint.remove(old)
            lastSentAt.remove(old)
            lastTitle.remove(old)
        }
        activeSessionKey = newSessionKey
        return true
    }

    /** 离开微信 / 离开单聊界面：清空全部内存状态。 */
    fun reset() {
        activeSessionKey?.let {
            lastFingerprint.remove(it)
            lastSentAt.remove(it)
            lastTitle.remove(it)
        }
        activeSessionKey = null
    }

    /**
     * 判断某个快照是否应当发出。
     *
     * @param sessionKey 会话键（标题指纹）；标题读不到时用统一占位键
     * @param title 本次标题（可为 null）
     * @param messages 本次消息列表
     * @param titleChanged 调用方判断的"标题相对上次发送是否变化"
     */
    fun shouldSend(
        sessionKey: String,
        title: String?,
        messages: List<String>,
        titleChanged: Boolean,
    ): Decision {
        val fingerprint = fingerprintOf(title, messages)
        val previous = lastFingerprint[sessionKey]

        // 内容完全没变 -> 不回传
        if (previous != null && previous == fingerprint && !titleChanged) {
            return Decision.SkipUnchanged
        }

        val lastAt = lastSentAt[sessionKey]
        if (lastAt != null && !titleChanged) {
            val elapsed = now() - lastAt
            if (elapsed < MIN_INTERVAL_MS) {
                return Decision.SkipThrottled(MIN_INTERVAL_MS - elapsed)
            }
        }

        return Decision.Send
    }

    /** 在真正发出后调用，更新节流状态。 */
    fun markSent(sessionKey: String, title: String?, messages: List<String>) {
        lastFingerprint[sessionKey] = fingerprintOf(title, messages)
        lastSentAt[sessionKey] = now()
        lastTitle[sessionKey] = title
    }

    fun lastSentTitle(sessionKey: String): String? = lastTitle[sessionKey]

    sealed interface Decision {
        data object Send : Decision
        data object SkipUnchanged : Decision

        /** 被 5 秒节流挡住，[remainingMs] 后可再发。 */
        data class SkipThrottled(val remainingMs: Long) : Decision
    }

    companion object {
        /** 同一会话两次回传最小间隔。 */
        const val MIN_INTERVAL_MS = 5_000L

        /** 同一会话内容变化去抖窗口。 */
        const val DEBOUNCE_MS = 2_000L

        fun fingerprintOf(title: String?, messages: List<String>): String =
            buildString {
                append(title ?: "\u0000null")
                append('\u0001')
                messages.forEach {
                    append(it)
                    append('\u0002')
                }
            }
    }
}
