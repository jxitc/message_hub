"""pkb_extract.py — build the entity index (`instance/pkb.db`) from the hub's own messages.

Pairs with `pkb_index.py`: that module reads the index, this one writes it. The
split matters because the index is a **derived projection** — delete the file and
nothing is lost — while `instance/message_hub.db` stays the only source of truth.

Why it lives here rather than in the repo where it started
---------------------------------------------------------
It began in `info_agent/info_agent/pkb/`, next to an experimental wiki compiler.
But the index is no longer an experiment: it has a REST API, a web UI, and the
question-answering pipeline depends on it. A program that produces a first-class
part of the hub's contract belongs in the hub — and, concretely, moving it in
removed a manual step that was silently rotting the index: it used to read a JSON
export that had to be dumped from this database on the server and copied to a
laptop, then the resulting database copied back. Now it reads the messages
directly and writes the index where the app already looks for it, so it can run
on the server on a schedule.

What it stores
--------------
    messages(id, ts, day, type, app, sender, gist, event_date, chars, raw_json,
             event_date_raw, event_date_source, entities_from_sender)
    entities(id, norm, name, kind, mentions, first_ts, last_ts)
    mentions(entity_id, message_id, name_in_text)

`entities.id` is a hash of the normalised name, **not** an autoincrement integer:
an id has to survive a rebuild, because it ends up in open browser tabs,
bookmarks and stored QA history. It was an integer once, and every re-extraction
reshuffled every id — clicking 张丽捷 landed on a different person.

Dates come from `event_dates.py`, not from the model. The model got them wrong
(a refund SMS saying "9月28日" came back as 2025) and dates are arithmetic.

Usage:
    ./venv/bin/python pkb_extract.py                 # everything worth extracting
    ./venv/bin/python pkb_extract.py --limit 40      # smoke test
    ./venv/bin/python pkb_extract.py --db /tmp/x.db  # write somewhere else
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from app import create_app
from models import Message, db
import event_dates
import llm

#: Apps whose notifications are pure noise or pure signal: a VPN's "免费线路",
#: every 抖音 video title, USB-charging banners. Feeding these in would create
#: hundreds of junk entities and cost real money for nothing.
NOISE_APPS = {'穿梭', '抖音', '虫虫钢琴', '系统界面', 'Android 系统', '电池',
              '时钟', '数字健康与家人守护', '微博', '汽水音乐', '米家',
              '小红书', '淘宝', '支付宝'}

KINDS = ('person', 'org', 'place', 'event', 'booking', 'document',
         'account', 'topic')

SYSTEM = """你是信息抽取器。输入是若干条来自手机通知/短信/邮件/笔记的记录。
对每一条输出：值得长期记住的实体、一句话摘要。

实体只抽"以后还会再提到"的东西，kind 必须从这几个里选：
person（人）| org（机构/公司/银行/学校）| place（地点）| event（一件事/一次聚会/一段行程）
| booking（预订/订单/票）| document（文档/证件/保单）| account（账户/卡/号码）| topic（长期话题）

绝对不要抽取，也不要写进 gist：验证码、卡号（含尾号）、账户余额、身份证号、护照号、
密码、详细门牌地址。金额可以（只记金额+商户+日期），余额不行。

gist：中文一句话说清"发生了什么"，≤40 字。不要复述原文，要概括。

**不要输出日期**：日期由代码从原文解析（模型猜日期既花钱又容易错，实测把
"9月28日"的年份猜成了 2025）。你只负责实体和摘要。

