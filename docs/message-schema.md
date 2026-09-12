# 消息字段契约 — Message Field Contract

> 一句话：**渠道不进 schema，角色才进 schema。**
>
> 这里说的"公共字段"就是所有渠道都有的那部分（曾被我缩写成 GCD，那个缩写已废弃：
> 它在别处指最大公因数，在 Apple 平台上还是 Grand Central Dispatch）。

契约写在代码里：`message_contract.py`（定义 + `lint_metadata()`），
可检查性由两处提供——入库时的日志告警，和 `scripts/audit-metadata.py` 的报表。

## 三层结构

### 第一层：公共列（`messages` 表的列，所有渠道都必须填）

| 列 | 角色 | 语义契约 |
|---|---|---|
| `timestamp` | 何时发生 | naive UTC（DB 里存的就是这个格式） |
| `type` | 什么类型 | `SMS` / `PUSH_NOTIFICATION` / `CALL_LOG` / `EMAIL` |
| `sender` | 谁发的 | **显示用**对端标识——地址或名称都可能，别指望它一定是地址 |
| `source_device_id` | 从哪来的 | 来源设备/实例（`android-phone-1`、`mail-main`） |
| `content` | 内容 | 纯文本正文 |
| `received_at` | hub 何时入库 | hub 生成，客户端不传 |

### 第二层：公共保留名（可选，放 JSON，但各渠道共用同一个名字）

| 名字 | 含义 | 现状 |
|---|---|---|
| `recipients` | 发给谁（地址数组，小写去重） | 只有邮件有；筛选/删除/列表显示都读它 |

将来若要加"标题"这类跨渠道可选维度，**统一叫 `title`**，不要再出现第 4 种叫法
（现在是邮件 `subject`、通知 `title`）。

### 第三层：渠道私有键（放 JSON，随便叫）

线上实际在用的（`scripts/audit-metadata.py` 出的真实报表）：

```
[PUSH_NOTIFICATION] 1735 行
    package_name        1735/1735 行  去重   22  · 渠道私有
    app_name            1735/1735 行  去重   22  · 渠道私有
    notification_id     1735/1735 行  去重   52  · 渠道私有
    title               1610/1735 行  去重   72  · 渠道私有
    timestamp           1735/1735 行  去重  207  ❌ 与列重复
    source              1610/1735 行  去重    1  ❌ 与列重复

[SMS] 63 行
    phone_number          63/63 行  去重   10  · 渠道私有
    contact_name          63/63 行  去重    2  ❌ 与列重复
    timestamp             63/63 行  去重   15  ❌ 与列重复
    source                49/63 行  去重    1  ❌ 与列重复
    message_id            49/63 行  去重    1  · 渠道私有

[EMAIL] 4 行
    mailbox / message_id / subject / recipients
```

未知的键**不做校验**——JSON 之所以是"逃生舱"，就是允许渠道加自己的东西。
契约约束的是"别把已有的事实再抄一份到 JSON"，不是限制渠道表达力。

## 什么时候把一个 JSON 键"提升"成列

按这三条判断，**不要**因为"看起来重要"就升：

1. **它是不是所有渠道都有？**
   是 → 第一层；否 → 留 JSON，**哪怕它是你最常用的筛选维度**。
   （`app_name` 覆盖 96% 的消息、只有 22 个去重值，看着最像该升列的——但只有通知有，
   所以按契约它留在 JSON，出路是下面的逃生舱。）
2. **筛选形态是单值还是数组？** 见下面的能力矩阵：单值键留 JSON 也能走索引，
   性能**不是**升列的理由；数组键想可索引就必须升成子表。
3. **它是不是已经存在于别处？** 与列重复的（`timestamp`/`source`/`contact_name`）
   直接别写。两个机读副本迟早不一致，而调用方无从判断该信哪个。

## JSON 筛选的真实能力边界（已实测，SQLite 3.37）

用户的原话是"真的要筛选也是能搞的，就是不优雅"——这句话**基本对，但要分两种情况**：

