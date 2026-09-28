"""Ask the knowledge base a question — the recall + answer entry point.

One POST returns the answer *and* the whole pipeline that produced it (rewrite,
extracted entities, what was recalled and why, per-step timing and tokens), plus
the turn id needed to rate it. That shape is deliberate: the caller never has to
ask a second question to find out why the first one was wrong.

    POST /api/v1/qa/ask        {"question": "...", "history": [...]}
    GET  /api/v1/qa/turns      history (phone chat page reads this)
    POST /api/v1/qa/turns/<id>/rate   {"rating": "good|bad", "note": "..."}
    GET  /api/v1/qa/stats      good/bad counts, for the debugging loop
"""

from flask import jsonify, request, current_app

from . import api_v1
import qa as _qa
import llm as _llm
from models import db, QaTurn


def _history_from(payload, conversation_id):
    """Conversation context: given explicitly, else the last turns of the thread.

    Reading it from the database (rather than trusting the client to resend it)
    means the phone and the web page behave the same, and a client that forgets
    to send history still gets a coherent conversation.
    """
    if isinstance(payload.get('history'), list):
        return payload['history'][-6:]
    turns = (QaTurn.query
             .filter(QaTurn.conversation_id == conversation_id,
                     QaTurn.error.is_(None))
             .order_by(QaTurn.created_at.desc()).limit(3).all())
    return [{'question': t.question, 'answer': t.answer} for t in reversed(turns)]


@api_v1.route('/qa/ask', methods=['POST'])
def qa_ask():
    payload = request.get_json(silent=True) or {}
    question = (payload.get('question') or '').strip()
    if not question:
        return jsonify({'error': 'question 不能为空'}), 400
    if len(question) > 2000:
        return jsonify({'error': '问题太长了（上限 2000 字）'}), 400
    if not _llm.configured():
        return jsonify({'error': '服务器没有配置 LLM_API_KEY',
                        'hint': '在服务器 .env 里加一行 LLM_API_KEY=...'}), 503

    conversation_id = (payload.get('conversation_id') or 'web')[:64]
    source = (payload.get('source') or 'api')[:32]
    history = _history_from(payload, conversation_id)

    turn = QaTurn(question=question, source=source,
                  conversation_id=conversation_id)
    try:
        result = _qa.ask(question, history=history, source=source)
    except _llm.LLMError as exc:
        # 失败也要留痕：只记成功的话，"为什么这次没答上来"就永远查不到。
        turn.error = str(exc)
        db.session.add(turn)
        db.session.commit()
        return jsonify({'error': str(exc), 'turn_id': turn.id}), 502

    turn.rewritten = result['rewritten']
    turn.answer = result['answer']
    turn.keywords = result['keywords']
    turn.entities = result['entities']
    turn.sources = result['sources']
    turn.steps = result['steps']
    turn.cited = result['cited']
    turn.elapsed_ms = result['elapsed_ms']
    db.session.add(turn)
    db.session.commit()

    return jsonify({'turn': turn.to_dict(),
                    'citations': [s for i, s in enumerate(result['sources'], 1)
                                  if i in result['cited']]})


@api_v1.route('/qa/turns', methods=['GET'])
def qa_turns():
    """Chat history. Newest first; `conversation_id` scopes it to one thread."""
    limit = min(request.args.get('limit', 30, type=int) or 30, 200)
    query = QaTurn.query
    conversation_id = (request.args.get('conversation_id') or '').strip()
    if conversation_id:
        query = query.filter(QaTurn.conversation_id == conversation_id)
    source = (request.args.get('source') or '').strip()
    if source:
        query = query.filter(QaTurn.source == source)
    if request.args.get('rating'):
        query = query.filter(QaTurn.rating == request.args['rating'])

    turns = query.order_by(QaTurn.created_at.desc()).limit(limit).all()
    return jsonify({'count': len(turns),
                    'turns': [t.to_dict(with_steps=False) for t in turns]})


@api_v1.route('/qa/turns/<turn_id>', methods=['GET'])
def qa_turn(turn_id):
    turn = QaTurn.query.get(turn_id)
    if turn is None:
        return jsonify({'error': 'Turn not found'}), 404
    return jsonify({'turn': turn.to_dict()})


@api_v1.route('/qa/turns/<turn_id>/rate', methods=['POST'])
def qa_rate(turn_id):
    """Record a good/bad judgement on one answer.

    This is the whole debugging loop: `GET /qa/turns?rating=bad` is the list of
    questions to actually look at, and because the turn stores its own steps, the
    failure is already localised to a step when you open it.
    """
    turn = QaTurn.query.get(turn_id)
    if turn is None:
        return jsonify({'error': 'Turn not found'}), 404

    payload = request.get_json(silent=True) or {}
    rating = (payload.get('rating') or '').strip().lower()
    if rating in ('', 'none', 'clear', 'null'):
        rating = None
    elif rating not in ('good', 'bad'):
        return jsonify({'error': "rating 只能是 good / bad（或 null 取消）"}), 400

    turn.rating = rating
    turn.rating_note = (payload.get('note') or None)
    from datetime import datetime, timezone
    turn.rated_at = datetime.now(timezone.utc) if rating else None
    db.session.commit()
    return jsonify({'id': turn.id, 'rating': turn.rating,
                    'rating_note': turn.rating_note})


@api_v1.route('/qa/stats', methods=['GET'])
def qa_stats():
    rows = (db.session.query(QaTurn.rating, db.func.count(QaTurn.id))
            .group_by(QaTurn.rating).all())
    counts = {(rating or 'unrated'): n for rating, n in rows}
    return jsonify({'total': sum(counts.values()), 'by_rating': counts,
                    'llm_configured': _llm.configured(),
                    'model': current_app.config.get('LLM_MODEL')})
