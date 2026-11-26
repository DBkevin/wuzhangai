package com.tencent.mm.assistant

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 从微信「我」页读取"当前登录账号的微信号"。
 *
 * 严格的只读行为：
 *  * 仅在「我」页面上取一个标识字段；
 *  * 不遍历通讯录、不读好友列表、不读会话列表、不读任何聊天内容；
 *  * 找不到就返回 null，调用方回退到设置页里手工填写的客服账号别名。
 *
 * 微信微信号的两种形态：
 *  1. 用户自定义 ID：6~20 位，字母或下划线开头（如 `__LINXIAO`），可含字母/数字/下划线/减号
 *  2. 系统默认 ID：wxid_xxxxxx
 * 「我」页上微信号通常紧邻"微信号"标签，或直接展示为一行文本。
 */
object WechatIdReader {

    private val WXID_REGEX = Regex("""^wxid_[A-Za-z0-9_-]{3,}$""")
    private val CUSTOM_ID_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_-]{5,19}$""")

    /** 明确排除的文案（微信「我」页固有条目，长得像 ID 但不是）。 */
    private val EXCLUDED = setOf(
        "微信号", "朋友圈", "收藏", "卡包", "表情", "设置", "服务", "视频号",
        "拍一拍", "状态", "更多", "微信", "我",
    )

    /** 「我」页特征文案：出现其中若干个才认为当前是「我」页，避免误读聊天内容。 */
    private val PROFILE_PAGE_MARKERS = setOf(
        "朋友圈", "收藏", "卡包", "表情", "设置", "服务", "视频号", "拍一拍", "状态",
    )

    /**
     * @return 微信号，或 null（不是「我」页 / 未找到）
     */
    fun find(root: AccessibilityNodeInfo): String? {
        var markerHits = 0
        var labeledId: String? = null
        var standaloneIds = LinkedHashSet<String>()

        walk(root, 0) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@walk

            if (PROFILE_PAGE_MARKERS.contains(text)) {
                markerHits++
                return@walk
            }
            // 形如 "微信号：wxid_abc123"
            if (text.startsWith("微信号")) {
                val id = text.substringAfter("微信号").trimStart(':', '：', ' ').trim()
                if (looksLikeWechatId(id)) labeledId = id
                return@walk
            }
            if (EXCLUDED.contains(text)) return@walk
            if (looksLikeWechatId(text)) standaloneIds.add(text)
        }

        // 必须是「我」页：至少命中 2 个特征条目
        if (markerHits < 2) return null

        labeledId?.let { return it }
        return standaloneIds.firstOrNull()
    }

    internal fun looksLikeWechatId(value: String): Boolean {
        if (value.isEmpty() || value.length > 32) return false
        if (EXCLUDED.contains(value)) return false
        return WXID_REGEX.matches(value) || CUSTOM_ID_REGEX.matches(value)
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, action: (AccessibilityNodeInfo) -> Unit) {
        if (depth > MAX_DEPTH) return
        action(node)
        val count = node.childCount
        for (i in 0 until count) {
            if (i > MAX_CHILDREN_PER_NODE) break
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, action)
        }
    }

    private const val MAX_DEPTH = 25
    private const val MAX_CHILDREN_PER_NODE = 30
    /**
     * 从当前单聊页面读取「本人（员工）昵称」。
     *
     * 只读判据（docs/04 §2.2）：右半屏（`centerX >= width/2`）气泡的头像节点，
     * 其 contentDescription 形如 `"<昵称>头像"`；取不到返回 null，绝不做任何点击/输入。
     */
    fun findSelfNickname(root: AccessibilityNodeInfo, screenWidth: Int): String? {
        if (screenWidth <= 0) return null
        val half = screenWidth / 2
        var found: String? = null
        walk(root, 0) { node ->
            if (found != null) return@walk
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (!desc.endsWith("头像") || desc.length <= 2) return@walk
            val bounds = android.graphics.Rect()
            if (!node.getBoundsInScreen(bounds)) return@walk
            if (bounds.centerX() < half) return@walk
            val name = desc.removeSuffix("头像").trim()
            if (name.isNotEmpty()) found = name
        }
        return found
    }
}