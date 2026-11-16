# 微信界面可读字段全清单（实测）

> 数据来源：2026-11-16，Redmi K70 / HyperOS / Android 14 / 微信 8.x，
> 用 `uiautomator dump` 导出真实节点树。
> 原始样本已存进仓库：[`docs/samples/chat-page.xml`](samples/chat-page.xml)、
> [`docs/samples/profile-page.xml`](samples/profile-page.xml)。

---

## 一、每个节点固定有的 19 个属性

无障碍树里**每个节点都有**这 19 个属性（实测 176/176 节点全部具备）：

| 属性 | 示例值 | 对我们的用途 |
| --- | --- | --- |
| `index` | `0` | 同级序号 |
| `text` | `一透风就冷，不透就热` | ⭐ **消息正文**、时间、按钮文案 |
| `resource-id` | `com.tencent.mm:id/bkl` | 定位节点（**但会随版本变，见 §五**）|
| `class` | `android.widget.TextView` | 区分文本/图片/列表 |
| `package` | `com.tencent.mm` | 恒为微信，判断归属 |
| `content-desc` | `大宝贝儿头像` | ⭐ **头像昵称**（唯一的会话对象来源）|
| `checkable` | `false` | 无用 |
| `checked` | `false` | 无用 |
| `clickable` | `true` | 判断是否可点（我们不点，只用于结构推断）|
| `enabled` | `true` | 无用 |
| `focusable` | `false` | 无用 |
| `focused` | `false` | 无用 |
| `scrollable` | `true` | ⭐ 识别消息列表（RecyclerView）|
| `long-clickable` | `false` | 无用 |
| `password` | `false` | 无用 |
| `selected` | `false` | 无用 |
| `bounds` | `[209,727][1106,882]` | ⭐ **左右分侧（对方/自己）**、区域判定 |
| `drawing-order` | `2` | 无用 |
| `hint` | （空） | 输入框提示，实测微信聊天页为空 |

### 微信私有属性：`NAF`

实测 7 个节点带 `NAF="true"`：

```
actionbar_up_indicator  LinearLayout  text=''
brp (×6)                TextView      text=''      ← 语音气泡区域
```

推测是 **Not Accessibility Friendly** —— 微信主动把某些节点标记为"无障碍不友好"。
这些节点共同特征：**text 与 content-desc 均为空**。这也是"看起来有气泡却读不到内容"的原因。

> 结论：**带 `NAF=true` 的节点基本拿不到文本**，不要指望从它们身上读消息。

---

## 二、聊天页结构（176 个节点）

### 2.1 分区与关键节点

| 区域 | resource-id | 类名 | bounds 示例 | 说明 |
| --- | --- | --- | --- | --- |
| 标题栏容器 | `ei` / `ef` | FrameLayout / ViewGroup | `[0,138][1440,311]` | **里面没有标题文本** |
| 右上角按钮 | `fq` | ImageView | `[1238,138][1440,311]` | `content-desc="更多信息"` |
| 消息列表 | `bp0` | **RecyclerView** | `[0,311][1440,2941]` | `scrollable=true`，消息都在这里 |
| 消息行容器 | `bn1` | RelativeLayout | `[0,311][1440,522]` | 每条消息一个 |
| 消息内容容器 | `bkj` | LinearLayout/RelativeLayout | — | 包裹气泡 |
| 头像 | `bk1` | ImageView | `[22,709][209,896]` | ⭐ `content-desc="大宝贝儿头像"` |
| **消息文本** | `bkl` | TextView | `[209,727][1106,882]` | ⭐ 正文在这里 |
| 时间分隔 | `br1` | TextView | `[641,1847][799,2005]` | `text="08:39"` / `"周五 15:44"` |
| 语音气泡 | `brp` | TextView | — | `NAF=true`，**读不到内容** |
| 输入栏-切换键 | `bpe` | ImageButton | `[7,2964][166,3123]` | `content-desc="切换到键盘"` 或 `"切换到按住说话"` |
| 输入框/按住说话 | `p6x` | Button | `[173,2964][1123,3122]` | `text="按住 说话"` |
| 表情按钮 | `bqr` | ImageButton | `[1130,2964][1289,3123]` | `content-desc="表情"` |
| 更多功能 | `bjz` | ImageButton | `[1274,2964][1433,3123]` | `content-desc="更多功能按钮，已折叠"` |

### 2.2 实测 text 分布

| resource-id | 条数 | 样本 |
| --- | --- | --- |
| `bkl` | 12 | `我昨天晚上没穿秋衣秋裤睡觉`、`一透风就冷，不透就热`、`3"`、`15"`、`是呀` |
| `br1` | 2 | `08:39`、`08:45` |
| `p6x` | 1 | `按住 说话` |

### 2.3 实测 content-desc 分布

| resource-id | 条数 | 样本 |
| --- | --- | --- |
| `bk1` | 12 | `大宝贝儿头像`、`旺仔小李头像` |
| `bkl` | 6 | `\xa0`（不间断空格，语音消息占位）|
| `fq` | 1 | `更多信息` |
| `bpe` | 1 | `切换到键盘` |
| `p6x` | 1 | `按住说话` |
| `bqr` | 1 | `表情` |
| `bjz` | 1 | `更多功能按钮，已折叠` |

### 2.4 三个容易踩的坑

**① 标题栏没有标题**
`ei`/`ef` 容器里只有一个 `fq`（更多信息按钮），**没有标题 TextView**。
所以"从标题栏读会话名"这条路在微信 8.x 上根本走不通。

