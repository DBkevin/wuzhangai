# wuzhanai（客服会话只读助手）AI / DSH 开发铁律（AGENTS.md）

> **定位声明**：本仓库是医美客户业务中枢（Customer Hub）与 `D:\project\wechat-mgr` 配套的**安卓工作手机端实时只读悬浮窗节点（`kf-readonly-node`）**。
> 跨系统对接协议、微信双开分身识别机制与中控防打扰规范，**唯一权威文档在 `D:\project\xiaochengxu\docs\04-私域双通道融合(wechat-mgr与wuzhanai)及AI旁路规范.md`**；派单施工单见 `D:\project\xiaochengxu\docs\05-DSH分阶段原子施工单(WP01-WP16).md`（`WP-13` / `WP-14`）。

---

## 本仓不可逾越的 5 条红线

1. **100% 零写入、零注入、零自动操控微信（生死红线）**：
   - 全仓库 `.kt` / `.java` / `.xml` 源码中，**严禁出现任何调用**：`performAction`、`dispatchGesture`、`ACTION_SET_TEXT`、`input text`、无障碍自动点击/自动滚动/自动粘贴、微信进程注入/Hook、读取微信本地加密数据库或后台截屏！
   - 完工提交前必须执行以下静态门禁扫描，**匹配行数必须严格为 `0`**：
     ```powershell
     Select-String -Path 'D:\project\wuzhanai\app\src\main\**\*.kt','D:\project\wuzhanai\app\src\main\**\*.java','D:\project\wuzhanai\app\src\main\**\*.xml' -Pattern 'perform.?action|dispatch.?gesture|action_?set_?text|input_?text'
     ```
2. **不得移除无障碍服务类名伪装与前台保活**：
   - `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 与 `KeepAliveService` 是绕过微信节点混淆及 MIUI/HyperOS `cgroup.freeze` 冻结的核心保障（详见 `docs/TROUBLESHOOTING.md`），严禁改名或移除。
3. **中控通信协议与鉴权契约**：
   - `SettingsStore.deriveFeedbackUrl()` 基于 `URI.path` 替换末段为 `/feedback`（会丢弃 `?query` 参数），因此生产中控 URL 统一采用**路径段携带设备密钥**：`https://<域名>/api/hub/kf/<device_token>/ingest`（自动派生 `/api/hub/kf/<device_token>/feedback`）；
   - 中控响应为**根级裸 JSON**（`{"action":"show_hint|show_candidates|no_match|none", "hint":"...", "candidates":[...], "request_id":"..."}`），App 侧只增可选字段（如 `self_nickname`），保持向后兼容。
4. **一机“微信双开/分身（主号+旧号）”防串号规则**：
   - 严禁在 `WeChatReadService.maybeCaptureWechatId()` 中做“只扫一次即永久锁死 `cachedWechatId`”的逻辑；每次经过任一微信实例的「我」页必须允许动态更新活跃微信号，且 `WechatIdReader` 正则必须支持 `_` 下划线开头的自定义微信号（`^[A-Za-z_][A-Za-z0-9_-]{5,19}$`）；
   - 单聊界面提取头像昵称时，同步采集屏幕右半侧（`centerX >= screenWidth / 2`）的本人气泡头像昵称作为 `self_nickname` 上报中控，供中控精准区分同一手机上的主号与分身旧号。
5. **本地零聊天落盘铁律**：
   - `SharedPreferences` 与 `SafeLogger` 严禁写入任何聊天消息原文、会话标题原文或对方微信号明文；离开微信前台必须立即清空内存会话快照并收起悬浮窗。
6. **100% 云端 GitHub Actions 编译铁律（严禁在本机安装环境或跑本地 Gradle 编译）**：
   - **禁止在开发机本地安装 JDK / Android SDK 或执行 `.\gradlew.bat` 本地编译 APK**！所有 Kotlin 编译、JVM 单测（`testDebugUnitTest`）与 APK 打包（`assembleDebug`）**必须 100% 通过远端 GitHub Actions（`DBkevin/wuzhangai` 仓库 `.github/workflows/build.yml`）云端执行**。
   - **固定 CI Debug 签名防清配置**：`app/build.gradle.kts` 的 `debug` 构建必须绑定仓库内固定的测试签名文件（`app/debug-ci.jks`，标准公开测试口令 `android`，不含任何生产密钥），确保 GitHub Actions 每次云端产出的 `app-debug.apk` 签名完全一致，支持真机直接 `adb install -r` 无损覆盖升级（不丢手机端 URL 配置与无障碍授权）。
   - **DSH 云端编译标准闭环流程**（复用 `tools\deploy.ps1` 的 GitHub API 凭据机制，禁止使用任何 `adb shell input`）：
     1. 完成本地静态零写入红线扫描（`0` 命中）后，`git commit` 并 `git push origin main` 触发 GitHub Actions `Android Build`；
     2. 通过 `C:\Program Files\Git\cmd\git.exe credential fill` 获取 GitHub Token，轮询 `https://api.github.com/repos/DBkevin/wuzhangai/actions/runs?per_page=5` 等待当前 `head_sha` 对应的 workflow 完成；
     3. 若 `conclusion != 'success'`：自动下载 `build-logs` 产物读取编译/单测报错并修复重推，直至云端全绿；
     4. 若 `conclusion == 'success'`：自动下载 `app-debug-apk` 解压至 `D:\project\wuzhanai\.runtime\app-debug.apk`；若 USB 真机在线（`adb devices`），直接执行 `adb install -r D:\project\wuzhanai\.runtime\app-debug.apk` 完成覆盖安装（严禁 `adb uninstall`，除非首次切换至固定 `debug-ci.jks` 签名时经用户确认）。
