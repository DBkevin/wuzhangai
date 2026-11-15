package com.tencent.mm.assistant.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 「当前窗口是不是微信单聊界面」的判定器，并负责提取标题 / 消息。
 *
 * 判定顺序（全部为只读观察）：
 *  1. 包名必须是 com.tencent.mm（调用方先行判断，这里再兜一层）。
 *  2. 屏幕顶部 30% 区域内必须"恰好存在一个"候选标题文本节点 —— 多于一个说明是群聊/公众号
 *     等多标题界面或聊天列表，直接放弃；等于零个说明标题读取失败（title=null）。
 *  3. 标题形态排除：群聊（形如 `名称(12)` / `名称[12]`）、服务号/公众号常见后缀（"服务号"、"订阅号"）、
 *     朋友圈入口（"朋友圈"）等。
 *  4. 页面中必须存在"消息列表容器"：面积占屏幕比例高、子节点多、类名含 RecyclerView/ListView，
 *     或位于屏幕中部大片区域的大容器。找不到 -> 判为非单聊（可能是聊天列表页或设置页）。
 *
 * 该类的所有方法都只做只读观察（text / 类名 / 位置 / 子节点遍历），
 * 不包含任何写入或手势注入调用。
 */
object ChatScreenDetector {

    const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 标题候选所在的顶部区域占比（微信聊天标题紧贴状态栏下方，实测约在 3%~12%）。 */
    private const val TITLE_REGION_TOP_RATIO = 0.02f
    private const val TITLE_REGION_BOTTOM_RATIO = 0.22f

    /** 标题候选的水平中心允许范围（微信标题是居中的）。 */
    private const val TITLE_CENTER_MIN_RATIO = 0.25f
    private const val TITLE_CENTER_MAX_RATIO = 0.75f

    /** 标题长度上限，超过基本是正文而不是标题。 */
    private const val MAX_TITLE_LENGTH = 32

    /** 消息容器至少要占屏幕高度这么大。 */
    private const val MESSAGE_CONTAINER_MIN_HEIGHT_RATIO = 0.35f
    private const val MESSAGE_CONTAINER_MIN_CHILDREN = 2

    /** 输入栏判定：中心点位于屏幕该比例以下即视为"底部区域"。 */
    private const val INPUT_BAR_TOP_RATIO = 0.55f

    /** 消息头像 contentDescription 的后缀，形如「张三头像」。 */
    private const val AVATAR_SUFFIX = "头像"

    private val GROUP_TITLE_REGEX = Regex(""".*[\(\[（【]\s*\d+\s*[\)\]）】]\s*$""")

    /** 这些文本出现在顶部区域时，说明不是普通单聊界面。 */
    private val NON_SINGLE_CHAT_TITLE_EXACTS = setOf(
        "微信", "通讯录", "发现", "我", "朋友圈", "聊天信息", "搜索",
        "订阅号消息", "服务通知", "微信团队", "新的朋友", "群聊", "标签",
        "收藏", "相册", "卡包", "表情", "设置",
    )

    private val NON_SINGLE_CHAT_TITLE_SUFFIXES = listOf(
        "服务号", "订阅号", "公众号", "企业微信",
    )

    // ---------------------------------------------------------------- 顶层判定

