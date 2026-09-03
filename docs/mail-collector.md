# 邮箱收集（IMAP）— Mail Collector

`mail_collector.py` 是一个**通用 IMAP 邮箱收集器**：用标准 IMAP 协议（`imaplib`，Python 标准库）从任意支持 IMAP 的邮箱（Gmail / QQ 邮箱 / Outlook / 163 等）拉取未读邮件，解析成**纯文本**（不取附件），然后 POST 到 Message Hub 的 `POST /api/v1/messages`（`type=EMAIL`）。

Message Hub 只做纯收集层，InfoAgent 等下游从 MH 的 API 消费邮件。

## 工作原理

```
[Gmail/QQ/Outlook ...]  --IMAP 993/SSL-->  mail_collector.py  --HTTP POST-->  Message Hub
        (UNSEEN 搜索)        (解析纯文本)        /api/v1/messages (type=EMAIL)
```

每个邮箱一轮的处理流程：

1. `IMAP4_SSL` 连接 → `login` → `select INBOX`
2. `UID SEARCH (UNSEEN)` 找未读邮件
3. 逐封 `UID FETCH (RFC822)`，用 `email` 库解析：
   - `From` → `sender`（取纯地址，如 `you@gmail.com`）
   - `Subject` → RFC 2047 解码
   - `Date` → ISO8601（统一转 UTC，`Z` 结尾）
   - 正文递归提取：优先 `text/plain`，没有则用内置 HTML→文本转换器清理 `text/html`（去标签、去 `<script>/<style>`、保留换行）；附件一律忽略
4. 构造 MH 消息并 POST：

   ```json
   {
     "source_device_id": "mail-<label>",
     "type": "EMAIL",
     "sender": "发件人地址",
     "content": "Subject: ...\nFrom: ...\nDate: ...\n\n正文纯文本",
     "timestamp": "2026-08-31T00:30:00Z",
     "metadata": {"mailbox": "<label>", "message_id": "<Message-ID>", "subject": "..."}
   }
   ```

5. 单封失败不中断：不记去重、不标已读，下一轮自动重试；成功后才标 `\Seen` 并写入去重状态。

## 配置

配置全部走环境变量（复制 `.env.example` 为 `.env` 后按需修改）。

### 多邮箱配置（推荐方式）

`MAIL_ACCOUNTS` 是一个 JSON 数组，一个元素一个邮箱：

```bash
MAIL_ACCOUNTS='[
  {"host":"imap.gmail.com","user":"you@gmail.com","password":"APP_PASSWORD","port":993,"use_ssl":true,"folder":"INBOX","label":"gmail"},
  {"host":"imap.qq.com","user":"you@qq.com","password":"AUTHORIZATION_CODE","port":993,"use_ssl":true,"folder":"INBOX","label":"qq"},
  {"host":"imap-mail.outlook.com","user":"you@outlook.com","password":"APP_PASSWORD","port":993,"use_ssl":true,"folder":"INBOX","label":"outlook"}
]'
```

字段说明：

| 字段 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `host` | 是 | — | IMAP 服务器地址（见下表） |
| `user` | 是 | — | 邮箱账号 |
| `password` | 是 | — | 见下方各家"应用专用密码/授权码" |
| `port` | 否 | `993` | IMAP 端口（SSL 通常 993） |
| `use_ssl` | 否 | `true` | 用 `IMAP4_SSL`；公司内网无 SSL 时可设 `false`（端口 143） |
| `folder` | 否 | `INBOX` | 收件箱（一般不用改） |
| `label` | 否 | `user` | 邮箱别名：用作 `source_device_id=mail-<label>` 和 `metadata.mailbox` |

### 多邮箱配置（逐个环境变量方式）

不想写 JSON 时也可以用 `MAIL_0_*`、`MAIL_1_*` …：

```bash
MAIL_0_HOST=imap.gmail.com
MAIL_0_USER=you@gmail.com
MAIL_0_PASSWORD=APP_PASSWORD
MAIL_0_PORT=993
MAIL_0_USE_SSL=true
MAIL_0_FOLDER=INBOX
MAIL_0_LABEL=gmail
```

### 其他配置

| 变量 | 默认 | 说明 |
|------|------|------|
| `MH_URL` | `http://127.0.0.1:5001` | Message Hub 地址（收集器把邮件 POST 到这里） |
| `MAIL_STATE_DIR` | `~/.message_hub` | 去重状态文件 `processed_mails.json` 的存放目录 |
| `MAIL_COLLECTOR_INTERVAL` | `300` | 随 MH 服务器自动启动时的轮询间隔（秒） |

### 去重

已成功导入的邮件按 `Message-ID`（没有则用 UID）记录在
`~/.message_hub/processed_mails.json`（按 `label` 分桶）。之后轮询遇到同样邮件直接跳过；
状态文件自动保留最近 90 天、每邮箱最多 10000 条。由于只有 POST 成功后才标 `\Seen`，
MH 暂时不可用时邮件会留在未读里，下一轮自动补发。

## 各家邮箱准备（App Password / 授权码）

IMAP 登录**不能用邮箱登录密码**，需要各家专门生成的"应用专用密码"：

