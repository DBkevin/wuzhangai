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
