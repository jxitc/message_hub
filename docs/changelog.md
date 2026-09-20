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


## 2026-09-04（消息内容 schema 精简）

- **问题**：安卓端采集时把「emoji🔔/📱 + 英文标签 + 时间戳」硬拼进 `content`（如 `🔔 Notification
App: 微信
Time:...`），显示层又要剥一遍，冗余不正式。
- **改法**：`content` 只存**原始正文**；结构化信息放 `type`/`sender`/`message_metadata`(JSON)。
  - `ProcessSmsUseCase.formatSmsAsMemory` → 返回原始 `smsMessage.content`；metadata 加 `source=phone`、`message_id`
  - `ProcessNotificationUseCase.formatNotificationAsMemory` → 返回 `title\nbody`（无正文用 title）；metadata 加 `source=app`、`title`
- **验证**（手机 → 线上）：
  - SMS：`content='CLEAN_SMS_194810'`（无 emoji/前缀）、`sender='+86139...'`、metadata 含 `source/phone_number/contact_name/message_id`
  - 通知：`content='WeChat\nhello'`、`sender='微信'`、metadata 含 `source/app_name/package_name/notification_id/title`
- 兼容：历史消息仍为旧格式不清理；新消息干净。服务器 schema 无需改（本就有 `message_metadata` JSON）。
- ⚠️ adb 发含空格 body 会被截断（`--es` 引号陷阱），测试用无空格标记。
- **收尾**：显示层 `cleanTitle` 去掉「剥 emoji/前缀」逻辑（content 已干净）；移除不再使用的 `dateFormat` + java import；新增可复用 E2E 脚本 `android_client/e2e/phone_to_mh_e2e.sh`（simulate SMS/通知 → 查服务器库，断言 content 干净 + metadata 结构化；实测 **PASS**）。
- **单元测试（2026-09-04）**：服务器 `tests/` pytest 套件（临时 SQLite + `MH_API_KEY`）——`test_auth`（无 key→401、对 key→200、错 key→401、`/health` 开放）、`test_messages`（POST 干净 content + 结构化 metadata → 201 + GET 回查），**7 passed**；安卓 JVM 单测——抽出纯函数 `MessageFormatter`/`MessageMapper` 并让 use case/ApiClient 复用，`MessageFormatterTest` 6 + `MessageMapperTest` 7，**14 例全过**；`requirements-dev.txt` 加 pytest。
- **忽略/屏蔽 app 列表（2026-09-04）**：`AppPreferences.removeBlockedApp`；主页长按菜单按状态显示「禁止/取消屏蔽此 app 的通知」（toggle，`blockedApps` 状态驱动）；设置页新增「已忽略的通知 apps」列表 + 移除按钮。

## 2026-09-12（邮件收件人识别 + 通用筛选与多选删除）

### 1. 邮件收件人（To）：一封邮件到底寄给了哪个信箱

- **问题**：托管了多个信箱（hotmail 等转发进 Gmail），只存 Subject/From/Date 就分不清
  「这封信是寄给谁的」。
- **实测结论**（Gmail IMAP 14 封转发邮件）：`Delivered-To` 恒为 Gmail 账号本身
  （❌ 区分不出别名）；**`To` 保留原始收件地址**（✅ `jxitc@hotmail.com`）；
  `Return-Path` 里的退信地址可交叉验证。
- **改法**（`mail_collector.py`）：新增 `extract_addresses()` / `unique_lower()` /
  `recipient_fields()`；`metadata` 增 `recipients`（To+Cc，小写去重）、`to`/`cc`/
  `original_to`/`delivered_to`；`content` 头部加一行 `To:`（CLI 与下游不用解 metadata）。
  空值不写入 metadata，保持 JSON 精简。
- **回填**（`scripts/backfill-mail-recipients.py`，新增）：按 `Message-ID` 回邮箱取头，
  `BODY.PEEK` **只读不标已读**，幂等。线上 4 封历史邮件实测：
  **3 封寄给 `jxitc@hotmail.com`**、1 封 `jiangxjx@gmail.com` —— 正好印证了这个问题。

### 2. 通用筛选 + 多选删除（用户纠正的模型）

- **用户纠正**：「不是按邮箱清空，而是先很方便地筛选（设备/类型/时间）→ 显示结果 →
  多选删除」。删除是作用在筛选结果上的动作，不是"清空某个邮箱"。
- **新增 `message_filters.py`**：筛选逻辑**只有一份**，网页 / API / CLI / 删除路径全走它。
  预览与删除若用两套筛选，迟早差一两行 → 用户丢掉没看过的数据。
  - `apply_filters()` 给直观调用方；`apply_filter_dict()` 给持有 `normalize_filters()`
    结果的调用方（键名是 `type`，`**filters` 会抛 TypeError，测试当场抓到）。
  - `recipient` 用 **SQLite JSON1** 在 SQL 里匹配 `metadata.recipients`（Python 侧过滤
    会让分页总数失真）；JSON1 缺失时 `list_recipients()` 返回空列表而不炸页面。
  - 时间筛 `timestamp`（事发时间）而非 `received_at`（入库时间）——补录邮件两者差好几天。
- **时区**：`<input type="date">` 无时区，若把 `2026-09-01` 当 UTC 日，UTC+8 下会错 8 小时。
  网页提交时用浏览器时区换算成本地当天 00:00 / 23:59:59.999 的 UTC 瞬时串放进
  `since_utc`/`until_utc`，服务端优先用 `*_utc`；CLI/API 给裸日期则按 UTC 日。
  绑定 naive UTC datetime 比较（DB 里存的就是 naive UTC）。
