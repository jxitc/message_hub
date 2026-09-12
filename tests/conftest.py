"""Shared pytest fixtures.

Two things here are deliberate and load-bearing — please don't "simplify" them:

1. ``DATABASE_URL`` is set **before** ``config`` is imported. ``Config`` reads it
   at import time, and Flask-SQLAlchemy 3.x builds its engines when ``init_app``
   runs inside ``create_app()``. Updating ``app.config`` *afterwards* therefore has
   no effect: the suite silently ran against the real ``instance/message_hub.db``
   and ``db.drop_all()`` dropped that database's tables at teardown.
2. ``_assert_isolated()`` fails loudly if the engine does not point at the temp
   file. A mistake like the one above must break the test run, not quietly delete
   someone's data.

Mail collection is pointed at a nonexistent config file so a developer's real
mailbox settings can never be picked up by a test run.
"""

import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

_TMP_DIR = tempfile.mkdtemp(prefix="message-hub-tests-")
_DB_PATH = os.path.join(_TMP_DIR, "test.db")

# Must precede `import app` / `import config`.
os.environ["DATABASE_URL"] = "sqlite:///" + _DB_PATH
os.environ.setdefault("SECRET_KEY", "test-secret")
os.environ["MAIL_CONFIG_FILE"] = os.path.join(_TMP_DIR, "no-mail-accounts.json")
os.environ.pop("MAIL_ACCOUNTS", None)
for _key in [k for k in os.environ if k.startswith("MAIL_") and k[5:6].isdigit()]:
    os.environ.pop(_key, None)

import pytest  # noqa: E402

from app import create_app  # noqa: E402
from models import db  # noqa: E402

KEY = "test-key-123"


def _assert_isolated(app):
    """Refuse to run against anything but the throwaway database."""
    with app.app_context():
        uri = str(db.engine.url)
    if uri != "sqlite:///" + _DB_PATH:
        raise AssertionError(
            "Refusing to run tests against %s — expected the temp database %s. "
            "Dropping tables here would destroy real data." % (uri, _DB_PATH))


@pytest.fixture
def app():
    application = create_app()
    application.config.update(
        TESTING=True,
        API_KEY=KEY,
        SECRET_KEY="test-secret",
    )
    _assert_isolated(application)
    with application.app_context():
        db.drop_all()
        db.create_all()
    yield application
    with application.app_context():
        db.session.remove()
        db.drop_all()


@pytest.fixture
def client(app):
    return app.test_client()


@pytest.fixture
def auth_headers():
    return {"X-API-Key": KEY}


def pytest_sessionfinish(session, exitstatus):
    shutil.rmtree(_TMP_DIR, ignore_errors=True)
