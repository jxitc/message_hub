from flask import jsonify, request, current_app
from marshmallow import ValidationError
from datetime import datetime, timezone
from . import api_v1
from models import db, Message
from schemas.message_schema import MessageCreateSchema, MessageResponseSchema, MessageListSchema

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
        
        # Build query
        query = Message.query
        
        if device_filter:
            query = query.filter(Message.source_device_id == device_filter)
        if type_filter:
            query = query.filter(Message.type == type_filter)
            
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

@api_v1.route('/messages/<message_id>/read', methods=['PUT'])
def mark_message_read(message_id):
    try:
        message = Message.query.get(message_id)
        if not message:
            return jsonify({'error': 'Message not found'}), 404
            
        message.is_read = True
        message.updated_at = datetime.now(timezone.utc)
        db.session.commit()
        
        return jsonify({
            'message': f'Message {message_id} marked as read',
            'data': message.to_dict()
        })
        
    except Exception as e:
        current_app.logger.error(f"Error marking message {message_id} as read: {str(e)}")
        db.session.rollback()
        return jsonify({'error': 'Internal server error'}), 500