- **三个删除入口，都防误触**：
  - 网页勾选删除（`POST /messages/delete`，表单 `ids` 可重复，上限 1000）；
  - 网页「Delete all N matching」（`POST /messages/delete-filtered`，N 为服务端真实总数，
    必须 `confirm=yes` 且**必须至少一个筛选条件**，否则拒绝——不然等于删库）；
  - API `DELETE /api/v1/messages`（按 id）与 `POST /api/v1/messages/delete`
    （按筛选，**`dry_run` 默认 true**，忘写只会拿到数量）；CLI `message-hub delete`
    默认演练、`--yes` 才真删，无筛选直接拒绝。
- **UI**：`/messages` 筛选区加「Received at (To)」下拉 + From/To 日期；每行复选框 +
  表头全选（含 indeterminate 状态）+ 实时已选条数；消息卡片显示 `to xxx`；
  筛选徽标与「filtered: …」摘要；翻页链接保留全部筛选参数。

### 3. 验证

- 本机功能测试（临时 SQLite，未碰真实数据）**全过**：筛选 6 例、API 5 例、
  dry-run / 按 id / 按筛选删除 8 例、防误触 5 例、日期边界 5 例。
- CLI 端到端（真起服务 + 真 CLI 子进程）**全过**：列筛选 4 例、拒绝无筛选 2 例、
  演练 2 例、`--yes` 删除 2 例、按 id 1 例、按收件人 1 例。
- 线上自查：收件人筛选 3 + 1 封符合预期；自建 `mh-selftest` 消息演练报 1 → 实删 1 →
  回查 0（用完即删）。网页服务端渲染确认下拉、全选、`Delete all 3 matching`、
  `to jxitc@hotmail.com` 均出现。
- 新文档：`docs/message-filtering.md`（筛选/删除模型、时区处理、三入口、实现要点）。
- `docs/mail-collector.md` 同步修正漂移：补 `To:` 与 recipients 元数据、新增
  「收件人识别」与「增量模式 `--since-days`」小节（此前文档仍写着"UNSEEN 并把邮件标已读"，
  且把 UID 增量列为"未实现"）、网页版配置来源优先级、回填脚本、路径修正 `jxitc` → `xiao`。
## 2026-09-12（追加：去掉重复的收件人字段 + 记录"对端维度"的取舍）

- **背景**：用户质疑"是不是又加新字段了？以后来一个渠道就加一套字段，不 scalable"。
  澄清：本次**没动 DB schema**（`messages` 仍 10 列），`recipients` 是写进 `message_metadata`
  JSON 的键。但质疑成立的地方在于筛选维度带渠道色彩（下拉框叫 "Received at (To)"，只有邮件有）。
- **现状盘点**（线上真实数据）：PUSH_NOTIFICATION 1735 行 / SMS 63 行 / EMAIL 4 行，
  三套不重叠的 metadata 键。翻译成"角色"后其实只有一套：谁发的（`sender` 列）、发给谁
  （**缺一等公民**）、在哪发生的（`source_device_id` 列）、什么类型（`type` 列）、何时（`timestamp` 列）；
  `message_id`/`notification_id`/`package_name`/`delivered_to` 属渠道细节，只用于去重与留档。
  原则：**渠道不进 schema，角色才进**。
- **评估了 `message_participants(message_id, role, address, name)` 子表方案**
  （role ∈ from/to/cc/bcc，索引 (address, role)，由 hub 入库时统一派生 → 客户端不改、
  新渠道只加映射分支、白送按对端/发件人筛选）。
  **决定暂不做，等第二个非邮件来源真接进来再上**（现在只有邮件有对端，此时建表是为想象中的
  渠道设计 schema）。已把理由与代价写进 `docs/message-filtering.md`：JSON 多值数组无法建索引
  （`json_each` 全表扫），1,802 行无感但一年 ~7.5 万行后线性变慢，以及后续回填量更大。
- **本次实际清理**（消除"两个真相来源"）：metadata 里原本同时存 `recipients`（规范化数组）与
  `to`/`cc`/`delivered_to`/`original_to`（原始头）。既然查询暂时仍靠 JSON，
  `recipients` 就是唯一真相来源，原始头不再进 metadata；其可读版本仍在 `content` 的 `To:` 行，
  下游没损失。`parse_message()` 仍返回这些原始头（渲染 content、将来区分 To/Cc 角色可用），
  只是 `build_payload()` 不再写进 metadata。
- **迁移**：`migrate.py` 新增 `strip_redundant_recipient_keys()`，用 `json_remove` 幂等清掉老行里的
  这 4 个键（只动 `type='EMAIL'`，其他渠道的 `to` 等键不受影响）。随 deploy 自动执行。
- **验证**：pytest 41 例全过（新增 `tests/test_migrate.py` 3 例：只删重复键且保留 `recipients`、
  幂等、不动其他渠道；`test_mail_recipients.py` 增断言 metadata 恰好只有 4 个键）。
## 2026-09-12（追加：把"公共字段"约定写成契约 + 可检查）

