package com.tencent.mm.assistant.core

/**
 * 一次"当前前台单聊窗口"的只读快照。
 *
 * 字段严格限定为 PRD 允许回传的四类信息：
 *  1. 客服账号标识（serviceAccountId）
 *  2. 会话标题文本（title，读不到时为 null）
 *  3. 最近 N 条可见消息文本（recentMessages）
 *  4. 时间戳（timestamp）
 *
 * 另外附带 read_status 供中控判断降级，但不含任何其他界面/通讯录/非当前会话内容。
 * 该对象只存在于内存，随会话切换被丢弃，不落盘。
 */
data class SessionSnapshot(
    /** 会话标题：读不到时为 null，由中控决定降级。 */
    val title: String?,
    /** 最近 N 条可见消息文本，按可见顺序（旧 -> 新）。 */
    val recentMessages: List<String>,
    /** full = 标题与消息都按预期取到；partial = 标题缺失或消息节点被混淆。 */
    val readStatus: ReadStatus,
    /** 采集时刻（毫秒）。 */
    val timestamp: Long,
    /** 该快照所属会话的去重键（标题指纹），用于会话切换时清空内存缓存。 */
    val sessionKey: String,
)

enum class ReadStatus(val wireValue: String) {
    FULL("full"),
    PARTIAL("partial"),
    ;

    companion object {
        fun fromWire(value: String?): ReadStatus =
            entries.firstOrNull { it.wireValue == value } ?: PARTIAL
    }
}

/**
 * 采集结果包装：区分「不是单聊界面」「读取失败」「读取成功」三种情况。
 */
sealed interface ExtractResult {

    /** 当前窗口不是单聊界面（群聊 / 公众号 / 服务号 / 朋友圈 / 聊天列表等），直接跳过。 */
    data object NotSingleChat : ExtractResult

    /** 是单聊界面，但标题节点缺失或为空 -> title=null, read_status=partial。 */
    data class TitleUnreadable(
        val recentMessages: List<String>,
        val sessionKey: String,
    ) : ExtractResult

    /** 消息节点被混淆（节点存在但文本为空）-> 只回传标题, read_status=partial。 */
    data class MessagesObfuscated(
        val title: String,
        val sessionKey: String,
    ) : ExtractResult

    /** 正常读取。 */
    data class Success(
        val title: String,
        val recentMessages: List<String>,
        val sessionKey: String,
    ) : ExtractResult
}
