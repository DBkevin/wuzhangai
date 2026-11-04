package com.tencent.mm.assistant.core

/**
 * 消息文本过滤器：把"看起来是消息、其实不是用户说的话"的节点剔除。
 *
 * 只做只读文本判断，不涉及任何界面操作。
 */
object MessageTextFilter {

    /** 纯时间戳，如 12:30 / 12:30:45 / 今天 12:30 / 昨天 / 星期三 / 3月5日 等。 */
    private val TIMESTAMP_REGEXES = listOf(
        Regex("""^\d{1,2}:\d{2}(:\d{2})?$"""),
        Regex("""^(上午|下午|凌晨|中午|晚上)\s*\d{1,2}:\d{2}$"""),
        Regex("""^今天\s*\d{1,2}:\d{2}$"""),
        Regex("""^昨天\s*\d{1,2}:\d{2}$"""),
        Regex("""^前天\s*\d{1,2}:\d{2}$"""),
        Regex("""^星期[一二三四五六日天]\s*\d{1,2}:\d{2}$"""),
        Regex("""^\d{1,2}月\d{1,2}日\s*(\d{1,2}:\d{2})?$"""),
        Regex("""^\d{4}年\d{1,2}月\d{1,2}日\s*(\d{1,2}:\d{2})?$"""),
        Regex("""^(今天|昨天|前天|星期[一二三四五六日天])$"""),
        Regex("""^\d{4}-\d{2}-\d{2}\s*(\d{2}:\d{2})?$"""),
    )

    /** 纯占位符，如 [图片] / [语音] / [动画表情]。 */
    private val PLACEHOLDER_REGEXES = listOf(
        Regex("""^\[[^\[\]]{1,8}]$"""),
        Regex("""^【[^【】]{1,8}】$"""),
    )

    /** 微信系统提示（含撤回、验证、群操作等）。 */
    private val SYSTEM_HINT_REGEXES = listOf(
        Regex(""".*撤回了一条消息.*"""),
        Regex(""".*已添加.*为好友.*"""),
        Regex(""".*开启了朋友验证.*"""),
        Regex(""".*通过了好友验证.*"""),
        Regex(""".*以上是打招呼的内容.*"""),
        Regex(""".*加入了群聊.*"""),
        Regex(""".*邀请.*加入了群聊.*"""),
        Regex(""".*移出了群聊.*"""),
        Regex(""".*你已添加了.*"""),
        Regex(""".*现在是.*的朋友.*"""),
        Regex(""".*消息已发出，但被对方拒收了.*"""),
        Regex(""".*对方开启了好友验证.*"""),
        Regex(""".*该消息类型暂不能展示.*"""),
        Regex(""".*此消息已过期.*"""),
        Regex(""".*拍了拍.*"""),
    )

    private val SYSTEM_HINT_EXACTS = setOf(
        "查看更多消息", "以下为新消息", "更多消息", "对方正在输入",
        "该内容不支持展示", "无法查看", "复制", "转发", "引用",
    )

    /** 微信底部输入栏 / 工具栏常见文案，避免误当消息。 */
    private val UI_NOISE_EXACTS = setOf(
        "按住 说话", "按住说话", "按住 说话 ", "发送", "表情", "语音",
        "更多", "聊天信息", "返回", "关闭", "搜索", "取消", "确定",
    )

    fun isTimestamp(text: String): Boolean =
        TIMESTAMP_REGEXES.any { it.matches(text) }

    fun isPlaceholder(text: String): Boolean =
        PLACEHOLDER_REGEXES.any { it.matches(text) }

    fun isSystemHint(text: String): Boolean {
        if (SYSTEM_HINT_EXACTS.contains(text)) return true
        // 中性系统提示通常居中且以「」或【】包裹，或整条以"你"开头描述动作
        return SYSTEM_HINT_REGEXES.any { it.matches(text) }
    }

    fun isUiNoise(text: String): Boolean = UI_NOISE_EXACTS.contains(text)

    /**
     * 是否可以作为一条"顾客/客服说的话"回传。
     */
    fun isDisplayableMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.length > MAX_MESSAGE_LENGTH) return false
        if (isTimestamp(t)) return false
        if (isPlaceholder(t)) return false
        if (isSystemHint(t)) return false
        if (isUiNoise(t)) return false
        // 纯符号/纯数字串（如红点计数、未读角标）
        if (t.matches(Regex("""^[\d\s.,:;\-+%]+$"""))) return false
        if (t.matches(Regex("""^[^\p{L}\p{N}]+$"""))) return false
        return true
    }

    const val MAX_MESSAGE_LENGTH = 500
}

/** 状态栏 / 导航栏噪声过滤（电量、时间、运营商等）。 */
object StatusBarFilter {

    private val NOISE_REGEXES = listOf(
        Regex("""^\d{1,2}:\d{2}$"""),
        Regex("""^\d{1,3}%$"""),
        Regex("""^\d+(\.\d+)?\s*(KB/s|MB/s|M|K|G|KB|MB)$"""),
        Regex("""^(WIFI|Wi-Fi|4G|5G|3G|2G|LTE|HD|VoLTE|NR)$"""),
        Regex("""^[★☆•·.\s]*$"""),
    )

    private val NOISE_EXACTS = setOf(
        "中国移动", "中国联通", "中国电信", "无服务", "仅限紧急呼叫",
    )

    fun isStatusBarNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (NOISE_EXACTS.contains(t)) return true
        return NOISE_REGEXES.any { it.matches(t) }
    }
}
