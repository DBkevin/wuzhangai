# 身份关联设计：聊天页 ↔ 个人资料页

> 目标：让中控能确定"这条会话属于哪个人"，从而**精确取任务，避免取错**。

## 1. 问题

同一顾客在两处出现：

| 页面 | 能读到的信息 |
| --- | --- |
| 单聊页 | 会话标题（备注名或昵称，从头像 `content-desc` 还原）、最近 N 条消息 |
| 个人资料页 | 昵称、**微信号** |

只靠昵称字符串匹配会出错，因为：

1. 单聊标题通常是**备注名**（如"王强-8231-沪"），资料页顶部是**昵称**（如"旺仔小李"），两者常常不同；
2. 不同顾客可能重名；
3. 昵称可以被随时修改。

## 2. 核心思路：不靠名字，靠"时间邻接"

资料页**只能由人工从某个单聊点进去**（本 App 绝不点击、不跳转）。因此：

> 在一段时间窗内，如果"刚看过会话 A"，紧接着出现"资料页 P"，那么 P 必然属于 A。

这是**结构上的因果关系**，比任何字符串匹配都可靠。

```
时间轴：
  t0  进入单聊「一滴相思泪」   → 记录 lastChatTitle="一滴相思泪", lastChatAt=t0
  t1  人工点头像 → 资料页      → t1-t0 < 5min，判定绑定成功
                               → 回传 linked_title="一滴相思泪", contact_wechat_id="wxid_xxx"
  t2  回到单聊                 → 会话上报自动带上 identity_key="wxid_xxx"
```

时间窗取 **5 分钟**（`PROFILE_LINK_WINDOW_MS`）：覆盖"点开看一眼再回来"的操作习惯，
又能避免隔太久误绑到别的会话。

## 3. 协议字段

在原字段基础上新增（旧字段保持不变，向后兼容）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `page_type` | `"chat" \| "profile"` | 页面类型 |
| `contact_wechat_id` | string \| null | 资料页微信号；聊天页若已学到则也会带上 |
| `linked_title` | string \| null | 资料页的**来源会话标题**（绑定的关键） |
| `profile_nickname` | string \| null | 资料页顶部昵称 |
| `identity_key` | string \| null | **统一身份键**：有微信号用微信号，否则退回会话标题 |

### 资料页上报示例

```json
{
  "page_type": "profile",
  "title": "一滴相思泪",
  "linked_title": "一滴相思泪",
  "profile_nickname": "旺仔小李",
  "contact_wechat_id": "wxid_abc123",
  "identity_key": "wxid_abc123",
  "recent_messages": [],
  "read_status": "full",
  "ts": 1730000000000
}
```

### 聊天页上报示例（已学到微信号后）

```json
{
  "page_type": "chat",
  "title": "一滴相思泪",
  "contact_wechat_id": "wxid_abc123",
  "identity_key": "wxid_abc123",
  "recent_messages": ["在吗", "我想问下订单12345"],
  "read_status": "full",
  "ts": 1730000000100
}
```

## 4. 中控侧建议实现

### 4.1 建立绑定表

收到 `page_type=profile` 且 `linked_title` 与 `contact_wechat_id` 均非空时，写入：

```
bindings[linked_title] = contact_wechat_id
```

**只信任带 `linked_title` 的资料页上报**（说明是从会话点进去的）。
从通讯录点开的资料页没有 `linked_title`，不应建立绑定。

### 4.2 取任务时的优先级

```
identity = contact_wechat_id ?? bindings[title] ?? title
```

1. **有微信号** → 用微信号查，最精确
2. 有绑定表 → 用 `bindings[title]` 得到微信号再查
3. 都没有 → 退回用会话标题查（可能不准，建议标注"低置信度"）

### 4.3 冲突处理

若同一 `linked_title` 先后绑定到不同微信号：

- 以**最新一次**为准（顾客可能被重新备注/改名）
- 保留历史记录并记 `updated_at`，便于人工复核
- 若两个微信号都活跃，视为"同一备注名对应多人"，取任务时降级为需人工确认

### 4.4 反馈闭环

用户点「确认关联 / 不是同一人」后，App 会 POST `/feedback`（含 `request_id`、`label`、
`candidate_index`、`decision`）。中控可据此修正绑定表，形成人工校准闭环。

## 5. App 侧的缓存策略

| 数据 | 存放位置 | 生命周期 |
| --- | --- | --- |
| 会话标题 → 微信号 映射 | **内存** `wechatIdByTitle` | 进程结束即失效 |
| 最近一次会话标题 | **内存** `lastChatTitle` | 进程结束即失效 |

**不落盘**。原因：这是联系人数据，不属于"配置项"。持久化交给中控，
App 每次冷启动后由人工再打开一次资料页即可重新学到 —— 中控侧已有记录，不影响取任务。

## 6. 已知局限

1. **公众号/服务号**：只有一个左侧头像，可能被误判为单聊并建立绑定。
   建议中控对标题做白名单校验，或忽略 `identity_key` 为标题（无微信号）的低置信度记录。
2. **群聊**：已通过"左侧多个不同头像"排除，不会上报。
3. **对方未公开微信号**：`contact_wechat_id=null`，`read_status=partial`，
   此时只能退回用标题匹配。
4. **人工操作顺序异常**：若用户从通讯录直接进资料页（未经过会话），
   `linked_title=null`，不做绑定（这是有意为之，避免误绑）。

## 7. 验证方法

```powershell
# 1. 进单聊，确认日志出现 detect ok
& $adb -s $dev logcat -d -s KfReadOnly | Select-String "detect ok"

# 2. 手动点头像进资料页，确认出现 profile ... linked=true
& $adb -s $dev logcat -d -s KfReadOnly | Select-String "profile"

# 3. 返回单聊，确认上报已带 identity_key（中控面板可看到 contact_wechat_id 非空）
```

中控 stub 的浮窗提示会直接显示绑定结果：`已绑定：一滴相思泪 = wxid_xxx`。
