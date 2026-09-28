"""Ask a question: rewrite → extract → recall → answer. Every step recorded.

The whole point of this module is that the answer is the *least* interesting
output. A wrong answer is only fixable if you can see which step went wrong —
did the rewrite lose the subject? did extraction find no entity? did recall come
back empty? — so each step's input, output, timing and token cost is captured in
`QaStep` records and stored on the turn.

    question
      ├─ 1 rewrite   → a standalone question + search keywords  (LLM)
      ├─ 2 extract   → entities + kinds                          (LLM)
      ├─ 3 recall    → entities hit, messages found, and *why*   (code)
      └─ 4 answer    → prose + citations                          (LLM)

Steps 1 and 2 come back from a single model call: they are the same act of
reading the question, and splitting them would double the latency to log things
separately that a single JSON reply already separates. They are still recorded as
two steps, because they are two things that can be wrong independently.

Recall is deliberately generous (recall-first) and the answer step is where
precision happens: recall a lot, cite what you used, and say "库里没有" rather
than improvise.
"""

from __future__ import annotations

import json
import re
import time

from flask import current_app

import llm
import pkb_index
from models import db, Message

#: A step record. Kept as a plain dict list (JSON column) rather than its own
#: table: a step only ever means something together with its turn, and one row
#: per question keeps the history query trivial.
MAX_SOURCE_EXCERPT = 700


def _step(name, **fields):
    fields['step'] = name
    return fields


def _excerpt(text, limit=MAX_SOURCE_EXCERPT):
    text = ' '.join((text or '').split())
    return text[:limit] + ('…' if len(text) > limit else '')


def _message_text(message):
    """Everything worth showing the model about one message."""
    metadata = message.message_metadata or {}
    parts = []
    if metadata.get('title') or metadata.get('subject'):
        parts.append(str(metadata.get('title') or metadata.get('subject')))
    if message.sender:
        parts.append('来源: %s' % message.sender)
    if message.content:
        parts.append(message.content)
    for attachment in metadata.get('attachments') or []:
        extracted = (attachment.get('extraction') or {}).get('text')
        if extracted:
            parts.append('[附件 %s] %s' % (attachment.get('name'), extracted))
    return _excerpt(' '.join(parts))


# ---------------------------------------------------------------------------
# step 1 + 2: read the question
# ---------------------------------------------------------------------------

READ_PROMPT = """你在为一个个人知识库做检索准备。用户问了一个问题，你要把它变成"能拿去搜的东西"。

输出严格 JSON：
{
  "rewritten": "把问题改写成一句自足的话：补上省略的主语/指代，保留时间、人名、机构名、金额等限定词；不要回答它。",
  "keywords": ["用于全文检索的词或短语", "..."],
  "entities": [{"name": "问题里提到或明显指向的实体名", "kind": "person|org|place|event|booking|document|account|topic"}]
}

规则：
- keywords 要紧贴原文用词，5~12 个。**同一个概念的多种说法都要给**（如 租车/租车订单/取车、机票/航班/航段、MOT/年检），因为检索是按字面匹配的。中文不要加空格。
- entities 只放问题真正指向的东西；推断不出来的宁可不给。kind 拿不准就选最接近的。
- 不要编造问题里没有的名字、日期、金额。
- 不要输出解释。"""


def read_question(question, history=None):
    """One call → (rewritten, keywords, entities, usage)."""
    context = ''
    if history:
        recent = '\n'.join('用户: %s\n助手: %s' % (h.get('question', ''),
                                                (h.get('answer') or '')[:200])
                           for h in history[-3:])
        context = '\n\n此前对话（用于理解指代）：\n' + recent
    data, usage = llm.chat_json(
        [{'role': 'system', 'content': READ_PROMPT},
         {'role': 'user', 'content': '问题：%s%s' % (question, context)}],
        temperature=0.1)
    rewritten = (data.get('rewritten') or question or '').strip()
    keywords = [str(k).strip() for k in (data.get('keywords') or []) if str(k).strip()]
    entities = [{'name': str(e.get('name', '')).strip(),
                 'kind': str(e.get('kind', '')).strip().lower()}
                for e in (data.get('entities') or []) if e.get('name')]
    return rewritten, keywords[:12], entities[:10], usage


# ---------------------------------------------------------------------------
# step 3: recall
# ---------------------------------------------------------------------------

#: Questions whose answer lives in a *date field* rather than in prose. Kept as a
#: plain word list because it only has to decide whether to also consult the
#: calendar; the words themselves are not used for matching.
SCHEDULE_WORDS = ('接下来', '安排', '日程', '计划', '待办', '什么时候', '几号', '哪天',
                  '下次', '下次预约', '到期', '截止', '有效期', '快到了', '即将',
                  'upcoming', 'next', 'schedule', 'deadline')


