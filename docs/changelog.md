# Message Hub - 开发变更日志

> Message Hub 作为"纯信息收集层"改造记录：接收多设备/多来源消息，供 InfoAgent 等 subscriber 消费。

## 2026-08-31 ~ 09-02（架构拆分：MH 收集层 + InfoAgent AI 层）

### 1. 接入安卓端（android_client/）
- 从 info_agent 仓库**原样复制**安卓端项目到 `android_client/InfoAgent`（Kotlin + Jetpack Compose，监听短信/通知 → 本地 Room → 后台同步）。
- **网络层改造对接 MH**（`data/remote/` 下 3 个文件）：
  - `ApiModels.kt`：替换为 MH 模型（`MessageCreateRequest`：source_device_id/type/sender/content/timestamp/metadata；`MessageApiResponse`、`MessageListResponse` 对齐 MH 响应）
  - `InfoAgentApiService.kt`：端点改 `POST api/v1/messages`、`GET api/v1/messages`（原 memories）
  - `InfoAgentApiClient.kt`：本地 Memory → MH Message 映射（type：SMS→SMS、NOTIFICATION→PUSH_NOTIFICATION；sender 从 metadata 取；timestamp ISO8601）
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
