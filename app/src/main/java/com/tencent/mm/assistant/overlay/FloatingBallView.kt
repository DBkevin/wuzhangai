package com.tencent.mm.assistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮球四态角标的纯函数判定（WP-27）。
 *
 * 抽成 object 是为了让 JVM 单测可覆盖，不依赖任何 Android API：
 *   * `撞` —— 撞单/重名/其他开发保护期；
 *   * `号` —— 聊天中识别到可补录岚时的手机号；
 *   * `档` —— 已关联岚时正式档案；
 *   * 空   —— 未建档新客，安静待命。
 */
object FloatingBallBadge {
    const val NONE = ""
    const val CONFLICT = "conflict"
    const val PHONE = "phone"
    const val ARCHIVED = "archived"

    private val KNOWN = setOf(CONFLICT, PHONE, ARCHIVED)

    fun normalize(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return if (value in KNOWN) value else NONE
    }

    fun textFor(raw: String?): String = when (normalize(raw)) {
        CONFLICT -> "撞"
        PHONE -> "号"
        ARCHIVED -> "档"
        else -> ""
    }

    fun colorFor(raw: String?): Int = when (normalize(raw)) {
        CONFLICT -> 0xFFE64A19.toInt()
        PHONE -> 0xFF1976D2.toInt()
        ARCHIVED -> 0xFF2E7D32.toInt()
        else -> 0xFF9E9E9E.toInt()
    }

    /** 闲置 4 秒后仅在「无角标」时降到 45% 不透明度；有角标保持醒目。 */
    fun alphaFor(idle: Boolean, panelOpen: Boolean, hasBadge: Boolean): Float = when {
        panelOpen -> 0f
        idle && !hasBadge -> 0.45f
        else -> 1f
    }
}

/**
 * 44dp 可拖拽磁吸贴边半透明悬浮球（纯原生 View，零微信操控）。
 *
 * 只接收员工对自己悬浮窗的触摸：拖动改的是本应用 WindowManager 的位置，
 * 绝不向微信派发任何 action / 手势 / 输入事件。
 */
class FloatingBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 点击（非拖动）回调：由悬浮窗管理器转给读屏服务发起 open 请求。 */
    var onTap: (() -> Unit)? = null

    /** 拖动回调：dx/dy 为本次位移，end=true 表示松手（由管理器负责磁吸与持久化）。 */
    var onDrag: ((dx: Float, dy: Float, end: Boolean) -> Unit)? = null

    private val circle = View(context)
    private val statusDot = View(context)
    private val badgeView = TextView(context)

    private val circleSize = dp(44)
    private val wrapSize = dp(52)

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    init {
        layoutParams = LayoutParams(wrapSize, wrapSize)
        setWillNotDraw(false)

        circle.layoutParams = LayoutParams(circleSize, circleSize, Gravity.CENTER)
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCCFFFFFF.toInt())
            setStroke(dp(1), 0x33000000)
        }
        addView(circle)

        statusDot.layoutParams = LayoutParams(dp(8), dp(8), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(3), dp(3))
        }
        statusDot.background = dotDrawable(0xFF2E7D32.toInt())
        addView(statusDot)

        badgeView.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP,
        )
        badgeView.textSize = 10f
        badgeView.setTextColor(Color.WHITE)
        badgeView.setPadding(dp(4), dp(1), dp(4), dp(1))
        badgeView.background = badgeDrawable(0xFFE64A19.toInt())
        badgeView.visibility = View.GONE
        addView(badgeView)
    }

    /** 更新角标（空串即隐藏）。 */
    fun setBadge(badge: String?) {
        val text = FloatingBallBadge.textFor(badge)
        badgeView.text = text
        badgeView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (text.isNotEmpty()) {
            badgeView.background = badgeDrawable(FloatingBallBadge.colorFor(badge))
        }
    }

    /** 服务健康指示灯：true=翠绿（正常），false=红色（网络/鉴权异常）。 */
    fun setHealthy(healthy: Boolean) {
        statusDot.background = dotDrawable(if (healthy) 0xFF2E7D32.toInt() else 0xFFD32F2F.toInt())
    }

    /** 闲置半透明：面板展开时隐藏，无角标闲置时降为 45%。 */
    fun applyAlpha(idle: Boolean, panelOpen: Boolean) {
        alpha = FloatingBallBadge.alphaFor(idle, panelOpen, badgeView.visibility == View.VISIBLE)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                alpha = 1f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (!dragging && abs(dx) + abs(dy) > dp(3)) dragging = true
                if (dragging) onDrag?.invoke(dx, dy, false)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    onTap?.invoke()
                } else {
                    onDrag?.invoke(0f, 0f, true)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                onDrag?.invoke(0f, 0f, true)
                return true
            }
        }
        return true
    }

    private fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x66FFFFFF)
    }

    private fun badgeDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(7).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
