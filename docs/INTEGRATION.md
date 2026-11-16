# 中控对接文档（API Integration Guide）

> 面向：中控后端开发。
> 本文档描述的**全部接口**都是「手机端 App → 中控」的主动调用；中控**不需要**向手机推送任何请求
> （App 不监听端口、不接受入站连接）。
>
> 版本：v1.1（对应 App `versionName 1.0.1` / `versionCode 2`）
> 最后更新：2026-11-16

---

## 0. 一页速览

| 项目 | 值 |
| --- | --- |
| 协议 | HTTP/1.1，JSON（UTF-8） |
| 方法 | 全部 `POST` |
| 接口 1 | `{中控地址}` —— 会话/资料页上报（App 主动调用）|
| 接口 2 | `{中控地址同源的 /feedback}` —— 候选反馈（用户点按钮时调用）|
| 超时 | 3 秒（连接/读/写），另有一次整体重试 |
| 重试 | **最多 2 次尝试**（首次 + 重试 1 次），均失败则静默放弃 |
| 鉴权 | 当前版本**无鉴权**（内网部署）；如需鉴权见 §7 |
| 频率 | 同一会话 ≥ 5 秒一次；内容未变不重复发；标题变化可突破 5 秒限制 |
| 失败策略 | 任何失败都不弹提示、不阻塞、不重试超过 1 次 |

---

## 1. 接入前准备：在手机上配置

| 配置项 | 位置 | 说明 |
| --- | --- | --- |
| 中控地址 | App → 设置 → 中控地址 | **完整 URL**，如 `http://10.0.0.5:8080/ingest` |
| 客服账号别名 | App → 设置 → 客服账号别名 | 如 `kf_001`；若读到微信号则优先用微信号 |
| 回传消息条数 N | App → 设置 | 1~20，默认 5 |
| 悬浮窗不透明度 | App → 设置 | 30~100%，默认 65% |
| 读取资料页微信号 | App → 设置 | 默认开，可关 |

**反馈地址自动派生**：若中控地址是 `http://10.0.0.5:8080/ingest`，则反馈地址自动为
`http://10.0.0.5:8080/feedback`（同 scheme + host + 端口，路径替换为同级的 `/feedback`）。
如需不同地址，代码中 `SettingsStore.feedbackUrlOverride` 可覆盖（当前无 UI，可通过 prefs 写入）。

> 明文 HTTP 默认允许（内网场景）。生产建议换 HTTPS。

---

## 2. 接口 1：上报（`POST {中控地址}`）

### 2.1 触发时机

| 时机 | `page_type` |
| --- | --- |
| 用户在微信**单聊**界面停留（每 2 秒轮询一次，内容有变化才发）| `chat` |
| 用户**手动点开**某人的个人资料页 | `profile` |

**不会触发的情况**：群聊、公众号/服务号、朋友圈、聊天列表页、非微信应用前台。

### 2.2 请求体

```json
{
  "service_account_id": "kf_001",
  "device_id": "3f2a9c8e1b7d4a6f0c5e8b2d9a1f4c7e",
  "title": "王强-8321-川田来中心-26121466889",
  "recent_messages": ["在吗", "我想问下订单12345"],
  "read_status": "full",
  "ts": 1730000000000,
  "page_type": "chat",
  "contact_wechat_id": null,
  "linked_title": null,
  "profile_nickname": null,
  "identity_key": "王强-8321-川田来中心-26121466889"
}
```

### 2.3 字段定义

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `service_account_id` | string | 否 | 当前登录客服的标识。优先微信「我」页读到的微信号，读不到则用设置里的别名。可能为空字符串（都未配置）|
| `device_id` | string | 否 | `android_id` 的 SHA-256（64 位小写十六进制）。用于区分设备 |
| `title` | string \| null | **是** | 会话标题。`chat` 时为对方备注名/昵称；`profile` 时为来源会话标题（见 `linked_title`）|
| `recent_messages` | string[] | 否 | 最近 N 条可见消息，**按时间从旧到新**。已过滤时间戳、系统提示、表情占位符、语音时长 |
| `read_status` | string | 否 | `full` 或 `partial`，见 §2.4 |
| `ts` | number | 否 | 采集时刻，Unix 毫秒 |
| `page_type` | string | 否 | `chat` 或 `profile` |
| `contact_wechat_id` | string \| null | 是 | 对方微信号。`profile` 页读到时有值；`chat` 页若之前学过该会话的微信号也会带上 |
| `linked_title` | string \| null | 是 | **仅 `profile` 有值**：该资料页是从哪个会话点进去的（5 分钟内有效）|
| `profile_nickname` | string \| null | 是 | **仅 `profile`**：资料页顶部昵称（常与 `title` 的备注名不同）|
| `identity_key` | string \| null | 是 | 统一身份键：有微信号用微信号，否则用标题 |

