# Message Hub - 开发变更日志

> Message Hub 作为"纯信息收集层"改造记录：接收多设备/多来源消息，供 InfoAgent 等 subscriber 消费。

## 2026-08-31 ~ 09-02（架构拆分：MH 收集层 + InfoAgent AI 层）

### 1. 接入安卓端（android_client/）
- 从 info_agent 仓库**原样复制**安卓端项目到 `android_client/MessageHub`（2026-09-03 由 InfoAgent 改名而来；Kotlin + Jetpack Compose，监听短信/通知 → 本地 Room → 后台同步）。
- **网络层改造对接 MH**（`data/remote/` 下 3 个文件）：
  - `ApiModels.kt`：替换为 MH 模型（`MessageCreateRequest`：source_device_id/type/sender/content/timestamp/metadata；`MessageApiResponse`、`MessageListResponse` 对齐 MH 响应）
  - `MessageHubApiService.kt`（原 InfoAgentApiService）：端点改 `POST api/v1/messages`、`GET api/v1/messages`（原 memories）
  - `MessageHubApiClient.kt`（原 InfoAgentApiClient）：本地 Memory → MH Message 映射（type：SMS→SMS、NOTIFICATION→PUSH_NOTIFICATION；sender 从 metadata 取；timestamp ISO8601）
  - 本地采集/存储/UI 逻辑保留不动；`MemorySyncService` 无需改
- 构建：`./gradlew assembleDebug` ✅

### 2. 服务器端（纯收集层）
- venv + 依赖就绪，`python app.py`（5001）运行，`init_db.py` 初始化（2 设备 + 示例消息）
- 验证：`POST /api/v1/messages` 201、`GET /api/v1/messages` 查回、EMAIL 类型也收

### 3. 邮箱收集器（mail_collector.py）
- **通用 IMAP 多邮箱收集**（Gmail/QQ/Outlook 等，imaplib/email 标准库）：
  - `MAIL_ACCOUNTS` JSON 多邮箱配置（host/user/password/port/use_ssl/folder/label）
  - 拉取未读邮件 → **纯文本解析**（text/plain 优先，HTML 清理，RFC2047 中文，附件忽略）→ `POST /api/v1/messages`（type=EMAIL）
  - **去重**：Message-ID/UID 记状态（`~/.message_hub/processed_mails.json`），仅成功才标 `\Seen`
  - CLI：`--once`（cron）/`--watch N`（常驻）/`--dry-run`；app.py 可自动起后台线程（配了 MAIL_ACCOUNTS 才启）
- 测试：`test_mail_collector.py` 16 用例全过（含对运行中 MH 真实 POST）
- 文档：`docs/mail-collector.md`

### 4. 安卓端 bug 修复（CHANGES_PLAN A/B/C）
- **A 通知采集去重**（`NotificationProcessor.kt`）：与全局"上一条"原始内容完全一致则丢弃（严格相邻：`A A A→1`，`A B A→3`）
- **B 主页聚合**（`MemoryListViewModel` + `MemoryListScreen`）：全量取数 + 聚合改"严格相邻"（`A A B A A→[A×2][B×1][A×2]`）+ 渐进式展示（默认 10 组 + 载入更多）
- **C 长按屏蔽 app**（`AppPreferences` + `NotificationListener` + `MemoryListScreen` + `MainActivity`）：`blockedApps` 黑名单，入口拦截，主页长按「禁止此 app 的通知」
- 构建：✅（修正了 A 的语义偏差：全局上一条，符合 CHANGES_PLAN `A B A→3`）

### 架构目标
```
手机(通知/短信) ──POST──▶ MH ◀──IMAP── Gmail/QQ/Outlook 邮箱
                              │ subscriber(delta sync)
                              ▼
                        InfoAgent(AI 处理 → memories)
```

## 2026-09-03（真机联调 + 收尾）

### 1. git
- commit `e6cf49e`（94 文件，+7914 行：android_client + mail_collector + 接线 + 文档）已 push 到 `github.com:jxitc/message_hub` main

