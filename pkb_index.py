"""Read-side access to the entity index produced by info_agent's extract.py.

The index is a **sidecar database** (`instance/pkb.db`), not part of this app's
schema. Keeping it separate is deliberate:

* the raw messages stay the only source of truth in `message_hub.db`; the entity
  index is a derived, rebuildable projection (drop it and nothing is lost);
* it is produced by a different program, on a different machine, with a
  different LLM — the boundary makes that explicit instead of hiding a second
  writer inside this app;
* `mentions.message_id` is a message uuid, so the join back to real messages is
  a plain id lookup and every entity answer ends at the original data.

Everything here is read-only and degrades to "no index yet" rather than raising:
a deploy without the file must still serve the rest of the hub.
"""

from __future__ import annotations

import os
import sqlite3

from flask import current_app

#: Overridable so a test (or a second index) can point somewhere else.
DEFAULT_FILENAME = 'pkb.db'

#: Entity kinds, in the order the UI shows them. Kept here rather than in the
#: template so the API and the page cannot disagree about what exists.
KIND_ORDER = ('person', 'org', 'place', 'event', 'booking', 'document',
              'account', 'topic')

KIND_LABELS = {
    'person': '人',
    'org': '机构',
    'place': '地点',
    'event': '事件',
    'booking': '预订',
    'document': '文档',
    'account': '账户',
    'topic': '话题',
}


def index_path() -> str:
    override = os.environ.get('PKB_DB')
    if override:
        return override
    return os.path.join(current_app.instance_path, DEFAULT_FILENAME)


def _connect():
    """Read-only connection, or None when the index has not been built yet.

    `mode=ro` matters: this process must not be able to corrupt the index, and a
    missing file raises here rather than at the first query.
    """
    path = index_path()
    if not os.path.exists(path):
        return None
    try:
        conn = sqlite3.connect('file:%s?mode=ro' % path, uri=True)
    except sqlite3.Error:
        return None
    conn.row_factory = sqlite3.Row
    return conn


def available() -> bool:
    conn = _connect()
    if conn is None:
        return False
    try:
        conn.execute('SELECT 1 FROM entities LIMIT 1')
    except sqlite3.Error:
        return False
    finally:
        conn.close()
    return True


def stats():
    """Counts for the landing page. Returns None when there is no index."""
    conn = _connect()
    if conn is None:
        return None
    try:
        row = conn.execute(
            'SELECT (SELECT COUNT(*) FROM entities),'
            '       (SELECT COUNT(*) FROM messages),'
            '       (SELECT COUNT(*) FROM mentions),'
            '       (SELECT MIN(ts) FROM messages),'
            '       (SELECT MAX(ts) FROM messages)').fetchone()
        kinds = {r['kind']: r['n'] for r in conn.execute(
            'SELECT kind, COUNT(*) n FROM entities GROUP BY kind')}
        dated = conn.execute(
            'SELECT COUNT(*) FROM messages WHERE event_date IS NOT NULL').fetchone()[0]
    except sqlite3.Error:
        return None
    finally:
        conn.close()
    return {
        'entities': row[0], 'messages': row[1], 'mentions': row[2],
        'first_ts': row[3], 'last_ts': row[4],
        'kinds': kinds, 'messages_with_date': dated,
    }


