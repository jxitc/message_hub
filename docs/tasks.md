# Message Hub Server - Task Breakdown

## 当前进度（2026-09-03）

### 已交付
- [x] 安卓端改名 InfoAgent → **MessageHub** + 新启动图标（C2 消息气泡）；包名 `com.jxitc.messagehub`
- [x] 远端部署到 DigitalOcean `188.166.172.192`：systemd + gunicorn + SQLite，`deploy/deploy.sh` 一键幂等
- [x] 域名 **mh.jxitc.com**：Squarespace `A` 记录 + nginx 反代 → `127.0.0.1:5001` + Let's Encrypt HTTPS（http→https 301）
- [x] Web UI 深色主题 + Messages 分页 Jinja `min()` bug 修复
- [x] 消息内容 schema 精简（2026-09-04）：`content` 只存原始正文（去 🔔/📱/英文标签/时间戳），结构化放 `type`/`sender`/`message_metadata`（`source`/`app_name`/`title`/`phone_number`/`message_id` 等）；Android 两个 processor + metadata 已改并验证；显示层去 emoji 剥离；新增可复用 E2E 脚本 `android_client/e2e/phone_to_mh_e2e.sh`（PASS）
- [x] **单元测试补上**（2026-09-04）：服务器 pytest 套件 `tests/`（`conftest` 临时 SQLite + `MH_API_KEY`；`test_auth` 鉴权 401/200/错 key、`test_messages` 干净 content + 结构化 metadata；**7 passed**）；安卓 JVM 单测（抽出纯函数 `MessageFormatter`/`MessageMapper`，`MessageFormatterTest`6 + `MessageMapperTest`7；**14 例全过**）。`requirements-dev.txt` 加 pytest
- [x] **忽略/屏蔽 app 列表**（2026-09-04）：`AppPreferences.removeBlockedApp`；主页长按菜单按状态显示「禁止/取消屏蔽此 app 的通知」（toggle）；设置页新增「已忽略的通知 apps」列表 + 可移除

### 待办
- [x] **API Key 鉴权**（重点：客户端手机端）——服务器 `/api/v1/*` 校验 `X-API-Key`（无 key→401、带 key→200、错 key→401、POST→201）；安卓端 `AppPreferences.apiKey` + OkHttp 拦截器自动带头；新 APK 已装手机并设 `server_url=https://mh.jxitc.com` + `api_key`；**E2E 通过**（simulate_sms → 远端 MH 库新增 `android-phone-1` SMS）
- [x] 手机 `server_url` 切到 `https://mh.jxitc.com`，并 `ufw delete allow 5001/tcp` 收回公网 5001（只剩 22/80/443）
- [ ] **CLI 增强（给 Agent 用，2026-09-04）**：`message-hub` 目前**没 `post`**、且加了鉴权后已失效（`make_request` 不带 `X-API-Key`，`/api/v1/*` 全 401）。要做：
  - ① CLI 支持 API Key（`--api-key` / config / env `MH_CLI_API_KEY`），`make_request` 带 `X-API-Key` header
  - ② 加 `post`（发消息：`--content`/`--type`/`--sender`/`--device`/`--metadata`）
  - ③ 加 `list`/`fetch` 别名（agent 拉最近消息用，等价 `messages`）
  - ④ 加 `mh` 入口脚本（短命令：`mh list` / `mh post ...`）
- [ ] （可选）邮箱收集器 Gmail/QQ App Password 真实凭据
- [ ] （可选）InfoAgent 导入真实库 / CHANGES_PLAN 同步回 info_agent 安卓端

## Phase 1: MVP Core Implementation

### 1. Project Setup & Infrastructure
- [x] **1.1** Initialize Flask project structure
- [x] **1.2** Create requirements.txt with core dependencies
- [x] **1.3** Setup development environment configuration (.env, config.py)
- [x] **1.4** Create Docker Compose for PostgreSQL (switched to SQLite for simplicity)
- [x] **1.5** Initialize git repository structure
- [x] **1.6** Setup basic logging with Flask logging

