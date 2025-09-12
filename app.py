from flask import Flask, jsonify, request
from flask_cors import CORS
from flask_migrate import Migrate
import os
import logging
from logging.handlers import RotatingFileHandler

from models import db
from api.v1 import api_v1

def create_app():
    app = Flask(__name__)
    CORS(app)
    
    # Load configuration
    app.config.from_object('config.Config')
    
    # Initialize database
    db.init_app(app)
    migrate = Migrate(app, db)
    
    # Register blueprints
    app.register_blueprint(api_v1)
    
    # Setup logging
    setup_logging(app)
    
    # Health check endpoint
    @app.route('/health')
    def health_check():
        return jsonify({'status': 'healthy', 'service': 'message-hub'})
    
    # Root endpoint
    @app.route('/')
    def index():
        return jsonify({
            'message': 'Message Hub Server',
            'version': '1.0.0',
            'endpoints': {
                'health': '/health',
                'api': '/api/v1'
            }
        })
    
    return app

def setup_logging(app):
    if not app.debug and not app.testing:
        # Create logs directory if it doesn't exist
        if not os.path.exists('logs'):
            os.mkdir('logs')
        
        # Setup file handler
        file_handler = RotatingFileHandler('logs/message_hub.log', 
                                         maxBytes=10240000, backupCount=10)
        file_handler.setFormatter(logging.Formatter(
            '%(asctime)s %(levelname)s: %(message)s [in %(pathname)s:%(lineno)d]'
        ))
        file_handler.setLevel(logging.INFO)
        app.logger.addHandler(file_handler)
        
        app.logger.setLevel(logging.INFO)
        app.logger.info('Message Hub Server startup')
    else:
        # Development logging
        logging.basicConfig(
            level=logging.DEBUG,
            format='%(asctime)s %(levelname)s: %(message)s'
        )

if __name__ == '__main__':
    app = create_app()
    app.run(debug=True, host='0.0.0.0', port=5001)