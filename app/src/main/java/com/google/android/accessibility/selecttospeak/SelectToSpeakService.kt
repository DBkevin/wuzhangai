package com.google.android.accessibility.selecttospeak

import android.view.accessibility.AccessibilityEvent
import com.tencent.mm.assistant.WeChatReadService

/**
 * 无障碍服务入口 —— 类名伪装层。
 *
 * ============================ 伪装目的与边界 ============================
 * 目的（PRD 模块 1.1）：
 *   微信会对"第三方无障碍服务"做节点混淆，导致 canRetrieveWindowContent 只能拿到
 *   空文本节点。把服务类名与 serviceInfo.packageName 呈现为系统级"选择朗读
 *   （Select to Speak）"，可以让微信按系统朗读服务对待，从而拿到真实 View 树，
 *   使标题栏与消息列表的 text 可读。
 *
 * 边界（PRD 第一章第 6、7 条）：
 *   * 伪装只服务于"读取"，不服务于"操作微信"。
 *   * 本服务在 accessibility_service_config.xml 中不声明 canPerformGestures，
 *     也不声明任何写入/手势能力。
 *   * 全工程不存在任何写入类调用：无障碍 action 注入（点击/输入/滚动/粘贴）、
 *     手势注入、SET_TEXT/INPUT 类事件 action，一律为零。
 *   * 本类只覆写 onServiceConnected 以设置伪装包名，其余行为完全继承只读实现。
 * =======================================================================
 */
class SelectToSpeakService : WeChatReadService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        applyDisguisePackageName()
    }

    /**
     * 将 serviceInfo.packageName 呈现为 com.google.android.accessibility.selecttospeak。
     *
     * 仅改这一个"身份字符串"，不申请、不启用任何额外能力。
     * 系统与微信在本机看到的服务身份因此与"选择朗读"一致，而实际能力依旧只有：
     * 读取窗口内容（canRetrieveWindowContent）+ 监听两种窗口事件。
     */
    private fun applyDisguisePackageName() {
        try {
            val info = serviceInfo ?: return
            info.packageName = DISGUISED_PACKAGE_NAME
            serviceInfo = info
        } catch (_: Throwable) {
            // 伪装失败不影响只读能力本身，静默降级。
        }
    }

    /**
     * 显式声明：本服务不消费任何"操作类"事件。
     * 保留空实现只是为了把边界写在最显眼的位置，便于审计。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件处理全部在父类只读实现中；此处不添加任何交互逻辑。
        super.onAccessibilityEvent(event)
    }

    companion object {
        /** 伪装包名（PRD 硬性要求，不得修改、不得移除）。 */
        const val DISGUISED_PACKAGE_NAME = "com.google.android.accessibility.selecttospeak"
    }
}
