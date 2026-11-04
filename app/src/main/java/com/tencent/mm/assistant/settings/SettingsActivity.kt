package com.tencent.mm.assistant.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tencent.mm.assistant.AssistantApp
import com.tencent.mm.assistant.R
import com.tencent.mm.assistant.util.SettingsStore

/**
 * 设置页（PRD 模块 4）：
 *  * 中控地址
 *  * 客服账号别名（微信号读不到时的兜底标识）
 *  * N（最近 N 条可见消息，默认 5）
 *  * OCR 降级开关（默认关闭；本版本仅保留开关与占位实现，默认路径始终是无障碍读取）
 *  * 本地日志开关（只记录时间戳/包名/read_status/HTTP 状态码）
 *  * 一键跳转系统无障碍设置与悬浮窗权限
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var urlInput: EditText
    private lateinit var aliasInput: EditText
    private lateinit var nInput: EditText
    private lateinit var ocrSwitch: Switch
    private lateinit var logSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AssistantApp.global?.settings ?: SettingsStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        root.addView(label(getString(R.string.settings_center_url)))
        urlInput = EditText(this).apply {
            hint = getString(R.string.settings_center_url_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.centerUrl)
        }
        root.addView(urlInput)

        root.addView(label(getString(R.string.settings_service_alias)))
        aliasInput = EditText(this).apply {
            hint = getString(R.string.settings_service_alias_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(settings.serviceAlias)
        }
        root.addView(aliasInput)

        root.addView(label(getString(R.string.settings_max_messages)))
        nInput = EditText(this).apply {
            hint = SettingsStore.DEFAULT_MAX_MESSAGES.toString()
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.maxMessages.toString())
        }
        root.addView(nInput)

        ocrSwitch = Switch(this).apply {
            text = getString(R.string.settings_ocr_switch)
            isChecked = settings.ocrFallbackEnabled
        }
        root.addView(ocrSwitch)

        logSwitch = Switch(this).apply {
            text = getString(R.string.settings_log_switch)
            isChecked = settings.localLogEnabled
        }
        root.addView(logSwitch)

        root.addView(button(getString(R.string.settings_btn_save)) {
            persistInputs()
            finish()
        })

        root.addView(button(getString(R.string.settings_btn_accessibility)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button(getString(R.string.settings_btn_overlay)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        })
        root.addView(button(getString(R.string.settings_btn_view_log)) {
            startActivity(Intent(this@SettingsActivity, LocalLogActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.settings_readonly_note)
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(root)
    }

    private fun persistInputs() {
        settings.centerUrl = urlInput.text.toString()
        settings.serviceAlias = aliasInput.text.toString()
        settings.maxMessages = nInput.text.toString().toIntOrNull() ?: SettingsStore.DEFAULT_MAX_MESSAGES
        settings.ocrFallbackEnabled = ocrSwitch.isChecked
        settings.localLogEnabled = logSwitch.isChecked
    }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
