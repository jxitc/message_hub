"""metadata JSON 的用法约定。

**默认策略：不确定的字段先塞进 ``message_metadata``（JSON）。**

不要把 JSON 想成"临时方案"——它就是常规落点。渠道会一直变，等想清楚了再决定要不要
升成列，比现在猜一个 schema 更划算。

列只保留真正决定结构与筛选的那几个：

======================  ==========================  ==========================================
列                      角色                        语义契约
======================  ==========================  ==========================================
``timestamp``           何时发生                    naive UTC（DB 里存的就是这个格式）
``type``                什么类型                    SMS / PUSH_NOTIFICATION / CALL_LOG / EMAIL
``sender``              谁发的                      **显示用**对端标识：地址或名称都可能
``source_device_id``    从哪来的                    来源设备/实例（`android-phone-1`、`mail-main`）
``content``             内容                        纯文本正文
``received_at``         hub 何时入库                hub 生成，客户端不传
======================  ==========================  ==========================================

唯一一条纪律
------------

**别把已经存在于列里的事实再抄一份进 JSON。**

"先塞进去"的默认很安全，但有一个例外：如果这个事实列里已经有了，塞进去就制造了
第二个真相来源——两份值迟早不一致，而调用方无从判断该信哪个。这类键直接别写。

线上审计（2026-09-12，1,802 行）出 3,457 次违规，全是这一类，且没有未知键：

- `PUSH_NOTIFICATION.timestamp` 1735 次、`.source` 1610 次
- `SMS.timestamp` 63 次、`.contact_name` 63 次、`.source` 49 次

`metadata.timestamp` 实测与列是同一时刻（`21:54:47.821000` ↔ `1788472487821`）。
麻烦之处在于**这些是客户端在写的，删不掉，只能等改客户端**——所以纪律要在入库前守。

什么时候考虑升成列
------------------

1. **是不是所有渠道都有？** 否则留在 JSON，**哪怕它是你最常用的筛选维度**。
   （`app_name` 覆盖 96% 的消息、只有 22 个去重值，看着最该升列——但只有通知有，所以不升。）
2. **筛选形态是单值还是数组？** 单值留在 JSON 也能走索引，性能不是理由；
   数组不能建索引（见 `docs/metadata-json.md` 的实测），那时才值得考虑子表。
3. **是不是已经存在于别处？** 见上面那条纪律。

名字上的一点点统一：已经是跨渠道概念的，各渠道用同一个键名，别再各叫各的
（`recipients` 就是这样；将来要跨渠道的"标题"就叫 `title`）。
"""

from __future__ import annotations

#: 消息类型词表。加新类型只改这里：API 校验与文档都引用它。
MESSAGE_TYPES = (
    'SMS',                # 短信
    'PUSH_NOTIFICATION',  # App 通知
    'CALL_LOG',           # 通话记录
    'EMAIL',              # 邮件（含附件）
    'NOTE',               # 手动记录（文本/图片）
    'DOCUMENT',           # 上传的文件（PDF 等）
)

#: 列（这些字段不进 JSON）。
CORE_COLUMNS = {
    'timestamp': ('何时发生', 'naive UTC；DB 里存的就是这个格式'),
    'type': ('什么类型', ' / '.join(MESSAGE_TYPES)),
    'sender': ('谁发的', '显示用对端标识，地址或名称都可能'),
    'source_device_id': ('从哪来的', '来源设备/实例，如 android-phone-1、mail-main'),
    'content': ('内容', '纯文本正文'),
    'received_at': ('hub 何时入库', 'hub 生成，客户端不传'),
}

#: 各渠道共用的 JSON 键名（同一个概念别各叫各的）。
SHARED_JSON_KEYS = {
    'recipients': '发给谁（地址数组，小写去重）',
}

#: 与列重复、不该出现在 JSON 里的键：键 → 为什么。这就是那条纪律的机器可读版本。
REDUNDANT_METADATA_KEYS = {
    'timestamp': '与 messages.timestamp 列是同一时刻（只是毫秒时间戳格式）',
    'source': '可由 type 推出（SMS→phone、PUSH_NOTIFICATION→app）',
    'contact_name': '与 sender 重复（已知联系人时两者都是联系人名）',
}

#: 线上实际在用的渠道私有键。只是清单，不做校验——JSON 本来就是随便塞的地方。
OBSERVED_CHANNEL_KEYS = {
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
    """找出一条消息 metadata 里"与列重复"的键。

    返回问题列表；空列表表示没问题。**只报告不拒绝**——入库时打日志、
    ``scripts/audit-metadata.py`` 出报表。拒绝会让老客户端直接写不进来，
    而这些键本来就是老客户端在写的。

    ``message_type`` 目前用不到，留着是为了将来按渠道细化规则时不必改所有调用点。
    """
    issues = []
    metadata = metadata or {}

    for key, reason in sorted(REDUNDANT_METADATA_KEYS.items()):
        if key in metadata and metadata.get(key) not in (None, '', [], {}):
            issues.append('metadata.%s：%s' % (key, reason))

    return issues


def describe_policy():
    """约定的人可读版本（给 CLI / 报表用）。"""
    lines = ['列（不进 JSON）']
    for name, (role, contract) in CORE_COLUMNS.items():
        lines.append('  %-18s %-12s %s' % (name, role, contract))
    lines.append('各渠道共用的 JSON 键名')
    for name, meaning in SHARED_JSON_KEYS.items():
        lines.append('  %-18s %s' % (name, meaning))
    lines.append('其余字段：不确定的先塞 metadata JSON')
    lines.append('唯一纪律：别把列里已有的事实再抄进 JSON —— ' +
                 '、'.join('%s（%s）' % (k, v) for k, v in REDUNDANT_METADATA_KEYS.items()))
    return '\n'.join(lines)
