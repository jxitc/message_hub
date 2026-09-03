from flask import Blueprint, request, jsonify, current_app

api_v1 = Blueprint('api_v1', __name__, url_prefix='/api/v1')


@api_v1.before_request
def require_api_key():
    """Require a shared X-API-Key header for all /api/v1/* requests.

    Reads the expected key from config (env MH_API_KEY). If no key is
    configured the API runs WITHOUT auth (dev/test only) and logs a warning —
    always set MH_API_KEY in production.
    """
    expected = current_app.config.get('API_KEY')
    if not expected:
        current_app.logger.warning(
            "MH_API_KEY not configured -> /api/v1/* is UNAUTHENTICATED (set MH_API_KEY in .env)"
        )
        return None
    provided = request.headers.get('X-API-Key') or ''
    if provided != expected:
        return jsonify({'error': 'Unauthorized', 'message': 'Invalid or missing API key'}), 401
    return None


from . import messages, devices, sync