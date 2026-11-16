# 故障排查手册（TROUBLESHOOTING）

> 本文档记录 2026-11 在 **Redmi K70（HyperOS / Android 14）** 上把本项目从"完全不动"调试到
> "稳定弹窗"的全过程。共 7 个坑，每个都给出了**现象 → 判定方法 → 根因 → 修复**。
> 下次遇到"没反应"，请**按顺序**逐条排查，不要跳步。

---

## 0. 三分钟快速自检

按顺序执行，任何一步不符合预期就先修它，再往下走。

```powershell
$adb='D:\tools\platform-tools\adb.exe'; $dev='30ebcf03'; $pkg='com.tencent.mm.assistant'

# ① App 装了吗？版本对吗？
& $adb -s $dev shell dumpsys package $pkg | Select-String "versionName|lastUpdateTime"

# ② 悬浮窗权限？（重装后必查，会被重置）
& $adb -s $dev shell appops get $pkg SYSTEM_ALERT_WINDOW

# ③ 无障碍服务启用了吗？
& $adb -s $dev shell settings get secure enabled_accessibility_services

# ④ 服务真的绑定上了吗？（注意：显示已绑定也可能是残留，见坑 3）
& $adb -s $dev shell dumpsys accessibility | Select-String "选择朗读内容"

# ⑤ 进程被冻结了吗？（最关键，见坑 7）
$pid_ = (& $adb -s $dev shell pidof $pkg).Trim()
$uid  = ((& $adb -s $dev shell "cat /proc/$pid_/status | grep -i '^Uid:'") -split '\s+')[1]
& $adb -s $dev shell "cat /sys/fs/cgroup/apps/uid_$uid/pid_$pid_/cgroup.freeze"
#    0 = 正常；1 = 被冻结，此时一切都会"静默失效"

# ⑥ 看日志
& $adb -s $dev logcat -d -s KfReadOnly | Select-Object -Last 30
```

**一键部署**（下载最新 CI 产物 + 全流程配置）：

```powershell
pwsh -ExecutionPolicy Bypass -File tools\deploy.ps1
```

> 必须用 **pwsh 7** 执行。Windows PowerShell 5.1 会按 ANSI 解码脚本里的中文导致语法错误
> （文件已加 UTF-8 BOM，但仍建议用 pwsh）。

---

## 坑 1：中控地址为空，App 什么都不发

**现象**：无障碍已开、微信前台，但中控收不到任何请求，浮窗永不出现。

**判定**：

```powershell
& $adb -s $dev shell run-as com.tencent.mm.assistant cat shared_prefs/kf_settings.xml
# 看到 <string name="center_url"></string> 即为此问题
```

**根因**：`CenterClient.report()` 第一道判断就是 `if (url.isBlank()) return`，静默放弃。

**修复**：在设置页填写中控地址，或由 `deploy.ps1` 直接写入 prefs。

> 这是**设计如此**（PRD 验收 2：网络异常不得打扰用户），不是 bug。

---

## 坑 2：事件被自己的"伪装包名"过滤掉了

**现象**：服务已绑定、无崩溃，但 `onAccessibilityEvent` 一次都不回调。

**根因**：`AccessibilityServiceInfo.packageNames` **有两重语义** —— 它既是服务身份标识，
也是系统投递事件的**包名白名单**。只填伪装包名，微信（`com.tencent.mm`）的事件会被系统全部过滤。

**修复**（`SelectToSpeakService.kt`）：

```kotlin
info.packageNames = arrayOf(
    "com.google.android.accessibility.selecttospeak",  // 伪装身份（PRD 1.1）
    "com.tencent.mm"                                    // 事件来源，必须保留
)
```

**教训**：任何"改 serviceInfo 身份"的操作，都要检查它是否同时改变了事件订阅范围。

---

## 坑 3：MIUI 残留旧绑定 —— 显示"已绑定"但完全不投递

**现象**：`dumpsys accessibility` 明确显示服务在 `Bound services` 里，
`service_connected` 也打过日志，但之后**一条事件都没有**。重装 App 后尤其容易触发。

**判定**：重启服务后仍无 `RAW onServiceConnected` / `POLL tick` 日志，而 dumpsys 又显示已绑定
→ 基本可判定为残留绑定。

**根因**：MIUI 的无障碍授权记录在卸载重装后可能失效，但设置项里仍残留旧条目。

**修复**（必须做完整循环，缺一不可）：

