package com.tencent.mm.assistant

import com.tencent.mm.assistant.core.MessageTextFilter
import com.tencent.mm.assistant.core.ReportGate
import com.tencent.mm.assistant.core.StatusBarFilter
import com.tencent.mm.assistant.net.CenterAction
import com.tencent.mm.assistant.net.CenterResponse
import com.tencent.mm.assistant.util.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：验证回传节流、文本过滤、协议解析、反馈地址派生。
 * 不依赖 Android 运行时，也不触碰任何微信数据。
 */
class CoreLogicTest {

    // ------------------------------------------------------------ ReportGate

    @Test
    fun `same content within interval is not resent`() {
        var now = 1_000_000L
        val gate = ReportGate(now = { now })

        assertTrue(gate.onSessionChanged("王强"))
        assertTrue(gate.shouldSend("王强", "王强", listOf("在吗"), titleChanged = false) is ReportGate.Decision.Send)
        gate.markSent("王强", "王强", listOf("在吗"))

        now += 1_000
        val decision = gate.shouldSend("王强", "王强", listOf("在吗"), titleChanged = false)
        assertTrue(decision is ReportGate.Decision.SkipUnchanged)
    }

    @Test
    fun `new content within five seconds is throttled`() {
        var now = 1_000_000L
        val gate = ReportGate(now = { now })

        assertTrue(gate.onSessionChanged("王强"))
        gate.markSent("王强", "王强", listOf("在吗"))

        now += 1_000
        val decision = gate.shouldSend("王强", "王强", listOf("在吗", "订单12345"), titleChanged = false)
        assertTrue(decision is ReportGate.Decision.SkipThrottled)
    }

    @Test
    fun `title change bypasses throttle`() {
        var now = 1_000_000L
        val gate = ReportGate(now = { now })

        assertTrue(gate.onSessionChanged("王强"))
        gate.markSent("王强", "王强", listOf("在吗"))

        now += 1_000
        val decision = gate.shouldSend("王强", "李娜", listOf("在吗"), titleChanged = true)
        assertTrue(decision is ReportGate.Decision.Send)
    }

    @Test
    fun `session switch clears cache`() {
        var now = 1_000_000L
        val gate = ReportGate(now = { now })

        assertTrue(gate.onSessionChanged("王强"))
        gate.markSent("王强", "王强", listOf("在吗"))

        assertTrue(gate.onSessionChanged("李娜"))
        assertTrue(gate.shouldSend("李娜", "李娜", listOf("你好"), titleChanged = true) is ReportGate.Decision.Send)

        // 切回旧会话后缓存已清空，立即可发
        assertTrue(gate.onSessionChanged("王强"))
        assertTrue(gate.shouldSend("王强", "王强", listOf("在吗"), titleChanged = false) is ReportGate.Decision.Send)
    }

    // ------------------------------------------------------------ 文本过滤

    @Test
    fun `timestamps and placeholders are filtered`() {
        assertTrue(MessageTextFilter.isTimestamp("12:30"))
        assertTrue(MessageTextFilter.isTimestamp("今天 09:05"))
        assertTrue(MessageTextFilter.isTimestamp("昨天"))
        assertTrue(MessageTextFilter.isTimestamp("3月5日 14:20"))
        assertFalse(MessageTextFilter.isTimestamp("订单12345"))

        assertTrue(MessageTextFilter.isPlaceholder("[图片]"))
        assertTrue(MessageTextFilter.isPlaceholder("[动画表情]"))
        assertTrue(MessageTextFilter.isPlaceholder("【链接】"))
        assertFalse(MessageTextFilter.isPlaceholder("[[]]"))

        assertTrue(StatusBarFilter.isStatusBarNoise("15:44"))
        assertTrue(StatusBarFilter.isStatusBarNoise("87%"))
    }

    @Test
    fun `system hints and ui noise are filtered`() {
        assertTrue(MessageTextFilter.isSystemHint("你撤回了一条消息"))
        assertTrue(MessageTextFilter.isSystemHint("对方开启了朋友验证"))
        assertTrue(MessageTextFilter.isSystemHint("张三邀请了李四加入了群聊"))
        assertTrue(MessageTextFilter.isUiNoise("按住 说话"))
        assertFalse(MessageTextFilter.isSystemHint("在吗"))
        assertTrue(MessageTextFilter.isDisplayableMessage("我想问下订单12345"))
        assertFalse(MessageTextFilter.isDisplayableMessage("12:30"))
        assertFalse(MessageTextFilter.isDisplayableMessage("[动画表情]"))
    }

    // ------------------------------------------------------------ 协议解析

    @Test
    fun `center response parses actions and caps candidates`() {
        val json = """
            {
              "action": "show_candidates",
              "hint": null,
              "candidates": [
                {"label": "客服A下的同名备注", "confidence": 0.82, "evidence": "相同手机号138****1234"},
                {"label": "b", "confidence": 0.5, "evidence": "e2"},
                {"label": "c", "confidence": 0.3, "evidence": "e3"},
                {"label": "d", "confidence": 0.1, "evidence": "e4"}
              ],
              "request_id": "req-1"
            }
        """.trimIndent()

        val response = CenterResponse.parse(json)
        assertNotNull(response)
        assertEquals(CenterAction.SHOW_CANDIDATES, response!!.action)
        assertEquals(3, response.candidates.size)
        assertEquals(0.82, response.candidates[0].confidence, 0.0001)
        assertEquals("req-1", response.requestId)
    }

    @Test
    fun `malformed json yields null instead of throwing`() {
        assertNull(CenterResponse.parse("not-json{"))
        assertNull(CenterResponse.parse(""))
    }

    @Test
    fun `no_match and none actions map correctly`() {
        assertEquals(
            CenterAction.NO_MATCH,
            CenterResponse.parse("""{"action":"no_match","request_id":"r"}""")!!.action,
        )
        assertEquals(
            CenterAction.NONE,
            CenterResponse.parse("""{"action":"unknown_action"}""")!!.action,
        )
        assertEquals(
            CenterAction.NONE,
            CenterResponse.parse("""{}""")!!.action,
        )
    }

    // ------------------------------------------------------------ 地址派生

    @Test
    fun `feedback url derived from center url`() {
        assertEquals(
            "http://10.0.0.5:8080/feedback",
            SettingsStore.deriveFeedbackUrl("http://10.0.0.5:8080/ingest"),
        )
        assertEquals(
            "https://center.example.com/feedback",
            SettingsStore.deriveFeedbackUrl("https://center.example.com"),
        )
        assertEquals("", SettingsStore.deriveFeedbackUrl(""))
        assertEquals("", SettingsStore.deriveFeedbackUrl("not a url"))
    }
}
