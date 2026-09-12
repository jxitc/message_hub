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
     "content": "Subject: ...\nFrom: ...\nTo: ...\nDate: ...\n\n正文纯文本",
     "timestamp": "2026-08-31T00:30:00Z",
     "metadata": {
       "mailbox": "<label>",
       "message_id": "<Message-ID>",
       "subject": "...",
       "to": "Xiao J <jxitc@hotmail.com>",
       "recipients": ["jxitc@hotmail.com"],
       "delivered_to": "jiangxjx@gmail.com"
     }
   }
   ```

5. 单封失败不中断：不记去重、不标已读，下一轮自动重试；成功后才标 `\Seen` 并写入去重状态
   （`--since-days` 模式下**从不**标已读，见下文「增量模式」）。

## 收件人（To）：一封邮件到底寄给了哪个信箱

托管多个信箱、或把别的信箱转发进 Gmail 时，"这封信是寄给谁的"是最常用的筛选维度。
实测（Gmail IMAP，14 封转发邮件）的规律：

| 头 | 内容 | 可用性 |
|----|------|--------|
| `Delivered-To` | 恒为 Gmail 账号本身（如 `jiangxjx@gmail.com`） | ❌ Gmail 会重写，区分不出别名 |
| **`To`** | **原始收件地址**（如 `jxitc@hotmail.com`） | ✅ **主要依据** |
| `Cc` | 抄送地址（同样保留原样） | ✅ 一并收录 |
| `X-Original-To` / `Envelope-To` | 投递链上的信封收件人（不是所有服务商都加） | ✅ 有则优先参考 |
| `Return-Path` | 退信地址里常含原信箱（`bounces+...-jxitc=hotmail.com@...`） | ⚠️ 仅作交叉验证 |

因此收集器保存：

- `metadata.recipients`：`To` + `Cc` 里所有地址（**小写去重**，保留首次出现的写法）——
  这是筛选与删除实际用的字段；
- `metadata.to` / `metadata.cc` / `metadata.delivered_to` / `metadata.original_to`：原始头，留档用；
- `content` 头部多一行 `To: ...`，让 CLI / 下游（InfoAgent）不用解 metadata 就能看到收件人。

### 回填历史邮件

早期导入的邮件只存了 Subject/From/Date。补收件人（**只读 IMAP**，用 `BODY.PEEK` 取头，
不会把邮件标成已读；幂等，已有 `recipients` 的行会跳过）：

```bash
cd /opt/message_hub
./venv/bin/python scripts/backfill-mail-recipients.py            # 演练，列出会改哪些行
./venv/bin/python scripts/backfill-mail-recipients.py --apply    # 写入
```

按 `Message-ID` 回原信箱取头，所以要求这些邮件仍在邮箱里（没被删/归档）。

## 配置

配置有三个来源，**优先级从高到低**：

| 顺序 | 来源 | 说明 |
|------|------|------|
| 1 | `instance/mail_accounts.json` | **网页版 Settings → Mail (IMAP) 写入**（`chmod 600`，不进 git）。不用 SSH 就能加/改信箱，密码也不经过任何第三方 |
| 2 | `MAIL_ACCOUNTS` 环境变量 | JSON 数组（见下） |
| 3 | `MAIL_0_*`、`MAIL_1_*` … | 逐个环境变量 |

同一个文件里还可以带 `since_days`，即网页上填的"只收最近 N 天"：

```json
{"accounts": [{"host": "imap.gmail.com", "user": "you@gmail.com", "password": "...",
               "label": "main"}],
 "since_days": 1}
