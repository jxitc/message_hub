from flask import jsonify, request, current_app
from marshmallow import ValidationError
from datetime import datetime, timezone
from . import api_v1
from models import db, Message
from schemas.message_schema import MessageCreateSchema, MessageResponseSchema, MessageListSchema
import message_filters as _mf
import metadata_policy as _policy

message_create_schema = MessageCreateSchema()
message_response_schema = MessageResponseSchema()
message_list_schema = MessageListSchema()

@api_v1.route('/messages', methods=['GET'])
def get_messages():
    try:
        # Get query parameters
        page = request.args.get('page', 1, type=int)
        per_page = min(request.args.get('per_page', 50, type=int), 1000)
        device_filter = request.args.get('device')
        type_filter = request.args.get('type')
        recipient_filter = request.args.get('recipient')
        since_filter = request.args.get('since')
        until_filter = request.args.get('until')
        
        # Build query — shared with the web UI so both agree on what matches.
        query = _mf.apply_filters(Message.query,
                                  message_type=type_filter,
                                  device=device_filter,
                                  recipient=recipient_filter,
                                  since=since_filter,
                                  until=until_filter)
            
        # Order by received_at desc (newest first)
        query = query.order_by(Message.received_at.desc())
        
        # Paginate
        pagination = query.paginate(
            page=page, 
            per_page=per_page, 
            error_out=False
        )
        
        messages = [message.to_dict() for message in pagination.items]
        
        return jsonify({
            'messages': messages,
            'total': pagination.total,
            'page': page,
            'per_page': per_page,
            'has_more': pagination.has_next
        })
        
    except Exception as e:
        current_app.logger.error(f"Error getting messages: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500

@api_v1.route('/messages', methods=['POST'])
def create_message():
    try:
        # Validate request data
        json_data = request.get_json()
        if not json_data:
            return jsonify({'error': 'No JSON data provided'}), 400
            
        # Validate against schema
        data = message_create_schema.load(json_data)
        
        # Create new message
        message = Message(
            source_device_id=data['source_device_id'],
            type=data['type'],
            sender=data['sender'],
            content=data['content'],
            timestamp=data['timestamp'],
            message_metadata=data.get('metadata', {}),
            received_at=datetime.now(timezone.utc)
        )
        
        # metadata JSON is the default place for anything channel-specific
        # (see metadata_policy.py), with one exception: a fact that already
        # lives in a column must not be copied in. Report (never reject) so
        # client drift is visible in logs instead of silently creating a
        # second source of truth.
        issues = _policy.lint_metadata(data['type'], message.message_metadata)
        for issue in issues:
            current_app.logger.warning(
                'metadata contract: %s (device=%s, type=%s)',
                issue, data['source_device_id'], data['type'])
        
        db.session.add(message)
        db.session.commit()
        
        return jsonify({
            'message': 'Message created successfully',
            'id': message.id,
            'data': message.to_dict()
        }), 201
        
    except ValidationError as e:
        return jsonify({'error': 'Validation error', 'details': e.messages}), 400
    except Exception as e:
        current_app.logger.error(f"Error creating message: {str(e)}")
        db.session.rollback()
        return jsonify({'error': 'Internal server error'}), 500

@api_v1.route('/messages/<message_id>', methods=['GET'])
def get_message(message_id):
    try:
        message = Message.query.get(message_id)
        if not message:
            return jsonify({'error': 'Message not found'}), 404
            
        return jsonify(message.to_dict())
        
    except Exception as e:
        current_app.logger.error(f"Error getting message {message_id}: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500


@api_v1.route('/messages', methods=['DELETE'])
def delete_messages():
    """Delete specific messages by id.

    Body: {"ids": ["<uuid>", ...]}

    Deletion is permanent — there is no trash — so this takes explicit ids
    rather than a filter. Filter-based cleanup goes through
    POST /messages/delete, which can show you the count first.
    """
    try:
        payload = request.get_json(silent=True) or {}
        ids = payload.get('ids') or []
        if not isinstance(ids, list) or not ids:
            return jsonify({'error': 'Provide a non-empty "ids" array'}), 400

        ids = [str(i).strip() for i in ids if str(i).strip()]
        if not ids:
            return jsonify({'error': 'Provide a non-empty "ids" array'}), 400

        deleted = db.session.query(Message).filter(Message.id.in_(ids)).delete(
            synchronize_session=False)
        db.session.commit()

        return jsonify({'deleted': deleted, 'requested': len(ids)})

    except Exception as e:
        db.session.rollback()
        current_app.logger.error(f"Error deleting messages: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500


@api_v1.route('/messages/delete', methods=['POST'])
def delete_messages_by_filter():
    """Delete every message matching filters, with a dry run by default.

    Body: {"device": ..., "type": ..., "recipient": ..., "since": ...,
           "until": ..., "dry_run": true}

    ``dry_run`` defaults to true, so a caller that forgets it gets a count
    instead of data loss. At least one filter must be set: an unfiltered call
    would erase the whole hub, and nothing here is recoverable.
    """
    try:
        payload = request.get_json(silent=True) or {}
        filters = _mf.normalize_filters(
            message_type=payload.get('type'),
            device=payload.get('device'),
            recipient=payload.get('recipient'),
            since=payload.get('since'),
            until=payload.get('until'),
        )
        if not any(filters.values()):
            return jsonify({
                'error': 'At least one filter (type/device/recipient/since/until) is required',
            }), 400

        query = _mf.apply_filter_dict(Message.query, filters)
        dry_run = payload.get('dry_run', True)
        summary = _mf.describe_filters(filters)

        if dry_run:
            return jsonify({
                'dry_run': True,
                'would_delete': query.count(),
                'filters': summary,
                'hint': 'Send "dry_run": false to actually delete.',
            })

        deleted = query.delete(synchronize_session=False)
        db.session.commit()
        return jsonify({'dry_run': False, 'deleted': deleted, 'filters': summary})

    except Exception as e:
        db.session.rollback()
        current_app.logger.error(f"Error deleting filtered messages: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500