def search_entities(query=None, kind=None, limit=60, order='mentions',
                    evidence_message_ids=None):
    """Entities matching `query`, most-mentioned first.

    A name-only search does not work, and the data says so plainly: on this hub
    `奥斯丁` (0 name hits, 3 evidence hits), `学校` (0 / 6) and `面试` (0 / 5)
    all match nothing by name, because the extracted name for the Austin car
    rental is "Cancun租车预订" — the name the model chose is not the word a person
    types. So a query matches an entity three ways:

    * **name**   — substring of the display name or the normalised key
    * **gist**   — substring of a one-line summary of a message it appears in
    * **content** — a message it appears in has matching raw text. The caller
      finds those ids in the hub database (that is where the text lives) and
      passes them in; this module never reaches into the hub's own tables.

    Each hit carries `match` and `match_hits` so the UI can say *why* it matched
    — an unexplained hit is indistinguishable from a bug.

    Substring rather than FTS on purpose: at this size a LIKE scan is instant,
    and an FTS5 `unicode61` index treats a run of Han characters as ONE token, so
    searching 消耗 or 丽 would silently return nothing.
    """
    conn = _connect()
    if conn is None:
        return []

    like = '%' + (query or '') + '%'
    matches = {}      # entity_id -> {'match': str, 'hits': int}

    try:
        if query:
            for row in conn.execute(
                    'SELECT id FROM entities WHERE name LIKE ? OR norm LIKE ?',
                    (like, like.lower())):
                matches[row['id']] = {'match': 'name', 'hits': 1}
            for row in conn.execute(
                    '''SELECT e.id, COUNT(*) n FROM entities e
                       JOIN mentions me ON me.entity_id = e.id
                       JOIN messages m ON m.id = me.message_id
                       WHERE m.gist LIKE ? GROUP BY e.id''', (like,)):
                entry = matches.setdefault(row['id'], {'match': 'gist', 'hits': 0})
                entry['hits'] += row['n']
            if evidence_message_ids:
                marks = ','.join('?' * len(evidence_message_ids))
                for row in conn.execute(
                        '''SELECT entity_id, COUNT(*) n FROM mentions
                           WHERE message_id IN (%s) GROUP BY entity_id''' % marks,
                        list(evidence_message_ids)):
                    entry = matches.setdefault(row['entity_id'],
                                               {'match': 'content', 'hits': 0})
                    entry['hits'] += row['n']

        sql = ('SELECT e.id, e.name, e.kind, e.mentions, e.first_ts, e.last_ts '
               'FROM entities e WHERE 1=1')
        params = []
        if query:
            if not matches:
                return []
            sql += ' AND e.id IN (%s)' % ','.join('?' * len(matches))
            params += list(matches)
        if kind:
            sql += ' AND e.kind = ?'
            params.append(kind)
        if order == 'recent':
            sql += ' ORDER BY e.last_ts DESC NULLS LAST, e.mentions DESC'
        else:
            sql += ' ORDER BY e.mentions DESC, e.name ASC'
        sql += ' LIMIT ?'
        params.append(limit)
        rows = [dict(r) for r in conn.execute(sql, params)]
    except sqlite3.Error:
        return []
    finally:
        conn.close()

    for row in rows:
        info = matches.get(row['id'])
        row['match'] = (info or {}).get('match')
        row['match_hits'] = (info or {}).get('hits', 0)
        row['score'] = row['mentions'] + 10 * row['match_hits']
    if query:
        # A name hit is a stronger signal than an evidence hit, so it sorts first
        # within its own tier; both beat "matched nothing but was in the list".
        rows.sort(key=lambda r: (r['match'] != 'name', -r['score']))
    return rows


def sample_gist(entity_id, query, limit=2):
    """The matching one-line summaries for an entity, to show *why* it hit."""
    conn = _connect()
    if conn is None:
        return []
    try:
        like = '%' + (query or '') + '%'
        return [{'gist': r['gist'], 'event_date': r['event_date'],
                 'message_id': r['id']} for r in conn.execute(
            '''SELECT m.id, m.gist, m.event_date FROM mentions me
               JOIN messages m ON m.id = me.message_id
               WHERE me.entity_id = ? AND (m.gist LIKE ? OR m.raw_json LIKE ?)
               ORDER BY m.ts DESC LIMIT ?''', (entity_id, like, like, limit))]
    except sqlite3.Error:
        return []
    finally:
        conn.close()


def get_entity(entity_id):
    """One entity by its **stable** id (a hash of the normalised name).

    The id is stable across index rebuilds on purpose: links live in browser
    tabs, bookmarks and stored QA history, and an id that shifts on every
    re-extraction would silently point them at a different entity — which is
    exactly what happened when this was an autoincrement integer.
    """
    conn = _connect()
    if conn is None:
        return None
    try:
        row = conn.execute(
            'SELECT id, norm, name, kind, mentions, first_ts, last_ts '
            'FROM entities WHERE id = ?', (entity_id,)).fetchone()
        return dict(row) if row else None
    except sqlite3.Error:
        return None
    finally:
        conn.close()