### 2. Database Foundation
- [x] **2.1** Create database connection and SQLAlchemy setup
- [x] **2.2** Implement Messages table schema
- [x] **2.3** Implement Devices table schema
- [x] **2.4** Create database indexes for performance
- [x] **2.5** Add database migration system (Flask-Migrate)
- [x] **2.6** Create seed data for testing

### 3. Core API Endpoints (No Authentication)
- [x] **3.1** Setup Flask application structure and routing
- [x] **3.2** Implement POST /api/v1/messages (message forwarding)
- [x] **3.3** Implement GET /api/v1/messages (basic message retrieval)
- [x] **3.4** Implement GET /api/v1/messages/:id (single message)
- [x] **3.5** Implement PUT /api/v1/messages/:id/read (mark as read)
- [x] **3.6** Add request validation using Marshmallow
- [x] **3.7** Add error handling and HTTP status codes

### 4. Delta Sync Implementation  
- [x] **4.1** Add timestamp-based sync endpoint GET /api/v1/sync/messages
- [x] **4.2** Implement query parameters (since, limit, device_filter)
- [x] **4.3** Add pagination and has_more logic
- [x] **4.4** Handle merge/dedup for overlapping time ranges
- [x] **4.5** Test sync performance with large datasets

### 5. CLI Interface (MVP)
- [x] **5.1** Create basic CLI application structure (Click framework)
- [x] **5.2** Implement `message-hub messages` command (basic listing)
- [x] **5.3** Add basic filtering options (--limit, --type)
- [x] **5.4** Implement `message-hub mark-read` command
- [x] **5.5** Add CLI configuration management (server URL, etc.)

### 6. Web Interface (MVP)
- [x] **6.1** Setup basic Flask templates and static files
- [x] **6.2** Create simple dashboard with message overview
- [x] **6.3** Implement basic message detail view
- [x] **6.4** Implement basic filtering UI (by type)
- [x] **6.5** Make interface mobile-friendly (responsive design)

### 7. MVP Testing & Validation
- [ ] **7.1** Create unit tests for core endpoints
- [ ] **7.2** Create integration tests for message flow
- [ ] **7.3** Test with sample Android SMS forwarding
- [ ] **7.4** Performance testing for sync operations
- [ ] **7.5** Add API documentation (basic)
- [ ] **7.6** Test CLI interface functionality
- [ ] **7.7** Test web interface functionality

## Phase 2: Enhanced Features

### 8. Device Management & Authentication
- [ ] **8.1** Implement POST /api/v1/devices/register (device registration)
- [ ] **8.2** Implement GET /api/v1/devices (list devices)
- [ ] **8.3** Generate API keys for devices
- [ ] **8.4** Add API key validation middleware
- [ ] **8.5** Update all endpoints to optionally use authentication

### 9. Enhanced Message Handling
- [ ] **9.1** Enhanced metadata flexibility for different sources
- [ ] **9.2** Message type validation and categorization
- [ ] **9.3** Add message tagging system
- [ ] **9.4** Implement message priority handling
- [ ] **9.5** Add timezone handling for global sources

### 10. Enhanced CLI Interface
- [ ] **10.1** Add advanced search and filtering options
- [ ] **10.2** Implement CLI installation and distribution
- [ ] **10.3** Add bulk operations (mark multiple as read)
- [ ] **10.4** Add export capabilities
- [ ] **10.5** Implement CLI plugins system

### 11. Enhanced Web Interface
- [ ] **11.1** Add advanced search functionality
- [ ] **11.2** Implement settings and configuration page
- [ ] **11.3** Add bulk operations UI
- [ ] **11.4** Implement dark mode and themes
- [ ] **11.5** Add export and backup features

## Phase 3: Production & Deployment

### 13. Production Readiness
- [ ] **13.1** Add comprehensive error handling
- [ ] **13.2** Implement API rate limiting
- [ ] **13.3** Add input validation and sanitization
- [ ] **13.4** Setup HTTPS enforcement
- [ ] **13.5** Add security headers (CORS, CSP, etc.)
- [ ] **13.6** Implement audit logging for API access

