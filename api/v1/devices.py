from flask import jsonify, request, current_app
from marshmallow import ValidationError
from datetime import datetime, timezone
import secrets
from . import api_v1
from models import db, Device
from schemas.device_schema import DeviceRegisterSchema, DeviceResponseSchema, DeviceListSchema

device_register_schema = DeviceRegisterSchema()
device_response_schema = DeviceResponseSchema()
device_list_schema = DeviceListSchema()

@api_v1.route('/devices', methods=['GET'])
def get_devices():
    try:
        devices = Device.query.filter(Device.is_active == True).all()
        device_list = [device.to_dict() for device in devices]
        
        return jsonify({
            'devices': device_list,
            'total': len(device_list)
        })
        
    except Exception as e:
        current_app.logger.error(f"Error getting devices: {str(e)}")
        return jsonify({'error': 'Internal server error'}), 500

@api_v1.route('/devices/register', methods=['POST'])
def register_device():
    try:
        # Validate request data
        json_data = request.get_json()
        if not json_data:
            return jsonify({'error': 'No JSON data provided'}), 400
            
        # Validate against schema
        data = device_register_schema.load(json_data)
        
        # Check if device already exists
        existing_device = Device.query.get(data['id'])
        if existing_device:
            return jsonify({'error': 'Device with this ID already exists'}), 409
        
        # Generate API key for MVP (simple approach)
        api_key = f"dev-key-{data['id']}-{secrets.token_hex(8)}"
        
        # Create new device
        device = Device(
            id=data['id'],
            name=data['name'],
            type=data['type'],
            api_key=api_key,
            is_active=True
        )
        
        db.session.add(device)
        db.session.commit()
        
        return jsonify({
            'message': 'Device registered successfully',
            'device_id': device.id,
            'api_key': api_key,  # Only return this once during registration
            'data': device.to_dict()
        }), 201
        
    except ValidationError as e:
        return jsonify({'error': 'Validation error', 'details': e.messages}), 400
    except Exception as e:
        current_app.logger.error(f"Error registering device: {str(e)}")
        db.session.rollback()
        return jsonify({'error': 'Internal server error'}), 500