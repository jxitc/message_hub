"""实体页上的每个链接都必须落到它自己写的那个人身上。

这条测试是为一个真实事故写的：实体 id 原本是自增整数，而表是每次重建索引时
从零建的、插入顺序取决于**并发批次谁先跑完**。于是每重建一次，所有 id 全部重排
（张丽捷 从 71 变成 66，而 71 变成了另一个人）。点进去看到的就是另一个人，
而且打开着的页面、书签、问答历史里的 entities_hit.id 全都一起指错。

所以这里做两件事：
1. 从页面里把 (链接, 链接文字) 抠出来，逐个 GET，断言页面里的实体名 == 链接文字；
2. 断言 id 是**由实体名派生**的，即同一份内容重建两次，id 不变。
"""
import re
import sqlite3

import pytest

from models import db, Message
from datetime import datetime


SCHEMA = """
CREATE TABLE messages (id TEXT PRIMARY KEY, ts TEXT, day TEXT, type TEXT,
                       app TEXT, sender TEXT, gist TEXT, event_date TEXT,
                       chars INTEGER, raw_json TEXT);
CREATE TABLE entities (id TEXT PRIMARY KEY, norm TEXT, name TEXT, kind TEXT,
                       mentions INTEGER, first_ts TEXT, last_ts TEXT);
CREATE TABLE mentions (entity_id TEXT, message_id TEXT, name_in_text TEXT,
                       PRIMARY KEY (entity_id, message_id));
"""


def build_index(path, entities):
    """Write a small index shaped exactly like info_agent's extract.py produces."""
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    for message_id, when in (('m-1', '2026-09-15T12:24:02'),
                             ('m-2', '2026-09-27T23:12:00')):
        conn.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,?,?)',
                     (message_id, when, when[:10], 'SMS', '', '95588',
                      '一条测试消息', None, 10, '{}'))
    for entity in entities:
        conn.execute('INSERT INTO entities VALUES (?,?,?,?,?,?,?)',
                     (entity['id'], entity['norm'], entity['name'], entity['kind'],
                      2, '2026-09-15', '2026-09-27'))
        for message_id in ('m-1', 'm-2'):
            conn.execute('INSERT INTO mentions VALUES (?,?,?)',
                         (entity['id'], message_id, '从正文抽出'))
    conn.commit()
    conn.close()


@pytest.fixture
def indexed(app, tmp_path, monkeypatch):
    """A hub with two real messages and an entity index that links to them."""
    import pkb_index

    path = str(tmp_path / 'pkb.db')
    build_index(path, [
        {'id': 'aaaa1111bbbb2222', 'norm': '张丽捷', 'name': '张丽捷', 'kind': 'person'},
        {'id': 'cccc3333dddd4444', 'norm': '简单心理', 'name': '简单心理', 'kind': 'org'},
    ])
    monkeypatch.setattr(pkb_index, 'index_path', lambda: path)
    with app.app_context():
        for message_id, when in (('m-1', '2026-09-15T12:24:02'),
                                 ('m-2', '2026-09-27T23:12:00')):
            db.session.add(Message(id=message_id, source_device_id='phone',
                                   type='SMS', sender='95588', content='一条测试消息',
                                   timestamp=datetime.fromisoformat(when),
                                   received_at=datetime.fromisoformat(when),
                                   message_metadata={}))
        db.session.commit()
    return path


def links_on(page):
    """(href, label) for every entity link on a page."""
    return [(m.group(1), m.group(2).strip())
            for m in re.finditer(r'href="(/entities/[^"]+)"[^>]*>\s*([^<\n]+?)\s*</a>', page)]


class TestEntityLinksPointAtTheRightEntity:
    def test_the_entity_list_links_land_on_their_own_entity(self, client, indexed):
        page = client.get('/entities').data.decode('utf-8')
        found = links_on(page)
        assert found, '实体列表上应该有链接，否则这条测试什么也没测'

        for href, label in found:
            detail = client.get(href)
            assert detail.status_code == 200, '%s 打不开' % href
            body = detail.data.decode('utf-8')
            heading = re.search(r'<h4[^>]*>\s*([^<\n]+?)\s*<span class="badge', body)
            assert heading, '%s 的详情页没有实体名' % href
            assert heading.group(1).strip() == label, (
                '链接写着「%s」，点进去却是「%s」——id 与实体对不上了'
                % (label, heading.group(1).strip()))

    def test_a_stale_id_does_not_silently_show_the_wrong_entity(self, client, indexed):
        """找不到就是找不到，绝不能"碰巧"渲染出另一个实体。"""
        response = client.get('/entities/deadbeefdeadbeef', follow_redirects=True)
        assert response.status_code == 200
        assert '没有这个实体' in response.data.decode('utf-8')

    def test_search_results_link_correctly_too(self, client, indexed):
        page = client.get('/entities?q=%E7%AE%80%E5%8D%95').data.decode('utf-8')
        for href, label in links_on(page):
            body = client.get(href).data.decode('utf-8')
            heading = re.search(r'<h4[^>]*>\s*([^<\n]+?)\s*<span class="badge', body)
            assert heading.group(1).strip() == label


class TestEntityIdsAreStable:
    def test_the_id_is_derived_from_the_name_not_from_row_order(self):
        """同一份内容，重建两次（甚至换个插入顺序），id 必须一样。"""
        import sys
        from pathlib import Path
        # 实体 id 的生成规则住在 info_agent 的抽取器里，这里只验证它的性质，
        # 不复制一份实现（复制了就会各自漂移）。
        sys.path.insert(0, str(Path(__file__).resolve().parents[2]
                               / 'info_agent' / 'info_agent'))
        from pkb import extract

        first = extract.entity_id(extract.norm_name('张丽捷'))
        again = extract.entity_id(extract.norm_name('张丽捷'))
        other = extract.entity_id(extract.norm_name('张丽'))
        assert first == again
        assert first != other
        assert re.fullmatch(r'[0-9a-f]{16}', first)

    def test_the_extractor_does_not_assign_autoincrement_ids(self):
        """回归：曾经用 INTEGER PRIMARY KEY，导致 id 随批次完成顺序变化。"""
        from pathlib import Path
        source = (Path(__file__).resolve().parents[1] / '..' / 'info_agent'
                  / 'info_agent' / 'pkb' / 'extract.py').read_text(encoding='utf-8')
        assert 'id INTEGER PRIMARY KEY' not in source
        assert 'id TEXT PRIMARY KEY' in source