| 查询形态 | 可行性 | 能否走索引 |
|---|---|---|
| 单值键等值 `json_extract(meta,'$.app_name')='抖音'` | ✅ 简单 | ✅ **能**：`CREATE INDEX i ON messages(json_extract(message_metadata,'$.app_name'))` → 实测查询计划 `SEARCH m USING INDEX i_app` |
| 单值键范围/排序（如 `title` 前缀） | ✅ | ✅ 同上（表达式索引） |
| 数组键包含 `json_each(...)` | ✅ 能写 | ❌ **不能**：实测 `SCAN m` + `CORRELATED SCALAR SUBQUERY` + `SCAN json_each VIRTUAL TABLE`，即使给 `json_extract(meta,'$.recipients')` 建了索引也用不上 → **线性全表扫** |
| DISTINCT 出候选值（下拉框选项） | ✅ | ❌ 全表扫（可物化成小表/缓存） |
| 跨键 OR / 复杂布尔 | ⚠️ 能写但很啰嗦 | ❌ |

**结论**：

- **单值键的 JSON 筛选既不慢也不脏**，只是 SQL 啰嗦。所以"把渠道私有字段放 JSON、需要时再筛"
  这条路对 `app_name`/`title`/`phone_number` 这类键**完全成立**。
- **数组键（`recipients` 这种）才是真的天花板**：想按数组元素做可索引筛选，就必须
  升成子表（`message_participants(message_id, role, address, name)`，索引 `(address, role)`）。
  现在 1,802 行无感，按 207 条/天算一年 ~7.5 万行后开始线性变慢。
  这个方案已设计好但**故意没做**，等第二个非邮件来源真接进来再上（详见
  `docs/message-filtering.md` 的「设计取舍」）。
- 因此**日常主筛选路径 = 列 + 通用逃生舱**（`meta=<key>:<value>` 之类的通用机制，
  一个机制服务所有渠道私有键），而不是"每来一个渠道就加一个专门的筛选维度"。

## 已知缺口（记录在案，别再重新推演）

- **"发给谁"没有公共列**：邮件放 `recipients`（JSON 数组），短信根本没有这个概念。
- **"谁发的"地址与显示名混在 `sender`**：已知联系人时 `sender` 是名字。
  实测 63 条短信里 28 条 `sender='张丽捷'` 而地址在 `metadata.phone_number`。
  所以 `sender` 只保证"能显示"，不保证"能当地址匹配"。
- **没有跨渠道的"标题"**：`subject` / `title` 各叫各的。
- **`metadata.timestamp` 是纯重复**：实测 `column=2026-09-03 21:54:47.821000` 与
  `meta.timestamp=1788472487821` 是同一时刻，只是格式不同。

## 怎么检查

```bash
# 契约本身（人可读）
./venv/bin/python -c "import message_contract; print(message_contract.describe_contract())"

# 线上实际写入情况 + 契约违规 + 值得做逃生舱的维度（只读）
./venv/bin/python scripts/audit-metadata.py
./venv/bin/python scripts/audit-metadata.py --contract
```

入库时也会检查：`POST /api/v1/messages` 对每条消息跑 `lint_metadata()`，
把"与列重复"的键打成 warning 日志（**只报告不拒绝**，否则老客户端会直接写不进来）。

## 验证记录（2026-09-12）

- 线上审计（1,802 行）：发现 5 类契约违规，共 3,457 次键出现——
  `PUSH_NOTIFICATION.timestamp` 1735 次、`.source` 1610 次；`SMS.timestamp` 63 次、
  `.contact_name` 63 次、`.source` 49 次。全部是"与列重复"，没有未知键。
- 索引能力实测（见上表）：单值键 `SEARCH ... USING INDEX`；数组键 `SCAN m`。
- pytest 覆盖：`tests/test_message_contract.py` 6 例，含"契约里的列必须真实存在"
  这条防止文档与 schema 脱节的断言。
