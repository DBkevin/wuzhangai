package com.tencent.mm.assistant.util

import android.util.Log

/**
 * 本地日志。
 *
 * 隐私边界（PRD 模块 4.3）：只记录
 *  * 时间戳
 *  * 包名
 *  * read_status
 *  * HTTP 状态码
 *
 * 绝不记录聊天文本、会话标题、微信号明文、中控响应正文。
 * 因此本类的所有公开方法签名都刻意不接收"任意自由文本"作为内容字段，
 * 只有受控的枚举式事件名与数值。
 */
class SafeLogger(private val settings: SettingsStore) {

    /** 读取事件：包名 + read_status。 */
    fun readEvent(packageName: String, readStatus: String) {
        write("read", "pkg=$packageName read_status=$readStatus")
    }

    /** 提取被跳过（例如判定为群聊/非单聊）。不记录任何界面文本。 */
    fun skipped(reason: String) {
        write("skip", "reason=$reason")
    }

    /** HTTP 结果：状态码 + 第几次尝试。不含 URL 中的敏感查询串，不含响应体。 */
    fun http(url: String, code: Int, attempt: Int) {
        write("http", "host=${hostOf(url)} code=$code attempt=$attempt")
    }

    /** 受控事件名（如 overlay_shown / overlay_dismissed / service_connected）。 */
    fun event(name: String) {
        write("event", "name=$name")
    }

    private fun write(tag: String, detail: String) {
        if (!settings.localLogEnabled) return
        try {
            // 只进 logcat 与内存环形日志，不落盘任何聊天内容。
            Log.i(LOG_TAG, "$tag $detail")
            RingLog.add("$tag $detail")
        } catch (_: Throwable) {
            // 日志失败绝不影响主流程
        }
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: "-"
    } catch (_: Exception) {
        "-"
    }

    companion object {
        const val LOG_TAG = "KfReadOnly"

        fun snapshot(): List<String> = RingLog.snapshot()
    }
}

/** 内存环形日志：仅保留最近若干条，进程结束即消失（不落盘）。 */
private object RingLog {
    private const val CAPACITY = 200
    private val buffer = ArrayDeque<String>(CAPACITY)

    @Synchronized
    fun add(line: String) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()
}
