package com.tencent.mm.assistant.core

/**
 * 资料页 ↔ 来源会话 的绑定判定（纯逻辑，可单测）。
 *
 * 背景：资料页只能由人工从某个单聊点进去（App 绝不点击、不跳转）。
 * 因此在时间窗内"刚看过会话 A"紧接着"出现资料页"，即可认定该资料页属于 A。
 * 这是结构性的因果关系，比昵称字符串匹配可靠（备注名与昵称常常不同）。
 *
 * 这里刻意不依赖 Android API，便于用 JVM 单测覆盖边界。
 */
class ProfileLinker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    private var lastTitle: String? = null
    private var lastAt: Long = 0L

    /** 记录"刚刚看过某个单聊"。 */
    fun onChatSeen(title: String?, now: Long) {
        if (title.isNullOrBlank()) return
        lastTitle = title
        lastAt = now
    }

    /** 离开微信时清空，避免隔了很久跨会话误绑。 */
    fun reset() {
        lastTitle = null
        lastAt = 0L
    }

    /**
     * 资料页出现时，返回其来源会话标题。
     *
     * @return 时间窗内的最近会话标题；超窗或没有记录时返回 null
     */
    fun sourceTitleFor(now: Long): String? {
        val title = lastTitle ?: return null
        if (title.isBlank()) return null
        val elapsed = now - lastAt
        // 负数（时钟回拨）也视为无效，避免异常绑定
        if (elapsed < 0 || elapsed > windowMs) return null
        return title
    }

    companion object {
        /**
         * 绑定时间窗：5 分钟。
         * 足够覆盖"点开资料页看一眼再回来"的操作习惯，
         * 又能避免隔很久之后误绑到别的会话。
         */
        const val DEFAULT_WINDOW_MS = 5 * 60 * 1000L
    }
}
