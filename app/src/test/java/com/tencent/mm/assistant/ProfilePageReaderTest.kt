package com.tencent.mm.assistant

import com.tencent.mm.assistant.core.ProfilePageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资料页解析单测。
 *
 * 样本来自 2026-11-16 在 Redmi K70 上对微信资料页的真实 uiautomator 导出：
 *
 *   大宝贝儿
 *   昵称:  小小林
 *   微信号:  __LINXIAO
 *   地区:  河南  驻马店
 *   发消息
 *   音视频通话
 *
 * 注意：微信允许**下划线开头**的自定义微信号（__LINXIAO），这是最容易漏的格式。
 */
class ProfilePageReaderTest {

    private val reader = ProfilePageReader

    private fun realPage(wechatId: String) = listOf(
        "大宝贝儿",
        "昵称:  小小林",
        "微信号:  $wechatId",
        "地区:  河南  驻马店",
        "发消息",
        "音视频通话",
    )

    @Test
    fun `parses real page with underscore-leading id`() {
        val info = reader.parse(realPage("__LINXIAO"))
        assertNotNull(info)
        assertEquals("__LINXIAO", info!!.wechatId)
        // 备注名优先作为昵称
        assertEquals("大宝贝儿", info.nickname)
    }

    @Test
    fun `parses wxid style id`() {
        val info = reader.parse(realPage("wxid_bt7097pan2cf22_6445"))
        assertNotNull(info)
        assertEquals("wxid_bt7097pan2cf22_6445", info!!.wechatId)
    }

    @Test
    fun `parses plain custom id`() {
        val info = reader.parse(realPage("hxzxxy999"))
        assertNotNull(info)
        assertEquals("hxzxxy999", info!!.wechatId)
    }

    @Test
    fun `parses id on next line`() {
        val texts = listOf(
            "张三",
            "微信号",
            "wxid_abc123",
            "发消息",
        )
        val info = reader.parse(texts)
        assertNotNull(info)
        assertEquals("wxid_abc123", info!!.wechatId)
    }

    @Test
    fun `returns null when not a profile page`() {
        // 没有"发消息"等动作按钮 -> 不是资料页
        assertNull(reader.parse(listOf("张三", "微信号:  abc123456")))
        // 没有"微信号"标签 -> 不是资料页
        assertNull(reader.parse(listOf("张三", "发消息", "音视频通话")))
        // 空
        assertNull(reader.parse(emptyList()))
    }

    @Test
    fun `id with no letters is rejected`() {
        // 纯数字不是微信号（避免把手机号/编号误判）
        val info = reader.parse(realPage("13800138000"))
        assertNotNull(info)
        assertNull(info!!.wechatId)
    }

    @Test
    fun `field lines are not treated as nickname`() {
        val texts = listOf(
            "昵称:  小小林",
            "微信号:  __LINXIAO",
            "地区:  河南  驻马店",
            "发消息",
        )
        val info = reader.parse(texts)
        assertNotNull(info)
        assertNull(info!!.nickname)
    }

    @Test
    fun `recognises wechat id formats`() {
        assertTrue(reader.looksLikeWechatId("wxid_bt7097pan2cf22_6445"))
        assertTrue(reader.looksLikeWechatId("hxzxxy999"))
        assertTrue(reader.looksLikeWechatId("__LINXIAO"))
        assertTrue(reader.looksLikeWechatId("a_b-c123"))

        assertFalse(reader.looksLikeWechatId("13800138000"))
        assertFalse(reader.looksLikeWechatId("发消息"))
        assertFalse(reader.looksLikeWechatId(""))
        assertFalse(reader.looksLikeWechatId("ab"))          // 太短
        assertFalse(reader.looksLikeWechatId("a".repeat(30))) // 太长
    }

    // ------------------------------------------------------------ 超长备注

    @Test
    fun `long remark is read in full`() {
        // 微信允许把整句作为备注，不能被长度上限截断或丢弃
        val longRemark = "王强-8231-沪-微信hxzxxy999-订单12345-2026春季复诊-备注很长很长很长"
        val texts = listOf(
            longRemark,
            "昵称:  小小林",
            "微信号:  hxzxxy999",
            "地区:  上海  浦东",
            "发消息",
            "音视频通话",
        )
        val info = reader.parse(texts)
        assertNotNull(info)
        assertEquals(longRemark, info!!.nickname)
        assertEquals("hxzxxy999", info.wechatId)
    }

    @Test
    fun `remark up to 64 chars is accepted`() {
        val remark64 = "备".repeat(64)
        val info = reader.parse(
            listOf(remark64, "微信号:  hxzxxy999", "发消息"),
        )
        assertNotNull(info)
        assertEquals(remark64, info!!.nickname)
    }

    @Test
    fun `absurdly long text is not treated as nickname`() {
        val tooLong = "备".repeat(65)
        val info = reader.parse(
            listOf(tooLong, "微信号:  hxzxxy999", "发消息"),
        )
        assertNotNull(info)
        assertNull(info!!.nickname)
    }
}
