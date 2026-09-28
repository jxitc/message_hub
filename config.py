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

    # 问答（RAG）用的 LLM。key 只从环境/服务器 .env 读，绝不写进入库文件。
    # 换供应商只要改这两个值，调用点在 llm.py 一处。
    LLM_API_KEY = os.environ.get('LLM_API_KEY') or os.environ.get('DEEPSEEK_API_KEY')
    LLM_BASE_URL = os.environ.get('LLM_BASE_URL') or 'https://api.deepseek.com'
    LLM_MODEL = os.environ.get('LLM_MODEL') or 'deepseek-chat'
    # 一次问答最多往上下文里塞多少东西（字符）。召回可以宽，喂进去必须封顶，
    # 否则一个问题就能把整库塞满。
    QA_MAX_CONTEXT_CHARS = int(os.environ.get('QA_MAX_CONTEXT_CHARS') or 40000)
    # 每次问答花多少钱：单价写在配置里，页面显示的是**按这个单价估算**的值。
    # 为什么不写死一个数：供应商改价、换模型、换供应商都会让硬编码的金额变成谎话，
    # 而"这是按配置单价算的"永远是实话。
    LLM_PRICE_INPUT_PER_M = float(os.environ.get('LLM_PRICE_INPUT_PER_M') or 2.0)
    LLM_PRICE_OUTPUT_PER_M = float(os.environ.get('LLM_PRICE_OUTPUT_PER_M') or 8.0)
    LLM_PRICE_CURRENCY = os.environ.get('LLM_PRICE_CURRENCY') or '¥'
    QA_MAX_SOURCES = int(os.environ.get('QA_MAX_SOURCES') or 40)

    # Message settings
    MAX_MESSAGE_LENGTH = int(os.environ.get('MAX_MESSAGE_LENGTH') or 10000)
    MAX_METADATA_SIZE = int(os.environ.get('MAX_METADATA_SIZE') or 5000)
    
    # Pagination defaults
    DEFAULT_PAGE_SIZE = int(os.environ.get('DEFAULT_PAGE_SIZE') or 50)
    MAX_PAGE_SIZE = int(os.environ.get('MAX_PAGE_SIZE') or 1000)