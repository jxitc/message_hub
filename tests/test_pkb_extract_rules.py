"""抽取流程里"由代码决定"的那部分规则。

这些规则存在的理由都是同一个：模型能看出关系，但它**不稳定**——同一批数据换个
batch 大小、或者重跑一次，结果就可能不一样。所以凡是能写成规则的，就从模型手里
拿回来，写在这里、锁在测试里。
"""
import re
import sqlite3

import pkb_extract as extract

SCHEMA = """
CREATE TABLE messages (
    id TEXT PRIMARY KEY, ts TEXT, day TEXT, type TEXT, app TEXT, sender TEXT,
    gist TEXT, event_date TEXT, chars INTEGER, raw_json TEXT,
    event_date_raw TEXT, event_date_source TEXT, entities_from_sender INTEGER DEFAULT 0
);
CREATE TABLE entities (
    id TEXT PRIMARY KEY, norm TEXT UNIQUE, name TEXT, kind TEXT,
    mentions INTEGER DEFAULT 0, first_ts TEXT, last_ts TEXT
);
CREATE TABLE mentions (
    entity_id TEXT, message_id TEXT, name_in_text TEXT,
    PRIMARY KEY (entity_id, message_id)
);
"""


def build(rows, entities):
    """rows: (message_id, sender); entities: (norm, name, kind)."""
    conn = sqlite3.connect(':memory:')
    conn.executescript(SCHEMA)
    for message_id, sender in rows:
        conn.execute("INSERT INTO messages (id, sender, ts) VALUES (?,?,?)",
                     (message_id, sender, '2026-09-22T20:05:33'))
    for norm, name, kind in entities:
        conn.execute("INSERT INTO entities (id, norm, name, kind) VALUES (?,?,?,?)",
                     (extract.entity_id(norm), norm, name, kind))
    return conn


def mention(conn, entity_norm, message_id, why='从正文抽出'):
    conn.execute('INSERT OR IGNORE INTO mentions VALUES (?,?,?)',
                 (extract.entity_id(entity_norm), message_id, why))


class TestSenderPropagation:
    """真实案例：一条短信正文是「线上视频咨询室将于咨询开始前5分钟开启…」，
    里面没有"简单心理"三个字。它以前是靠"模型在同一批里看到了同号码的续约通知"
    才挂上的——那是运气，不是保证。号码是确定的，所以改由代码决定。"""

    def test_a_sender_whose_messages_mostly_share_an_entity_propagates_it(self):
        conn = build(
            rows=[('m1', '106865254007365'), ('m2', '106865254007365'),
                  ('m3', '106865254007365'), ('bare', '106865254007365')],
            entities=[('简单心理', '简单心理', 'org')])
        mention(conn, '简单心理', 'm1')
        mention(conn, '简单心理', 'm2')
        mention(conn, '简单心理', 'm3')

        added = extract.propagate_from_sender(conn)

        assert added == 1
        row = conn.execute('SELECT entity_id, name_in_text FROM mentions '
                           'WHERE message_id = ?', ('bare',)).fetchone()
        assert row[0] == extract.entity_id('简单心理')
        assert '同发信号码' in row[1], '理由要写明白，否则界面说不清这条是哪来的'
        assert conn.execute('SELECT entities_from_sender FROM messages WHERE id = ?',
                            ('bare',)).fetchone()[0] == 1

    def test_a_message_that_already_knows_what_it_is_about_is_left_alone(self):
        conn = build(
            rows=[('m1', '999'), ('m2', '999'), ('own', '999')],
            entities=[('简单心理', '简单心理', 'org'), ('别的', '别的', 'org')])
        mention(conn, '简单心理', 'm1')
        mention(conn, '简单心理', 'm2')
        mention(conn, '别的', 'own')

        assert extract.propagate_from_sender(conn) == 0

    def test_a_forwarding_sender_propagates_nothing(self):
        """短信 App（sender=信息）会把所有来源的短信都转发过来。没有任何实体能占它的
        一半，所以什么都不该传播——这正是门槛放在"过半"上的原因。"""
        rows = [('a', '信息'), ('b', '信息'), ('c', '信息'), ('d', '信息')]
        conn = build(rows=rows,
                     entities=[('银行', '银行', 'org'), ('学校', '学校', 'org'),
                               ('医院', '医院', 'org'), ('快递', '快递', 'org')])
        for message_id, norm in zip('abcd', ['银行', '学校', '医院', '快递']):
            mention(conn, norm, message_id)

        assert extract.propagate_from_sender(conn) == 0

    def test_a_single_message_sender_has_no_habit_to_learn(self):
        conn = build(rows=[('only', '888')], entities=[('甲', '甲', 'org')])
        assert extract.propagate_from_sender(conn) == 0

    def test_below_half_does_not_propagate(self):
        """3 条里只有 1 条指向某实体（1/3 < 1/2）→ 不传播。"""
        conn = build(rows=[('a', '777'), ('b', '777'), ('c', '777')],
                     entities=[('甲', '甲', 'org')])
        mention(conn, '甲', 'a')
        assert extract.propagate_from_sender(conn) == 0