```powershell
& $adb -s $dev shell am force-stop $pkg
& $adb -s $dev shell settings put secure accessibility_enabled 0
& $adb -s $dev shell settings put secure enabled_accessibility_services '""'
Start-Sleep -Seconds 4
& $adb -s $dev shell settings put secure enabled_accessibility_services $svc
& $adb -s $dev shell settings put secure accessibility_enabled 1
```

`deploy.ps1` 已内置该流程。

**补充**：装完 App 后最好先手动启动一次 App（`am start`），再开启无障碍，成功率更高。

---

## 坑 4：MIUI 上无障碍事件投递不可靠 → 改用轮询

**现象**：有时能收到事件，有时几十分钟收不到；同样操作结果不一致。

**修复**：架构上改为**轮询为主、事件为辅**：

| 通道 | 频率 | 作用 |
| --- | --- | --- |
| 轮询 | 每 2 秒 | 主动读根节点，包名是微信才提取（主通道） |
| 事件 | 到达即触发 | 缩短延迟（加速器） |

两条通道共用 `ReportGate`，不会重复回传。轮询在熄屏时自动跳过（`PowerManager.isInteractive`）。

---

## 坑 5：会话列表页被误判成单聊

**现象**：停在微信聊天列表，却上报了 `title=null, read_status=partial`。

**根因**：列表页也有铺满屏幕的 RecyclerView，能通过"消息容器"判据。

**修复**：增加"底部输入栏"判据。

**重要**：微信的输入栏**不是 `EditText`**（实测是自定义控件），底部实际暴露的是：

```
content-desc = "切换到按住说话" / "切换到键盘" / "表情" / "更多功能按钮，已折叠" / "听写"
```

所以判据必须同时接受 `EditText`、`按住 说话` 文本、以及上述 `content-desc` 标记。

---

## 坑 6：聊天标题永远读不到（微信根本不在树里暴露它）

**现象**：`detect title_null msgs=5` —— 消息能拿到，标题永远为 null。

**判定**：`uiautomator dump` 后检查标题栏区域（屏幕顶部 ~7% 处）：

```
y=224  [FrameLayout] id=com.tencent.mm:id/ei
y=224  [ImageView]  id=com.tencent.mm:id/fq  content-desc="聊天信息"   ← 只有"…"按钮
```

**根因**：微信聊天页标题栏的**标题文本节点根本不存在**（既无 text 也无 content-desc）。
这不是混淆，是微信的固有实现。所以任何"从标题栏取文本"的方案都必然失败。

**修复**：从**消息头像的 `content-desc`** 还原会话对象：

```
y=334   x=116   content-desc='一滴相思泪头像'    ← 对方（屏幕左半）
y=1516  x=1324  content-desc='旺仔小李头像'      ← 自己（屏幕右半）
```

规则：

| 左侧（屏幕左半）头像昵称 | 判定 |
| --- | --- |
| 恰好 1 个 | 单聊 → 用该昵称作 title |
| ≥ 2 个不同昵称 | **群聊 → 跳过** |
| 0 个 | 读不到 → `title=null, partial` |

**额外收益**：群聊判定比"标题形如 `名称(12)`"的正则更可靠 —— 群聊必然出现多个不同人的左侧头像。

**局限**：公众号/服务号聊天只有一个左侧头像，可能被误判为单聊。如需严格排除，
可再叠加"标题栏是否有『关注』按钮"等特征（当前未实现）。

---

## 坑 7：进程被 MIUI 冻结 —— 一切"静默失效"的终极原因

**现象**：刚才还好好的，突然日志一条不出、浮窗不弹、`dumpsys` 仍显示服务已绑定。
进程状态 `S (sleeping)`、线程数正常、无崩溃。

**判定**（决定性证据）：

```powershell
& $adb -s $dev shell "cat /sys/fs/cgroup/apps/uid_<UID>/pid_<PID>/cgroup.freeze"
# 1 = 已冻结（Handler 停止、无障碍回调停止、日志停止）
# 0 = 正常
```

**根因**：MIUI / HyperOS 会把**仅持有无障碍绑定**的后台进程放进 cgroup freezer 冻结。
进程虽然还活着，但完全不执行代码。这解释了本项目调试期间反复出现的"突然死寂"。

**修复**：加入**前台服务保活**（`KeepAliveService`）：