- **用户提问**："我们有没有最大公约数字段？把渠道专门字段塞 JSON 里，真要筛选也能搞，就是不优雅。"
  答案：**有，但一直是隐式约定**——没人写下来、没人检查，于是每个渠道都重新漂移一次。
- **术语更正**：我把它缩写成了 GCD，**已废弃**——GCD 在别处指最大公因数，在 Apple 平台上还是
  Grand Central Dispatch。模块定名 `message_contract.py`，中文叫「公共字段」。
- **契约三层**（`message_contract.py`）：
  - 第一层公共列：`timestamp` / `type` / `sender` / `source_device_id` / `content` / `received_at`
  - 第二层公共保留名（可选，放 JSON）：`recipients`；将来加"标题"统一叫 `title`，别再出现第 4 种叫法
  - 第三层渠道私有键：`app_name` / `package_name` / `title` / `phone_number` / `mailbox` / `subject` …
- **提升规则**（什么时候 JSON 键该升成列，三条）：① 是不是所有渠道都有 ② 筛选形态是单值还是数组
  ③ 是不是已经存在于别处。**"看起来重要"不是理由**——`app_name` 覆盖 96% 消息、只有 22 个去重值，
  但只有通知有，所以按契约它留在 JSON。
- **JSON 筛选能力边界（实测，SQLite 3.37，已写进文档）**：
  - 单值键等值：**能走索引**——`CREATE INDEX ... ON messages(json_extract(message_metadata,'$.app_name'))`
    → 查询计划 `SEARCH m USING INDEX i_app`；所以"放 JSON + 需要时再筛"这条路对 `app_name`/`title` 完全成立；
  - 数组键包含（`json_each`）：**不能走索引**——实测 `SCAN m` + `CORRELATED SCALAR SUBQUERY` +
    `SCAN json_each VIRTUAL TABLE`，给 `json_extract(meta,'$.recipients')` 建了索引也用不上 → 线性扫。
    这才是真正的天花板，也是将来必须升子表（`message_participants`）的唯一理由。
  - 结论：日常主筛选路径 = 列 + **一个通用的 JSON 逃生舱**（`meta=key:value`），
    而不是每来一个渠道加一个专门筛选维度。
- **可检查性**：
  - `scripts/audit-metadata.py`（新，只读）：按渠道列出实际写入的键 + 契约违规 + 值得做逃生舱的维度；
  - `POST /api/v1/messages` 入库时跑 `lint_metadata()`，把"与列重复"的键打成 warning
    （**只报告不拒绝**，否则老客户端会直接写不进来）。
- **线上审计结果**（1,802 行）：3,457 次契约违规，全是"与列重复"，**没有未知键**——
  `PUSH_NOTIFICATION.timestamp` 1735 / `.source` 1610；`SMS.timestamp` 63 / `.contact_name` 63 /
  `.source` 49。其中 `metadata.timestamp` 实测与列是同一时刻（`21:54:47.821000` ↔ `1788472487821`）。
- **已知缺口记录在案**：没有公共的"发给谁"列；`sender` 地址与显示名混用（63 条短信里 28 条
  `sender` 存的是联系人名 `张丽捷`，地址在 JSON）；没有跨渠道"标题"。
- **验证**：pytest **47 例全过**（新增 `tests/test_message_contract.py` 6 例，含"契约里声明的列必须
  真实存在"以防文档与 schema 脱节）；线上端到端：POST 一条违规消息 → journald 出现 3 条契约告警 →
  用 `DELETE /api/v1/messages` 清掉（`deleted:1`，回查 0 残留）。合规消息 0 告警。
- 新文档：`docs/message-schema.md`（三层结构、提升规则、JSON 筛选能力矩阵、已知缺口）。
## 2026-09-12（更正：就叫 metadata JSON，不确定的先塞进去）

- **用户定调**："就叫 metadata json 吧，不确定的先往里面塞。"
  我上一轮造的"公共字段契约 / 三层结构"那套概念名收掉——**默认落点就是 `message_metadata`
  （JSON）**，不当临时方案看待：渠道会一直变，等想清楚了再决定要不要升列，比现在猜 schema 划算。
- **改名**（上一轮的命名整体作废）：`message_contract.py` → `metadata_policy.py`，
  `docs/message-schema.md` → `docs/metadata-json.md`，`COMMON_FIELDS`→`CORE_COLUMNS`、
  `RESERVED_JSON_NAMES`→`SHARED_JSON_KEYS`、`KNOWN_CHANNEL_KEYS`→`OBSERVED_CHANNEL_KEYS`、
  `describe_contract()`→`describe_policy()`、审计脚本 `--contract`→`--policy`。
  三层结构压成两句大白话：**列只留决定结构的 6 个；其余不确定的先塞 JSON**。
- **只保留一条纪律**：别把已经存在于列里的事实再抄一份进 JSON（否则两个真相来源迟早漂移）。
  这条不删的理由是有实测代价：线上 1,802 行里 3,457 次违规**全是这一类**，
  而且**是客户端在写的、服务端删不掉，只能等改客户端**——所以必须守在入库前（`lint_metadata()`
  打 warning，只报告不拒绝）。
- **保留的实测结论**（支撑"塞进去之后还能筛"）：单值键建表达式索引后走 `SEARCH ... USING INDEX`；
  数组键只能 `SCAN m` + `json_each` 相关子查询。所以日常筛选路径 = 列 + 一个通用 JSON 逃生舱，
  数组才需要考虑升子表。