### 2. 真机联调（USB adb，绕开无线 No route 环境限制）
- MH 版 APK 已 `install -r` 装到手机（当时包名 `com.jxitc.infoagent`，lastUpdateTime 9/3 21:38；**9/3 改名后为 `com.jxitc.messagehub`，需卸载重装**），server_url 配为 `http://192.168.3.226:5001`（MH）
- **手机 → MH 链路验证通过**：采集触发后文件日志显示 `Message created on MH server (HTTP 201)`；MH 库内 `android-phone-1` 累计 **1100+ 条** PUSH 通知（8/31~9/3 持续来自手机）
- 注意：`simulate_*` debug 后门**绕过** `NotificationProcessor` 去重（直接调 use case），去重只对真实通知路径生效；验证去重需真实连续通知

### 3. InfoAgent MH 导入器（跨仓，本仓依赖侧）
- `info_agent/info_agent/mh_importer.py` + CLI `mh-import`：从 MH `/sync/messages?since=` 拉 → DeepSeek 处理 → memories（按 `mh_message_id` 幂等 + `mh_import_state` 水位）；实测 15 条、幂等验证 ✅；导入真实库未跑（需全权限）

### 4. 当前环境 / 接续状态
- 手机：USB `MV95UKDYGMFQKRQ8`，MH 版 app（server_url=MH 5001）
- MH：`python app.py`（5001），**常需重启**；DB `instance/message_hub.db`（~1247 条）
- InfoAgent：DeepSeek 驱动，`python -m info_agent.api`（0.0.0.0:8000），真实库需全权限
- DSH：升级 `@deepseek-ai/dsh@0.1.2-rc.1`（pre-release）
- 待办：真实连续通知验证去重、邮箱收集填真实凭据(Gmail/QQ App Password)、InfoAgent 导入真实库、CHANGES_PLAN 同步回 info_agent 安卓端(可选)

## 2026-09-03（追加：安卓端改名 MessageHub + 新图标）

- **安卓端全量改名 InfoAgent → MessageHub**：
  - 工程目录 `android_client/InfoAgent` → `android_client/MessageHub`
  - 包名 / applicationId `com.jxitc.infoagent` → `com.jxitc.messagehub`（namespace 同步）
  - 类与文件：`MessageHubApplication` / `MessageHubApiClient` / `MessageHubApiService` / `MessageHubDatabase`（原 InfoAgent*）
  - 资源/文案：app_name → `MessageHub`、主题 `Theme.MessageHub`（原 Theme.InfoAgent）、通知渠道 id、日志 TAG、`messagehub_prefs` / `messagehub_database`（原 info_agent_*）等
  - **包名变化 ⇒ 手机旧 app 不再被覆盖，需卸载后重装**