```

命令行/环境变量方式见下。

### 多邮箱配置（环境变量方式）

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
| `MH_API_KEY` | — | MH 的 API key，作为 `X-API-Key` 头发送（没配 key 的服务器可留空） |
| `MAIL_STATE_DIR` | `~/.message_hub` | 去重状态文件 `processed_mails.json` 的存放目录 |
| `MAIL_CONFIG_FILE` | `<项目>/instance/mail_accounts.json` | 网页版写入的账号文件路径 |
| `MAIL_COLLECTOR_INTERVAL` | `3600` | 随 MH 服务器自动启动时的轮询间隔（秒） |
| `MAIL_SINCE_DAYS` | — | 等价于 `--since-days`，可给常驻线程设默认增量窗口 |

### 增量模式（`--since-days N`，推荐）

默认的 UNSEEN 模式有个副作用：**读完会把你的邮件标成已读**。想完全不打扰邮箱时用
增量模式，它按 `SINCE` 日期取信、按 `Message-ID` 去重、**永不修改 `\Seen`**：

```bash
python mail_collector.py --once --since-days 1     # 最近 1 天
```

服务器上常驻的收集线程走的也是这个模式，窗口取网页 Settings 里填的"最近 N 天"
（默认 1 天）。两种模式的区别：

| | UNSEEN 模式（默认） | 增量模式（`--since-days`） |
|---|---|---|
| 取信条件 | `SEARCH UNSEEN` | `SEARCH SINCE <日期>` |
| 会不会标已读 | 成功导入后标 `\Seen` | **从不** |
| 适合 | 专用收集邮箱 | 自己的日常邮箱 |

### 去重

已成功导入的邮件按 `Message-ID`（没有则用 UID）记录在
`~/.message_hub/processed_mails.json`（按 `label` 分桶）。之后轮询遇到同样邮件直接跳过；
状态文件自动保留最近 90 天、每邮箱最多 10000 条。UNSEEN 模式下只有 POST 成功后才标 `\Seen`，
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
cd /Users/xiao/mypro/message_hub
source venv/bin/activate

# 跑一轮就退出（给 cron 用）
python mail_collector.py --once

# 常驻：每 300 秒一轮
python mail_collector.py --watch 300

# 增量模式：最近 1 天，且永不标已读（推荐给日常邮箱）
python mail_collector.py --once --since-days 1

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
* * * * * cd /Users/xiao/mypro/message_hub && venv/bin/python mail_collector.py --once >> /var/log/mail_collector.log 2>&1
```

注意：`mail_collector.py` 只读环境变量（不会自动加载 `.env`），而 cron 默认不继承
shell 环境。两种做法：

1. 把 `MAIL_ACCOUNTS`、`MH_URL` 等直接写进 crontab 顶部：
   ```cron
   MAIL_ACCOUNTS='[{"host":"imap.gmail.com",...}]'
   MH_URL=http://127.0.0.1:5001
   * * * * * cd /Users/xiao/mypro/message_hub && venv/bin/python mail_collector.py --once >> /var/log/mail_collector.log 2>&1
   ```
2. 或写一个小启动脚本 `run_mail_collector.sh`，先加载 `.env` 再执行：
   ```bash
   #!/usr/bin/env bash
   set -a; source /Users/xiao/mypro/message_hub/.env; set +a
   /Users/xiao/mypro/message_hub/venv/bin/python \
     /Users/xiao/mypro/message_hub/mail_collector.py --once
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
cd /Users/xiao/mypro/message_hub
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
- **想只收集特定发件人/主题**：当前版本是全量（UNSEEN 或按天数）；可在
  `fetch_unseen_uids` / `fetch_recent_uids` 的 `SEARCH` 条件里加 `FROM "xxx"` /
  `SUBJECT "yyy"` 扩展。
- **历史邮件没有收件人（To）**：跑一次 `scripts/backfill-mail-recipients.py`（见上文
  「回填历史邮件」），它按 `Message-ID` 回邮箱补 `To`。

## 后续方向（未实现）

- OAuth2（Gmail API / XOAUTH2）替代静态 App Password
- 附件转存/摘要（当前严格"只有文本"）
- 按文件夹（非 INBOX）或按标签收集
- 收集器自身的 Prometheus 指标 / 失败告警