只输出 JSON，不要 markdown 代码块、不要解释：
{"results":[{"id":"<原样返回>","gist":"...",
             "entities":[{"name":"...","kind":"..."}]}]}"""


def entity_id(norm: str) -> str:
    """Stable id for an entity: derived from its normalised name.

    Deliberately not an autoincrement integer — see the module docstring. A
    rebuild must not change an entity's identity, or every open page and every
    stored `entities_hit.id` silently points at a different entity.
    """
    return hashlib.sha1(norm.encode('utf-8')).hexdigest()[:16]


def norm_name(name: str) -> str:
    """The prototype's entire entity-resolution rule.

    Strip separators and case so 张丽 / 张 丽 collapse, and nothing else.
    Deliberately not fuzzy: a wrong merge is unrecoverable and we have no way to
    show the user why it happened. 张丽吗喽 and 张丽捷 therefore stay two
    entities, which is correct — nothing in the data proves they are one person.
    """
    return re.sub(r'[\s·・.。、,，:：()（）「」【】“”"\'’]+', '',
                  (name or '').strip()).lower()


def message_text(message, cap=2000) -> str:
    """Everything extractable about one message, attachments included.

    The OCR text matters as much as the body: a scanned passport or a photographed
    booking confirmation is only readable through it.
    """
    metadata = message.message_metadata or {}
    parts = []
    head = metadata.get('title') or metadata.get('subject')
    if head:
        parts.append(str(head))
    if message.sender:
        parts.append('来源: %s' % message.sender)
    if message.content:
        parts.append(message.content)
    for attachment in metadata.get('attachments') or []:
        text = (attachment.get('extraction') or {}).get('text')
        if text:
            parts.append('[附件 %s] %s' % (attachment.get('name'), text))
    return ' '.join(' '.join(parts).split())[:cap]


def app_of(message) -> str:
    metadata = message.message_metadata or {}
    return str(metadata.get('app_name') or metadata.get('package_name') or '')


def is_worth_extracting(message) -> bool:
    if message.type == 'PUSH_NOTIFICATION' and app_of(message) in NOISE_APPS:
        return False
    return len(message_text(message).strip()) >= 20


def batches(seq, size):
    for start in range(0, len(seq), size):
        yield seq[start:start + size]


def call_model(app, items, model, timeout=300, tries=3):
    """One batch through llm.chat_json, with the app context pushed for the thread.

    Reuses llm.py rather than growing a second HTTP client: two call sites would
    drift on retries, timeouts and JSON handling, and the drift would only show up
    as one of them mysteriously returning prose where JSON was expected.
    """
    lines = [json.dumps({'id': item['id'][:8], 'type': item['type'],
                         'app': item['app'], 'text': item['text']},
                        ensure_ascii=False) for item in items]
    with app.app_context():
        return llm.chat_json(
            [{'role': 'system', 'content': SYSTEM},
             {'role': 'user', 'content': '\n'.join(lines)}],
            temperature=0.1, timeout=timeout, tries=tries)


SCHEMA = """
DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS entities;
DROP TABLE IF EXISTS mentions;