| 服务 | IMAP 服务器 | 端口 | 密码来源 |
|------|-------------|------|----------|
| **Gmail** | `imap.gmail.com` | 993 | 需开启两步验证 → Google 账号 → 安全性 → 应用专用密码（App Password，16 位） |
| **QQ 邮箱** | `imap.qq.com` | 993 | 设置 → 账户 → 开启 IMAP/SMTP → 生成"授权码"（16 位，用它当 password） |
| **Outlook / Office 365** | `imap-mail.outlook.com` | 993 | 开启两步验证后在安全设置里生成 App Password；或用企业管理员允许的 IMAP 凭据 |
| **163 邮箱** | `imap.163.com` | 993 | 设置 → 客户端授权密码（生成授权码） |

> 注意：Gmail 未开启两步验证时无法生成 App Password；QQ 邮箱的授权码≠登录密码。

## 运行方式

### 方式 A：独立脚本（推荐给 cron / 容器）

```bash
cd /Users/jxitc/mypro/message_hub
source venv/bin/activate

# 跑一轮就退出（给 cron 用）
python mail_collector.py --once

# 常驻：每 300 秒一轮
python mail_collector.py --watch 300

# 演练模式：只拉取+解析，不 POST、不标已读
python mail_collector.py --once --dry-run

# 指定状态目录 / 日志级别
python mail_collector.py --once --state-dir /var/lib/message_hub --log-level DEBUG
```

输出示例（每轮结束打印统计）：

```json
{
  "gmail": {"imported": 3, "failed": 0, "skipped": 2},
  "qq":    {"imported": 0, "failed": 1, "skipped": 0}
}
```

**挂 cron**（每分钟跑一轮，`--once`）：

```cron
* * * * * cd /Users/jxitc/mypro/message_hub && venv/bin/python mail_collector.py --once >> /var/log/mail_collector.log 2>&1
```

注意：`mail_collector.py` 只读环境变量（不会自动加载 `.env`），而 cron 默认不继承
shell 环境。两种做法：

1. 把 `MAIL_ACCOUNTS`、`MH_URL` 等直接写进 crontab 顶部：
   ```cron
   MAIL_ACCOUNTS='[{"host":"imap.gmail.com",...}]'
   MH_URL=http://127.0.0.1:5001
   * * * * * cd /Users/jxitc/mypro/message_hub && venv/bin/python mail_collector.py --once >> /var/log/mail_collector.log 2>&1
   ```
2. 或写一个小启动脚本 `run_mail_collector.sh`，先加载 `.env` 再执行：
   ```bash
   #!/usr/bin/env bash
   set -a; source /Users/jxitc/mypro/message_hub/.env; set +a
   /Users/jxitc/mypro/message_hub/venv/bin/python \
     /Users/jxitc/mypro/message_hub/mail_collector.py --once
   ```
   然后 cron 里 `* * * * * /path/to/run_mail_collector.sh`。

### 方式 B：随 MH 服务器自动启动（后台线程）

在 `.env` 里配好 `MAIL_ACCOUNTS`（并可选 `MAIL_COLLECTOR_INTERVAL=300`），
启动 MH 时 `create_app()` 会自动起一个 daemon 线程，每 300 秒收集一轮：

```bash
source venv/bin/activate
python app.py          # 日志里会出现 "Mail collector thread started (interval=300s)"
```

不想让服务器自带收集器（改用 cron）时，把 `MAIL_ACCOUNTS` 留空即可。

## 测试

```bash
cd /Users/jxitc/mypro/message_hub
source venv/bin/activate
python test_mail_collector.py
```

覆盖：RFC 822 解析（纯文本 / 纯 HTML / multipart/alternative 优先纯文本 / RFC 2047
编码标题与中文 / 时区转 UTC）、payload 构造、配置解析、以及用 mock IMAP 服务器验证
完整收集流程（导入、标已读、去重、失败不中断、dry-run 零副作用）。其中
`TestLiveMH` 在检测到 127.0.0.1:5001 的 MH 存活时会真发一封测试邮件做端到端验证。

## 常见问题

- **`LOGIN failed`**：密码不是登录密码——Gmail 用 App Password，QQ 用授权码。
- **`[AUTHENTICATIONFAILED]`（QQ）**：确认已开启 IMAP 服务并生成了授权码。
- **Gmail 报 `Web login required`**：账号安全设置里允许"不够安全的应用"或改用 App Password。
- **重复导入**：`processed_mails.json` 是按 `Message-ID` 去重的；换过邮箱服务商导致
  Message-ID 格式变化时，可删除 `~/.message_hub/processed_mails.json` 强制重放（会重复导入）。
- **想只收集特定发件人/主题**：当前版本是全量未读；可在 `fetch_unseen_uids` 的
  `SEARCH` 条件里加 `FROM "xxx"` / `SUBJECT "yyy"` 扩展。

## 后续方向（未实现）

- OAuth2（Gmail API / XOAUTH2）替代静态 App Password
- 附件转存/摘要（当前严格"只有文本"）
- UID 增量同步替代 UNSEEN（减少标记已读对用户的影响，可配 `MAIL_READ_ONLY=1`）
- 按文件夹（非 INBOX）或按标签收集
- 收集器自身的 Prometheus 指标 / 失败告警