### 2.4 `read_status` 语义

| 值 | 含义 | 中控应对 |
| --- | --- | --- |
| `full` | 标题与消息都正常读到 | 正常匹配 |
| `partial` | 标题读不到（`title=null`）**或**消息节点被混淆（`recent_messages=[]`）| 降级处理，建议走人工/模糊匹配 |

> 注意：`partial` + `title=null` 是**真实存在的正常情况**（微信改版可能让标题读不到），
> 不是异常。App 不会重试、不会抛错。

### 2.5 实测样例

**单聊（长备注 25 字）**

```json
{
  "service_account_id": "kf_001",
  "device_id": "3f2a9c8e...",
  "title": "王强-8321-川田来中心-26121466889",
  "recent_messages": ["刚刚看到", "权威媒体有的呀", "明天上班", "我发新闻源后台给你哈", "行"],
  "read_status": "full",
  "ts": 1730000000000,
  "page_type": "chat",
  "contact_wechat_id": null,
  "linked_title": null,
  "profile_nickname": null,
  "identity_key": "王强-8321-川田来中心-26121466889"
}
```

**资料页（已绑定来源会话）**

```json
{
  "service_account_id": "kf_001",
  "device_id": "3f2a9c8e...",
  "title": "大宝贝儿",
  "recent_messages": [],
  "read_status": "full",
  "ts": 1730000000100,
  "page_type": "profile",
  "contact_wechat_id": "__LINXIAO",
  "linked_title": "大宝贝儿",
  "profile_nickname": "小小林",
  "identity_key": "__LINXIAO"
}
```

---

## 3. 接口 1 的响应（中控 → App）

### 3.1 响应体

```json
{
  "action": "show_hint",
  "hint": "该顾客已由客服A添加",
  "candidates": [],
  "request_id": "0f3b1c9a-2e4d-4a71-9c8b-1d2e3f4a5b6c"
}
```

### 3.2 字段定义

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `action` | string | 是 | `show_hint` / `show_candidates` / `no_match` / `none`；未知值按 `none` 处理 |
| `hint` | string | 否 | `show_hint` 时展示的文本；**支持 `\n` 换行**，每行会变成一条独立可复制的条目。上限 2000 字符 |
| `candidates` | object[] | 否 | `show_candidates` 时使用，**最多取前 3 条** |
| `candidates[].label` | string | 是 | 候选名称，上限 160 字符 |
| `candidates[].confidence` | number | 是 | 0~1 的小数（`0.82` 表示 82%）。传 0~100 也可，但显示会变成 `8200%`，请按 0~1 传 |
| `candidates[].evidence` | string | 是 | 佐证说明，上限 160 字符 |
| `request_id` | string | 建议 | 反馈时原样回传，用于关联 |

### 3.3 各 action 的手机端表现

| action | 悬浮窗表现 | 用户操作 |
| --- | --- | --- |
| `show_hint` | 顶部 1/3 面板，`hint` 按行拆成列表，每条右侧「复制」 | 复制某条 / 关闭 |
| `show_candidates` | 每条候选一行（label + 置信度 + evidence），右侧「复制」；下方「确认关联」「不是同一人」 | 复制 / 确认 / 拒绝 / 关闭 |
| `no_match` | 显示「未找到该顾客的历史记录」 | **5 秒后自动消失** |
| `none` | **不显示任何窗口** | — |

> `action` 缺失或非法值 → 按 `none`，静默。

### 3.4 状态码要求

* **HTTP 200** 且响应体是合法 JSON → 按上面的 `action` 处理
* 非 200 / 非法 JSON / 超时 → **视为失败，静默丢弃**（不弹窗、不提示用户）
* 响应体为空的 200 → 同样视为失败