    fun detect(
        root: NodeTree,
        screen: Rect,
        maxMessages: Int,
    ): ExtractResult {
        if (screen.width() <= 0 || screen.height() <= 0) return ExtractResult.NotSingleChat("bad_screen")

        val title = findTitleText(root, screen)
        val container = findMessageContainer(root, screen)
        if (container == null) {
            // 没有消息列表容器：聊天列表页 / 详情页 / 朋友圈等，一律跳过。
            return ExtractResult.NotSingleChat("no_container")
        }

        // 必须有"底部输入栏"，否则多半是会话列表页（它同样有 RecyclerView 占满屏幕）。
        // 这是区分「单聊页」与「会话列表页」最可靠的结构特征。
        if (!hasInputBar(root, screen)) {
            return ExtractResult.NotSingleChat("no_input_bar")
        }

        // 头像昵称：微信聊天页标题栏文本不在无障碍树里，但每条消息左侧头像的
        // contentDescription 形如「XXX头像」，可据此还原会话对象。
        // 左侧出现多个不同昵称 => 群聊，一律跳过。
        val avatar = readContactNameFromAvatars(root, screen)
        if (avatar.isGroup) {
            return ExtractResult.NotSingleChat("group_multi_avatar")
        }

        // 标题优先级：标题栏文本 > 左侧头像昵称（微信聊天页标题栏文本常不暴露在树里）
        val resolvedTitle = title ?: avatar.name

        // 标题缺失：属"单聊界面成立但读取失败"，按 PRD 要求 title=null + partial。
        if (resolvedTitle == null) {
            val messages = extractMessages(container, maxMessages)
            return ExtractResult.TitleUnreadable(
                recentMessages = messages,
                sessionKey = SESSION_KEY_UNKNOWN,
            )
        }

        // 群聊 / 公众号 / 服务号 等一律跳过（判定放在标题拿到之后，避免误伤）。
        // 日志只记分类与长度，不记标题原文（PRD：本地日志不含聊天文本）。
        isNonSingleChatCategory(resolvedTitle)?.let { category ->
            return ExtractResult.NotSingleChat(
                "non_single_title:" + category + ":len=" + resolvedTitle.length,
            )
        }

        val chatTitle: String = resolvedTitle

        val messages = extractMessages(container, maxMessages)
        val messageNodesPresent = countTextCandidateNodes(container) > 0

        // 消息节点被混淆：树里有节点但取不到任何文本 -> 只回传标题。
        if (messages.isEmpty() && !messageNodesPresent) {
            return ExtractResult.MessagesObfuscated(
                title = chatTitle,
                sessionKey = sessionKeyOf(chatTitle),
            )
        }

        return ExtractResult.Success(
            title = chatTitle,
            recentMessages = messages,
            sessionKey = sessionKeyOf(chatTitle),
        )
    }

    fun sessionKeyOf(title: String?): String =
        title?.takeIf { it.isNotBlank() } ?: SESSION_KEY_UNKNOWN

    /** 从头像 contentDescription 还原出的会话对象信息。 */
    data class AvatarContact(val name: String?, val isGroup: Boolean)

    /**
     * 从消息头像的 contentDescription 还原会话对象。
     *
     * 背景：微信聊天页的标题栏文本不在无障碍树里（实测标题栏只有"聊天信息"按钮），
     * 但每条消息的头像会带 contentDescription，形如「XXX头像」。
     * 左侧（屏幕左半）头像属于对方，右侧属于自己。
     *
     * 判定：
     *   * 左侧只有 1 个不同昵称 -> 单聊，取该昵称
     *   * 左侧出现 >= 2 个不同昵称 -> 群聊
     *   * 没有左侧头像 -> 无法判断，返回 name=null（后续走 title=null 分支）
     */
    fun readContactNameFromAvatars(root: NodeTree, screen: Rect): AvatarContact {
        val half = screen.left + screen.width() / 2
        val leftNames = LinkedHashSet<String>()
        root.forEachNode(maxDepth = DEFAULT_MAX_DEPTH, maxNodes = DEFAULT_MAX_NODES) { node, _ ->
            val cd = node.contentDescription?.toString()?.trim().orEmpty()
            if (cd.length <= AVATAR_SUFFIX.length || !cd.endsWith(AVATAR_SUFFIX)) return@forEachNode
            val b = node.bounds
            if (b.width() <= 0 || b.height() <= 0) return@forEachNode
            if (b.centerX() >= half) return@forEachNode
            val name = cd.removeSuffix(AVATAR_SUFFIX).trim()
            if (name.isNotEmpty()) leftNames.add(name)
        }
        return when {
            leftNames.size == 1 -> AvatarContact(leftNames.first(), false)
            leftNames.size > 1 -> AvatarContact(null, true)
            else -> AvatarContact(null, false)
        }
    }

