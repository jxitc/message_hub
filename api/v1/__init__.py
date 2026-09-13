import hashlib
import time
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app

from models import db, ApiKey

api_v1 = Blueprint('api_v1', __name__, url_prefix='/api/v1')


def _hash_key(key):
    return hashlib.sha256(key.encode('utf-8')).hexdigest()


@api_v1.before_request
def require_api_key():
    """Require a valid X-API-Key for all /api/v1/* requests.

    Validation order:
      1. A key stored in the api_keys table (sha256 match, is_active=True).
         These are created/revoked from the web Settings page.
      2. (compat) the legacy shared MH_API_KEY env value, if configured.
    If no key material exists at all the API runs WITHOUT auth and logs a
    warning — dev/test only; production must have at least one key.
    """
    # APK 下载由手机浏览器直接打开链接（无法携带 header），有意豁免鉴权。
    # 只有 GET 下载豁免；列表 /api/v1/releases 仍需 key。见 api/v1/releases.py
    if request.method == 'GET' and request.path.startswith('/api/v1/releases/'):
        return None

    # 附件下载同理：浏览器 <img>/<a> 带不了 header，所以允许用 HMAC 签名 token
    # 代替 key。注意这里**只**豁免验证通过的签名链接，没有 token 或 token 过期
    # 仍然走下面的 key 校验（保持默认拒绝）。
    if (request.method == 'GET' and request.path.startswith('/api/v1/blobs/')
            and request.args.get('token')):
        from .blobs import verify
        key = request.path[len('/api/v1/blobs/'):]
        if verify(key, request.args.get('token', '')):
            return None

    provided = (request.headers.get('X-API-Key') or '').strip()
    if provided:
        h = _hash_key(provided)

        # 1) per-key lookup (updates last_used_at)
        try:
            ak = ApiKey.query.filter_by(key_hash=h, is_active=True).first()
        except Exception:
            ak = None  # table may not exist yet on a fresh deploy pre-create_all
        if ak is not None:
            if ak.last_used_at is None or (
                time.time() - ak.last_used_at.timestamp() > 60):
                ak.last_used_at = datetime.now(timezone.utc)
                db.session.commit()
            return None

        # 2) legacy shared key compat
        expected = current_app.config.get('API_KEY')
        if expected and provided == expected:
            return None

    # no key configured anywhere -> dev-only unauth mode
    try:
        any_key = ApiKey.query.filter_by(is_active=True).first() is not None
    except Exception:
        any_key = False
    if not any_key and not current_app.config.get('API_KEY'):
        current_app.logger.warning(
            "no API keys configured -> /api/v1/* is UNAUTHENTICATED "
            "(create one in the web Settings page)"
        )
        return None

    return jsonify({'error': 'Unauthorized',
                    'message': 'Invalid or missing API key'}), 401


from . import messages, devices, sync, diagnostics, releases, blobs
