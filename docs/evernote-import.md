# 导入 Evernote 笔记

把 Evernote 导出成 `.enex`，用 `scripts/import-evernote.py` 灌进 hub。
**不要**用 HTML 导出（散成一堆 `.html` + `_files`，解析更脆），也不要手工粘贴（笔记库是几千条）。

## 1. 从 Evernote 导出

Evernote 桌面版（Mac）：

1. 左侧选中**一个笔记本**（或笔记列表里选中若干条）
2. **文件 → 导出笔记…**
3. 格式选 **`.enex`**（Evernote 导出格式），保存

建议**按笔记本分开导**：一次导整个账号容易卡住，而且分批导入本身是安全的（见下）。
若导出工具限制单次条数，就多导几次。

`.enex` 里含正文、标签、创建/更新时间、来源链接、附件（base64）——正是我们需要的全部。

## 2. 传到服务器

导入脚本要直接写数据库与 blob，所以在服务器上跑：

```bash
scp -i ~/mypro/.mh_deploy/id_ed25519 notes.enex root@188.166.172.192:/tmp/
```

## 3. 先演练，再写入

```bash
ssh -i ~/mypro/.mh_deploy/id_ed25519 root@188.166.172.192
cd /opt/message_hub

# 演练：只报告会导入什么，不写任何东西
./venv/bin/python scripts/import-evernote.py --file /tmp/notes.enex

# 确认无误后写入
./venv/bin/python scripts/import-evernote.py --file /tmp/notes.enex --apply

# 先看前 20 条的效果（大导出推荐先这样试）
./venv/bin/python scripts/import-evernote.py --file /tmp/notes.enex --limit 20 --apply
```

## 4. 导进去之后

| 会变成什么 | 说明 |
|---|---|
| `content` | 笔记正文（ENML→纯文本，复选框变成 `[ ]`，图片位置变成 `[附件: 文件名]`） |
| `metadata.title` / `tags` / `notebook` | 标题、标签、笔记本（标签以后能用来筛选） |
| `metadata.url` | 笔记里的来源链接（网页剪藏尤其有用） |
| `timestamp` | 用笔记的**创建时间**，不是导入时间 —— 时间线不会乱 |
| 附件 | 存成 blob，**并自动走现有提取流水线**：图片 OCR、PDF 抽文本 |

最后一条是这个方案最有价值的地方：**你 Evernote 里的扫描件、白板照片、截图，导入后会变成可搜索的文本**，
不需要你在 Evernote 里手动整理。

网页版列表按 `device=evernote-import` 就能只看导入的笔记：

```
https://mh.jxitc.com/messages?device=evernote-import
```

## 字段映射（Evernote → Message Hub）

导入前逐字段核对过**真实导出**里出现的每一个标签（不是照文档猜的）：

| Evernote | 出现次数 | 落到我们这边 |
|---|---|---|
| `title` | 338 | `metadata.title` |
| `content`（ENML/XHTML） | 338 | `content`（ENML→纯文本） |
| `created` | 338 | `timestamp`（**列**，用创建时间而非导入时间） |
| `updated` | 338 | `metadata.evernote.updated` |
| `tag` ×N | 55 | `metadata.tags[]` |
| `resource` ×N | 572 | `metadata.attachments[]` + blob |
| `note-attributes/source-url` | 21 | `metadata.url` |
| `note-attributes/author` | 217 | `metadata.evernote.author` |
| `note-attributes/source` | 291 | `metadata.evernote.source`（哪个客户端，如 `desktop.mac`） |
| `note-attributes/source-application` | 60 | `metadata.evernote.source_application` |
| `note-attributes/content-class` | 19 | `metadata.evernote.content_class` |
| `note-attributes/subject-date` | 3 | `metadata.evernote.subject_date` |
| `resource/mime` | 572 | `attachment.mime` |
| `resource-attributes/file-name` | 572 | `attachment.name` |
| `resource/width`+`height` | 483 | `attachment.width` / `height`（详情页会显示"1080 × 2376 像素"） |
| `resource-attributes/source-url` | 572 | `attachment.source_url`（这张图的原始出处） |
| `resource-attributes/application-data` | 35 | ⚠️ **不存**（应用私有数据，如 Skitch 标注；体积不可控且对我们无用） |
| `guid` | **0** | 这份导出没有 → 用 `sha1(标题+创建时间)` 当键 |
| `notebook` | **0** | 这份导出没有；若将来有则进 `metadata.notebook` |