**② 语音消息的 text 是时长**
语音气泡的 `bkl.text` 是 `3"` / `15"`，`content-desc` 是 `\xa0`。
如果不处理，会把 `3"` 当成一条用户消息回传。**已修复**（过滤器会把 `数字+"` 判为占位符）。

**③ 对方/自己靠 bounds 分侧**
同一屏里 `bk1` 的 bounds：
- 对方（左）：`[22,709][209,896]` → 中心 x ≈ 116
- 自己（右）：`[1231,335][1418,522]` → 中心 x ≈ 1324

以屏幕中线（720）为界即可区分。这是"识别会话对象"和"识别群聊"的基础。

---

## 三、资料页结构（61 个节点）

| resource-id | 类名 | 文本样本 | 说明 |
| --- | --- | --- | --- |
| `cf8` | TextView | `大宝贝儿` | **备注名**（= 会话标题）|
| `cf7` | TextView | `昵称:  小小林` | 昵称（带 `昵称:` 前缀）|
| `cff` | TextView | `微信号:  __LINXIAO` | ⭐ **微信号（唯一来源）** |
| `cf6` | TextView | `地区:  河南  驻马店` | 地区（我们不采集）|
| `o3b` | TextView | `发消息` | 动作按钮（资料页判据）|
| `o3b` | TextView | `音视频通话` | 动作按钮 |
| `cey` | ImageView | `content-desc="头像"` | 头像 |
| `cfc` | TextView | `content-desc="女"` | 性别 |
| `m7k` | LinearLayout | `content-desc="朋友圈个人相册,共0张"` | 朋友圈入口 |
| `fr` | ImageView | `content-desc="编辑备注"` | 编辑备注 |
| `fs` | ImageView | `content-desc="更多信息"` | 更多 |

### 微信号的两种形态（都要支持）

| 形态 | 示例 | 正则要点 |
| --- | --- | --- |
| 系统默认 ID | `wxid_bt7097pan2cf22_6445` | `^wxid_[A-Za-z0-9_-]{3,}$` |
| 自定义 ID | `hxzxxy999` | 字母开头，6~20 位 |
| 自定义 ID（下划线开头） | `__LINXIAO` | ⚠️ **允许 `_` 开头**，容易漏 |

---

## 四、能读到什么 / 读不到什么

### ✅ 能读到

| 信息 | 来源 | 备注 |
| --- | --- | --- |
| 消息正文 | `bkl.text` | 文本消息 |
| 语音时长 | `bkl.text`（如 `3"`） | 需过滤，不能当消息 |
| 时间分隔 | `br1.text` | 需过滤 |
| 对方昵称/备注 | `bk1.content-desc` 去掉「头像」 | 单聊唯一来源 |
| 消息左右归属 | `bk1.bounds` 中心 x | 判断对方/自己 |
| 消息列表 | `bp0`（RecyclerView, scrollable） | |
| 输入栏存在 | `bpe`/`p6x`/`bqr`/`bjz` 的 content-desc | 区分聊天页与列表页 |
| 微信号 | 资料页 `cff.text` | **仅资料页** |
| 昵称 | 资料页 `cf7.text` | |
| 地区/性别 | 资料页 `cf6.text` / `cfc.content-desc` | 我们不采集 |

### ❌ 读不到

| 信息 | 说明 |
| --- | --- |
| **wxid** | 聊天页/聊天信息页均为 0 次出现；资料页也只显示"微信号"一行 |
| **微信号（聊天页）** | 同上 |
| **发送者 ID** | 树里没有任何 openid/username 字段 |
| **手机号** | 不在聊天页；资料页也不显示 |
| 聊天标题文本 | 标题栏无 TextView（见 §2.4①）|
| 语音消息内容 | `NAF=true`，无文本 |
| 图片/视频内容 | 只有占位，需 OCR（默认关闭）|

---

## 五、重要警告：resource-id 不可硬编码

微信的 resource-id 是**混淆过的短标识**（`bkl`、`bk1`、`br1`、`p6x`…），
**每次微信大版本更新都可能变**。

因此本项目**不以 resource-id 作为主要判据**，而是用稳定特征：

| 要判断的东西 | 我们用的稳定特征 | 而不是 |
| --- | --- | --- |
| 是否聊天页 | 存在 scrollable 的 RecyclerView + 输入栏 content-desc（`切换到键盘`/`表情`/`更多功能按钮`/`按住说话`） | resource-id |
| 会话对象 | 头像 `content-desc` 以「头像」结尾 | resource-id |
| 对方/自己 | bounds 中心 x 与屏幕中线比较 | resource-id |
| 群聊 | 左侧出现 ≥2 个不同头像昵称 | 标题 `(12)` 正则 |
| 资料页 | 有 `发消息`/`音视频通话` 且有 `微信号` 标签 | resource-id |

`resource-id` 只用于**调试定位**和日志，不参与判定逻辑。

---

## 六、复现这份分析

```powershell
$adb='D:\tools\platform-tools\adb.exe'; $dev='30ebcf03'
# 1. 进到目标页面，导出节点树
& $adb -s $dev shell uiautomator dump /sdcard/page.xml
& $adb -s $dev pull /sdcard/page.xml docs\samples\page.xml

# 2. 统计所有属性
python tools\analyze_ui.py docs\samples\page.xml
```

`tools/analyze_ui.py` 会输出：属性清单、resource-id 汇总、text/content-desc 分组、NAF 节点。
