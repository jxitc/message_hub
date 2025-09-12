from flask import jsonify, request
from . import api_v1

@api_v1.route('/devices', methods=['GET'])
def get_devices():
    return jsonify({'devices': [], 'total': 0})

@api_v1.route('/devices/register', methods=['POST'])
def register_device():
    return jsonify({'message': 'Device registered', 'device_id': 'placeholder'}), 201