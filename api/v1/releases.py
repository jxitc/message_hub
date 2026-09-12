"""Release distribution — serve built APKs for phone download.

GET /api/v1/releases                    list available builds (needs X-API-Key)
GET /api/v1/releases/<filename>         download a build (NO key: a phone browser
                                        cannot send headers)

Why under /api/v1: the deploy puts mh.jxitc.com behind Cloudflare Access, which
302s every non-whitelisted path to a login page. `/api/*` is already whitelisted
(so the phone can POST messages), so serving APKs from here is what makes
"open a link on the phone and install" work without asking the user to touch
Cloudflare again.

Downloads are intentionally public: the APKs are debug builds of the owner's own
app and contain no secrets. The *listing* endpoint still requires the API key.
"""

import os

from flask import jsonify, send_from_directory, current_app, abort

from . import api_v1


def _releases_dir():
    path = os.path.join(current_app.instance_path, 'releases')
    os.makedirs(path, exist_ok=True)
    return path


def _list():
    out = []
    for name in os.listdir(_releases_dir()):
        if not name.lower().endswith('.apk'):
            continue
        full = os.path.join(_releases_dir(), name)
        st = os.stat(full)
        out.append({
            'name': name,
            'size_bytes': st.st_size,
            'size_mb': round(st.st_size / 1048576, 1),
            'mtime': st.st_mtime,
        })
    return sorted(out, key=lambda r: r['mtime'], reverse=True)


@api_v1.route('/releases', methods=['GET'])
def list_releases():
    """List downloadable builds (newest first)."""
    return jsonify({'releases': _list(), 'count': len(_list())})


@api_v1.route('/releases/<path:filename>', methods=['GET'])
def download_release_public(filename):
    """Serve one APK. Public on purpose — see module docstring."""
    if not filename.lower().endswith('.apk') or '/' in filename:
        abort(404)
    directory = _releases_dir()
    if not os.path.isfile(os.path.join(directory, filename)):
        abort(404)
    return send_from_directory(directory, filename, as_attachment=True,
                               mimetype='application/vnd.android.package-archive')


@api_v1.route('/releases/latest', methods=['GET'])
def download_latest():
    """Redirect to the newest APK — a short URL to type on a phone."""
    from flask import redirect
    items = _list()
    if not items:
        abort(404)
    return redirect('/api/v1/releases/' + items[0]['name'], code=302)


# ---------------------------------------------------------------------------
# Version metadata for the in-app updater.
#
# The upload script writes instance/releases/latest.json alongside the APK:
#   {"version_name":"1.0","version_code":2,"filename":"messagehub-debug.apk",
#    "size_bytes":..., "uploaded_at":"...","notes":"..."}
# The app compares version_code with its own to decide whether to offer an update.
# ---------------------------------------------------------------------------

LATEST_META = 'latest.json'


def _latest_meta():
    path = os.path.join(_releases_dir(), LATEST_META)
    if not os.path.isfile(path):
        return None
    try:
        import json as _json
        with open(path, 'r', encoding='utf-8') as fh:
            meta = _json.load(fh)
    except Exception:
        return None

    # fill in derived fields; the file supplies version_name/version_code/filename
    name = meta.get('filename')
    if name:
        full = os.path.join(_releases_dir(), name)
        if os.path.isfile(full):
            st = os.stat(full)
            meta.setdefault('size_bytes', st.st_size)
            meta.setdefault('size_mb', round(st.st_size / 1048576, 1))
            meta.setdefault('uploaded_at', st.st_mtime)
            meta['download_url'] = '/api/v1/releases/' + name
    return meta


@api_v1.route('/releases/latest-info', methods=['GET'])
def latest_info():
    """Version metadata for the in-app updater (JSON, no redirect)."""
    meta = _latest_meta()
    if meta is None:
        return jsonify({'error': 'No release metadata published yet'}), 404
    return jsonify(meta)
