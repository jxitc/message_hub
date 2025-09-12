from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()

from .message import Message
from .device import Device