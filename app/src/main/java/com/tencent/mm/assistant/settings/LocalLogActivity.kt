package com.tencent.mm.assistant.settings

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tencent.mm.assistant.AssistantApp
import com.tencent.mm.assistant.R

/**
 * 本地日志查看页。
 *
 * 展示 SafeLogger 的内存环形日志。日志内容在生成时就已受限：
 * 只有时间戳、包名、read_status、HTTP 状态码与受控事件名，
 * 永远不包含聊天文本、会话标题、微信号明文或响应正文。
 */
class LocalLogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val logger = AssistantApp.global?.logger
        val lines = logger?.snapshot().orEmpty()

        val text = TextView(this).apply {
            textSize = 12f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            text = if (lines.isEmpty()) {
                getString(R.string.log_empty)
            } else {
                lines.joinToString(separator = "\n")
            }
        }

        val scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
