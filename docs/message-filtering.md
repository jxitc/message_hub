# 消息筛选与删除 — Filtering & Deletion

> 设计原则：**先筛选，再显示，再操作**。删除不是"清空某个邮箱"，而是在筛选结果上的一个动作。

## 为什么是这个模型

早期的做法是"按邮箱清空"（重置某个 device 的全部消息）。这在实际使用里很快就不够用：

- 想清掉的是"**抖音这一个月推的通知**"，不是"这台手机的所有消息"；
- 想清掉的是"**发给 hotmail 那个信箱的验证码**"，不是"所有邮件"；
- 同一批要删的东西，往往要跨 device、跨类型才能框出来。

所以逻辑反过来：**筛选是主语，删除是谓语**。筛选维度是通用的，删除只是作用在
当前筛选结果上的一个操作——这样以后加新维度（发件人、关键词…）时，删除自动跟着能用。

## 筛选维度

| 维度 | 参数 | 说明 |
|------|------|------|
| 类型 | `type` | `SMS` / `PUSH_NOTIFICATION` / `EMAIL` / `CALL_LOG` |
| 设备 | `device` | 即 `source_device_id`（`android-phone-1`、`mail-main`…） |
| 收件人 | `recipient` | 邮件投递到的地址（`jxitc@hotmail.com`），大小写不敏感；见 `docs/mail-collector.md` |
| 起始时间 | `since` / `since_utc` | 含当天/该时刻 |
| 结束时间 | `until` / `until_utc` | 含当天（`YYYY-MM-DD` 按整天算） |

时间筛的是**消息自身的时间**（事情发生的时间，`timestamp`），不是 MH 收到的
时间（`received_at`）。补录的历史邮件这两者能差好几天，而人是按前者思考的。

### 时区：为什么有 `since` 和 `since_utc` 两个参数

`<input type="date">` 只给日期、没有时区。服务器若直接把 `2026-09-01` 当成 UTC 日，
在 UTC+8 就会错开 8 小时。所以网页在**提交时用浏览器时区**把日期换算成两个瞬时点
（本地当天 00:00:00 和 23:59:59.999 的 UTC ISO 串）放进 `since_utc` / `until_utc`；
服务器优先用 `*_utc`，没有才回退把裸日期当 UTC 日读。CLI / API 调用方给裸日期即按 UTC 日。

DB 里 `timestamp` 存的是 **naive UTC**（SQLAlchemy 的 SQLite DATETIME 写入时会丢掉
tzinfo），所以比较时绑定 naive UTC datetime，避免和带 `+00:00` 的字符串混着比。

## 三种删除入口

删除是**永久**的，没有回收站。三个入口都做了防误触：

### 1. 网页：勾选后删除（当前页）

`/messages` 每条消息左侧有复选框，表头有"全选本页"。按钮显示
「Delete selected」+ 已选条数，未勾选时禁用。提交前弹确认框报数量。
POST `/messages/delete`，表单字段 `ids`（可重复）。上限 1000 个 id。

### 2. 网页：删除全部匹配项

筛选生效时才出现「Delete all N matching」按钮（**N 是服务端算的真实总数**，
不是当前页条数）。POST `/messages/delete-filtered`，带当前筛选条件，
且**必须** `confirm=yes`——JS 在确认框点"确定"后才把这个字段解禁；
没有筛选条件时服务端直接拒绝（否则等于"删库"）。

> 无 JS 环境下这个按钮只能走安全路径（服务端会因缺 `confirm` 而拒绝），
> 不会误删。

### 3. API / CLI

```bash
# 按 id 删（精确）
message-hub delete --id 3f2a... --id 9c1b...

# 按筛选删：默认演练，只报数量
message-hub delete --device mail-main --until 2026-08-31
# 🔎 42 messages match [device=mail-main, to=2026-08-31] — nothing deleted.
#    Re-run with --yes to delete them.

# 确认后执行
message-hub delete --device mail-main --until 2026-08-31 --yes
```

REST API：

| 方法 | 路径 | 说明 |
|------|------|------|
| `DELETE` | `/api/v1/messages` | body `{"ids": [...]}`，精确删除 |
| `POST` | `/api/v1/messages/delete` | body `{type/device/recipient/since/until, dry_run}`；**`dry_run` 默认 `true`**，忘写只会得到数量而不是数据丢失 |

`POST /messages/delete` 要求至少一个筛选条件——不带条件的调用会清空整个 hub。

## 实现要点

**筛选逻辑只有一份**：`message_filters.py`。网页、API、CLI、以及删除路径都走
`apply_filters()` / `apply_filter_dict()`。这是刻意的：如果"预览"和"删除"用了两套
筛选代码，它们迟早会差一两行，用户就会丢掉自己从没看到过的数据。

