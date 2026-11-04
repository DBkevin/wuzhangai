# 客服会话只读助手（kf-readonly-node）

微信客服会话**只读**手机端节点。只做三件事：

1. 读取当前微信**单聊**界面的有限信息（标题、最近 N 条可见消息文本）；
2. 回传中控（HTTP POST JSON）；
3. 展示中控返回的提示（悬浮窗）。

**严禁任何写入微信或自动操作微信的行为。** 发送消息永远由人工在微信内完成。

---

## 1. 硬性边界（与本仓库代码一一对应）

| 边界 | 实现位置 |
| --- | --- |
| 不调用 `dispatch`/`Gesture`、`perform`/`Action`、`ACTION_SET`/`TEXT`、`INPUT`/`TEXT` 类写入 API | 全工程零调用；可在仓库根目录搜索验证（见 §5） |
| 不自动发送消息、不写入输入框 | 悬浮窗按钮只 POST `/feedback`，见 `HintOverlayManager` |
| 不读微信数据库、不注入进程、不 hook | 无任何相关依赖与代码路径 |
| 默认无障碍读取，OCR 仅降级开关（默认关闭） | `SettingsStore.ocrFallbackEnabled` 默认 `false` |
| 回传数据仅四类字段 | `ReadReport`：`service_account_id` / `device_id` / `title` / `recent_messages` / `read_status` / `ts` |
| 聊天内容不落盘 | 会话内容仅存于内存，会话切换即清空（`ReportGate.onSessionChanged`） |

### 关于服务类名伪装

无障碍服务类名为 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`，
并在 `onServiceConnected()` 中把 `serviceInfo.packageName` 设为
`com.google.android.accessibility.selecttospeak`。

* **目的**：微信对第三方无障碍服务做节点混淆（text 取不到），伪装成系统"选择朗读"服务可绕过该混淆，拿到真实节点文本。
* **边界**：伪装只服务于"读取"。`accessibility_service_config.xml` 未声明 `canPerformGestures` / `canTakeScreenshot`，代码中也不存在任何写入或手势调用。业务实现在 `WeChatReadService`（`WeChatReadService.kt` 文件头有完整边界声明）。

## 2. 工程结构

```
.
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/
│   │   ├── com/tencent/mm/assistant/
│   │   │   ├── WeChatReadService.kt        # 无障碍只读业务实现（关键类）
│   │   │   ├── WechatIdReader.kt           # 「我」页微信号读取（唯一非会话字段）
│   │   │   ├── AssistantApp.kt             # 单例装配
│   │   │   ├── MainActivity.kt             # 状态展示 + 权限跳转
│   │   │   ├── core/                       # 提取/判定/节流（纯逻辑，可单测）
│   │   │   │   ├── ChatScreenDetector.kt   #   单聊判定 + 标题/消息提取
│   │   │   │   ├── MessageTextFilter.kt    #   时间戳/系统提示/表情占位过滤
│   │   │   │   ├── NodeTree.kt             #   只读节点树抽象
│   │   │   │   ├── ReportGate.kt           #   2s 去抖 + 5s 频控 + 去重
│   │   │   │   └── SessionSnapshot.kt      #   快照模型
│   │   │   ├── net/
│   │   │   │   ├── CenterClient.kt         # HTTP（关键类，3s 超时 + 重试 1 次）
│   │   │   │   └── CenterProtocol.kt       #   请求/响应模型
│   │   │   ├── overlay/
│   │   │   │   └── HintOverlayManager.kt   # 悬浮窗（关键类）
│   │   │   ├── settings/
│   │   │   │   ├── SettingsActivity.kt     # 设置页（关键类）
│   │   │   │   └── LocalLogActivity.kt     # 日志查看（仅状态行）
│   │   │   └── util/
│   │   │       ├── SettingsStore.kt        # 配置持久化 + device_id 哈希
│   │   │       └── SafeLogger.kt           # 受控日志（无聊天文本）
│   │   └── com/google/android/accessibility/selecttospeak/
│   │       └── SelectToSpeakService.kt     # 伪装服务入口（继承 WeChatReadService）
│   └── res/xml/accessibility_service_config.xml
├── app/src/test/.../CoreLogicTest.kt       # JVM 单测（节流/过滤/协议解析）
└── .github/workflows/build.yml             # CI 编译 + AI CI Healer
```

## 3. 配置中控地址

1. 安装 APK（CI 产物或 `assembleDebug` 产出），打开 **客服只读助手**；
2. 进入 **打开设置**；
3. 填写 **中控地址**（上报接口完整 URL，如 `http://10.0.0.5:8080/ingest`）；
4. （可选）填写 **客服账号别名**（如 `kf_001`）。若成功在微信「我」页读到过微信号，则优先使用微信号；
5. 保存。

**接口约定**

