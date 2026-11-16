package com.tencent.mm.assistant

import android.app.Application
import com.tencent.mm.assistant.net.CenterClient
import com.tencent.mm.assistant.overlay.HintOverlayManager
import com.tencent.mm.assistant.util.SafeLogger
import com.tencent.mm.assistant.util.SettingsStore

/**
 * Application：只负责装配单例。
 *
 * 聊天内容不在此处（也不在任何磁盘位置）持久化；
 * 会话状态只存在于 WeChatReadService 的内存字段中，随会话切换清空。
 */
class AssistantApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var logger: SafeLogger
        private set
    lateinit var centerClient: CenterClient
        private set
    lateinit var overlay: HintOverlayManager
        private set

    /**
     * 资料页 ↔ 来源会话 的绑定状态。
     *
     * 放在 Application（进程级）而不是无障碍服务实例里：
     * 系统可能在不杀进程的情况下重新绑定无障碍服务（如授予权限后），
     * 若状态挂在服务实例上会被清零，导致"刚看过会话却绑不上资料页"。
     */
    val profileLinker = com.tencent.mm.assistant.core.ProfileLinker()

    /**
     * 会话标题 -> 微信号 的内存映射（不落盘）。
     * 同样放在进程级，避免服务重连丢失。
     */
    val wechatIdByTitle = HashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        logger = SafeLogger(settings)
        centerClient = CenterClient(settings, logger)
        overlay = HintOverlayManager(this, centerClient, settings, logger)
    }

    override fun onTerminate() {
        centerClient.shutdown()
        super.onTerminate()
    }

    companion object {
        @Volatile
        private var instance: AssistantApp? = null

        fun from(any: android.content.Context): AssistantApp? {
            val app = instance ?: run {
                // Application.onCreate 一定先于任何 Service/Activity，
                // 这里只是防御性兜底（例如单元测试环境）。
                (any.applicationContext as? AssistantApp)
            }
            return app
        }

        val global: AssistantApp?
            get() = instance
    }

    init {
        instance = this
    }
}
