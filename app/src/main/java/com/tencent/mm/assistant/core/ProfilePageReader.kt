package com.tencent.mm.assistant.core

/**
 * 微信「个人资料页」（单聊/好友详情）读取器 —— 纯只读。
 *
 * 用途：当**人工**在微信里点头像进入某个人的资料页时，读取该页展示的微信号，
 * 用于中控做跨客服的同一人匹配。
 *
 * 边界：
 *   * 绝不点击头像、绝不打开页面 —— 页面由人工打开，本类只读已经显示出来的内容；
 *   * 只取「昵称 + 微信号 + 已展示标签/地区/电话」等页面可见文本，不遍历通讯录、不读朋友圈；
 *   * 不是资料页时返回 null，不做任何推断。
 */
data class ProfileInfo(
    /** 资料页顶部的昵称/备注名（WP-28：始终取顶部真实备注，不再被「昵称: xxx」行覆盖）。 */
    val nickname: String?,
    /** 资料页展示的微信号（可能为空：对方未公开或读取失败）。 */
    val wechatId: String?,
    /**
     * 资料页已展示的「标签」（仅读取人工已经打开页面上可见的文本，零点击零输入）。
     * 用于中控把微信标签映射为岚时项目标签（WP-26）。
     */
    val tags: List<String> = emptyList(),
    /** 资料页已展示的「地区」文本（如「河南  驻马店」）。 */
    val region: String? = null,
    /**
     * WP-28：资料页「电话 / 手机 / 描述」栏与备注行里出现的全部 11 位手机号（去重）。
     * 仅用于中控实时查重与建档透传，绝不本地持久化。
     */
    val phones: List<String> = emptyList(),
    /** WP-28：页面若存在「昵称: xxx」行，记录冒号后的原昵称；昵称展示仍以顶部备注为准。 */
    val rawNicknameLine: String? = null,
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
        // WP-28：资料页固定词，避免被误当成昵称/标签。
        "朋友资料", "更多信息", "电话", "电话号码", "手机", "描述", "共同群聊",
    )

    /** 带冒号前缀的字段（"昵称: xxx" / "地区: xxx"），不参与昵称候选。 */
    private val FIELD_PREFIXES = listOf(
        "微信号", "昵称", "地区", "备注", "标签", "来源", "个性签名",
        "电话", "手机", "描述", "更多信息", "共同群聊",
    )

    /** 11 位中国大陆手机号（前后不得紧邻数字，避免截取更长数字串）。 */
    private val PHONE_REGEX = Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""")

    /** 资料页的手机号字段名。 */
    private val PHONE_FIELD_PREFIXES = listOf("电话", "电话号码", "手机", "手机号")

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
                if (t.isNotEmpty() && t.length <= MAX_COLLECTED_TEXT_LEN) texts.add(t)
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
            t.length in 1..MAX_NICKNAME_LEN &&
                !LABELS.contains(t) &&
                !isFieldLine(t) &&
                !looksLikeWechatId(t) &&
                !isPhoneFieldLine(t) &&
                !PHONE_REGEX.matches(t.trim())
        }

        // WP-28：资料页「电话 / 手机 / 描述」栏或备注里的 11 位手机号（去重保序）。
        val phones = texts.flatMap { text ->
            PHONE_REGEX.findAll(text).map { it.value }.toList()
        }.distinct()

        // 「昵称: xxx」行只作为原昵称留痕，展示名仍以顶部真实备注为准。
        val rawNicknameLine = texts.firstOrNull { it.startsWith("昵称") }
            ?.removePrefix("昵称")
            ?.trimStart(':', '：', ' ')
            ?.trim()
            ?.ifEmpty { null }

        return ProfileInfo(
            nickname = nickname,
            wechatId = wechatId,
            tags = parseTags(texts),
            region = parseRegion(texts),
            phones = phones,
            rawNicknameLine = rawNicknameLine,
        )
    }

    /** 「电话 / 手机 / 电话号码」字段行。 */
    private fun isPhoneFieldLine(text: String): Boolean {
        val trimmed = text.trim()
        return PHONE_FIELD_PREFIXES.any { trimmed == it || trimmed.startsWith("$it:") || trimmed.startsWith("$it：") }
    }

    /**
     * 解析资料页「标签」：兼容同行（`标签: A、B`）与下一行两种布局。
     * 只读取已展示文本，绝不点击「设置备注和标签」。
     */
    private fun parseTags(texts: List<String>): List<String> {
        val index = texts.indexOfFirst { it == "标签" || it.startsWith("标签:") || it.startsWith("标签：") }
        if (index < 0) return emptyList()
        val sameLine = texts[index].removePrefix("标签").trimStart(':', '：', ' ').trim()
        val raw = when {
            sameLine.isNotEmpty() -> sameLine
            index + 1 < texts.size -> texts[index + 1].trim()
            else -> return emptyList()
        }
        if (raw.isEmpty() || ACTION_TEXTS.contains(raw) || LABELS.contains(raw)) return emptyList()
        return raw.split(',', '，', '、', '/', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TAG_LEN }
            .filter { !ACTION_TEXTS.contains(it) && !LABELS.contains(it) }
            .distinct()
    }

    private fun parseRegion(texts: List<String>): String? {
        val line = texts.firstOrNull { it == "地区" || it.startsWith("地区:") || it.startsWith("地区：") } ?: return null
        val value = line.removePrefix("地区").trimStart(':', '：', ' ').trim()
        return value.ifEmpty { null }
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

    /**
     * 收集文本行的长度上限。
     *
     * 微信允许超长备注（实测有人用整句做备注），过小的上限会把长备注整行丢掉，
     * 导致昵称解析失败。200 足够覆盖正常备注，同时仍能排除异常长文本。
     */
    private const val MAX_COLLECTED_TEXT_LEN = 200

    /** 昵称/备注长度上限：放宽到 64，避免长备注被丢弃。 */
    private const val MAX_NICKNAME_LEN = 64

    /** 单个标签长度上限（过滤异常长文本）。 */
    private const val MAX_TAG_LEN = 32
}
