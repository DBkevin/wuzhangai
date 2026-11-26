package com.tencent.mm.assistant

import com.tencent.mm.assistant.net.ReadReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-14：一机微信双开（主号 + 旧号）只读协议契约测试。
 *
 * 只覆盖纯 JVM 可验证的部分（正则判定 + 上报 JSON 字段），
 * 不触碰任何 AccessibilityService / 点击 / 输入 / 截图 API：
 * 本测试存在的意义是「切换账号后上报字段必须正确」，而非模拟微信界面。
 */
class DualWechatProtocolTest {

    @Test
    fun customWechatIdAcceptsUnderscoreLeading() {
        // v1.0.1 缺陷：`__LINXIAO` 这类合法微信号被 CUSTOM_ID_REGEX 漏掉。
        assertTrue(WechatIdReader.looksLikeWechatId("__LINXIAO"))
        assertTrue(WechatIdReader.looksLikeWechatId("_abc123"))
        assertTrue(WechatIdReader.looksLikeWechatId("wxid_abc123"))
        assertTrue(WechatIdReader.looksLikeWechatId("linxiao_2026"))
    }

    @Test
    fun customWechatIdRejectsIllegalValues() {
        assertFalse(WechatIdReader.looksLikeWechatId(""))
        assertFalse(WechatIdReader.looksLikeWechatId("123456"))
        assertFalse(WechatIdReader.looksLikeWechatId("a"))
        assertFalse(WechatIdReader.looksLikeWechatId("微信号"))
        assertFalse(WechatIdReader.looksLikeWechatId("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(WechatIdReader.looksLikeWechatId("13800000011"))
    }

    @Test
    fun reportCarriesWechatIdAndSelfNickname() {
        val report = ReadReport(
            serviceAccountId = "wuzhan_kf01",
            deviceId = "dev-test",
            title = "测试会话",
            recentMessages = listOf("顾客：超光子还剩几次"),
            readStatus = "full",
            ts = 1_764_065_400_000L,
            wechatId = "wuzhan_kf01",
            selfNickname = "医美顾问小陈",
        )
        val json = JSONObject(report.toJson())
        assertEquals("wuzhan_kf01", json.getString("wechat_id"))
        assertEquals("医美顾问小陈", json.getString("self_nickname"))
        // 兼容字段必须保留，避免打断既有采集链路。
        assertEquals("wuzhan_kf01", json.getString("service_account_id"))
        assertEquals("chat", json.getString("page_type"))
    }

    @Test
    fun reportDefaultsAreBackwardCompatible() {
        val report = ReadReport(
            serviceAccountId = "wuzhan_kf02",
            deviceId = "dev-test",
            title = null,
            recentMessages = emptyList(),
            readStatus = "partial",
            ts = 1_764_065_410_000L,
        )
        assertNull(report.selfNickname)
        assertNull(report.wechatId)
        val json = JSONObject(report.toJson())
        // wechat_id 缺省时回落到 service_account_id，保证中控总能拿到工作号维度。
        assertEquals("wuzhan_kf02", json.getString("wechat_id"))
        assertTrue(json.isNull("self_nickname"))
    }
}