---

## 4. 接口 2：候选反馈（`POST /feedback`）

### 4.1 触发时机

用户在 `show_candidates` 悬浮窗里点「确认关联」或「不是同一人」。

### 4.2 请求体

```json
{
  "request_id": "0f3b1c9a-2e4d-4a71-9c8b-1d2e3f4a5b6c",
  "service_account_id": "kf_001",
  "device_id": "3f2a9c8e...",
  "title": "大宝贝儿",
  "label": "客服A下的同名备注",
  "candidate_index": 0,
  "decision": "confirm",
  "accepted": true,
  "ts": 1730000000200
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `request_id` | string \| null | 回传上报响应里的 `request_id` |
| `service_account_id` | string | 同上报 |
| `device_id` | string | 同上报 |
| `title` | string \| null | 当时的会话标题 |
| `label` | string | 用户点击的那条候选的 `label` 原文 |
| `candidate_index` | number | 该候选在响应数组里的下标（0 起）|
| `decision` | string | `confirm`（确认关联）/ `reject`（不是同一人）|
| `accepted` | boolean | `true` = 确认；`false` = 拒绝（与 `decision` 冗余，便于老系统兼容）|
| `ts` | number | 点击时刻，Unix 毫秒 |

### 4.3 响应

返回 `200` + 任意 JSON（如 `{"ok":true}`）即可，App 不解析内容。

---

## 5. 时序图

```
手机 App                                  中控
   │                                        │
   │  (每 2s 轮询微信界面)                   │
   │  内容有变化 && 距上次 ≥5s               │
   │──────── POST {center_url} ────────────>│
   │        (会话标题 + 最近 N 条消息)        │
   │                                        │  匹配顾客/任务
   │<─────── 200 {action, hint, ...} ───────│
   │                                        │
   │  按 action 弹悬浮窗                     │
   │                                        │
   │  用户点「确认关联」                      │
   │──────── POST /feedback ───────────────>│
   │        {request_id, label, decision}   │  修正绑定
   │<─────── 200 {"ok":true} ───────────────│
   │                                        │
