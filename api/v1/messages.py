from flask import jsonify, request, current_app
from marshmallow import ValidationError
from datetime import datetime, timezone
from . import api_v1
from models import db, Message
from schemas.message_schema import MessageCreateSchema, MessageResponseSchema, MessageListSchema
import message_filters as _mf
import metadata_policy as _policy
from blob_store import BlobError, MAX_ATTACHMENT_BYTES
from .blobs import attachment_public as _attachment_public
import message_ingest as _ingest

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
        
        messages = [_with_attachments(message) for message in pagination.items]
        
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
    """Create a message, optionally with attachment files.

    Two content types are accepted:

    * `application/json` — the original shape, unchanged; used by the Android
      collector for SMS/notifications.
    * `multipart/form-data` — same fields plus repeated `attachments` file parts.
      Used by manual capture (image / PDF / file) and by the mail collector.

    Two ordering decisions matter here:

    * Files are written to blob storage **before** the message row is committed.
      A crash in between leaves an unreferenced file (swept later by
      `BlobStore.collect_garbage`) rather than a message whose attachment is gone.
    * Text extraction is **not** done in this request. A scanned PDF can take far
      longer than a request should, and the hub runs one worker with two threads,
      so a slow synchronous request would stall everything. The extractor picks
      the message up in the background and fills `content`.
    """
    try:
        _reject_oversized_request()
        is_multipart = bool(request.files) or request.mimetype == 'multipart/form-data'

        if is_multipart:
            json_data = _multipart_payload()
            files = request.files.getlist('attachments')
            if len(files) > MAX_FILES_PER_REQUEST:
                return jsonify({'error': '一次最多上传 %d 个文件（收到 %d 个）'
                                % (MAX_FILES_PER_REQUEST, len(files))}), 400
        else:
            json_data = request.get_json()
            files = []

        if not json_data:
            return jsonify({'error': 'No JSON data provided'}), 400

        # One shared implementation with the web "add" page — see message_ingest.
        result = _ingest.create_message(json_data, files, source='api')
        db.session.commit()

        if result.duplicate:
            # 200, not 409: this is the *desired* outcome for a client that is
            # retrying, and a phone that treats it as success stops retrying.
            # Every client (Android's Retrofit `isSuccessful`, the browser) does.
            payload = {
                'message': 'Message already exists',
                'id': result.message.id,
                'duplicate': True,
                'data': result.message.to_dict(),
            }
            stored = (result.message.message_metadata or {}).get('attachments') or []
            if stored:
                payload['attachments'] = [_attachment_public(a) for a in stored]
            if result.upgraded:
                payload['note'] = '已存在的正文比这次上报的短，已用这次的内容替换。'
            return jsonify(payload), 200

        message = result.message
        payload = {
            'message': 'Message created successfully',
            'id': message.id,
            'data': message.to_dict()
        }
        if result.attachments:
            payload['attachments'] = [_attachment_public(a) for a in result.attachments]
            payload['extraction'] = 'pending'
            payload['note'] = ('文本提取在后台进行；稍后 GET /api/v1/messages/<id> '
                               '即可看到 content 或 metadata.attachments[].extraction')
        if result.rejected:
            payload['rejected'] = result.rejected
        return jsonify(payload), 201

        
    except ValidationError as e:
        return jsonify({'error': 'Validation error', 'details': e.messages}), 400
    except BlobError as e:
        # 413 太大 / 507 配额满 —— 都是客户端能理解并据以行动的错误
        db.session.rollback()
        return jsonify({'error': e.message}), e.status
    except Exception as e:
        current_app.logger.error(f"Error creating message: {str(e)}")
        db.session.rollback()
        return jsonify({'error': 'Internal server error'}), 500

def _with_attachments(message):
    """Message dict with attachments rewritten to carry a usable URL.

    The stored metadata keeps only the storage key and facts about the bytes; the
    URL is derived on read so that a future move to object storage never requires
    rewriting stored rows. Unsigned only — clients authenticate with X-API-Key.
    The web UI signs its own links (see api/v1/blobs.sign).
    """
    payload = message.to_dict()
    metadata = dict(payload.get('metadata') or {})
    attachments = metadata.get('attachments') or []
    if attachments:
        metadata['attachments'] = [_attachment_public(a) for a in attachments]
        payload['metadata'] = metadata
    return payload


#: How many files one request may carry. A per-file cap alone is not enough: a
#: client could send a hundred 1MB files and the body would be read into memory
#: before any of them is validated. The byte ceiling below sits just above
#: MAX_FILES_PER_REQUEST * MAX_ATTACHMENT_BYTES so the two agree.
MAX_FILES_PER_REQUEST = 8
#: Matches nginx's client_max_body_size (see deploy/deploy.sh) so the proxy and
#: the app reject the same requests instead of disagreeing.
MAX_REQUEST_BYTES = 16 * 1024 * 1024


def _reject_oversized_request():
    """Fail fast on an absurd body before doing any work.

    nginx enforces the same byte limit, but that only protects the public path: a
    direct request to gunicorn (or a future different proxy) would not be capped,
    and a large body read into memory on a 960MB box is a denial of service
    waiting to happen.
    """
    declared = request.content_length
    if declared and declared > MAX_REQUEST_BYTES:
        raise BlobError('请求体过大（%.1f MB，上限 %.1f MB）'
                        % (declared / 1048576, MAX_REQUEST_BYTES / 1048576), status=413)


def _multipart_payload():
    """Rebuild the JSON payload shape from multipart form fields.

    `metadata` arrives as a JSON *string*, since multipart has no nested values.
    A malformed one is rejected rather than dropped: silently losing metadata is
    worse than a 400.
    """
    import json as _json
    payload = {}
    for key in ('source_device_id', 'type', 'sender', 'content', 'timestamp'):
        value = request.form.get(key)
        if value is not None:
            payload[key] = value
    raw_metadata = request.form.get('metadata')
    if raw_metadata:
        try:
            payload['metadata'] = _json.loads(raw_metadata)
        except ValueError as exc:
            raise BlobError('metadata 不是合法 JSON：%s' % exc)
    else:
        payload['metadata'] = {}
    return payload


@api_v1.route('/messages/<message_id>', methods=['GET'])
def get_message(message_id):
    try:
        message = Message.query.get(message_id)
        if not message:
            return jsonify({'error': 'Message not found'}), 404
            
        return jsonify(_with_attachments(message))
        
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