- **顺手修**：`scripts/audit-metadata.py` 在空库上不再抛 SQLAlchemy 栈，改为明确提示
  （本地开发库此前被测试误删过表，已重新建表恢复）。
- 验证：pytest 47 例全过；线上 `--policy` 输出与审计报表均正常（3,457 次重复键明细可复现）。
## 2026-09-13（附件与原件全链路：存储 + 上传 + OCR/PDF 提取 + 手机端）

- **决定**：原件一律保留（用户明确"原图和文件都要上传保留"）；单文件上限 **1MB**，
  图片超限由手机端压缩，PDF 无法压缩直接拒绝；文件落服务器本地磁盘，
  但**结构上为迁移 R2 准备**（内容寻址 key + 存储抽象 + 稳定下载入口）。
- **存储层 `blob_store.py`**：内容寻址 `blobs/<sha[0:2]>/<sha[2:4]>/<sha256><ext>`；
  **key 由内容派生**（扩展名取自嗅探到的 magic bytes，不采信客户端 Content-Type 与文件名）
  → 路径穿越从结构上不可能、重复上传天然幂等；写临时文件后原子 `os.replace`；
  先写字节后写库（崩溃只留孤儿，不留悬挂引用）；`collect_garbage` 做 mark-and-sweep；
  配额到线返回 507 而不是把磁盘写满（磁盘满会让 SQLite 写失败）。
- **限制与白名单**：1MB/文件、8 文件/请求、16MB/请求（与 nginx `client_max_body_size` 对齐）；
  允许 png/jpeg/gif/webp/pdf/text（按内容嗅探）；**明确拒绝 svg/html/js/xml**
  （它们是文本但会在浏览器执行脚本）。
- **接口**：`POST /api/v1/messages` 支持 `multipart/form-data`（`metadata` 为 JSON 字符串；
  有附件时 `content` 可为空）；`GET /api/v1/attachments/limits`（客户端先问再压，别靠 413 试错）；
  `GET /api/v1/blobs/<key>` 稳定下载入口，`X-API-Key` 或 **HMAC 签名 token**（浏览器 `<img>` 带不了
  header，当年 APK 踩过同一个坑）；`GET /api/v1/blobs` 用量与引擎状态。
- **文本提取 `extraction.py`**（异步后台线程，30s 轮询；1 核机器上一张扫描件可能几十秒，
  同步会把 `-w 1 --threads 2` 的整个服务堵住）：PDF 用 `pdftotext`，**无文本层自动回退**
  `pdftoppm`+`tesseract`（最多 10 页）；图片 `tesseract -l eng+chi_sim`（中英文都已安装）；
  纯文本依次试 utf-8/gb18030/latin-1。
  **提取文本的落位（只有一份，绝不重复）**：`content` 为空 → 写进 `content`；
  `content` 已有文本 → 留在 `metadata.attachments[i].extraction.text`（追加会混淆
  "发件人写的"与"OCR 猜的"，且重跑会追加两遍）。两种都在库里，下游 AI 读同一种数据、永不开文件。
  可重跑：`scripts/reextract.py --status/--pending/--all/--engines`。
- **邮件侧**：收集器先向 `/api/v1/attachments/limits` 问一次限额（不在本地再抄白名单），
  允许的走 multipart 上传，其余记 `metadata.attachments_skipped`——
  "有附件但没存"和"没附件"必须可分辨。新增 `--attachments-only`（历史发票归档用）与
  `scripts/backfill-mail-attachments.py`（给上线前导入的邮件补附件）。
- **手机端**（同一目标，独立提交）：手动添加支持选图/选文件；≤1MB 原样上传（编码器零调用），
  >1MB 走降采样+长边 2048+JPEG q80→q50；非图片超限直接拒绝；`sender`=设备名（`Build.MODEL`）；
  新增 79 个单测（共 97）全部通过；构建成功（APK 25.8MB）。
- **运维**：`scripts/blob-gc.py`（先列后删，数字诚实）；deploy.sh 写入 nginx 16m 并把
  gunicorn 超时做成 `MH_TIMEOUT`（默认 120，多部分上传在慢链路下别被掐断）。
- **修掉两个真 bug**：① 超大文件返回 415 而非 413（大小是硬错误，类型才是逐文件拒绝）；
  ② `reset_status` 用浅拷贝改 metadata，导致 SQLAlchemy 比较新旧值"深度相等"→ **不发 UPDATE**，
  重跑提取静默无效。已补"必须用新 session 回查才抓得到"的回归测试。
- **验证**：pytest 86 例全过；线上端到端 42KB PNG → tesseract OCR、13KB PDF → pdftotext，
  文本分别按规则落进 `content` / `metadata`；下载字节 `cmp` 一致；无密钥 401、签名链接匿名可下；
  非图片强制 attachment + nosniff；收集器 multipart 路径线上打通；GC 在真实孤儿上验证。
- **未做（已记录）**：独立 blob 子域 `blob.mh.jxitc.com`（当前与 Web UI 同源，靠
  Content-Disposition + nosniff 防护）；真机验证（未连 USB）。
- 新文档：`docs/attachments.md`（含"为什么字节不进 DB"、迁移到 R2 的步骤、安全说明）。
## 2026-09-13（追加：附件独立源 mhblob.jxitc.com）