- 无障碍服务 `onServiceConnected` 时启动，`onDestroy` 时停止
- 常驻通知内容固定（不含任何聊天信息）
- Android 14 用 `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性

**关键前提**：必须授予**通知权限**，否则 MIUI 仍会冻结：

```powershell
& $adb -s $dev shell pm grant com.tencent.mm.assistant android.permission.POST_NOTIFICATIONS
```

实测：授权前 `cgroup.freeze=1`，授权 + 重启后 `cgroup.freeze=0`，轮询持续运行。

**用户侧建议**（双保险）：

- 设置 → 应用管理 → 客服只读助手 → **省电策略 → 无限制**
- **自启动 → 允许**

> 注意：`dumpsys deviceidle whitelist +pkg` 对已冻结的进程**无效**，必须重启进程。

---

## 坑 8：ADB 注入的点击对悬浮窗无效（不是 bug）

**现象**：用 `adb shell input tap` 点浮窗里的按钮，完全没反应，连日志都不打；
但真人手指点击正常。

**判定**：窗口属性显示可触摸，却收不到事件：

```powershell
& $adb shell dumpsys window windows | Select-String "APPLICATION_OVERLAY" -Context 3,3
# fl=NOT_FOCUSABLE NOT_TOUCH_MODAL HARDWARE_ACCELERATED   ← 没有 NOT_TOUCHABLE
```

**根因**：MIUI/HyperOS 的安全策略会拦截**注入式**触摸对 overlay 窗口的投递（防点击劫持）。
真实触摸不受影响。

**应对**：

* 调试浮窗交互时**不要**用 `input tap` 判断"按钮坏了"，必须真人点；
* 若确实要自动化，改用 `input motionevent` 或让测试脚本走 UI Automator（同样可能被拦）；
* 记录结论：**浮窗交互只能人工验证**。

> 这条曾导致误判：一度以为 `ScrollView` 吞掉了子控件点击，把滚动功能删掉重做。
> 后来真人测试发现点击一直是好的，ScrollView 已恢复。

---

## 附：装包失败的两个坑

### A. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

**根因**：CI 每次构建使用临时 debug keystore，签名与已安装版本不一致。

**修复**：先 `uninstall` 再 `install`（`deploy.ps1` 已内置）。

### B. `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`

**根因**：MIUI 在**锁屏/熄屏**状态下拒绝 ADB 安装。

**修复**：安装前先唤醒并保持亮屏：

```powershell
& $adb -s $dev shell input keyevent KEYCODE_WAKEUP
& $adb -s $dev shell svc power stayon true
```

若仍失败：开发者选项 → 打开 **「USB 安装」**，并在手机弹窗点「继续安装」。

---

## 附：诊断日志速查表

日志 TAG：`KfReadOnly`。开关：设置页「本地日志」。

| 日志 | 含义 |
| --- | --- |
| `RAW onServiceConnected` | 服务回调触发（无条件日志） |
| `RAW app=true` | Application 单例就绪 |
| `POLL tick` | 轮询循环在跑（**没有这行 = 进程被冻结**） |
| `POLL root pkg=com.tencent.mm` | 拿到了微信根节点 |
| `POLL root_null` / `screen_off` | 拿不到根 / 熄屏跳过 |
| `detect ok title_len=5 msgs=5` | 识别成功（只记长度，不记原文） |
| `detect skip:no_input_bar` | 不是聊天页（列表页等） |
| `detect skip:no_container` | 没找到消息列表 |
| `detect skip:group_multi_avatar` | 群聊，已跳过 |
| `detect skip:non_single_title:reserved` | 标题命中保留词（微信/通讯录等） |
| `title_null msgs=N` | 单聊但标题读不到 → partial |
| `profile id_len=18 nick_len=4 linked=true` | 资料页识别成功且已绑定来源会话 |
| `read pkg=com.tencent.mm read_status=full` | 已发起回传 |
| `http host=... code=200` | 中控响应正常 |
| `overlay_shown` / `overlay_dismissed` | 浮窗显示/关闭 |
| `overlay_no_permission` | **悬浮窗权限丢失**（重装后常见） |
| `skip reason=unchanged` | 内容未变，按设计不重复回传 |
| `skip reason=throttled` | 5 秒频控内，按设计跳过 |

---

## 附：隐私边界（调试时也必须遵守）

- 本地日志**只记**：时间戳、包名、`read_status`、HTTP 状态码、长度类数值
- **绝不记录**：聊天文本、会话标题原文、微信号明文、中控响应正文
- 调试用的 `RAW` / `POLL` 日志同样不包含任何聊天内容