* 上报：`POST {中控地址}`，请求体：

  ```json
  {
    "service_account_id": "kf_001",
    "device_id": "android_id 的 sha-256",
    "title": "王强-8231-沪",
    "recent_messages": ["在吗", "我想问下订单12345"],
    "read_status": "full",
    "ts": 1730000000000
  }
  ```

* 响应：

  ```json
  {
    "action": "show_hint | show_candidates | no_match | none",
    "hint": "该顾客已由客服A添加",
    "candidates": [
      {"label": "客服A下的同名备注", "confidence": 0.82, "evidence": "相同手机号138****1234"}
    ],
    "request_id": "uuid"
  }
  ```

* 反馈：`POST {同源}/feedback`（自动从上报地址派生；如上报为 `http://10.0.0.5:8080/ingest`，反馈为 `http://10.0.0.5:8080/feedback`），携带 `request_id`、`label`、`candidate_index`、`decision(confirm|reject)` 等。

中控地址当前默认允许 HTTP 明文（`network_security_config.xml`），方便内网调试；生产请换 HTTPS 并收紧配置。

## 4. 开启权限

| 权限 | 入口 |
| --- | --- |
| 无障碍服务 | App 首页 →「前往开启无障碍服务」→ 找到 **选择朗读内容** → 开启 |
| 悬浮窗 | App 首页 →「前往开启悬浮窗权限」→ 允许显示在其他应用上层 |

无障碍服务开启后，仅当前台包名为 `com.tencent.mm` 时处理事件；离开微信立即清空内存会话缓存并收起悬浮窗。

## 5. 如何验证"只读不写入"

**静态验证（任何人可做）**：在仓库根目录执行（正则写法故意把禁用词拆开，避免本 README 自身命中）：

```bash
# 期望：无结果（排除 .github/workflows/build.yml —— 该文件按 PRD 7.1 原文包含约束声明文本）
grep -rniE "perform.?action|dispatch.?gesture|action_?set_?text|input_?text" \
  --include="*.kt" --include="*.java" --include="*.xml" .
```

Kotlin/XML 源码中不允许出现上述任何调用形态（无障碍 action 注入、手势注入、SET_TEXT/INPUT 类事件 action），即使作为"降级方案"也不允许。PRD 第 7.1 节规定的 `build.yml` 中 `custom-context` 含约束声明文字，属规范原文要求，不构成调用。

**行为验证**

1. 打开微信单聊 → 中控收到 `title + recent_messages`；切到群聊/公众号 → 中控**不**收到上报；
2. 关闭手机网络 → App 无崩溃、微信操作不受影响、悬浮窗不出现（HTTP 失败静默）；
3. 悬浮窗出现时，点击微信输入框可正常输入（悬浮窗 `FLAG_NOT_FOCUSABLE` 且不覆盖输入区）；
4. 悬浮窗按钮点击只会触发 `/feedback` 请求，微信内不会出现任何自动输入或发送；
5. 会话切换后内存缓存清空（可在中控侧观察：新会话首包立刻到达，旧会话不再上报）。

**日志验证**：设置页开启"本地日志"后，「查看本地日志」只有 `时间戳 事件 pkg=... read_status=... / host=... code=...` 这类状态行，永远没有聊天文本。

## 6. CI / CD（无本地 Android 环境）

* **编译**：push 到 `main` / `develop` 或对 `main` 发 PR 自动触发；`workflow_dispatch` 可手动触发。首次运行下载 Gradle 8.7 与依赖，之后 `gradle/actions/setup-gradle` 复用缓存。
* **失败诊断**：`build` job 失败后，`ai-heal` job 用 AI CI Healer（Gemini）分析日志，把**根因 + 修复建议**以 PR/issue 评论回写；置信度 < 50 不回写。
* **硬约束防越界**：workflow 的 `custom-context` 已写死"不得引入写入类 API、不得移除伪装"。若 AI 建议中出现任何写入类调用（点击/输入/手势注入），一律人工否决。
* **日志留存**：失败时上传 `build-logs` artifact，保留 7 天。

**失败处理闭环**：push → CI 编译 → 失败时把 AI CI Healer 评论内容贴给 AI 助手 → 只修被指出的问题 → 再 push，直至绿。

## 7. 已知限制

* 微信无公开 UI 契约，标题/消息容器的判定为启发式（顶部唯一居中文本 + 大面积列表容器 + 群聊/公众号特征过滤）。微信大版本更新可能需要微调 `ChatScreenDetector`。
* 标题读不到时回传 `title=null, read_status=partial`；消息节点被混淆时只回传标题（`partial`），均不重试、不抛异常。
* OCR 降级开关在本版本仅保留设置项与默认关闭语义（默认路径始终是无障碍读取），未内置截屏识别实现，避免引入截屏能力边界争议。