- **完成 Phase 1 最后一块**：附件不再与 Web UI 同源，改由 **`https://mhblob.jxitc.com/<key>`**
  提供（nginx 把 `/<key>` 映射到 `/api/v1/blobs/<key>`，鉴权仍由应用负责：X-API-Key 或 HMAC 签名 token）。
  这样用户上传的 PDF/SVG 即使被诱导打开，也够不到主站的会话。
- **代码**：新增 `BLOB_PUBLIC_BASE`（`config.py`）；`blob_url()` 在有该配置时返回独立源绝对地址，
  否则退回同源相对路径（不配置也能照常工作，等于回滚开关）。网页端与 API 统一走**同一个签名机制**
  （删掉了原来的 `web.download_attachment` 路由，避免两套签名并存）。
  新增 `test_blob_url_switches_to_the_separate_origin`（88 例全过）。
- **⚠️ 踩坑并记录**：最初用 `blob.mh.jxitc.com`（**二级**子域），CF 边缘握手直接失败
  （`sslv3 alert handshake failure`，无 HTTP 状态码）。根因：**免费版 Universal SSL 只覆盖 apex 与
  一级子域**；二级子域需要付费的 Advanced Certificate Manager。实测对比：
  `mh.jxitc.com` → `CN=jxitc.com` verify ok；`blob.mh.jxitc.com` → 握手失败；
  `mhblob.jxitc.com` → verify ok。改用一级子域解决。
- **签发源站证书**：CF 代理会干扰 ACME HTTP-01（边缘可能先 301 到 https 而源站尚无该域名证书），
  做法是**临时把记录切为 DNS-only → 跑 certbot → 再切回橙云**；旧名字的证书已清理。
- **deploy.sh**：blob vhost 纳入管理，但用「文件不存在才创建」——certbot 追加的 TLS 行不能被
  每次部署冲掉（已验证重部署后 2 行 ssl_certificate 仍在）。
  另修一个部署脚本 bug：新加的 nginx 配置里 `$host` 等未转义，被未加引号的外层 heredoc 在本地展开，
  远端 `set -u` 直接 `unbound variable` **导致整次部署中断**（当时只 rsync 了代码、服务没重启）。
- **线上验证**：API 返回 `https://mhblob.jxitc.com/<key>`；网页版附件链接同样指向该源并带签名；
  从该源用 token 下载字节与原件 `cmp` 一致；无 token 401；OCR 仍正常（tesseract 34 字进 content）；
  自测数据已清理、孤儿 blob 已 GC。
- **技能沉淀**：`cloudflare-publish` 新增 §2.1「子域层级决定证书覆盖」+ 排障表两行
  （commit `c476965`）——这个坑跨项目通用。
## 2026-09-13（追加：可复跑的线上验收 + 两个由它抓出的问题）

- **新增 `scripts/acceptance-attachments.sh`**（23 项，自带真素材、跑完自清理）：
  单元测试验不了"已部署的系统"——nginx 的 `client_max_body_size`、CF 的证书与代理、
  独立源路由、gunicorn 超时、服务器上有没有 OCR 工具链，只有打真实域名才知道。
  素材是**真**的：脚本内用纯 Python 手搓带正确 xref 的文本层 PDF（不依赖 cupsfilter 等外部工具），
  图片用 PIL 画字（无 PIL 则退化为白图并降低断言强度）——上一版用 1x1 空白图，
  那根本证明不了 OCR 能出字。
- **它当场抓出两个问题**：
  1. **PDF 文本层判据错了**：原来按绝对字数（≥20）判断"有无文本层"，于是**18 字文本层的单页 PDF
     白跑一遍渲染+OCR**（1 核上几十秒），而 `pdftotext` 早已给出答案。改为"文本层下限
     （`PDF_TEXT_FLOOR_CHARS=10`）"并记录 `chars_per_page` 让薄文本层可见；
     另加 `reextract.py --ocr` 作为手动杠杆（原件都在，随时可强制重跑 OCR）。
  2. 我自己的验收脚本有 3 个 bug：绝对 URL 又被拼了 BASE 前缀、签名只取了文件名而不是
     `ab/cd/<sha>` 三段、以及用空白图当 OCR 素材。现在一律用 API 返回的 `key` 字段拼路径，
     不再做字符串切割。
- **验证**：pytest 94 例全过；线上验收 **23 项全过**（含六条拒绝路径：真二进制 / 危险扩展名 /
  超 1MB / 空正文无附件 / 未知类型 / 无鉴权下载）。
## 2026-09-13（附件详情页 + 中文不再被 JSON 转义）

- **用户反馈**：想看附件的 OCR 信息只能在 metadata JSON 里看，而且中文全是 `\uXXXX` 转义，完全没法读。
- **根因是 Flask 默认 `ensure_ascii=True`**（不是 JSON 本身的问题）：网页版的 metadata 面板与
  **API 响应**都把中文转义了。`app.py` 一行 `app.json.ensure_ascii = False` 解决，
  网页版与接口同时受益（顺带响应体积更小）。
- **新增附件详情页** `/messages/<id>/attachments/<index>`（纯服务端，不需要发 APK）：
  预览块**按 kind 分支**（图片/文本/音频/未知），其余全部通用（大小、mime、来源、sha256、
  提取状态与引擎、字符数与字/页、文本落位、错误提示）；操作为下载原件（签名链接）、
  **重新提取**、**强制 OCR 重跑**（后台排队，避免请求超出 gunicorn 超时）。
  页面按"附件的记录"而非"图片"来设计，所以以后加录音/聊天导出/邮件文件只需加一个 preview 分支。
