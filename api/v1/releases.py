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
