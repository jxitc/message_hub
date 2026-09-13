# 附件与原件（Attachments）

> 原则：**元数据进库，字节出库。** `messages` 存"这个附件是什么"，文件存在 blob 目录里，
> 两边靠一个**由内容派生的 key** 关联。这样 DB 备份始终很小，而"以后换对象存储"是改配置，
> 不是数据迁移。

## 为什么字节不进 DB

SQLite 官方对 in-DB vs 外部文件做过对比（[Internal Versus External BLOBs](http://www2.sqlite.org/draft/matrix/intern-v-extern-blob.html)），
量级大概是 **100KB 以内放库内更快**（省 syscall），更大则外部文件反超。我们的上限是 1MB，
正好落在"该外置"的一侧——但**性能不是主要理由**，主要理由是：

- 字节进库后，DB 备份/rsync 的体积与耗时随文件数线性增长；
- 以后迁到 R2 会变成"从 DB 里导出再上传"的数据迁移，而不是同步文件；
- 换存储的动作应该是**改配置**。

代价也说清楚：外置就**失去事务一致性**，孤儿文件和 GC 得自己管（见下）。

## key 的规则

```
instance/blobs/<sha256[0:2]>/<sha256[2:4]>/<sha256><ext>
例：instance/blobs/7f/0e/7f0ec16f…81a4.png
```

- **由内容派生**：`sha256` 是文件字节的哈希；扩展名由**嗅探到的 magic bytes** 决定，
  不采信客户端声明的 `Content-Type`，也不用上传的原始文件名。
- 因此**路径里不可能出现用户输入**——路径穿越这个问题从结构上就不存在；
  重复上传同一文件天然幂等（`deduplicated: true`）。
- key 稳定 → 内容不可变 → HTTP 缓存可以永久（`immutable`）。这也是"独立 blob 域"有价值的原因。
- **不存本地路径**。存了就把数据绑死在本地，是换存储的头号障碍。

`key` 与物理位置分离，所以同一串 key 在本地磁盘和 R2 上都成立——这是迁移只改配置的前提。

## 限制与白名单

| 项 | 值 | 说明 |
|---|---|---|
| 单文件上限 | **1 MB**（`MAX_ATTACHMENT_BYTES`） | 图片超限由**手机端压缩**；PDF 无法压缩，直接拒绝 |
| 单请求文件数 | 8（`MAX_FILES_PER_REQUEST`） | 防止"100 个 1MB 文件"在逐个校验前先把内存吃掉 |
| 请求体上限 | 16 MB（`MAX_REQUEST_BYTES`，与 nginx 的 `client_max_body_size` 一致） | 代理与应用必须给出同样的答案，否则会出现"nginx 放行、应用 500"这类怪事 |
| 存储软配额 | 8 GB（`BLOB_SOFT_QUOTA_BYTES`） | 到线**拒绝新上传**（507），而不是把磁盘写满——磁盘满会连带 SQLite 写入失败 |

允许的类型（按内容嗅探）：`image/png`、`image/jpeg`、`image/gif`、`image/webp`、
`application/pdf`、`text/plain`（合法 UTF-8 且无 NUL 字节）。

**明确拒绝** `.svg` / `.html` / `.js` / `.xml`：它们本质是文本，但会在浏览器里执行脚本。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/messages` | `multipart/form-data`：常规字段 + 可重复的 `attachments` 文件部分；`metadata` 是 **JSON 字符串**（multipart 没有嵌套值）。带附件时 `content` 可以为空 |
| `GET` | `/api/v1/attachments/limits` | `{max_bytes, allowed[]}`——客户端**先问再压**，别靠 413 试错 |
| `GET` | `/api/v1/blobs/<key>` | 稳定下载入口。`X-API-Key` 或 `?token=`（HMAC 签名 + 过期）二选一 |
| `GET` | `/api/v1/blobs` | 存储用量、配额、提取引擎是否可用 |

**为什么要签名链接**：浏览器 `<img>`/`<a>` 带不了 header（当年 APK 下载踩过同一个坑）。
但附件不像 APK 那样可以公开，所以链接自带 HMAC token 与过期时间，默认 24h。

**为什么客户端不该直连对象存储的签名 URL**：手机里装着已发布的 APK，一旦让它直连 R2，
以后换桶/换厂商/改签名策略都要发新版。让**服务端 302**，客户端毫不知情。

## 写入顺序与 GC

- **先写字节，后提交消息行**：崩在中间只会留下"孤儿文件"（可扫可删），
  不会留下"指向不存在文件的引用"（用户点开 404）。
- **删除消息不立即删字节**：同一份内容可能被多条消息引用，即时引用计数很容易错。
  GC 用 mark-and-sweep，显式跑：

```python
from blob_store import BlobStore
store = BlobStore()
removed = store.collect_garbage(referenced_keys, dry_run=False)
```

## 文本提取（OCR / PDF）

提取**异步**进行（`extraction.py`，后台线程，30s 轮询），因为 1 核机器上一张扫描件
可能跑几十秒，而 hub 是 `-w 1 --threads 2`——同步做会把整个服务堵住。

| 类型 | 引擎 | 说明 |
|---|---|---|
| PDF | `pdftotext`（poppler） | 有文本层直接取 |
| 扫描版 PDF | `pdftoppm` + `tesseract` | 无文本层时自动回退，最多 OCR 10 页 |

**"这算有文本层还是扫描件"怎么判**（`PDF_TEXT_FLOOR_CHARS`）：**只要文本层超过很小的下限就用它**，
低于下限才当作扫描件走 OCR。没有哪个阈值能真正区分"很短的正文"和"扫描件上的水印"，所以这条
有意偏向**少做无用功**：

- 阈值定高了会白干活——实测一个 18 字文本层的单页 PDF 落在"20 字"线下，被**白白渲染+OCR**
  （1 核机器上几十秒），而 `pdftotext` 早就给出答案了；
- 它本要防的情况（扫描件的文本层只有页眉）很少见，而且在输出里**看得见**
  （`extraction.chars` 与 `chars_per_page` 都记录），更可以随时重跑纠正——
  原件一直留着，`scripts/reextract.py --ocr` 就是那个手动杠杆。
| 图片 | `tesseract -l eng+chi_sim` | 中英文都装 |
| 纯文本 | 直接解码 | 依次尝试 utf-8 / gb18030 / latin-1 |

**提取出来的文本放在哪（只有一份，不会重复）**：

- `content` 为空（例如只上传一张图）→ 文本写进 **`content`**，普通消费者不用知道附件的存在；
- `content` 已有文本（邮件正文、或客户端自己提取的）→ 文本留在
  **`metadata.attachments[i].extraction.text`**。追加进 `content` 会混淆"发件人写的"与
  "OCR 猜的"，而且重跑会追加两遍。

两种情况下文本都在数据库里，所以下游 AI 读的是**和其他消息同一种数据**，永远不用去开文件。

**可重跑**：原件不可变、提取是确定性的，所以换了更好的引擎或补了语言包就重建一遍：

```bash
./venv/bin/python scripts/reextract.py --status      # 现状
./venv/bin/python scripts/reextract.py --pending     # 立刻清空队列
./venv/bin/python scripts/reextract.py --all         # 全部重跑
./venv/bin/python scripts/reextract.py --engines     # 工具链在不在
./venv/bin/python scripts/reextract.py --ocr --all   # 强制走 OCR（文本层太薄、疑似扫描件）
```

## 邮件侧

收集器在导入时：

1. 向 `/api/v1/attachments/limits` **问一次**限额（不在本地再抄一份白名单，避免漂移）；
2. 允许且不超限的附件以 multipart 上传；其余的记进
   `metadata.attachments_skipped = [{name, size, mime, reason}]`。
   ——"这封信有附件、只是没存"和"这封信没附件"是两回事，后者无法事后分辨。

历史邮件（功能上线前导入的、以及落在增量窗口之外的）用：

```bash
./venv/bin/python scripts/backfill-mail-attachments.py            # 演练
./venv/bin/python scripts/backfill-mail-attachments.py --apply
```

只导入带附件的邮件（历史发票归档，避免把几千封闲聊灌进时间线）：

```bash
./venv/bin/python mail_collector.py --once --since-days 400 --attachments-only
```

## 迁移到对象存储（R2）

```
1. 用同样的 key 同步：rclone / aws s3 sync instance/blobs/ r2:bucket/blobs/
2. 校验对象数与 sha256 抽样
3. 改配置指向 R2 实现（读取走抽象层，key 不变）
4. 稳定后删本地
```

**不要现在为迁移加任何字段**。只有"不能停机、不能一次性搬"才需要每条记录带 backend 列
做增量搬迁；我们这个规模一次性搬完就是几分钟。要保住的只有两件事：**key 稳定** +
**访问都走抽象层**。

## 安全

- 非图片一律 `Content-Disposition: attachment`，配合 `X-Content-Type-Options: nosniff`；
  图片才 `inline`（App 要显示截图）。
- **独立源已启用**：附件从 **`https://mhblob.jxitc.com/<key>`** 提供，与 Web UI
  （`mh.jxitc.com`）不同源。API 返回的 `url` 会带上这个绝对地址（由 `BLOB_PUBLIC_BASE`
  控制；不配置则退回同源相对路径 `/api/v1/blobs/<key>`，功能不变）。
  nginx 把 `/<key>` 映射到 `/api/v1/blobs/<key>`，鉴权仍由应用负责。

  ⚠️ **主机名必须是一级子域**——这是踩过的坑：**Cloudflare 免费版 Universal SSL 只覆盖
  apex 与一级子域**（`jxitc.com`、`*.jxitc.com`）。最初用的 `blob.mh.jxitc.com` 是**二级**
  子域，CF 边缘没有对应证书，TLS 直接在握手阶段失败（`sslv3 alert handshake failure`），
  绕过它需要付费的 Advanced Certificate Manager。实测对比：

  ```
  mh.jxitc.com      → subject: CN=jxitc.com，verify ok      ← Universal SSL 覆盖
  blob.mh.jxitc.com → handshake failure                     ← 二级，无证书
  mhblob.jxitc.com  → subject: CN=jxitc.com，verify ok      ← 一级，覆盖
  ```

  签发源站证书时还有个连带问题：**CF 开着代理会干扰 ACME HTTP-01 校验**
  （边缘可能先 301 到 https，而这时源站还没有该域名的证书，校验必然失败）。
  做法是**临时把该 DNS 记录切为 DNS-only（灰云）→ 跑 certbot → 再切回代理**。
- 密钥、签名 token 都不入库；token 用 `SECRET_KEY` 做 HMAC，自带过期。

## 附件详情页

`/messages/<id>/attachments/<index>`：一个**附件的视图**，不是"图片的页面"。

页面结构刻意分成两半，这样加文件类型不需要新页面：

| 部分 | 是否通用 | 说明 |
|---|---|---|
| 预览块 | **按 kind 分支** | 图片 `<img>`；文本 `<pre>`；音频 `<audio>`；PDF 与未知类型给下载按钮 |
| 处理信息 | 通用 | 状态徽标、引擎、提取字符（含页数与字/页）、文本落位、错误与提示 |
| 操作 | 通用 | 下载原件（签名）、**重新提取**、**强制 OCR 重跑**（后台排队，不阻塞请求） |
| 原件信息 | 通用 | 大小（人类可读）、mime、kind、来源与 origin、sha256 |

两个刻意的决定：

- **PDF 不做内嵌预览**。我们给非图片一律下发 `Content-Disposition: attachment`
  （同源 XSS 防护的一环），内嵌反而会触发下载；而 PDF 的有用部分本来就是抽取出来的文字，
  已经摆在右侧。想要内嵌预览可以加，但要想清楚这道防护怎么保留。
- **"有附件但没存下来"也会显示**。邮件里被超限/类型拒掉的文件在消息详情页单列一节，
  带原因——"有附件没存"和"没附件"必须能分辨。

另外顺手修了一个让 metadata 没法读的问题：Flask 的 JSON 默认 `ensure_ascii=True`，
中文全变成 `\uXXXX`（网页版的 metadata 面板和 **API 响应**都受影响）。
`app.py` 里 `app.json.ensure_ascii = False` 一行解决，输出真正的 UTF-8。

## 手机端：走现有接口就够，不用加接口

上传 → 轮询 → 下拉，用现有契约即可：

```
1) POST /api/v1/messages (multipart)   → 拿到 id 和 attachments[].key
2) GET  /api/v1/messages/<id>          → 读 metadata.attachments[].extraction.status
                                          pending → done | failed | empty
3) GET  {serverUrl}/api/v1/blobs/<key>  ← 带 X-API-Key，拉原件
```

⚠️ **两个必须避开的坑**（客户端实现时最容易在这里出问题）：

1. **别用 API 返回的 `url` 字段去下载**。那个字段现在指向独立源 `mhblob.jxitc.com`
   （浏览器需要它，因为 `<img>` 带不了 header）。App 的 OkHttp 鉴权拦截器通常只对 API 域名加
   `X-API-Key`，跨主机请求会直接 401。**用 `key` + 自己的 serverUrl**：
   这也正是当初把下载设计成"稳定入口"的目的——`BLOB_PUBLIC_BASE` 怎么改都不影响已发布的 APK。
2. **提取文本可能在两个地方之一**：`content`（填进了正文）或
   `metadata.attachments[i].extraction.text`（正文本来就有内容时）。两处都要看，否则会出现
   "手机上 OCR 文字不见了"。

本地缓存建议：**附件字节不必进 Room**（按需拉取 + 图片库自带磁盘缓存即可），
但**提取文本值得同步进 Room**——离线也能看，详情页还能立刻显示。

## 验收脚本（验证**已部署**的系统）

单元测试跑的是内存里的 Flask 客户端，验不了 nginx 的体积上限、Cloudflare 的证书与代理、
独立源路由、gunicorn 超时、以及服务器上到底有没有 OCR 工具链。这些只有打真实域名才看得出来：

```bash
./scripts/acceptance-attachments.sh          # 23 项，自带素材与清理
```

素材是**真的**：PDF 是脚本里用纯 Python 手搓的（带正确 xref 的文本层），图片用 PIL 画字
（没装 PIL 就退化成白图并降低断言强度）。用 1x1 空白图是证明不了 OCR 的——那正是上一版脚本
犯的错。

## 验证记录（2026-09-13）

- 单测 94 例（`tests/test_attachments.py` 覆盖嗅探/白名单/去重/GC/上传校验/下载鉴权/
  签名与过期/提取文本落位/独立源 URL 切换），全过。
- 线上端到端（真实文件）：
  - 42KB PNG → `tesseract` OCR → `content` = `MH OCR TEST 12345\nsecond line here`；
  - 13KB PDF → `pdftotext` → 同样文本；
  - 下载字节与原件 `cmp` 完全一致；无密钥 401；签名链接匿名可下；非图片强制 attachment；
  - 网页版列表正确显示附件、大小与"34 字"提取状态。
- 收集器 multipart 路径线上验证：`report_message` → 201、附件落库、后台提取完成；
  邮件正文非空时 PDF 文本正确落在 `metadata…extraction.text`。
- 修掉两个真 bug：① 超大文件返回 415 而非 413；② `reset_status` 浅拷贝导致 SQLAlchemy
  判定"未变化"而不发 UPDATE，重跑提取静默无效（已补"必须用新 session 回查"的回归测试）。
