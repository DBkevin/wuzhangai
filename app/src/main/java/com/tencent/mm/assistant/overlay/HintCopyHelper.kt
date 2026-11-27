package com.tencent.mm.assistant.overlay

/**
 * 悬浮窗「复制」语义的纯函数集合（WP-26 T5）。
 *
 * 为什么单独抽出来：
 *   * 复制按钮的行为（纯数字档案号 / ` #档案号` 备注码 / 剥前缀的话术正文）必须可被 JVM 单测锁定；
 *   * 这里只做字符串处理，**不**触碰 Android ClipboardManager，更不涉及任何微信输入事件；
 *     真正写剪贴板的动作仍然只发生在 [HintOverlayManager] 的 ClipboardManager 调用里。
 */
object HintCopyHelper {

    /** 岚时/中枢档案号：`#` + 10~13 位数字（兼容历史 11 位与现行 12 位）。 */
    private val DOSSIER_NO = Regex("""#\s*(\d{10,13})""")

    /** 话术行前缀：`💬【话术建议】` 之后才是可复制的纯话术正文。 */
    private val SCRIPT_PREFIX = Regex("""^💬【话术建议】\s*""")

    /** 状态行前缀：这些行只用于提示，不提供复制按钮。 */
    private val STATUS_PREFIXES = listOf("✅", "⚠️", "📌", "🎯", "ℹ️", "🆕", "❌", "🔔")

    /** 状态字段行前缀：归属/资产/来源等派生事实，不提供复制按钮。 */
    private val STATUS_FIELD_PREFIXES = listOf(
        "归属：", "资产：", "来源：", "余次：", "摘要：", "保护期",
    )

    /** 从提示正文中提取档案号（纯数字，不含 `#`）。 */
    fun extractDossierNo(hint: String?): String? {
        val text = hint?.trim().orEmpty()
        if (text.isEmpty()) return null
        return DOSSIER_NO.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 「复制档案号」按钮的剪贴板内容：纯数字（如 `25070860884`）。 */
    fun dossierCopyText(hint: String?): String? = extractDossierNo(hint)

    /** 「复制#备注码」按钮的剪贴板内容：` #25070860884`（可直接粘到微信备注末尾）。 */
    fun remarkCopyText(hint: String?): String? = extractDossierNo(hint)?.let { " #$it" }

    /** 该行是否应隐藏右侧复制按钮（状态行/空行）。 */
    fun isStatusLine(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return true
        if (STATUS_PREFIXES.any { text.startsWith(it) }) return true
        return STATUS_FIELD_PREFIXES.any { text.startsWith(it) }
    }

    /**
     * 列表行可复制的正文：
     *   * 话术行 → 剥离 `💬【话术建议】` 前缀后的纯话术；
     *   * 状态行 → null（不渲染复制按钮）；
     *   * 其他行 → 原文。
     */
    fun copyableRowText(line: String): String? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val script = SCRIPT_PREFIX.find(text)
        if (script != null) {
            return text.substring(script.range.last + 1).trim().ifEmpty { null }
        }
        if (isStatusLine(text)) return null
        return text
    }
}
