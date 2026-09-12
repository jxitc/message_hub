"""渠道无关的「公共字段」契约。

消息来自不同渠道（短信、通知、邮件，将来还有别的），但它们在**角色**上只有一套字段：
谁发的、发给谁、从哪来、什么类型、什么时候、内容是什么。其余的键都是渠道私有细节，
一律放 ``message_metadata``（JSON）。原则：

    渠道不进 schema，角色才进 schema。

（这里说的"公共字段"就是「最大公约数字段」——即所有渠道都有的那部分。刻意不缩写：
GCD 在别处指最大公因数，在 Apple 平台上还是 Grand Central Dispatch，写进代码只会害人。）

这个模块把那条约定**写成代码**，并提供 ``lint_metadata()`` 让它可检查——
隐式约定会在每个新渠道上重新漂移一次，写下来的约定才拦得住。

字段分三层
----------

**第一层：公共列**（``messages`` 表的列，所有渠道都必须填）

======================  ==========================  ==========================================
列                      角色                        语义契约
======================  ==========================  ==========================================
``timestamp``           何时发生                    naive UTC（DB 里就是这么存的）
``type``                什么类型                    SMS / PUSH_NOTIFICATION / CALL_LOG / EMAIL
``sender``              谁发的                      **显示用**的对端标识：地址或名称都可能
``source_device_id``    从哪来的                    来源设备/实例（`android-phone-1`、`mail-main`）
``content``             内容                        纯文本正文
``received_at``         hub 何时入库                hub 生成，客户端不传
======================  ==========================  ==========================================

**第二层：公共保留名**（可选、放 JSON，但各渠道要用同一个名字）

- ``recipients``：发给谁（地址数组，小写去重）——目前只有邮件有，见下文"已知缺口"
- 将来若要加"标题"这类跨渠道可选维度，统一叫 ``title``，不要再出现第 4 种叫法
  （现在是邮件 ``subject``、通知 ``title``）

**第三层：渠道私有键**（放 JSON，随便叫，只服务于该渠道）

``package_name`` / ``app_name`` / ``notification_id``（通知）、``phone_number`` /
``contact_name``（短信）、``mailbox`` / ``subject``（邮件）、``message_id``（去重用）…

什么时候把一个 JSON 键"提升"成列
--------------------------------

按这三条判断，**不要**因为"看起来重要"就升：

1. **它是不是所有渠道都有？**（是 → 第一层；否 → 留 JSON，哪怕它是你最常用的筛选维度）
2. **筛选形态是单值还是数组？** 单值键留 JSON 也能建表达式索引（``json_extract`` 是标量路径），
   性能不是理由；数组键不能建索引，只能 ``json_each`` 线性扫——想要可索引的数组筛选，
   就得升成子表（如 ``message_participants``）。
3. **它是不是已经存在于别处？** 与列重复的（``timestamp``/``source``/``contact_name``）
   直接别写，两个机读副本迟早漂移。

已知缺口（记录下来，别再重新推演）
----------------------------------

- **"发给谁"没有公共列**：邮件放 ``recipients``（JSON 数组），短信没有这个概念。
- **"谁发的"地址与显示名混在 ``sender``**：已知联系人时 ``sender`` 是名字（如 ``张丽捷``），
  地址在 JSON 的 ``phone_number``。所以 ``sender`` 只保证"能显示"，不保证"能当地址匹配"。
- **没有跨渠道的"标题"**：``subject`` / ``title`` 各叫各的。
- **高频但渠道私有的筛选维度埋在 JSON**：``app_name``（1735 条 / 22 个去重值）——
  按上面的第 1 条它**不该**升列，正确出路是"通用 JSON 筛选逃生舱"（见
  ``docs/message-schema.md`` 的能力矩阵）。
"""

from __future__ import annotations

#: 第一层：公共列。名字 → (角色, 语义契约)。
COMMON_FIELDS = {
    'timestamp': ('何时发生', 'naive UTC；DB 里存的就是这个格式'),
    'type': ('什么类型', 'SMS / PUSH_NOTIFICATION / CALL_LOG / EMAIL'),
    'sender': ('谁发的', '显示用对端标识，地址或名称都可能'),
    'source_device_id': ('从哪来的', '来源设备/实例，如 android-phone-1、mail-main'),
    'content': ('内容', '纯文本正文'),
    'received_at': ('hub 何时入库', 'hub 生成，客户端不传'),
}

#: 第二层：公共保留名（可选，放 JSON，但各渠道共用同一个名字）。
RESERVED_JSON_NAMES = {
    'recipients': '发给谁（地址数组，小写去重）',
}

#: 与第一层重复、不该出现在 JSON 里的键：键 → 为什么。
#:
#: 这些是"同一个事实的第二份机读副本"。留着不会立刻出错，但两份值迟早不一致，
#: 而调用方无从判断该信哪个。
REDUNDANT_METADATA_KEYS = {
    'timestamp': '与 messages.timestamp 列是同一时刻（只是毫秒时间戳格式）',
    'source': '可由 type 推出（SMS→phone、PUSH_NOTIFICATION→app）',
    'contact_name': '与 sender 重复（已知联系人时两者都是联系人名）',
}

#: 渠道私有键清单：出现这些属于预期之内（列出来便于审查，不做限制）。
#:
#: 未知的键也不校验——JSON 之所以是"逃生舱"，就是允许渠道加自己的东西。
KNOWN_CHANNEL_KEYS = {
    # 通知
    'package_name', 'app_name', 'notification_id', 'title',
    # 短信
    'phone_number', 'contact_name',
    # 邮件
    'mailbox', 'subject',
    # 去重/幂等
    'message_id',
}


def lint_metadata(message_type, metadata):
    """检查一条消息的 metadata 是否符合公共字段契约。

    返回问题列表（人类可读字符串）；空列表表示没问题。**只报告不拒绝**——
    契约的作用是让漂移可见（入库时打日志、``scripts/audit-metadata.py`` 出报表），
    不是给客户端上新枷锁，否则老客户端会直接写不进来。

    ``message_type`` 目前用不到，保留是为了将来按渠道细化规则（例如某渠道强制的
    保留名），免得改签名时又要动所有调用点。
    """
    issues = []
    metadata = metadata or {}

    for key, reason in sorted(REDUNDANT_METADATA_KEYS.items()):
        if key in metadata and metadata.get(key) not in (None, '', [], {}):
            issues.append(
                'metadata.%s 与公共字段重复：%s；该事实的真相来源是列，不是 JSON'
                % (key, reason))

    return issues


def describe_contract():
    """契约的人可读版本（给 CLI / 报表用）。"""
    lines = ['第一层：公共列（所有渠道都填）']
    for name, (role, contract) in COMMON_FIELDS.items():
        lines.append('  %-18s %-12s %s' % (name, role, contract))
    lines.append('第二层：公共保留名（可选，放 JSON）')
    for name, meaning in RESERVED_JSON_NAMES.items():
        lines.append('  %-18s %s' % (name, meaning))
    lines.append('第三层：渠道私有键（放 JSON）')
    lines.append('  ' + ', '.join(sorted(KNOWN_CHANNEL_KEYS)))
    return '\n'.join(lines)