- **两个刻意的取舍**：① PDF 不做内嵌预览——非图片一律 `Content-Disposition: attachment`
  是 XSS 防护的一环，内嵌会触发下载，而 PDF 的有用部分本就是抽取出来的文字；
  ② 消息详情页新增"未存储的附件"一节（邮件里被超限/类型拒掉的文件 + 原因），
  让"有附件没存"与"没附件"可分辨。
- **顺手**：消息详情页新增附件卡片列表（带状态徽标，点击进详情页）；列表页的附件 chip 改为进详情页；
  新增 `human_size` 模板过滤器。
- **验证**：pytest 100 例全过；线上实测上传含中文的文本附件后，详情页正确显示
  原件信息 / 处理信息 / 提取结果，**中文直接可读**（`收据/金额/谢谢惠顾`），
  消息页 metadata 面板中 `\u` 出现次数为 0，API 响应中文也不再转义。
- **顺带确认了手机端首个真实附件**（用户实测）：来源 `OPPO-Find-X7-db1792`、
  `sender=PHZ110`（设备名，符合设计）、560KB 的 JPEG **原样上传未重编码**、
  服务端 `tesseract` 提取 928 字、因正文已有用户输入所以**未覆盖正文**而是留在附件记录里
  —— 四条设计规则全部命中。中文 OCR 在真实截图上把关键文字与数字全部识别正确。
## 2026-09-13（安卓端附件预览：轮询提取状态 + 缩略图 + 离线文本）

- **需求**（用户）：手机端也要能看附件；解析在服务端做，所以流程是"上传 → 查服务端提取状态
  → 完成后拉下来"。**结论：现有接口就够，不需要新接口**（用户也选择了不加 pending 列表接口）。
- **客户端实现**（`android_client/**`）：附件列表（名称/人类可读大小/类型/状态徽标）、
  图片缩略图（Coil 2.7.0 复用带 `X-API-Key` 的 OkHttpClient + 磁盘缓存，`respectCacheHeaders(false)`
  以免服务端没给 `Cache-Control` 时缓存根本不写）、上传后轮询 `GET /api/v1/messages/<id>`
  （退避 3/5/10/20s → 每 30s，3 分钟上限，连续 3 次失败即停，页面不可见即停）、
  提取文本可复制并标明来源、`attachments_skipped` 带原因列出、非图片原件可下载后用系统应用打开。
- **两条防坑措施写进了代码结构**（都是这轮讨论中明确的坑）：
  ① `AttachmentUrls.shouldAttachApiKey()` 让 API key **只发给配置的 API 主机**（并归一默认端口），
     从结构上避免 key 被发去独立 blob 域；`ServerAttachment` 领域模型**根本不保留 `url` 字段**，
     编译期杜绝误用（下载一律 `key + serverUrl`）。
  ② 提取文本的**两处落位**（`content` 与 `extraction.text`）按规则判定，并处理
     "`applied_to_content` 为真但本地正文为空（快照旧）"的回退，避免文本丢失。
- **Room 1 → 2 迁移**：四条可空列（`serverMessageId`/`attachmentsJson`/`skippedAttachmentsJson`/
  `attachmentsSyncedAt`），既有数据不动；**移除了 `fallbackToDestructiveMigration()`**（它会在未处理
  的版本变化上清库）。字节不入库，只存元信息 + 提取文本。
- **验证**：`assembleDebug` 成功（APK 26,246,883 B）；**单测 145 例 / 0 失败**（从 JUnit XML 报告核对，
  新增 48 例：文本落位 19、URL 拼接 10、轮询循环 8、JSON 编解码 6、退避表 5）；
  已发布 **1.6.4 (code 12)**，线上包与本地构建 `cmp` 字节一致。
- **未验证（无 USB/真机）**：缩略图真实加载、端到端提取显示、FileProvider 打开原件、
  真机 Room v1→v2 运行时迁移 —— 只能靠用户装 1.6.4 后实测。
## 2026-09-13（修正：附件状态挂到那条消息上，不再用列表页顶部横幅）

- **用户反馈**（装 1.6.4 实测后）："正在等服务器提取附件文本"为什么要放在顶上？应该挂到那条消息上；
  提取文本打开消息详情再显示就可以了。
- **改法**：删掉列表页顶部的全局横幅（`MemoryListScreen`），改为**在那一行消息上**显示一行摘要
  （`AttachmentPreviewRules.attachmentsSummary()`，纯函数）：
  `1 个附件 · 提取中` / `2 个附件 · 928 字` / `1 个附件 · 提取失败` / `1 个附件 · 不支持` /
  `1 个附件 · 12 字 · 1 个未保存`。**轮询逻辑本身不变**，只是不再用全局方式呈现。
  提取文本仍然只在消息详情里显示（`AttachmentSection` 本来就只挂在详情对话框上）。
- 判断依据：等待与结果都是**某一条消息**的属性，全局横幅既说不清是哪条、又会一直占位滚动。
- **保留**：添加页保存成功后的那句"已保存，正在等服务器提取附件文本…（离开本页会停止等待）"——
  它在动作发生处，属于"接下来会发生什么"的即时反馈，不是全局状态条。用户若也不要，说一声即可。
