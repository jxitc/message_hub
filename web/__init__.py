from flask import Blueprint, request, url_for
from datetime import datetime, timezone

web = Blueprint('web', __name__, template_folder='../templates', static_folder='../static')


def _utc_iso(value):
    """Render a (possibly naive-UTC) datetime as an explicit UTC ISO string.

    SQLite returns naive datetimes (tzinfo lost). Storage is UTC by
    convention, so we stamp +00:00 here so the browser can convert to the
    viewer's local timezone.
    """
    if value is None:
        return ''
    if not isinstance(value, datetime):
        return str(value)
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


def _page_href(page):
    """Build a messages-page URL for the given page number, preserving the
    current query params (type/device/recipient/since/until/limit). Lets
    pagination links be plain <a href> — no JS interception needed."""
    args = {k: v for k, v in request.args.items() if v != ''}
    args['page'] = page
    return url_for('web.messages', **args)


def _attachment_url(key):
    """Signed, expiring download URL for the web UI.

    Uses the same signed-link mechanism as the API (one mechanism, not two), so
    setting BLOB_PUBLIC_BASE moves web-UI attachment links onto the separate blob
    origin automatically.
    """
    from api.v1.blobs import blob_url
    return blob_url(key, signed=True)


web.add_app_template_filter(_utc_iso, 'utc_iso')
web.add_app_template_global(_attachment_url, 'attachment_url')
web.add_app_template_global(_page_href, 'page_href')

from . import views