"""Entity index API — what the knowledge base knows, and the raw data behind it.

The contract has one shape and one rule:

* one shape: **an entity, and every raw message it appears in.**
* one rule: the entity layer is a *derived index*; the messages it points at are
  the data. So an answer here is never just a name — it always comes with the
  message ids and full rows behind it, which is what makes a claim checkable.

Served from the same hub as the messages themselves, so `/entities/<id>/messages`
returns exactly what `GET /api/v1/messages/<id>` returns, attachments included.
"""

from flask import jsonify, request, current_app

from . import api_v1
from models import Message
import pkb_index as _idx


@api_v1.route('/entities', methods=['GET'])
def list_entities():
    """Search or list entities.

    Query params:
      q     substring of the name (Chinese works — see pkb_index.search_entities)
      kind  one of person/org/place/event/booking/document/account/topic
      order mentions (default) | recent
      limit default 60, max 500
    """
    if not _idx.available():
        return jsonify({
            'error': 'Entity index is not built yet',
            'hint': 'run info_agent: python -m info_agent.pkb.extract, then copy '
                    'pkb/pkb.db to instance/pkb.db',
        }), 503

    limit = min(request.args.get('limit', 60, type=int) or 60, 500)
    kind = (request.args.get('kind') or '').strip() or None
    order = (request.args.get('order') or 'mentions').strip()
    entities = _idx.search_entities(query=(request.args.get('q') or '').strip() or None,
                                    kind=kind, limit=limit, order=order)
    stats = _idx.stats() or {}
    return jsonify({
        'entities': entities,
        'count': len(entities),
        'total_entities': stats.get('entities'),
        'kinds': _idx.KIND_LABELS,
    })


@api_v1.route('/entities/<entity_id>', methods=['GET'])
def get_entity(entity_id):
    entity = _idx.get_entity(entity_id)
    if entity is None:
        return jsonify({'error': 'Entity not found'}), 404
    return jsonify({
        'entity': entity,
        'kind_label': _idx.KIND_LABELS.get(entity['kind'], entity['kind']),
        'co_entities': _idx.co_entities(entity_id),
        'messages_url': '/api/v1/entities/%s/messages' % entity_id,
    })


@api_v1.route('/entities/<entity_id>/messages', methods=['GET'])
def entity_messages(entity_id):
    """Every raw message this entity appears in — the point of the whole thing.

    Returns the same payload shape as `GET /api/v1/messages`, plus the entity's
    one-line gist and event date for each hit, so a caller can render a timeline
    without a second request.
    """
    entity = _idx.get_entity(entity_id)
    if entity is None:
        return jsonify({'error': 'Entity not found'}), 404

    limit = min(request.args.get('limit', 200, type=int) or 200, 1000)
    ids = _idx.message_ids_for(entity_id, limit=limit)
    gists = _idx.gists_for(entity_id)

    from .messages import _with_attachments
    messages = []
    if ids:
        rows = {m.id: m for m in Message.query.filter(Message.id.in_(ids)).all()}
        for mid in ids:                       # keep the index's order (newest first)
            message = rows.get(mid)
            if message is None:
                continue
            payload = _with_attachments(message)
            gist, event_date = gists.get(mid, (None, None))
            payload['entity_gist'] = gist
            payload['entity_event_date'] = event_date
            messages.append(payload)

    return jsonify({
        'entity': entity,
        # `count` is what this response contains; `total` is everything the index
        # has for this entity. They differ whenever `limit` bites, and conflating
        # them would make a truncated answer look complete.
        'count': len(messages),
        'total': entity['mentions'],
        'messages': messages,
    })


@api_v1.route('/entities/upcoming', methods=['GET'])
def upcoming():
    """Messages whose event date is still in the future (flights, bookings,
    appointments, expiries). Free to compute — no LLM involved."""
    if not _idx.available():
        return jsonify({'error': 'Entity index is not built yet'}), 503
    limit = min(request.args.get('limit', 40, type=int) or 40, 200)
    items = _idx.upcoming(limit=limit)
    return jsonify({'count': len(items), 'upcoming': items})