def _wants_schedule(rewritten, keywords):
    blob = (rewritten or '') + ' ' + ' '.join(keywords or [])
    return any(word in blob for word in SCHEDULE_WORDS)


def recall(rewritten, keywords, entities, limit=None):
    """Find candidate messages, and record exactly why each one came back.

    Two routes, merged:

    * **by entity** — the question named something the index knows; pull the
      messages that entity appears in. This is the high-precision route.
    * **by text** — every keyword is matched against message content. This is the
      high-recall route that saves us when the question's wording and the
      extracted entity name disagree (asking about 奥斯丁 when the entity is
      called "Cancun租车预订").

    Returns (sources, trace). `sources` is ordered by how many routes found the
    message, so a message both routes agree on comes first.
    """
    limit = limit or current_app.config.get('QA_MAX_SOURCES', 40)
    trace = {'entities_hit': [], 'keywords': keywords, 'by_entity': [],
             'by_text': [], 'counts': {}}

    # --- route 1: entities from the question
    entity_ids = set()
    if pkb_index.available():
        for entity in entities:
            for hit in pkb_index.search_entities(query=entity['name'], limit=5):
                if hit['id'] in entity_ids:
                    continue
                entity_ids.add(hit['id'])
                trace['entities_hit'].append(
                    {'id': hit['id'], 'name': hit['name'], 'kind': hit['kind'],
                     'mentions': hit['mentions'], 'asked_as': entity['name']})
        for hit in trace['entities_hit']:
            ids = pkb_index.message_ids_for(hit['id'], limit=limit)
            trace['by_entity'].append({'entity': hit['name'], 'messages': len(ids)})
            for message_id in ids:
                hit.setdefault('_ids', set()).add(message_id)

    scores = {}

    def add(message_id, route, reason):
        entry = scores.setdefault(message_id, {'id': message_id, 'routes': [],
                                               'why': []})
        if route not in entry['routes']:
            entry['routes'].append(route)
        if reason not in entry['why']:
            entry['why'].append(reason)

    for hit in trace['entities_hit']:
        for message_id in hit.pop('_ids', set()):
            add(message_id, 'entity', '实体「%s」' % hit['name'])

    # --- route 2: keywords against raw text (the hub owns the text)
    terms = []
    for term in [rewritten] + list(keywords):
        term = (term or '').strip()
        # A whole sentence as a LIKE pattern never matches; long phrases are cut
        # down to their first clause so they still contribute something.
        if len(term) > 12:
            term = re.split(r'[，。？?！!,;；\s]', term)[0]
        if 1 < len(term) <= 24 and term not in terms:
            terms.append(term)
    for term in terms[:14]:
        rows = (db.session.query(Message.id)
                .filter(Message.content.like('%' + term + '%'))
                .limit(60).all())
        trace['by_text'].append({'term': term, 'messages': len(rows)})
        for (message_id,) in rows:
            add(message_id, 'text', '正文含「%s」' % term)

    # gist route: the entity index's one-line summaries, which cover messages
    # whose raw text is thin (an attachment-only note).
    if pkb_index.available():
        for term in terms[:14]:
            for row in pkb_index.messages_matching_gist(term, limit=30):
                add(row['id'], 'gist', '摘要含「%s」' % term)

    # --- route 3: the calendar, when the question is about time
    #
    # Added after a real failure: "我接下来有什么安排？" recalled the cancelled
    # therapy session but NOT the car rental (event_date 10-05) or the MOT expiry
    # (10-16), because those messages contain none of the words 安排/日程/预约 —
    # the date is in a field, not in the prose. A question about what is coming up
    # has to consult the dates, or the most useful rows in the whole hub are the
    # ones it misses.
    if pkb_index.available() and _wants_schedule(rewritten, keywords):
        for row in pkb_index.upcoming(limit=40):
            add(row['id'], 'schedule', '事件日期 %s（未来）' % row['event_date'])
        trace['schedule_route'] = True

    ordered = sorted(scores.values(),
                     key=lambda e: (-len(e['routes']), e['id']))
    ordered = ordered[:limit]

    messages = {}
    if ordered:
        ids = [entry['id'] for entry in ordered]
        for message in Message.query.filter(Message.id.in_(ids)).all():
            messages[message.id] = message

    sources = []
    for entry in ordered:
        message = messages.get(entry['id'])
        if message is None:
            continue
        sources.append({
            'id': message.id,
            'timestamp': message.timestamp.isoformat() if message.timestamp else None,
            'type': message.type,
            'sender': message.sender,
            'source_device': message.source_device_id,
            'text': _message_text(message),
            'routes': entry['routes'],
            'why': entry['why'],
        })

    trace['counts'] = {
        'entities_hit': len(trace['entities_hit']),
        'text_terms': len(terms[:14]),
        'candidates': len(scores),
        'used': len(sources),
    }
    return sources, trace


