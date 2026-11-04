package com.tencent.mm.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面：状态展示 + 权限跳转 + 设置入口。
 * 纯展示页，不读取任何微信数据。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.main_readonly_statement)
            textSize = 13f
            setPadding(0, 0, 0, dp(16))
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(statusView)

        root.addView(button(getString(R.string.main_btn_accessibility)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button(getString(R.string.main_btn_overlay)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        })
        root.addView(button(getString(R.string.main_btn_settings)) {
            startActivity(Intent(this@MainActivity, com.tencent.mm.assistant.settings.SettingsActivity::class.java))
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val accessibilityOn = isAccessibilityServiceEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val centerConfigured = AssistantApp.global?.settings?.centerUrl?.isNotBlank() == true

        statusView.text = getString(
            R.string.main_status_fmt,
            if (accessibilityOn) getString(R.string.state_on) else getString(R.string.state_off),
            if (overlayOn) getString(R.string.state_on) else getString(R.string.state_off),
            if (centerConfigured) getString(R.string.state_on) else getString(R.string.state_off),
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expected = "$packageName/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        return enabled.any { it.resolveInfo?.serviceInfo?.let { si -> "${si.packageName}/${si.name}" } == expected }
    }

    private fun button(text: String, onClick: () -> Unit): View =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
