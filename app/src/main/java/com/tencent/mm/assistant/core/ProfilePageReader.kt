package com.tencent.mm.assistant.core

/**
 * 微信「个人资料页」（单聊/好友详情）读取器 —— 纯只读。
 *
 * 用途：当**人工**在微信里点头像进入某个人的资料页时，读取该页展示的微信号，
 * 用于中控做跨客服的同一人匹配。
 *
 * 边界：
 *   * 绝不点击头像、绝不打开页面 —— 页面由人工打开，本类只读已经显示出来的内容；
 *   * 只取「昵称 + 微信号」两个字段，不取手机号、地区、标签、朋友圈等；
 *   * 不是资料页时返回 null，不做任何推断。
 */
data class ProfileInfo(
    /** 资料页顶部的昵称/备注名。 */
    val nickname: String?,
    /** 资料页展示的微信号（可能为空：对方未公开或读取失败）。 */
    val wechatId: String?,
)

object ProfilePageReader {

    private val WXID_REGEX = Regex("""^wxid_[A-Za-z0-9_-]{3,}$""")

    /**
     * 自定义微信号。
     *
     * 实测微信允许**下划线开头**（例如 `__LINXIAO`），因此首位字符集为字母或下划线。
     * 长度按微信规则 6~20，字符集 字母/数字/下划线/减号。
     */
    private val CUSTOM_ID_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_-]{5,19}$""")

    /** 资料页上的固定标签与按钮，不能当作昵称。 */
    private val LABELS = setOf(
        "微信号", "地区", "备注", "标签", "来源", "个性签名", "更多", "返回",
        "发消息", "音视频通话", "添加到通讯录", "设置备注和标签", "朋友权限",
        "投诉", "删除", "朋友圈", "视频号", "转账", "语音通话", "分享",
        "置顶聊天", "消息免打扰", "查找聊天内容", "清空聊天记录",
    )

    /** 带冒号前缀的字段（"昵称: xxx" / "地区: xxx"），不参与昵称候选。 */
    private val FIELD_PREFIXES = listOf(
        "微信号", "昵称", "地区", "备注", "标签", "来源", "个性签名",
    )

    /** 资料页上必须出现的动作按钮（用于确认"这确实是资料页"）。 */
    private val ACTION_TEXTS = setOf(
        "发消息", "音视频通话", "添加到通讯录", "语音通话", "视频通话",
    )

    /**
     * @return 资料页信息；当前界面不是资料页时返回 null
     */
    fun find(root: NodeTree): ProfileInfo? {
        val texts = ArrayList<String>(64)
        try {
            root.forEachNode(maxDepth = PROBE_DEPTH, maxNodes = PROBE_NODES) { node, _ ->
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && t.length <= 60) texts.add(t)
            }
        } catch (_: Throwable) {
            return null
        }
        if (texts.isEmpty()) return null
        return parse(texts)
    }

    /**
     * 纯解析：从资料页上按顺序收集到的文本行中提取昵称与微信号。
     *
     * 与 Android API 解耦，便于 JVM 单测（真实页面样本见 ProfilePageReaderTest）。
     */
    fun parse(texts: List<String>): ProfileInfo? {
        if (texts.isEmpty()) return null

        // 必须同时具备：资料页动作按钮 + 微信号标签（否则不是资料页）
        val hasAction = texts.any { ACTION_TEXTS.contains(it) }
        if (!hasAction) return null

        val labelIndex = texts.indexOfFirst { it.startsWith("微信号") }
        if (labelIndex < 0) return null

        // 微信号可能在同一行（"微信号：wxid_xxx"），也可能在下一行
        var wechatId: String? = null
        val sameLine = texts[labelIndex].substringAfter("微信号").trimStart(':', '：', ' ').trim()
        if (looksLikeWechatId(sameLine)) {
            wechatId = sameLine
        } else if (labelIndex + 1 < texts.size) {
            val next = texts[labelIndex + 1].trim()
            if (looksLikeWechatId(next)) wechatId = next
        }

        val nickname = texts.firstOrNull { t ->
            t.length in 1..24 &&
                !LABELS.contains(t) &&
                !isFieldLine(t) &&
                !looksLikeWechatId(t)
        }

        return ProfileInfo(nickname = nickname, wechatId = wechatId)
    }

    /** 是否是"字段行"，如 `昵称:  小小林` / `地区:  河南  驻马店`。 */
    private fun isFieldLine(text: String): Boolean =
        FIELD_PREFIXES.any { text.startsWith(it) }

    fun looksLikeWechatId(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > 32) return false
        if (LABELS.contains(v)) return false
        // 纯数字/纯符号不当作微信号
        if (!v.any { it.isLetter() }) return false
        return WXID_REGEX.matches(v) || CUSTOM_ID_REGEX.matches(v)
    }

    private const val PROBE_DEPTH = 25
    private const val PROBE_NODES = 260
}
