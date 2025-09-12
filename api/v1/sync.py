from flask import jsonify, request, current_app
from datetime import datetime, timezone
from dateutil import parser
from sqlalchemy import and_
from . import api_v1
from models import db, Message

@api_v1.route('/sync/messages', methods=['GET'])
def sync_messages():
    """
    Delta sync endpoint for efficient message synchronization
    Supports timestamp-based sync with deduplication
    """
    try:
        # Get query parameters
        since_param = request.args.get('since')
        limit = min(request.args.get('limit', 50, type=int), 1000)
        device_filter = request.args.get('device')
        type_filter = request.args.get('type')
        
        # Parse 'since' timestamp
        since_timestamp = None
        if since_param:
            try:
                since_timestamp = parser.isoparse(since_param)
                if since_timestamp.tzinfo is None:
                    since_timestamp = since_timestamp.replace(tzinfo=timezone.utc)
            except (ValueError, TypeError) as e:
                return jsonify({
                    'error': 'Invalid since parameter. Use ISO 8601 format (e.g., 2024-01-01T12:00:00Z)'
                }), 400
        
        # Build query - use received_at for sync (server timestamp)
        query = Message.query
        
        # Filter by timestamp (only get messages after 'since')
        if since_timestamp:
            query = query.filter(Message.received_at > since_timestamp)
        
        # Additional filters
        if device_filter:
            query = query.filter(Message.source_device_id == device_filter)
        if type_filter:
            query = query.filter(Message.type == type_filter)
        
        # Order by received_at (oldest first for sync)
        query = query.order_by(Message.received_at.asc())
        
        # Apply limit
        messages = query.limit(limit).all()
        
        # Check if there are more messages
        has_more = len(messages) == limit
        if has_more:
            # Check if there are actually more beyond the limit
            next_query = query.offset(limit).limit(1)
            has_more = next_query.first() is not None
        
        # Get the last timestamp for the next sync
        last_timestamp = None
        if messages:
            last_timestamp = messages[-1].received_at.isoformat()
        
        # Convert to dict
        message_list = [msg.to_dict() for msg in messages]
        
        # Get total count for since timestamp (for informational purposes)
        total_query = Message.query
        if since_timestamp:
            total_query = total_query.filter(Message.received_at > since_timestamp)
        if device_filter:
            total_query = total_query.filter(Message.source_device_id == device_filter)
        if type_filter:
            total_query = total_query.filter(Message.type == type_filter)
        
        total_count = total_query.count()
        
        # Sync response format
        response = {
            'messages': message_list,
            'has_more': has_more,
            'last_timestamp': last_timestamp,
            'total_count': total_count,
            'sync_info': {
                'since': since_param,
                'limit': limit,
                'returned': len(messages),
                'filters': {
                    'device': device_filter,
                    'type': type_filter
                }
            }
        }
        
        return jsonify(response)
        
    except Exception as e:
        current_app.logger.error(f"Error in sync_messages: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500

@api_v1.route('/sync/status', methods=['GET'])
def sync_status():
    """
    Get sync status information - latest message timestamp, total count
    """
    try:
        # Get latest message timestamp
        latest_message = Message.query.order_by(Message.received_at.desc()).first()
        latest_timestamp = latest_message.received_at.isoformat() if latest_message else None
        
        # Get total message count
        total_messages = Message.query.count()
        
        # Get count by device
        device_counts = db.session.query(
            Message.source_device_id, 
            db.func.count(Message.id)
        ).group_by(Message.source_device_id).all()
        
        device_stats = {device: count for device, count in device_counts}
        
        return jsonify({
            'latest_timestamp': latest_timestamp,
            'total_messages': total_messages,
            'device_stats': device_stats,
            'server_time': datetime.now(timezone.utc).isoformat()
        })
        
    except Exception as e:
        current_app.logger.error(f"Error in sync_status: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500