### 14. Deployment Infrastructure
- [ ] **14.1** Create Dockerfile for application
- [ ] **14.2** Setup docker-compose for full stack
- [ ] **14.3** Create deployment scripts
- [ ] **14.4** Configure production database (PostgreSQL)
- [ ] **14.5** Setup reverse proxy (nginx)
- [ ] **14.6** Add SSL/TLS certificates
- [ ] **14.7** Configure environment-specific settings

### 15. Monitoring & Operations
- [ ] **15.1** Add health check endpoints
- [ ] **15.2** Implement metrics collection
- [ ] **15.3** Setup log aggregation
- [ ] **15.4** Add database backup strategy
- [ ] **15.5** Create monitoring dashboard
- [ ] **15.6** Setup alerting for critical issues

## Phase 4: Advanced Features

### 16. Performance & Scalability
- [ ] **16.1** Database query optimization
- [ ] **16.2** Add caching layer (Redis)
- [ ] **16.3** Implement database connection pooling
- [ ] **16.4** Add background job processing
- [ ] **16.5** Optimize API response times

### 17. Advanced Sync Features
- [ ] **17.1** Implement sequence-based sync option
- [ ] **17.2** Add conflict resolution for concurrent updates
- [ ] **17.3** Implement incremental sync strategies
- [ ] **17.4** Add sync status tracking per device

### 18. Search & Analytics
- [ ] **18.1** Implement full-text search (PostgreSQL or Elasticsearch)
- [ ] **18.2** Add message analytics and statistics
- [ ] **18.3** Create usage reports and insights
- [ ] **18.4** Add search filters and advanced queries

### 19. Real-time Features
- [ ] **19.1** Implement WebSocket support
- [ ] **19.2** Add real-time message notifications
- [ ] **19.3** Create live dashboard updates
- [ ] **19.4** Add push notifications for clients

## Phase 5: Future Enhancements

### 20. Media & Content
- [ ] **20.1** Add file upload capabilities
- [ ] **20.2** Implement media storage (local/S3)
- [ ] **20.3** Add image thumbnail generation
- [ ] **20.4** Support for audio/video messages

### 21. Security & Privacy
- [ ] **21.1** Implement end-to-end encryption
- [ ] **21.2** Add PII filtering and detection
- [ ] **21.3** Implement data retention policies
- [ ] **21.4** Add data export capabilities
- [ ] **21.5** GDPR compliance features

### 22. Integration & Extensions
- [ ] **22.1** Add webhook support for external services
- [ ] **22.2** Create plugin architecture
- [ ] **22.3** Add multi-tenancy support
- [ ] **22.4** Implement message forwarding rules
- [ ] **22.5** Add external API integrations

## Dependencies & Prerequisites

**Phase 1 Prerequisites:**
- SQLite database (file-based, no server needed)
- Python 3.8+ environment
- Basic understanding of Flask framework

**Phase 2 Prerequisites:**
- Completed Phase 1 MVP
- Working message forwarding from at least one source

**Phase 3 Prerequisites:**
- Completed Phase 2 features
- Production environment setup
- SSL certificates

**Phase 4 Prerequisites:**
- Production deployment from Phase 3
- Performance baseline metrics
- User feedback and usage patterns

## Success Criteria

**MVP Success (Phase 1):**
- Can receive and store messages from Android SMS app
- Can retrieve messages via API
- Delta sync works correctly
- Basic device management functional
- CLI interface provides basic functionality
- Web interface allows message viewing and management

**Production Success (Phase 3):**
- System handles 1000+ messages/day
- 99% uptime
- Sub-200ms API response times
- Secure and compliant deployment

**Full Feature Success (Phase 5):**
- Multi-device support with real-time sync
- Web and CLI interfaces fully functional
- Media forwarding operational
- Enterprise-ready security features