class TestEntityIdsAreStable:
    """实体 id 必须跨重建稳定。

    真实事故：id 原本是自增整数，而表是每重建一次从零建的、插入顺序取决于并发批次
    谁先跑完 —— 于是每重建一次所有 id 全部重排（张丽捷 71 → 66，而 71 变成另一个人），
    打开着的页面、书签、问答历史里的 entities_hit.id 全都会指错人。
    """

    def test_the_id_is_derived_from_the_name(self):
        assert extract.entity_id(extract.norm_name('张丽捷')) == \
            extract.entity_id(extract.norm_name('张丽捷'))
        assert extract.entity_id('张丽捷') != extract.entity_id('张丽')
        assert re.fullmatch(r'[0-9a-f]{16}', extract.entity_id('张丽捷'))

    def test_the_id_does_not_depend_on_insertion_order(self):
        first = build(rows=[('a', '1'), ('b', '1')], entities=[('甲', '甲', 'org')])
        second = build(rows=[('b', '1'), ('a', '1')], entities=[('甲', '甲', 'org')])
        assert first.execute('SELECT id FROM entities').fetchone()[0] == \
            second.execute('SELECT id FROM entities').fetchone()[0]

    def test_the_extractor_never_uses_autoincrement_ids(self):
        from pathlib import Path
        source = (Path(__file__).resolve().parents[1] / 'pkb_extract.py').read_text(encoding='utf-8')
        assert 'id INTEGER PRIMARY KEY' not in source
        assert 'id TEXT PRIMARY KEY' in source


class TestEntityCountsMatchTheMentionsTable:
    """`entities.mentions` 必须等于 mentions 表里该实体的行数，**对每个实体**。

    真实 bug：发信号码传播原本跑在统计**之后**，被传播补上的提及永远没算进计数 ——
    实测 4 个实体"记 2 实际 4"。计数偏低不会让页面报错，只会安静地少报。
    现在两步合成了 `finalise()`，顺序不可能写错，所以这条测试盯的是结果：计数会不会漂。
    """

    def _invariant_violations(self, conn):
        rows = conn.execute('SELECT id, name, mentions FROM entities').fetchall()
        return [(name, stored,
                 conn.execute('SELECT COUNT(*) FROM mentions WHERE entity_id = ?',
                              (eid,)).fetchone()[0])
                for eid, name, stored in rows
                if stored != conn.execute('SELECT COUNT(*) FROM mentions '
                                          'WHERE entity_id = ?', (eid,)).fetchone()[0]]

    def test_finalise_leaves_every_count_equal_to_its_mention_rows(self):
        conn = build(rows=[('m1', '106865254007365'), ('m2', '106865254007365'),
                           ('m3', '106865254007365'), ('bare', '106865254007365')],
                     entities=[('简单心理', '简单心理', 'org')])
        for message_id in ('m1', 'm2', 'm3'):
            mention(conn, '简单心理', message_id)

        propagated = extract.finalise(conn, {'简单心理': ['简单心理', 'org']})

        assert propagated == 1, '那条没有实体的消息应该被号码传播补上'
        assert self._invariant_violations(conn) == [], (
            'entities.mentions 与 mentions 表对不上（曾经靠后的统计漏算了传播进来的行）')
        assert conn.execute('SELECT mentions FROM entities').fetchone()[0] == 4

    def test_recounting_an_entity_with_no_mentions_writes_zero_not_null(self):
        conn = build(rows=[('m1', '1')], entities=[('甲', '甲', 'org')])
        extract.finalise(conn, {'甲': ['甲', 'org']})
        assert conn.execute('SELECT mentions FROM entities').fetchone()[0] == 0