# ---------------------------------------------------------------------------
# step 4: answer
# ---------------------------------------------------------------------------

ANSWER_PROMPT = """你在替用户查他自己的个人知识库。下面是他库里的原始记录（可能包含重复、噪音、错字）。

硬规则：
1. **只依据给出的记录回答。** 记录里没有的，直接说"库里没有这方面的记录"，不要推测、不要用常识补。
2. **每一条事实后面必须标出处**，格式 `[编号]`，编号就是记录前的数字。没有出处的句子不要写。
3. 记录之间如果有冲突（同一件事有两个不同说法/时间/金额），**把冲突指出来并分别标注出处**，不要自己选一个。
4. 注意时间：记录的 `收到` 是入库时间，正文里的日期可能是另一个时区（+8）写的。日期不确定时说明不确定。
5. **绝不输出**：验证码、银行卡号或尾号、账户余额、身份证/护照号、密码。
6. 记录可能有多条是同一件事的重复（短信 + 同一条短信的推送），归纳时按一件事说，但可以并列引用多个编号。

输出格式：先给结论（1~3 句），再给必要的细节。用中文。简洁，不要复述问题。"""


def answer(question, rewritten, sources, history=None):
    """Compose the answer from recalled sources. Returns (answer, usage, cited)."""
    if not sources:
        return ('库里没有找到和这个问题相关的记录。可以换个说法，或者告诉我更具体的'
                '人名/机构/时间，我再找一次。', {}, [])

    blocks = []
    for index, source in enumerate(sources, 1):
        when = (source['timestamp'] or '')[:19]
        blocks.append('[%d] 收到 %s | %s | %s\n%s'
                      % (index, when, source['type'], source['sender'] or '',
                         source['text']))
    context = '\n\n'.join(blocks)
    budget = current_app.config.get('QA_MAX_CONTEXT_CHARS', 40000)
    if len(context) > budget:
        context = context[:budget] + '\n\n…（更多记录已省略）'

    messages = [{'role': 'system', 'content': ANSWER_PROMPT}]
    if history:
        for turn in history[-3:]:
            messages.append({'role': 'user', 'content': turn.get('question', '')})
            messages.append({'role': 'assistant',
                             'content': (turn.get('answer') or '')[:500]})
    messages.append({'role': 'user', 'content':
                     '问题：%s\n（检索用的改写：%s）\n\n库里的记录：\n\n%s'
                     % (question, rewritten, context)})

    text, usage = llm.chat(messages, temperature=0.2, max_tokens=1200)
    cited = sorted({int(n) for n in re.findall(r'\[(\d{1,3})\]', text or '')
                    if 1 <= int(n) <= len(sources)})
    return (text or '').strip(), usage, cited


# ---------------------------------------------------------------------------
# the whole pipeline
# ---------------------------------------------------------------------------

def ask(question, history=None, source='web'):
    """Run the pipeline and return a result dict ready to be stored and shown.

    Never raises for a "no answer" outcome — only for LLM/transport failure — so
    the caller can log a failed attempt too.
    """
    started = time.time()
    steps = []

    t0 = time.time()
    rewritten, keywords, entities, usage = read_question(question, history)
    steps.append(_step('rewrite', elapsed_ms=int((time.time() - t0) * 1000),
                       input=question, output=rewritten,
                       tokens=usage, model=usage.get('model')))
    steps.append(_step('extract', elapsed_ms=0, entities=entities,
                       note='与 rewrite 同一次调用返回'))

    t0 = time.time()
    sources, trace = recall(rewritten, keywords, entities)
    steps.append(_step('recall', elapsed_ms=int((time.time() - t0) * 1000),
                       keywords=keywords, entities_hit=trace['entities_hit'],
                       by_text=trace['by_text'], counts=trace['counts'],
                       schedule_route=trace.get('schedule_route', False),
                       sources=[{'id': s['id'], 'why': s['why'],
                                 'routes': s['routes'],
                                 'excerpt': s['text'][:160]} for s in sources]))

    t0 = time.time()
    text, usage, cited = answer(question, rewritten, sources, history)
    steps.append(_step('answer', elapsed_ms=int((time.time() - t0) * 1000),
                       tokens=usage, model=usage.get('model'),
                       cited=cited, context_chars=sum(len(s['text']) for s in sources)))

    return {
        'question': question,
        'rewritten': rewritten,
        'keywords': keywords,
        'entities': entities,
        'sources': sources,
        'cited': cited,
        'answer': text,
        'steps': steps,
        'elapsed_ms': int((time.time() - started) * 1000),
        'source': source,
    }