CREATE TABLE messages (
    id TEXT PRIMARY KEY, ts TEXT, day TEXT, type TEXT, app TEXT, sender TEXT,
    gist TEXT, event_date TEXT, chars INTEGER, raw_json TEXT,
    -- 日期不是模型给的：event_date 由 event_dates.py 从原文解析。
    -- 这两列让"为什么是这一天"可查——命中的原串，以及年份是原文写的还是推断的。
    event_date_raw TEXT,
    event_date_source TEXT,
    entities_from_sender INTEGER DEFAULT 0
);
CREATE TABLE entities (
    -- id 由 norm 派生（sha1 前 16 位），**不是自增整数**：自增整数按"批次谁先跑完"
    -- 分配，并发抽取每次顺序都不同，于是每重建一次索引所有实体 id 全部重排。
    id TEXT PRIMARY KEY,
    norm TEXT UNIQUE,
    name TEXT,
    kind TEXT,
    mentions INTEGER DEFAULT 0,
    first_ts TEXT,
    last_ts TEXT
);
CREATE TABLE mentions (
    entity_id TEXT,
    message_id TEXT,
    name_in_text TEXT,
    PRIMARY KEY (entity_id, message_id)
);
CREATE INDEX ix_mentions_msg ON mentions(message_id);
CREATE INDEX ix_messages_day ON messages(day);
CREATE INDEX ix_entities_kind ON entities(kind);
"""


def collect(app, limit=None):
    """Every message worth extracting, in a plain dict shape (no ORM in threads)."""
    with app.app_context():
        rows = []
        for message in Message.query.order_by(Message.timestamp):
            if not is_worth_extracting(message):
                continue
            rows.append({
                'id': message.id,
                'type': message.type,
                'sender': message.sender or '',
                'app': app_of(message),
                'timestamp': message.timestamp.isoformat() if message.timestamp else None,
                'text': message_text(message),
            })
    if limit:
        rows = rows[:limit]
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--limit', type=int, help='only the first N messages')
    parser.add_argument('--batch', type=int, default=10, help='messages per call')
    parser.add_argument('--workers', type=int, default=6, help='concurrent calls')
    parser.add_argument('--db', help='index path (default: instance/pkb.db)')
    parser.add_argument('--incremental', action='store_true',
                        help='only extract messages the index does not have yet '
                             '(the scheduled run uses this; a full rebuild is the '
                             'safe default and the only way to drop stale rows)')
    args = parser.parse_args()

    app = create_app()
    # llm.py 读 current_app.config，所以这里必须先有 app context（脚本里没有请求上下文）
    with app.app_context():
        if not llm.configured():
            raise SystemExit('no LLM_API_KEY (the server .env, or the environment)')
        target = args.db or os.path.join(app.instance_path, 'pkb.db')
        model = app.config.get('LLM_MODEL')

    rows = collect(app, args.limit)

    conn = sqlite3.connect(target)
    existing = set()
    if args.incremental and os.path.exists(target):
        try:
            existing = {row[0] for row in conn.execute('SELECT id FROM messages')}
        except sqlite3.Error:
            existing = set()          # 旧库结构不对就当没有，走全量
    if existing:
        before = len(rows)
        rows = [row for row in rows if row['id'] not in existing]
        print('incremental: %d new message(s), %d already indexed'
              % (len(rows), before - len(rows)), flush=True)
        if not rows:
            print('  nothing new — index left as is', flush=True)
            return 0
    else:
        # 全量重建：先清空。增量时**不能**清，否则会把已经索引的消息一起删掉。
        conn.executescript(SCHEMA)
        conn.commit()

    work = list(batches(rows, args.batch))
    print('messages to extract: %d | batches: %d | workers: %d | model: %s'
          % (len(rows), len(work), args.workers, model), flush=True)
    print('index: %s' % target, flush=True)

    by_id = {row['id'][:8]: row for row in rows}
    entities = {}          # norm -> [name, kind]
    done = in_tok = out_tok = failures = 0
    started = time.time()

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(call_model, app, chunk, model): index
                   for index, chunk in enumerate(work)}
        for future in as_completed(futures):
            index = futures[future]
            try:
                parsed, usage = future.result()
            except Exception as exc:
                failures += 1
                print('  ! batch %d failed: %s' % (index, exc), flush=True)
                continue
            in_tok += usage.get('prompt_tokens', 0)
            out_tok += usage.get('completion_tokens', 0)

            for result in parsed.get('results', []):
                source = by_id.get(str(result.get('id') or '').strip()[:8])
                if source is None:
                    continue
                event_date, evidence = event_dates.infer_event_date(
                    source['text'], source['timestamp'], debug=True)
                conn.execute(
                    'INSERT OR REPLACE INTO messages '
                    '(id, ts, day, type, app, sender, gist, event_date, chars, '
                    ' raw_json, event_date_raw, event_date_source, '
                    ' entities_from_sender) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)',
                    (source['id'], source['timestamp'],
                     (source['timestamp'] or '')[:10], source['type'],
                     source['app'], source['sender'],
                     (result.get('gist') or '')[:200],
                     event_date.isoformat() if event_date else None,
                     len(source['text']), json.dumps(result, ensure_ascii=False),
                     (evidence or {}).get('raw'),
                     (evidence or {}).get('year_from') or (evidence or {}).get('reason')))
                for entity in result.get('entities') or []:
                    name = (entity.get('name') or '').strip()
                    kind = (entity.get('kind') or '').strip().lower()
                    norm = norm_name(name)
                    if not norm or kind not in KINDS:
                        continue
                    entities.setdefault(norm, [name, kind])
                    conn.execute('INSERT OR IGNORE INTO mentions VALUES (?,?,?)',
                                 (entity_id(norm), source['id'], name))
                    done += 1
            conn.commit()
            if done and done % 400 < args.batch:
                print('  %d mentions | %d batches left | %.0fs'
                      % (done, len(work) - index - 1, time.time() - started),
                      flush=True)

    if args.incremental:
        for row in conn.execute('SELECT norm, name, kind FROM entities'):
            entities.setdefault(row[0], [row[1], row[2]])
    propagated = finalise(conn, entities)
    if propagated:
        print('  sender propagation: %d mention(s) added' % propagated, flush=True)

    total_e = conn.execute('SELECT COUNT(*) FROM entities').fetchone()[0]
    total_m = conn.execute('SELECT COUNT(*) FROM messages').fetchone()[0]
    total_x = conn.execute('SELECT COUNT(*) FROM mentions').fetchone()[0]
    dated = conn.execute('SELECT COUNT(*) FROM messages '
                         'WHERE event_date IS NOT NULL').fetchone()[0]
    with app.app_context():
        cost, currency = llm.price({'prompt_tokens': in_tok,
                                    'completion_tokens': out_tok})
    print('\n== done in %.1fs ==' % (time.time() - started))
    print('  messages stored : %d' % total_m)
    print('  entities        : %d' % total_e)
    print('  mentions        : %d' % total_x)
    print('  with a date     : %d' % dated)
    print('  batches failed  : %d' % failures)
    print('  tokens          : %s in / %s out (%s%.2f)'
          % (format(in_tok, ','), format(out_tok, ','), currency, cost))
    print('  index           : %s (%.1f MB)'
          % (target, os.path.getsize(target) / 1048576))
    conn.close()
    return 1 if failures else 0


def finalise(conn, entities):
    """Propagate from senders, then recount. In that order, in one place.

    The order is the whole point: propagating *adds* mentions, so counting first
    leaves those entities reporting fewer mentions than they have — the real index
    had four such entities ("Car+ 车联: 2" against 4 rows in `mentions`). A count
    that is quietly too low breaks nothing visibly, which is why it survived
    unnoticed until the two were compared.

    Two steps in one function rather than two calls in main() so the wrong order
    cannot be written. The first attempt at testing this called both steps in the
    test and asserted the result — a tautology: it checked that counting after
    propagating gives the right answer, not that the program does that. Folding
    them together makes the invariant structural, and the test then checks what
    can really go wrong: the count drifting away from the table.
    """
    propagated = propagate_from_sender(conn)
    _recount_entities(conn, entities)
    return propagated


def _recount_entities(conn, entities):
    """Write each entity's mention count and time span, from the mentions table.

    Must run **after** `propagate_from_sender`: propagating adds mentions, and
    counting first left four entities in the real index reporting fewer mentions
    than they had ("Car+ 车联: 2" against 4 actual rows). A count that is quietly
    too low breaks nothing visibly — which is exactly why it went unnoticed.
    """
    for norm, (name, kind) in entities.items():
        row = conn.execute(
            'SELECT COUNT(*), MIN(m.ts), MAX(m.ts) FROM mentions me '
            'JOIN messages m ON m.id = me.message_id WHERE me.entity_id = ?',
            (entity_id(norm),)).fetchone()
        conn.execute('INSERT OR REPLACE INTO entities '
                     '(id, norm, name, kind, mentions, first_ts, last_ts) '
                     'VALUES (?,?,?,?,?,?,?)',
                     (entity_id(norm), norm, name, kind, row[0], row[1], row[2]))
    conn.commit()


def propagate_from_sender(conn, min_messages=2, share=0.5, min_shared=2):
    """Attach a sender's own entities to its messages that had none (deterministic).

    A real case: an SMS reading "线上视频咨询室将于咨询开始前5分钟开启…" contains no
    mention of 简单心理. It was linked to that entity only because the *model* saw
    a sibling message from the same number in the same batch — which depends on
    where the batch boundary falls, so it is luck rather than a guarantee, and it
    is not reproducible.

    The number is not luck. The rule, in full:

    * a sender needs at least `min_messages` messages to have a "usual" subject;
    * the entity must appear in at least `share` of them **and in at least
      `min_shared` messages** — a number that has only ever sent two messages,
      one of which happens to be about X, is not evidence that the other one is;
      the "1/2" cases this excluded were real and visible in the index;
    * only messages with **zero** entities of their own are touched — a message
      that already said what it is about is never overridden;
    * the added mention records why, so the UI shows it was inferred from the
      sender rather than read out of the text.

    The `share` threshold is what excludes forwarding senders: the SMS app
    (sender=信息) relays messages from everywhere, so no entity reaches half of
    them and nothing propagates — which is exactly right.

    Returns the number of mentions added.
    """
    senders = conn.execute(
        "SELECT sender, COUNT(*) n FROM messages "
        "WHERE sender IS NOT NULL AND sender != '' GROUP BY sender HAVING n >= ?",
        (min_messages,)).fetchall()

    added = 0
    for sender, total in senders:
        ids = [row[0] for row in conn.execute(
            'SELECT id FROM messages WHERE sender = ?', (sender,)).fetchall()]
        if not ids:
            continue
        marks = ','.join('?' * len(ids))
        counts = conn.execute(
            'SELECT me.entity_id, COUNT(DISTINCT me.message_id) c FROM mentions me '
            'WHERE me.message_id IN (%s) GROUP BY me.entity_id' % marks, ids).fetchall()
        usual = [(entity_id_, c) for entity_id_, c in counts
                 if c >= min_shared and c / float(total) >= share]
        if not usual:
            continue

        empty = conn.execute(
            'SELECT m.id FROM messages m WHERE m.sender = ? AND NOT EXISTS '
            '(SELECT 1 FROM mentions me WHERE me.message_id = m.id)',
            (sender,)).fetchall()
        for (message_id,) in empty:
            for entity_id_, count in usual:
                conn.execute(
                    'INSERT OR IGNORE INTO mentions VALUES (?,?,?)',
                    (entity_id_, message_id,
                     '同发信号码（%s 的 %d/%d 条消息属于此实体）'
                     % (sender, count, total)))
                added += 1
            conn.execute('UPDATE messages SET entities_from_sender = 1 WHERE id = ?',
                         (message_id,))
    conn.commit()
    return added


if __name__ == '__main__':
    sys.exit(main())
