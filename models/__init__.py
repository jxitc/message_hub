from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()

from .message import Message
from .device import Device
from .api_key import ApiKey
from .crash_report import CrashReport