```

---

## 6. 频率、去重与失败处理（务必按此设计中控）

### 6.1 频率

| 规则 | 值 | 说明 |
| --- | --- | --- |
| 轮询周期 | 2 秒 | App 每 2 秒读一次界面 |
| 同会话最小间隔 | **5 秒** | 5 秒内的变化会被丢弃（`skip reason=throttled`）|
| 标题变化 | 不受 5 秒限制 | 切换会话立即上报 |
| 内容未变 | 不发送 | 指纹（标题 + 消息列表）相同则跳过 |
| 会话切换 | 立即清空内存缓存 | 旧会话不会串到新会话 |

**结论**：中控侧不需要自己做限流，App 已经控制。峰值约 **0.2 次/秒/设备**（5 秒一次）。

### 6.2 失败处理

| 情况 | App 行为 |
| --- | --- |
| 连接失败 / 超时（3s）| 重试 1 次 |
| 非 200 | 重试 1 次 |
| 仍失败 | 静默放弃，**不弹任何提示**，仅写本地日志 |
| 响应非法 JSON | 当作失败，静默 |
| 中控地址为空 | 直接放弃，不发请求 |

> **重要**：中控挂掉不会影响微信使用、不会崩溃、不会堆积请求。

### 6.3 中控侧幂等建议

App 可能因重试/重新进入会话而重复发送**相同内容**（虽然概率低）。建议中控按
`(device_id, title, ts)` 或内容哈希做幂等，避免重复建任务。

---

## 7. 安全与部署建议

1. **网络**：当前为明文 HTTP（内网调试方便）。生产请：
   * 用 HTTPS + 有效证书；或
   * 保留 HTTP 但限定内网/VPN（如 EasyTier、Tailscale）
2. **鉴权**：当前版本请求头不带 token。若需要，建议：
   * 中控校验来源 IP（最省事）
   * 或在 `center_url` 里带查询参数（如 `?token=xxx`），App 会原样带上
3. **数据合规**：
   * 回传内容仅：客服标识、设备哈希、会话标题、最近 N 条消息、时间戳、微信号（可选）
   * **不采集**通讯录、好友列表、非当前会话内容、微信数据库
   * 手机端**不落盘**聊天内容，进程结束即清空
4. **日志**：手机端本地日志只记时间戳/包名/`read_status`/HTTP 状态码，**不含聊天文本**

---

## 8. 身份关联（微信号绑定）—— 中控侧实现要点

详见 [IDENTITY-LINKING.md](IDENTITY-LINKING.md)，这里只给实现清单：

1. 收到 `page_type=profile` 且 `linked_title`、`contact_wechat_id` 均非空 → 写入绑定表
   ```
   bindings[linked_title] = contact_wechat_id
   ```
2. 收到 `page_type=chat` → 取任务时按以下优先级：
   ```
   identity = contact_wechat_id ?? bindings[title] ?? title
   ```
3. 同一 `linked_title` 绑定到不同微信号时：以最新为准，保留历史并记录 `updated_at`
4. 收到 `/feedback` 的 `confirm`/`reject` → 修正或删除对应绑定

> **为什么不能只靠昵称匹配**：会话标题是**备注名**（如 `王强-8321-沪`），资料页显示的是**昵称**
> （如 `小小林`），两者通常不同。App 用「时间邻接」把资料页绑到刚看过的会话，中控只需存这张表。

---

## 9. 最小可用中控（联调参考）

仓库自带一个仅依赖 Python 标准库的模拟中控：

```bash
python tools/center_stub.py --port 8099
# 手机设置里填： http://<PC的局域网IP>:8099/ingest
# 浏览器打开 http://127.0.0.1:8099/ 可看实时上报面板
```

它实现了本文档的全部接口，可直接作为对接参考实现或联调桩。

---

## 10. 联调自检清单

| # | 检查项 | 预期 |
| --- | --- | --- |
| 1 | 手机打开单聊，停留 6 秒 | 中控收到 1 条 `page_type=chat`，`title` 非空 |
| 2 | 中控返回 `show_hint` | 手机顶部 1/3 出现面板，可点「复制」写入剪贴板 |
| 3 | 中控返回 `show_candidates`（≤3 条）| 每条一行，带「确认关联/不是同一人」|
| 4 | 点「确认关联」| 中控收到 `/feedback`，`decision=confirm` |
| 5 | 中控返回 `no_match` | 面板显示 5 秒后自动消失 |
| 6 | 中控返回 `none` 或空 | 不出现任何面板 |
| 7 | 停掉中控，再切会话 | 手机不崩溃、微信正常、不弹窗 |
| 8 | 切到群聊 / 公众号 | 中控**收不到**上报 |
| 9 | 手动点开某人资料页 | 中控收到 `page_type=profile`，含 `contact_wechat_id` 与 `linked_title` |
| 10 | 同一会话静置 30 秒 | 中控不应收到重复上报（内容未变）|

---

## 11. 版本与兼容

| App 版本 | 新增字段 | 兼容说明 |
| --- | --- | --- |
| 1.0.0 | 基础 6 字段 | — |
| 1.0.1 | `page_type`、`contact_wechat_id`、`linked_title`、`profile_nickname`、`identity_key` | 中控忽略未知字段即可；旧中控仍能工作 |

**约定**：App 只增字段、不改已有字段语义。中控解析时请**忽略未知字段**。

---

## 12. 常见问题

**Q：为什么 `title` 有时是 null？**
A：微信改版后聊天标题栏文本可能不在无障碍树里。App 会退回用「消息头像昵称」还原；
仍失败时 `title=null, read_status=partial`。这是**预期行为**，不是故障。

**Q：为什么 `recent_messages` 里没有语音/图片内容？**
A：语音消息只暴露时长（已被过滤），图片/视频无可读文本。需要内容请走 OCR（默认关闭）。

**Q：同一会话为什么偶尔重复上报？**
A：切换会话会清空缓存，再切回会重新上报一次。中控做幂等即可。

**Q：中控返回很长的 hint 会怎样？**
A：面板顶部 1/3、内部可滚动，最多 2000 字符；超出截断。

**Q：能主动让手机做什么吗？**
A：不能。App 不监听端口、不接受推送。所有交互都是 App 主动请求 + 中控在响应里下指令。

**Q：手机上需要装什么？**
A：只有本 App + 开启无障碍 + 悬浮窗权限（详见 README §4）。