- **新图标（用户选定 C2「消息气泡」）**：蓝→藏青垂直渐变背景 + 白色圆角气泡（带小尾巴）+ 气泡内三色圆点（amber/green/cyan）；adaptive icon（anydpi-v26，vector background/foreground）+ 5 档密度 legacy PNG；512/1024 母版在 `android_client/MessageHub/art/`（对比用的 5 个候选在 `art/candidates/`）
- 构建验证：`./gradlew :app:assembleDebug` ✅（BUILD SUCCESSFUL；APK `app/build/outputs/apk/debug/app-debug.apk`，badging 确认 `com.jxitc.messagehub` / label `MessageHub`；沙箱内用工程内 debug keystore 走 `ANDROID_DEBUG_KEYSTORE`，避免写 `~/.android`）
- 外部 AI 层表述（info_agent 仓库 / InfoAgent server / mh_import 导入器等）保持原名未动；`android_client/docs/` 下旧版设计文档属历史遗留（描述旧 InfoAgent 全栈架构），未随改名重写
- **手机装机 + 端到端验证（2026-09-03 22:20）**：adb 安装 `com.jxitc.messagehub`（旧包 `com.jxitc.infoagent` 已卸载）；run-as 预置 `messagehub_prefs.xml`（server_url=`http://192.168.3.226:5001`，进程内存缓存旧值需 force-stop 重启生效）；`enabled_notification_listeners` 已切到新包；`DebugCommand simulate_sms` + sync → MH 库新增 SMS（`android-phone-1`，内容含 `MH_RENAME_TEST_222013`）✅；注意 ColorOS 后台限制：DEBUG 广播需 app 前台才送达；新包需重授短信/通知权限（本次首启已授），自启动白名单如旧 app 开过需补
- **远端部署（2026-09-03，DigitalOcean 188.166.172.192）**：systemd + gunicorn 托管 Flask（`0.0.0.0:5001`），SQLite（`/opt/message_hub/instance/message_hub.db`，纯空 schema），ufw 放行 22/5001；SSH 走专用密钥（本机 `.mh_deploy/`，不在 git）；一键脚本 `deploy/deploy.sh`（幂等：rsync→venv→pip→.env SECRET_KEY→create_all→systemd→ufw→health）；公网 `/health`、三个 Web 页面、API POST/GET 闭环均验证 ✅；文档 `docs/deploy.md`。注意：接口目前无认证，需尽快加 API Key（见 docs/deploy.md §9）
- **域名接入 + HTTPS（2026-09-03）**：Squarespace 加 `A` 记录 `mh -> 188.166.172.192`（`dig` 生效）；服务器 nginx 反代 `mh.jxitc.com -> 127.0.0.1:5001`（与原有 `ads-science.com` vhost 并存）；`certbot --nginx` 签 Let's Encrypt（到期 2026-12-02，`certbot.timer` 自动续期），`http` 301→`https`，`https://mh.jxitc.com/health`=healthy。注意：接口仍无认证，需尽快加 API Key
- **API Key 鉴权（2026-09-03）**：服务器 `/api/v1/*` 加共享 `X-API-Key` 校验（`MH_API_KEY` 存服务器 `.env`；无 key→401、带对 key→200、错 key→401、POST→201 ✅）。安卓端 `AppPreferences.apiKey` + OkHttp 拦截器自动带头；`deploy.sh` 排除 `.env`（`--delete` 曾连带删掉服务器 `.env` 的坑已修）。**安卓端待装新 APK**（需手机 USB 重新连接）

## 2026-09-03（今日收尾）

今天这条线全部打通并完结：

- **安卓端**：`InfoAgent → MessageHub` 改名 + 新图标（C2 气泡）；包名 `com.jxitc.messagehub`；已装手机并推送线上。
- **Web UI**：统一深色主题；修复 Messages 分页 Jinja `min()` bug。
- **部署**：DigitalOcean `188.166.172.192`，systemd + gunicorn + SQLite，`deploy/deploy.sh` 一键幂等部署。
- **域名 + HTTPS**：`https://mh.jxitc.com`（Squarespace A 记录 + nginx 反代 + Let's Encrypt，http→https 301）。
- **鉴权**：`/api/v1/*` 共享 `X-API-Key`；手机带 key 推送，E2E 通过（远端库新增 `android-phone-1` SMS，HTTP 201）。
- **加固**：公网仅开放 `22/80/443`（`5001` 已收回内网）。

**线上当前状态**
| 项 | 值 |
|---|---|
| 入口 | `https://mh.jxitc.com`（Web UI）/ `/health` |
| 服务器 | DO `188.166.172.192`，`/opt/message_hub`，systemd `message-hub.service` |
| 手机 | `com.jxitc.messagehub` → `https://mh.jxitc.com`（带 `X-API-Key`） |
| 开放端口 | 22, 80, 443 |
| 机密 | SSH key 本机 `.mh_deploy/`；服务器 `.env`（`SECRET_KEY`+`MH_API_KEY`）；均不入库 |

**运维速查**
- 重部署：`./deploy/deploy.sh`（幂等）
- 服务：`systemctl restart message-hub` / `journalctl -u message-hub -f`
- 证书：`certbot.timer` 自动续期（到期 2026-12-02）