- **验证**：单测 **152 例 / 0 失败**（新增 7 条摘要规则测试，覆盖 pending / 字数汇总 / 失败 /
  不支持 / 未保存附件）；已发布 **1.6.5 (code 13)**，线上包与本地构建 `cmp` 字节一致。
- 过程中的自身失误记录：我用 `cat >>` 追加测试时把用例追加到了**类体之外**，
  且用字符串切割修花括号时削错了行——两次都是构建直接报错暴露的（这正是"改完必须真跑构建"的价值）。
## 2026-09-17（网页版「添加」页 + 浏览器插件划选入库）

- **需求**（用户）：① 网页端加一个跟手机一样的添加页（文字/图片/文件）；
  ② 做一个浏览器插件，划选文字自动入库。
- **先做了一次小重构**：抽出 `message_ingest.create_message()`，让 **REST API 与网页端共用同一份创建逻辑**。
  之前只有 API 一条创建路径，再加一条必然导致限额、"有附件时正文可为空"这条不变式、
  以及 metadata 契约检查各写一套、慢慢跑偏。重构后 100 例测试全过（行为不变）。
  - 顺带修一个真 bug：**marshmallow 3.20 的 `DateTime` 只接受字符串**，
    网页端传 `datetime.now()` 会得到 "Not a valid datetime"（一个正确的值却报错）。
    归一放在共享函数里，两个调用方都受益，并补了回归测试。
- **网页版添加页** `/messages/new`（导航栏新增「添加」入口）：文字 + 图片/文件（多选、拖放、
  **Ctrl/⌘+V 粘贴截图**）+ 来源链接与标题（进 `metadata.url/title`）。
  浏览器侧压缩参数与手机端一致（长边 2048 + JPEG q80→q50；**≤1MB 的图片原样上传不重编码**），
  压缩后用 `DataTransfer` 塞回真实 file input、走普通表单提交（不需要 fetch 与手工处理重定向）。
  校验走同一个 `message_ingest`，所以限额/白名单/不变式与手机端天然一致。
- **浏览器插件** `browser_extension/`（MV3，Chromium 系）：右键菜单四种用法——
  划选文字、右键图片（超 1MB 用 OffscreenCanvas 压缩）、存本页标题+链接；弹窗快速记一条。
  权限刻意最小：`contextMenus/storage/notifications/activeTab`，目标域名**运行时按需申请**
  而不是笼统要 `<all_urls>`；`activeTab` 是拿到来源 URL 的最小代价（仅在用户主动触发时授予）。
  选项页内置**两步测试连接**（先 `/health` 验连通、再带 key 打鉴权接口），避免"服务器通了但 key 错"的假阳性。
- **安全边界（已写进插件 README）**：API key **没有任何权限位**，等于整个 hub 的读写权 →
  插件必须用**单独生成、可单独吊销**的 key（不复用手机那把）；key 存 `chrome.storage.local`
  （不参与账号同步），且只发给你配置的那一个域名。
- **验证**：pytest **108 例全过**（新增 8 条：网页添加页渲染/文字/图片/来源字段/空提交拒绝/
  不支持类型时保留已输入文字/超限拒绝/timestamp 归一回归）。
  线上实测：`/messages/new` 渲染齐全、导航入口生效、真上传中文内容与 metadata 均正确入库；
  插件的两种载荷（JSON 划选、multipart 图片）用 curl 按插件代码逐字段打真实域名，**均 201**；
  三个 JS 文件用 JavaScriptCore 验证可解析（缺浏览器 API 报的是 ReferenceError 而非 SyntaxError）。
- **未验证**：插件没在真实浏览器里加载过（本机没有可自动化驱动的浏览器环境），
  实际右键菜单、图片跨域抓取、通知提示需要用户加载后确认。
- 顺带修正一次我自己的操作错误：清理测试数据时按 id 删却用了"按筛选删"的端点
  （应为 `DELETE /api/v1/messages` + `{ids:[...]}`），报错信息里写清了原因。
## 2026-09-17（Evernote 导入器）

- **需求**（用户）："我怎么导出 Evernote 的笔记？放进 MH 里。"
- **结论**：用 `.enex`（Evernote 导出格式），配 `scripts/import-evernote.py`。
  不用 HTML 导出（散成一堆 .html + _files，解析更脆），更不可能手工粘贴（几千条笔记 + 附件）。
- **映射关系**：正文→`content`（ENML→纯文本，复选框变 `[ ]`、图片位置变 `[附件: 文件名]`）；
  标签/笔记本→`metadata.tags/notebook`；来源链接→`metadata.url`；时间用笔记**创建时间**；
  附件→blob **并自动走现有提取流水线**（图片 OCR、PDF 抽文本）。
  最后一条是最大价值：Evernote 里的扫描件/白板照片导入后变成**可搜索文本**。
- **实现要点**：
  - 用 `iterparse` **流式解析**（导出文件可能几百 MB，目标机器只有 960MB 内存）。
  - **按笔记 GUID 去重**（存 `metadata.evernote_guid`）→ 重复导入不产生重复、中途打断可续跑、可分批导。
  - 走与 API/网页端**同一个** `message_ingest.create_message()`，限额与白名单没有被批量路径绕过。
  - 复用 `mail_collector.html_to_text`（同一套 HTML→文本规则，不另写一份）。
  - **踩到并修正一个 ENEX 细节**：`<resource>` 里**没有 hash 字段**，正文 `<en-media hash="...">`
    引用的是资源数据的 **MD5**，必须自己算才能把附件名对回正文（否则正文里只能显示 `[附件]`）。
  - **抓到并修正一个设计错配**：最初大附件会让**整条笔记失败**（因为上传接口里 413 是硬错误）。
    但批量导入时这条不成立——谁也没法压缩那个文件，而笔记正文是有价值的。
    改为逐资源用同一个 `blob_store.validate_upload` 校验、失败记进 `attachments_skipped` 并保留正文。
