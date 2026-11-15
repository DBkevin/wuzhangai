package com.tencent.mm.assistant.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点树的一层极薄抽象。
 *
 * 存在的唯一理由：让「单聊判定 + 标题/消息提取」这段纯逻辑可以在 JVM 单测里跑，
 * 不需要真机、不需要 Robolectric。生产代码用 [AccessibilityNodeTree] 包装真实节点。
 *
 * 再次强调：本抽象只有读取能力（text / viewId / bounds / 子节点遍历），
 * 刻意不提供任何 click / action / 输入相关方法，从类型层面杜绝写入微信的可能。
 */
interface NodeTree {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val viewIdResourceName: String?
    val className: String?
    val bounds: Rect
    val childCount: Int
    val isVisibleToUser: Boolean
    fun child(index: Int): NodeTree?
}

/** 真实无障碍节点 -> NodeTree 的适配器。 */
class AccessibilityNodeTree(
    private val node: AccessibilityNodeInfo,
) : NodeTree {

    private val rect = Rect()

    override val text: CharSequence?
        get() = node.text

    override val contentDescription: CharSequence?
        get() = node.contentDescription

    override val viewIdResourceName: String?
        get() = node.viewIdResourceName

    override val className: String?
        get() = node.className?.toString()

    override val bounds: Rect
        get() {
            node.getBoundsInScreen(rect)
            return rect
        }

    override val childCount: Int
        get() = node.childCount

    override val isVisibleToUser: Boolean
        get() = node.isVisibleToUser

    override fun child(index: Int): NodeTree? {
        val c = node.getChild(index) ?: return null
        return AccessibilityNodeTree(c)
    }
}

/**
 * 深度受限的前序遍历，避免微信极深布局导致遍历耗时失控（不能阻塞微信）。
 * 收集过程中不再持有 AccessibilityNodeInfo 引用，只取需要的标量值。
 */
fun NodeTree.forEachNode(
    maxDepth: Int = DEFAULT_MAX_DEPTH,
    maxNodes: Int = DEFAULT_MAX_NODES,
    action: (NodeTree, Int) -> Unit,
) {
    val budget = NodeBudget(maxNodes)
    walk(this, 0, maxDepth, budget, action)
}

class NodeBudget(private var remaining: Int) {
    fun tryConsume(): Boolean {
        if (remaining <= 0) return false
        remaining--
        return true
    }

    val exhausted: Boolean get() = remaining <= 0
}

const val DEFAULT_MAX_DEPTH = 40
const val DEFAULT_MAX_NODES = 600

private fun walk(
    node: NodeTree,
    depth: Int,
    maxDepth: Int,
    budget: NodeBudget,
    action: (NodeTree, Int) -> Unit,
) {
    if (depth > maxDepth || budget.exhausted) return
    if (!budget.tryConsume()) return
    action(node, depth)
    val n = node.childCount
    for (i in 0 until n) {
        if (budget.exhausted) return
        val c = node.child(i) ?: continue
        walk(c, depth + 1, maxDepth, budget, action)
    }
}