我们自己有、Evernote 没有的：`source_device_id`（固定 `evernote-import`）、`type`（`NOTE`）、
`sender`（`Evernote`）、`received_at`（导入时间），以及附件的内容寻址 key/sha256/提取状态。

**"description"**：Evernote 的笔记没有独立描述字段——正文就是描述。这份导出里也确认没有。

**通用概念（`title`/`tags`/`url`）保持扁平**，Evernote 专属字段集中放进 `metadata.evernote` 命名空间。
这符合"渠道不进 schema，渠道细节进 JSON"那条原则。

## 附件与提取：与其他渠道完全同一套

附件记录的形状对**所有渠道都一样**，与手机上传、邮件附件、网页添加没有任何区别：

```
{ key, sha256, kind, mime, size, name, extraction: {status, engine, chars, ...} }
+ Evernote 额外给的：width / height / source_url（可选字段，别的渠道将来也能用）
```

提取流水线是**渠道无关**的：`extraction.py` 只看 `kind`/`mime` 决定用 `pdftotext` 还是
`tesseract`，结果写进同一处（`content`，或正文非空时写进
`metadata.attachments[i].extraction.text`）。所以：

- 导入后**后台自动 OCR/抽取**，和邮件、手机附件一样，不需要额外操作；
- 附件详情页（`/messages/<id>/attachments/<n>`）对它同样可用：原件、处理信息、提取文本、重跑按钮；
- `scripts/reextract.py --all/--ocr` 同样适用；
- 反过来说，**这批档案是这套流水线目前最大的一批真实输入**（483 张图 + 81 个 PDF）。

## 安全与可重入

- **去重键**存在 `metadata.evernote_key`：有 Evernote GUID 就用 GUID，**没有则用
  `sha1(标题 + 创建时间)`**。新版 Evernote 导出（v11 实测）**不带 GUID**，所以后者是常态。
  它**不含正文**，所以你在 Evernote 里改过内容再重导仍然是 no-op——这正是"一次性搬迁"需要的性质。
  为什么不用"只哈希标题"：真实档案里标题重复很多（这份有 26 条「无标题笔记」、10 条「未命名 - 名片」），
  只按标题会让它们互相覆盖、静默丢掉 25 条。创建时间能把它们分开。
  万一出现"标题与创建时间都相同"的两条，脚本会在报告里**显式列出碰撞**，不会伪装成正常的跳过。
- **导入的附件上限是 25MB，不是上传的 1MB**（`IMPORT_MAX_ATTACHMENT_BYTES`，可用
  `--max-attachment-bytes` 覆盖）。两个上限不同是**故意的**：1MB 是为手机/网页上传定的
  （移动流量、1 核机器、图片可压缩），而本地导入一份已有档案时这些约束都不成立——
  实测你这批档案里最有价值的文件恰恰超过 1MB（护照、签证函、竞业协议、户口本、13MB 施工图）。
- 超限或类型不支持的附件**不会让笔记失败**：记进 `metadata.attachments_skipped` 并说明原因，
  正文照常保留。想看哪些没存下来，打开那条消息的详情页就有清单。
- 走的是与手机端、网页版**同一个** `message_ingest`，所以限额与类型白名单没有被绕过。
- 空笔记（无正文也无附件）会跳过，不占时间线。

## 常见问题

| 现象 | 原因 |
|---|---|
| `找不到文件` | 路径错了；先 `ls -la /tmp/*.enex` |
| 大量 `未存储: xxx -> 超过 1024 KB 上限` | 该笔记的附件超限，笔记本身仍然导入了 |
| 想提高附件上限 | 服务器 `.env` 里设 `MAX_ATTACHMENT_BYTES`（例如 `5242880` 表示 5MB）后重启服务，**这是全局设置**，手机端与网页端的上限会一起变 |
| 导入很慢 | 附件要 base64 解码 + 存储，几千条笔记的资源是主要耗时；`--limit` 可以分批 |