    /**
     * 判断页面底部是否存在聊天输入栏。
     *
     * 微信单聊页底部必有输入区：一个 EditText，或语音模式的「按住 说话」。
     * 会话列表页没有这个结构，因此这条判据能把"列表页"从"单聊页"里剔除。
     * 阈值取 0.55：键盘弹起时输入栏会整体上移，留足余量。
     */
    fun hasInputBar(root: NodeTree, screen: Rect): Boolean {
        val bottomStart = screen.top + (screen.height() * INPUT_BAR_TOP_RATIO).toInt()
        var hit = false
        root.forEachNode(maxDepth = DEFAULT_MAX_DEPTH, maxNodes = DEFAULT_MAX_NODES) { node, _ ->
            if (hit) return@forEachNode
            val b = node.bounds
            if (b.height() <= 0) return@forEachNode
            if (b.centerY() < bottomStart) return@forEachNode
            val cls = node.className.orEmpty()
            val t = node.text?.toString()?.trim().orEmpty()
            if (cls.contains("EditText")) {
                hit = true
            } else if (t == "按住 说话" || t == "按住说话") {
                hit = true
            }
        }
        return hit
    }

    const val SESSION_KEY_UNKNOWN = "__unknown_title__"

    // ---------------------------------------------------------------- 标题