def message_ids_for(entity_id, limit=500):
    """Every message this entity appears in, newest first — the raw data."""
    conn = _connect()
    if conn is None:
        return []
    try:
        return [r[0] for r in conn.execute(
            'SELECT m.id FROM mentions me JOIN messages m ON m.id = me.message_id '
            'WHERE me.entity_id = ? ORDER BY m.ts DESC LIMIT ?', (entity_id, limit))]
    except sqlite3.Error:
        return []
    finally:
        conn.close()


def gists_for(entity_id):
    """{message_id: (gist, event_date)} — the one-line summaries for a timeline."""
    conn = _connect()
    if conn is None:
        return {}
    try:
        return {r['id']: (r['gist'], r['event_date']) for r in conn.execute(
            'SELECT m.id, m.gist, m.event_date FROM mentions me '
            'JOIN messages m ON m.id = me.message_id WHERE me.entity_id = ?',
            (entity_id,))}
    except sqlite3.Error:
        return {}
    finally:
        conn.close()


def co_entities(entity_id, limit=25):
    """Entities that appear in the same messages — a free one-hop neighbourhood.

    This is the whole "network" for now, and it costs nothing: no relation
    extraction, no graph store, just a self-join on `mentions`. It answers the
    question a graph was supposed to answer ("what else is this connected to")
    using data we already have, so the graph stays an optimisation rather than a
    prerequisite.
    """
    conn = _connect()
    if conn is None:
        return []
    try:
        rows = conn.execute(
            '''SELECT e.id, e.name, e.kind, COUNT(*) shared
               FROM mentions me1
               JOIN mentions me2 ON me2.message_id = me1.message_id
                                AND me2.entity_id != me1.entity_id
               JOIN entities e ON e.id = me2.entity_id
               WHERE me1.entity_id = ?
               GROUP BY e.id ORDER BY shared DESC, e.mentions DESC LIMIT ?''',
            (entity_id, limit))
        return [dict(r) for r in rows]
    except sqlite3.Error:
        return []
    finally:
        conn.close()


def entities_for_messages(message_ids):
    """{message_id: [{name, kind, id}, ...]} for showing chips on the list page."""
    if not message_ids:
        return {}
    conn = _connect()
    if conn is None:
        return {}
    out = {}
    try:
        marks = ','.join('?' * len(message_ids))
        rows = conn.execute(
            'SELECT me.message_id, e.id, e.name, e.kind FROM mentions me '
            'JOIN entities e ON e.id = me.entity_id WHERE me.message_id IN (%s) '
            'ORDER BY e.mentions DESC' % marks, list(message_ids))
        for r in rows:
            out.setdefault(r['message_id'], []).append(
                {'id': r['id'], 'name': r['name'], 'kind': r['kind']})
    except sqlite3.Error:
        return {}
    finally:
        conn.close()
    return out


def upcoming(limit=40):
    """Messages whose *event* date is in the future, soonest first.

    Free (a field comparison, no LLM) and the most immediately useful view a
    personal knowledge base has: flights, rentals, appointments, expiries.
    """
    conn = _connect()
    if conn is None:
        return []
    try:
        rows = conn.execute(
            '''SELECT m.id, m.event_date, m.gist, m.type, m.app, m.sender
               FROM messages m WHERE m.event_date IS NOT NULL
                 AND m.event_date >= date('now')
               ORDER BY m.event_date ASC LIMIT ?''', (limit,))
        return [dict(r) for r in rows]
    except sqlite3.Error:
        return []
    finally:
        conn.close()


def messages_matching_gist(term, limit=30):
    """Messages whose one-line summary contains `term`.

    A separate route because a message can be thin in raw text (an
    attachment-only note) while its gist carries the whole meaning, and because
    the gist is the only place some recalled context exists in short form.
    """
    conn = _connect()
    if conn is None or not term:
        return []
    try:
        like = '%' + term + '%'
        return [{'id': r['id'], 'gist': r['gist']} for r in conn.execute(
            'SELECT id, gist FROM messages WHERE gist LIKE ? '
            'ORDER BY ts DESC LIMIT ?', (like, limit))]
    except sqlite3.Error:
        return []
    finally:
        conn.close()
