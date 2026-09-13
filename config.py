import os
from dotenv import load_dotenv

load_dotenv()

class Config:
    SECRET_KEY = os.environ.get('SECRET_KEY') or 'dev-secret-key-change-in-production'
    
    # Database configuration
    SQLALCHEMY_DATABASE_URI = os.environ.get('DATABASE_URL') or \
        'sqlite:///message_hub.db'
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    
    # Application settings
    DEBUG = os.environ.get('FLASK_ENV') == 'development'
    HOST = os.environ.get('HOST') or '0.0.0.0'
    PORT = int(os.environ.get('PORT') or 5000)
    
    # Shared API key for /api/v1/* endpoints (set in server .env as MH_API_KEY).
    # If unset the API runs WITHOUT auth (dev/test only) and logs a warning.
    API_KEY = os.environ.get('MH_API_KEY')
    
    # Public base for attachment URLs. Unset -> /api/v1/blobs/<key> on the same
    # origin. Set (e.g. https://mhblob.jxitc.com) -> attachments are served from a
    # separate origin, so user-uploaded files never share an origin with the web
    # UI (a PDF/SVG that executes cannot reach the session on the main host).
    BLOB_PUBLIC_BASE = os.environ.get('BLOB_PUBLIC_BASE')

    # Message settings
    MAX_MESSAGE_LENGTH = int(os.environ.get('MAX_MESSAGE_LENGTH') or 10000)
    MAX_METADATA_SIZE = int(os.environ.get('MAX_METADATA_SIZE') or 5000)
    
    # Pagination defaults
    DEFAULT_PAGE_SIZE = int(os.environ.get('DEFAULT_PAGE_SIZE') or 50)
    MAX_PAGE_SIZE = int(os.environ.get('MAX_PAGE_SIZE') or 1000)