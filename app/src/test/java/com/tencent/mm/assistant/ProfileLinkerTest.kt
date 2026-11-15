package com.tencent.mm.assistant

import com.tencent.mm.assistant.core.ProfileLinker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 身份关联（资料页 ↔ 来源会话）的纯逻辑单测。
 *
 * 验证的是"时间邻接"判定：只有刚从某个单聊进入资料页，才把资料页绑定到该会话。
 * 不需要真机，也不接触任何微信数据。
 */
class ProfileLinkerTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `links profile to chat seen just before`() {
        val linker = ProfileLinker()
        linker.onChatSeen("一滴相思泪", t0)

        assertEquals("一滴相思泪", linker.sourceTitleFor(t0 + 3_000))
    }

    @Test
    fun `no link when window exceeded`() {
        val linker = ProfileLinker(windowMs = 60_000)
        linker.onChatSeen("一滴相思泪", t0)

        // 窗口内
        assertEquals("一滴相思泪", linker.sourceTitleFor(t0 + 59_999))
        // 超出窗口
        assertNull(linker.sourceTitleFor(t0 + 60_001))
    }

    @Test
    fun `no link without any chat seen`() {
        val linker = ProfileLinker()
        assertNull(linker.sourceTitleFor(t0))
    }

    @Test
    fun `blank title is ignored`() {
        val linker = ProfileLinker()
        linker.onChatSeen("   ", t0)
        assertNull(linker.sourceTitleFor(t0 + 1_000))

        linker.onChatSeen(null, t0)
        assertNull(linker.sourceTitleFor(t0 + 1_000))
    }

    @Test
    fun `latest chat wins`() {
        val linker = ProfileLinker()
        linker.onChatSeen("会话A", t0)
        linker.onChatSeen("会话B", t0 + 10_000)

        assertEquals("会话B", linker.sourceTitleFor(t0 + 11_000))
    }

    @Test
    fun `reset clears the link`() {
        val linker = ProfileLinker()
        linker.onChatSeen("会话A", t0)
        linker.reset()

        assertNull(linker.sourceTitleFor(t0 + 1_000))
    }

    @Test
    fun `clock going backwards does not link`() {
        val linker = ProfileLinker()
        linker.onChatSeen("会话A", t0)

        // 时钟回拨：elapsed 为负，视为无效，避免异常绑定
        assertNull(linker.sourceTitleFor(t0 - 5_000))
    }

    @Test
    fun `default window is five minutes`() {
        assertEquals(5 * 60 * 1000L, ProfileLinker.DEFAULT_WINDOW_MS)
    }
}
