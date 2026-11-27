package com.tencent.mm.assistant

import com.tencent.mm.assistant.overlay.HintCopyHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-26：悬浮窗「复制」语义单测。
 *
 * 只验证字符串处理（纯函数），不触碰 Android ClipboardManager，也不涉及任何微信输入事件：
 * 复制动作永远只写系统剪贴板，粘贴由人工完成。
 */
class HintCopyHelperTest {

    @Test
    fun `extracts 12-digit dossier number from success hint`() {
        val hint = "✅【岚时建档成功 #261127624598】来源：医美APP/美团 · 开发：李贝贝"
        assertEquals("261127624598", HintCopyHelper.extractDossierNo(hint))
    }

    @Test
    fun `extracts historical 11-digit dossier number`() {
        assertEquals("25070860884", HintCopyHelper.extractDossierNo("【#25070860884】张*(待实名) · 沟通中"))
    }

    @Test
    fun `dossier copy text is pure digits`() {
        val hint = "✅【岚时建档成功 #261127624598】"
        assertEquals("261127624598", HintCopyHelper.dossierCopyText(hint))
        assertFalse(HintCopyHelper.dossierCopyText(hint)!!.contains("#"))
    }

    @Test
    fun `remark copy text keeps leading space and hash`() {
        val hint = "✅【岚时建档成功 #261127624598】"
        assertEquals(" #261127624598", HintCopyHelper.remarkCopyText(hint))
    }

    @Test
    fun `returns null when hint has no dossier number`() {
        assertNull(HintCopyHelper.extractDossierNo("未匹配到门诊档案，可点开对方头像进入资料页"))
        assertNull(HintCopyHelper.extractDossierNo("顾客手机号 13800138000"))
        assertNull(HintCopyHelper.extractDossierNo(null))
        assertNull(HintCopyHelper.dossierCopyText(""))
    }

    @Test
    fun `script row copies body without prefix`() {
        val line = "💬【话术建议】先报可用积分与可抵扣项目，再顺势邀约到店体验"
        assertEquals(
            "先报可用积分与可抵扣项目，再顺势邀约到店体验",
            HintCopyHelper.copyableRowText(line),
        )
    }

    @Test
    fun `script row with empty body is not copyable`() {
        assertNull(HintCopyHelper.copyableRowText("💬【话术建议】"))
    }

    @Test
    fun `status rows have no copy button`() {
        val statusLines = listOf(
            "✅【你的保护期档案】请优先跟进",
            "⚠️【保护期档案·归属开发：李贝贝】请勿重复建档",
            "📌 备注一行",
            "🎯【建档缺项】待沟通获取：真实姓名 / 地区 / 生日",
            "归属：开发[李贝贝] 顾问[王芳]",
            "资产：可用积分 300 · 手机尾号 1234",
            "来源：医美APP / 美团",
            "余次：光子嫩肤×2",
            "摘要：顾客关注超光子与补水",
        )
        for (line in statusLines) {
            assertTrue("应判定为状态行: $line", HintCopyHelper.isStatusLine(line))
            assertNull("状态行不应可复制: $line", HintCopyHelper.copyableRowText(line))
        }
    }

    @Test
    fun `normal row is copied as-is`() {
        val line = "【#261127624598】张*(待实名) · 沟通中"
        assertEquals(line, HintCopyHelper.copyableRowText(line))
        assertFalse(HintCopyHelper.isStatusLine(line))
    }

    @Test
    fun `blank lines are not copyable`() {
        assertNull(HintCopyHelper.copyableRowText("   "))
        assertTrue(HintCopyHelper.isStatusLine(""))
    }
}