**待办（可选，下一步再排）**
- [ ] 邮箱收集器 Gmail/QQ App Password 真实凭据
- [ ] InfoAgent 导入真实库 / CHANGES_PLAN 同步回 info_agent 安卓端
- [ ] 接口进一步加固（如 Basic Auth 或用设备 `api_key` 做分设备鉴权）

## 2026-09-04（Web 修复 + 移除 read/unread 概念）

### 1. Web 显示修复（公网/时区/分页/配色）
- **同源探活**：前端不再硬编码 `http://127.0.0.1:5001`（公网打开时 badge 误报 Disconnected；mark-read 也连错地址），改走同源相对路径；status 页连接测试不再直连需鉴权的 `/api/v1/*`
- **时区**：时间一律存 UTC；模板输出带 `+00:00` 的 ISO（`utc_iso` 过滤器，补偿 SQLite 丢 tzinfo），JS `localizeTimestamps()` 按浏览器本地时区渲染（dashboard/messages/detail/status 全覆盖）
- **分页**：Message 页翻页改真实 `<a href>`（保留筛选参数），移除 JS 双拦截导致的"下一页无反应"
- **配色**：详情页内容/元数据块 `bg-light` → 暗色主题 `.mh-panel`（修复白底白字）

### 2. 移除 read/unread 概念（用户决定：不需要"已读"语义）
- DB：删 `messages.is_read` 列；新增幂等迁移脚本 `migrate.py`（deploy.sh 在 `create_all` 后自动跑；SQLite/PostgreSQL 通用，重复执行无害）
- API：删 `PUT /api/v1/messages/<id>/read`；CLI 删 `mark-read`、`--unread`、`✓/○` 状态符号
- Web/模板/JS：删 Unread 计数卡、unread 筛选、Mark as Read 按钮、状态徽章、`status-indicator` 样式；dashboard 统计卡 4→3（等宽）
- 安卓端：`ApiModels.kt` 删 `isRead` 响应字段（Gson 缺省 false，无影响）
- 文档：README/CLAUDE.md 同步（mail_collector 的 IMAP UNSEEN 语义不受影响，保留）
- 验证：迁移幂等（旧库删列保数据/新库 no-op）；API 响应无 is_read、PUT read→404；CLI 无状态符号、`mark-read` 报 No such command；test_api/sync/cli/mail_collector 全过

## 2026-09-04（API Key 管理体系：网页生成 → 设备/CLI 独立配置）

取代"单一共享 `MH_API_KEY`"的鉴权，改为**多把独立 key**（GitHub PAT 模式）：

- **DB**：新增 `api_keys` 表（`models/api_key.py`）：name/prefix/key_hash(sha256，不存明文)/is_active/last_used_at；`db.create_all()` 自动建表，无需迁移
- **中间件**（`api/v1/__init__.py`）：`require_api_key` 先查表（sha256 匹配 + active，成功后更新 last_used_at）；兼容回退 legacy `MH_API_KEY` env（平滑过渡，等各端换新 key 后从 `.env` 移除即可）；表与 env 均无 key 时仍进 dev 无认证模式并告警
- **Web Settings 页**（`templates/settings.html` + 3 路由，受 Cloudflare Access 保护）：
  - 列表：name/prefix/状态/创建时间/最后使用，可逐个 **Revoke**（软删）
  - Generate：输入 label → 返回 `mhk_<随机>` 明文**仅一次**（flash 展示），DB 只存 hash
  - 导航栏新增 Settings 入口
- **CLI**：新增 `--api-key/-k` 全局参数；config 持久化（`config-set --api-key` / config.json / env `MH_CLI_API_KEY`）；`make_request` 自动带 `X-API-Key` —— **修复鉴权后 CLI 全部 401 的问题**（tasks.md 待办①完成）
- 验证：生 key → 带 key API 200 / 无 key·错 key 401 / 吊销后 401 / 别把 key 误伤；DB 只存 hash；last_used_at 自动更新；CLI `--api-key` 与 config 持久化读消息通；test_api/sync/mail_collector 全过