- **验证**：pytest **118 例全过**（新增 10 条：时间解析、ENML→文本、MD5↔附件名映射、
  超限/不支持类型记为未存储、metadata 与附件落库、按 GUID 跳过、空笔记跳过、
  只有被跳过附件的笔记仍保留正文、共享入库不变式）。
  另用真实形状的 `.enex`（含 CDATA 里的 ENML、base64 附件、1.17MB 超大附件、空笔记）
  在临时库上跑通"演练 → 写入 → 重复导入"三遍，落库结果逐条核对无误。
- 文档：`docs/evernote-import.md`（导出步骤、scp、演练/写入、可重入与安全说明、常见问题）。
## 2026-09-20（Evernote 导入：字段审计 + 三个真 bug）

- **背景**：用户把 342MB 真实导出（`evnote_20260920_backup.enex`，338 条笔记 / 572 个附件）
  传上来准备导入。导入前先做了**逐字段审计**，结果发现 8 个字段原本会静默丢掉。
- **审计方法**：写 `scripts/profile-enex.py` 流式统计（17 秒扫完 326MB，**不把内容喂给 LLM**），
  再枚举导出里出现的**每一个标签**及其出现次数，逐个对到我们的模型上。
- **Evernote 导出格式的实际情况（实测，非照文档猜）**：
  - **新版导出不带 GUID**（`note-attributes` 只有 author/source 等），所以去重键的兜底路径是常态；
  - 标题重复极多：26 条「无标题笔记」、10 条「未命名 - 名片」、5 条「王艳 - 名片」、4 条「婚礼」——
    **只按标题哈希会静默丢掉约 47 条**。最终键 = `GUID（有则用）`，否则 `sha1(标题 + 创建时间)`：
    稳定（不含正文 → 改了内容重导仍是 no-op）、且实测 338 条两两唯一。
  - 那份导出里**没有** `notebook`、没有 `guid`、没有独立 description 字段（正文即描述）。
- **补齐的 8 个字段**：`author`、`source`、`source-application`、`content-class`、`subject-date`
  收进 `metadata.evernote` 命名空间（通用概念 `title`/`tags`/`url` 保持扁平）；
  资源的 `width`/`height` 与 `source-url` 记到附件记录上（附件详情页现在会显示"1080 × 2376 像素"
  与原始出处）。已加测试 `test_every_evernote_field_finds_a_home` 锁死这个映射。
- **导入专用附件上限**：`IMPORT_MAX_ATTACHMENT_BYTES`（默认 25MB，`--max-attachment-bytes` 可改）。
  与客户端的 1MB 上限**故意不同**：那 1MB 是为手机/网页上传定的（移动流量、1 核、图片可压缩），
  本地导入档案时这些约束都不成立——实测这份档案里最重要的文件恰好超 1MB（护照、签证函、竞业协议、
  户口本、13MB 施工图，共 50 个 / 163MB）。
- **抓到并修掉三个"看起来成功、实际丢数据"的 bug**（都是验证抓的，不是读代码读出来的）：
  1. **演练其实在写库写盘**：`main()` 没把 `apply_changes=args.apply` 传下去（默认 True），
     且重构时把"演练要 rollback"改成了无条件 `commit` → 本地多 336 条消息 + 77MB 孤儿文件。
     靠"演练后核对没多东西"这一步发现；已加 `test_dry_run_writes_nothing`。
  2. **导入上限形同虚设**：导入器只在自己这层比了大小，而 `validate_upload()` 内部仍按客户端
     1MB 判定 → **50 个护照/签证/合同扫描件全被当成"超限"跳过**。靠追问"572 个附件为什么只入库
     516 个"发现。修法：`validate_upload(max_bytes=...)` 让上限一路传到真正判断的那一层，
     并加 `test_import_cap_actually_reaches_the_validator`。
  3. **`attachments_skipped` 会静默消失**：重构时把它移进了 `if rejected:` 分支，于是超限附件
     唯一的痕迹没了。单元测试抓到。
  另修：把尺寸标注写在了 `create_message` **之前**，而它内部会重新生成附件记录并覆盖 metadata，
  标注全被丢掉（还白读一遍字节）——改为建完消息再标注**真正入库的那组**。
- **验证**：pytest **125 例全过**（新增 Evernote 相关 17 条）。线上演练：**336 条将导入、
  564 个附件、0 失败、0 碰撞**，且演练零副作用（消息数不变、blob 不变）。
  字段覆盖实测：author 217 / source 291 / source-application 60 / content-class 19 /
  subject-date 3 / 笔记级 source-url 21；附件尺寸 483 个、资源出处 564 个。
- **不存的**：`application-data`（35 个，应用私有数据，体积不可控且无用途）；
  类型不在白名单的 8 个文件（APK 22MB、2 个 xlsx、2 个 docx、2 个 m4a、1 个许可证）——
  记进 `attachments_skipped`，笔记正文照常保留。

