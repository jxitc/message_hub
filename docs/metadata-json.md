# metadata JSON

> **默认策略：不确定的字段先塞 `message_metadata`（JSON）。**
>
> 这不是"临时方案"，它就是常规落点。渠道会一直变，等想清楚了再决定要不要升成列，
> 比现在猜一个 schema 划算。

约定写在代码里：`metadata_policy.py`；可检查性由入库日志告警和
`scripts/audit-metadata.py` 的报表提供。

## 列（不进 JSON）

只保留真正决定结构与筛选的那几个：

| 列 | 角色 | 语义契约 |
|---|---|---|
| `timestamp` | 何时发生 | naive UTC（DB 里存的就是这个格式） |
| `type` | 什么类型 | `SMS` / `PUSH_NOTIFICATION` / `CALL_LOG` / `EMAIL` |
| `sender` | 谁发的 | **显示用**对端标识——地址或名称都可能 |
| `source_device_id` | 从哪来的 | 来源设备/实例（`android-phone-1`、`mail-main`） |
| `content` | 内容 | 纯文本正文 |
| `received_at` | hub 何时入库 | hub 生成，客户端不传 |

## 唯一一条纪律

**别把已经存在于列里的事实再抄一份进 JSON。**

"先塞进去"很安全，只有一个例外：如果这个事实列里已经有了，塞进去就制造了第二个真相
来源——两份值迟早不一致，而调用方无从判断该信哪个。

线上审计（2026-09-12，1,802 行）出 3,457 次违规，**全是这一类，没有未知键**：

| 渠道 | 键 | 次数 | 为什么是重复 |
|---|---|---|---|
| PUSH_NOTIFICATION | `timestamp` | 1735 | 与 `messages.timestamp` 同一时刻（`21:54:47.821000` ↔ `1788472487821`，实测） |
| PUSH_NOTIFICATION | `source` | 1610 | 可由 `type` 推出（SMS→phone、PUSH_NOTIFICATION→app） |
| SMS | `timestamp` | 63 | 同上 |
| SMS | `contact_name` | 63 | 与 `sender` 重复（已知联系人时两者都是联系人名） |
| SMS | `source` | 49 | 同上 |

麻烦之处：**这些是客户端在写的，服务端删不掉，只能等改客户端**——所以纪律要守在入库前。

## 其余字段：随便塞，但名字尽量统一

已经跨渠道的概念，各渠道用同一个键名，别各叫各的：

- `recipients`：发给谁（地址数组，小写去重）——目前只有邮件有
- 将来要跨渠道的"标题"就叫 `title`，不要再出现第 4 种叫法（现在是邮件 `subject`、通知 `title`）

其他渠道私有键（线上实际在用的）：
`package_name` / `app_name` / `notification_id` / `title`（通知）、
`phone_number` / `contact_name`（短信）、`mailbox` / `subject`（邮件）、`message_id`（去重）。

未知的键**不做校验**——JSON 本来就是随便塞的地方。契约只约束那一条纪律。

## 塞进 JSON 之后还能筛吗？能，但要分两种情况（已实测）

| 查询形态 | 能否走索引 | 实测查询计划（SQLite 3.37） |
|---|---|---|
| **单值键**等值 `json_extract(meta,'$.app_name')='抖音'` | ✅ **能** | 建表达式索引后：`SEARCH m USING INDEX i_app` |
| 单值键范围/排序 | ✅ | 同上 |
| **数组键**包含 `json_each(meta,'$.recipients')` | ❌ **不能** | `SCAN m` + `CORRELATED SCALAR SUBQUERY` + `SCAN json_each VIRTUAL TABLE`；给 `json_extract(meta,'$.recipients')` 建了索引也用不上 |

**结论**：单值键的 JSON 筛选既不慢也不脏，只是 SQL 啰嗦——所以"先塞 JSON、需要时再筛"
对 `app_name`/`title`/`phone_number` 这类键**完全成立**。真正的天花板只有**数组键**：
想按数组元素做可索引筛选，就必须升成子表
（`message_participants(message_id, role, address, name)`，索引 `(address, role)`）。
现在 1,802 行无感，按 207 条/天算一年 ~7.5 万行后开始线性变慢。方案已设计好但**故意没做**，
等第二个非邮件来源真接进来再上（见 `docs/message-filtering.md` 的「设计取舍」）。

因此日常主筛选路径 = **列 + 一个通用的 JSON 逃生舱**（`meta=<key>:<value>`，一个机制服务
所有渠道私有键），而不是每来一个渠道加一个专门筛选维度。

## 什么时候考虑升成列

1. **是不是所有渠道都有？** 否则留在 JSON，**哪怕它是你最常用的筛选维度**。
   （`app_name` 覆盖 96% 的消息、只有 22 个去重值，看着最该升列——但只有通知有，所以不升。）
2. **筛选形态是单值还是数组？** 单值留 JSON 也能走索引，性能不是理由；数组才需要考虑子表。
3. **是不是已经存在于别处？** 见上面那条纪律。

## 已知缺口（记录在案，别再重新推演）

- **"发给谁"没有列**：邮件放 `recipients`（JSON 数组），短信没有这个概念。
- **`sender` 把地址和显示名混在一起**：已知联系人时存的是名字。实测 63 条短信里 28 条
  `sender='张丽捷'` 而号码在 `metadata.phone_number`。所以 `sender` 只保证"能显示"，
  不保证"能当地址匹配"。
- **没有跨渠道的"标题"**：`subject` / `title` 各叫各的。

## 怎么检查

```bash
# 线上实际写入情况 + 与列重复的键 + 值得做逃生舱的维度（只读）
./venv/bin/python scripts/audit-metadata.py
./venv/bin/python scripts/audit-metadata.py --policy   # 顺便打印约定本身

# 约定本身（人可读）
./venv/bin/python -c "import metadata_policy; print(metadata_policy.describe_policy())"
```

入库时也会检查：`POST /api/v1/messages` 对每条消息跑 `lint_metadata()`，把"与列重复"的键
打成 warning 日志（**只报告不拒绝**，否则老客户端会直接写不进来）。

## 验证记录（2026-09-12）

- 线上审计 1,802 行：3,457 次违规，全是"与列重复"，零未知键（明细见上表）。
- 索引能力实测：单值键 `SEARCH ... USING INDEX`；数组键 `SCAN m`。
- 线上端到端：POST 一条带重复键的消息 → journald 出现 3 条告警；合规消息 0 告警。
- pytest 覆盖 `tests/test_metadata_policy.py`，含"约定里声明的列必须真实存在"
  这条防文档与 schema 脱节的断言。
