import os
from dotenv import load_dotenv

load_dotenv()

class Config:
    SECRET_KEY = os.environ.get('SECRET_KEY') or 'dev-secret-key-change-in-production'
    
    # Database configuration
    SQLALCHEMY_DATABASE_URI = os.environ.get('DATABASE_URL') or \
        'postgresql://postgres:password@localhost:5432/message_hub'
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    
    # Application settings
    DEBUG = os.environ.get('FLASK_ENV') == 'development'
    HOST = os.environ.get('HOST') or '0.0.0.0'
    PORT = int(os.environ.get('PORT') or 5000)
    
    # Message settings
    MAX_MESSAGE_LENGTH = int(os.environ.get('MAX_MESSAGE_LENGTH') or 10000)
    MAX_METADATA_SIZE = int(os.environ.get('MAX_METADATA_SIZE') or 5000)
    
    # Pagination defaults
    DEFAULT_PAGE_SIZE = int(os.environ.get('DEFAULT_PAGE_SIZE') or 50)
    MAX_PAGE_SIZE = int(os.environ.get('MAX_PAGE_SIZE') or 1000)