- `apply_filters(query, message_type=..., ...)` — 给直观调用方用；
- `apply_filter_dict(query, filters)` — 给持有 `normalize_filters()` 结果的调用方用
  （该 dict 里键叫 `type`，而 `type` 不能当关键字参数，`**filters` 会直接抛错）。

`recipient` 用 SQLite 的 JSON1 在 SQL 里匹配 `metadata.recipients` 数组：

```sql
EXISTS (SELECT 1 FROM json_each(messages.message_metadata, '$.recipients')
        WHERE lower(json_each.value) = :mh_recipient)
```

必须在 SQL 里做——在 Python 里过滤会让 `pagination.total` 和分页都失真。
JSON1 需要 SQLite ≥ 3.38（服务器实测 3.46.1；本机 3.37.0 也带 JSON1）。
`list_recipients()`（下拉框选项）在 JSON1 缺失时返回空列表，不让整个页面挂掉。

## 设计取舍：「对端」为什么暂时还留在 JSON 里（2026-09-12 决定）

现状盘点（线上真实数据）——三个渠道各有一套不重叠的键：

| 渠道 | metadata 键 |
|---|---|
| PUSH_NOTIFICATION（1735 行） | `package_name` / `app_name` / `notification_id` / `timestamp` / `source` / `title` |
| SMS（63 行） | `phone_number` / `contact_name` / `timestamp` / `source` / `message_id` |
| EMAIL（4 行） | `mailbox` / `message_id` / `subject` / `recipients` |

看起来像"每个渠道一套字段"，但把它们翻译成**角色**后其实只有一套：谁发的（`sender` 列）、
发给谁（**目前缺一等公民**）、在哪发生的（`source_device_id` 列）、什么类型（`type` 列）、
什么时候（`timestamp` 列）；剩下的 `message_id`/`notification_id`/`package_name`/`delivered_to`
是渠道细节，只用于去重和留档，不参与筛选。原则：**渠道不进 schema，角色才进。**

"发给谁"是所有渠道共有的维度（邮件 `To`、短信是收到的号码、群聊是群名），现在被邮件独占，
这是已知的味道。

**评估过的方案**：`message_participants(message_id, role, address, name)` 子表，
`role ∈ {from,to,cc,bcc}`，索引 `(address, role)`，由 hub 在**入库时统一派生**
（`derive_participants(type, sender, metadata)`）——客户端不用改，新渠道只加一个映射分支，
白送"按对端筛选"（含现在完全缺失的按发件人筛选）。

**决定：暂不做**，等第二个非邮件来源真的接进来再上。理由是现在只有邮件有对端，
此时建表是为想象中的第二个渠道设计 schema（YAGNI）。代价要记住：

- JSON 多值数组**没法建索引**（表达式索引只能索引单个标量路径，数组要么每个位置一个索引，
  要么 `json_each` 全表扫），所以 `recipient` 筛选是线性扫描。1,802 行无感，
  但按 207 条/天算，一年 ~7.5 万行后会开始变成几十到上百毫秒并线性增长——这是 JSON 方案的硬天花板，
  不是调参能解决的；
- 那之后要回填的数据比现在多（回填来源是 `sender` + metadata，或按 `Message-ID` 回邮箱重取头，
  `scripts/backfill-mail-recipients.py` 已经是这个套路，可复用）；
- 期间筛选维度仍带邮件色彩（下拉框叫 "Received at (To)"）。

**本次已做的清理**：metadata 里原来同时存 `recipients`（规范化）和 `to`/`cc`/`delivered_to`/
`original_to`（原始头），是同一个事实的两份机读副本。既然暂时靠 JSON 承载查询，
`recipients` 就是那个唯一真相来源，原始头不再进 metadata（可读版本仍在 `content` 的 `To:` 行）。
`migrate.py` 幂等清掉老数据里的这几个键。

## 验证记录（2026-09-12）

- 本机功能测试（临时 SQLite，不碰真实数据）：筛选 6 例、API 5 例、
  dry-run / 按 id / 按筛选删除 8 例、防误触 5 例、日期边界 5 例 —— **全过**；
- CLI 端到端（真起服务 + 真 CLI 子进程）：列筛选 4 例、拒绝无筛选 2 例、
  演练 2 例、`--yes` 删除 2 例、按 id 1 例、按收件人 1 例 —— **全过**；
- 线上（Cloudflare → VPS）：收件人筛选 `jxitc@hotmail.com` → 3 封、
  `jiangxjx@gmail.com` → 1 封；日期区间可用；自建一条 `mh-selftest` 消息 →
  演练报 1 → 实删 1 → 回查 0（用完即删，未触碰真实数据）。