    /**
     * 在顶部区域找"唯一"的候选标题。
     *
     * 微信单聊标题栏结构稳定：一个居中的 TextView。群聊标题形如 `名称(12)`，
     * 公众号/服务号标题通常带后缀，且这类界面顶部往往还有其它文本（如"关注"），
     * 因此"候选数量 > 1 即放弃"这条既保守又有效。
     */
    fun findTitleText(root: NodeTree, screen: Rect): String? {
        val top = screen.top + (screen.height() * TITLE_REGION_TOP_RATIO).toInt()
        val bottom = screen.top + (screen.height() * TITLE_REGION_BOTTOM_RATIO).toInt()
        val minCenterX = screen.left + (screen.width() * TITLE_CENTER_MIN_RATIO).toInt()
        val maxCenterX = screen.left + (screen.width() * TITLE_CENTER_MAX_RATIO).toInt()

        val candidates = ArrayList<String>(4)
        root.forEachNode { node, _ ->
            val raw = node.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return@forEachNode
            if (raw.length > MAX_TITLE_LENGTH) return@forEachNode
            if (!node.isVisibleToUser) return@forEachNode

            val b = node.bounds
            if (b.height() <= 0 || b.width() <= 0) return@forEachNode
            val centerY = b.centerY()
            if (centerY < top || centerY > bottom) return@forEachNode

            val centerX = b.centerX()
            if (centerX < minCenterX || centerX > maxCenterX) return@forEachNode

            // 纯时间/电量等状态栏噪声
            if (StatusBarFilter.isStatusBarNoise(raw)) return@forEachNode
            // 过滤掉本身就是整段正文的节点
            if (raw.length > MAX_TITLE_LENGTH) return@forEachNode

            if (!candidates.contains(raw)) candidates.add(raw)
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        // 多个候选时，剔除明显不是人名的（保留词/群人数括注），只剩一个才采信。
        val filtered = candidates.filter { isNonSingleChatCategory(it) == null }
        return if (filtered.size == 1) filtered.first() else null
    }

    fun isNonSingleChatTitle(title: String): Boolean = isNonSingleChatCategory(title) != null

    /**
     * 返回"为什么不是单聊"的分类码（用于诊断日志），单聊返回 null。
     * 分类码不含标题原文，符合"本地日志不记录聊天文本"。
     */
    fun isNonSingleChatCategory(title: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return "empty"
        if (NON_SINGLE_CHAT_TITLE_EXACTS.contains(t)) return "reserved"
        if (GROUP_TITLE_REGEX.matches(t)) return "group_count"
        if (NON_SINGLE_CHAT_TITLE_SUFFIXES.any { t.endsWith(it) }) return "official_suffix"
        // "(12)" 这类纯人数括注
        if (Regex("""^[\(\[（【]\s*\d+\s*[\)\]）】]$""").matches(t)) return "count_only"
        return null
    }

    // ---------------------------------------------------------------- 消息容器

    /**
     * 找消息列表容器：优先类名含 RecyclerView/ListView 且竖向占据屏幕中部大片区域的大节点；
     * 退化为"高度占比足够且子节点数达标"的最大节点。
     */
    fun findMessageContainer(root: NodeTree, screen: Rect): NodeTree? {
        val minHeight = (screen.height() * MESSAGE_CONTAINER_MIN_HEIGHT_RATIO).toInt()

        var best: NodeTree? = null
        var bestScore = 0

        root.forEachNode { node, depth ->
            // 标题栏本身在顶部，跳过过浅的节点
            if (depth < 2) return@forEachNode
            if (!node.isVisibleToUser) return@forEachNode
            if (node.childCount < MESSAGE_CONTAINER_MIN_CHILDREN) return@forEachNode

            val b = node.bounds
            if (b.height() < minHeight) return@forEachNode
            // 消息区必须在中部偏下，避免把整页根节点当成容器
            if (b.top < screen.top) return@forEachNode

            val cls = node.className.orEmpty()
            var score = b.height()
            if (cls.contains("RecyclerView")) score += screen.height()
            else if (cls.contains("ListView")) score += screen.height() / 2

            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }

        return best
    }

    // ---------------------------------------------------------------- 消息

    /**
     * 从容器中按可见顺序取文本节点，过滤时间戳 / 系统提示 / 表情占位符。
     */
    fun extractMessages(container: NodeTree, maxMessages: Int): List<String> {
        if (maxMessages <= 0) return emptyList()

        val ordered = ArrayList<String>(maxMessages * 3)
        container.forEachNode { node, _ ->
            val raw = node.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return@forEachNode
            val cls = node.className.orEmpty()
            // 只取文本类节点，避免把容器的 contentDescription 当消息
            if (cls.isNotEmpty() && !cls.contains("TextView") && !cls.contains("EditText")) {
                return@forEachNode
            }
            if (!MessageTextFilter.isDisplayableMessage(raw)) return@forEachNode
            ordered.add(raw)
        }

        // 连续重复去重（列表项复用可能产生同一文本的多个节点）
        val deduped = ArrayList<String>(ordered.size)
        for (item in ordered) {
            if (deduped.isNotEmpty() && deduped.last() == item) continue
            deduped.add(item)
        }

        // 取最近 N 条：可见顺序为旧 -> 新，因此取尾部
        return if (deduped.size <= maxMessages) deduped else deduped.subList(deduped.size - maxMessages, deduped.size).toList()
    }

    /**
     * 统计容器内"像是消息文本但取不到文本"的节点数量。
     *
     * 微信混淆后节点仍然存在（有 View、有位置），但 text 为空；
     * 若树里连一个文本类节点都没有，则视为被混淆，read_status=partial。
     */
    fun countTextCandidateNodes(container: NodeTree): Int {
        var count = 0
        container.forEachNode { node, _ ->
            val cls = node.className.orEmpty()
            if (cls.contains("TextView")) {
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) count++
            }
        }
        return count
    }

    // ---------------------------------------------------------------- 工具

    /** 从聊天列表首页/会话列表里抓微信号的上层界面判定用：仅做"是否在'我'页"判断。 */
    fun looksLikeProfilePage(root: NodeTree, screen: Rect): Boolean {
        var hasId = false
        var hasQrOrMore = false
        root.forEachNode { node, _ ->
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) return@forEachNode
            if (t.startsWith("微信号") || t.matches(Regex("""^wxid_[A-Za-z0-9_-]+$"""))) hasId = true
            if (t == "朋友圈" || t == "收藏" || t == "拍一拍" || t == "服务") hasQrOrMore = true
        }
        return hasId || hasQrOrMore
    }

    /** 兼容旧入口：直接给 AccessibilityNodeInfo 的调用点。 */
    fun detect(node: AccessibilityNodeInfo, screen: Rect, maxMessages: Int): ExtractResult =
        detect(AccessibilityNodeTree(node), screen, maxMessages)
}
