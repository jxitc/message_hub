from flask import jsonify, request
from . import api_v1

@api_v1.route('/sync/messages', methods=['GET'])
def sync_messages():
    return jsonify({
        'messages': [],
        'has_more': False,
        'last_timestamp': None,
        'total_count': 0
    })