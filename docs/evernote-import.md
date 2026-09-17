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

## 安全与可重入

- **按笔记 GUID 去重**，存在 `metadata.evernote_guid`。同一份导出重复导入**不会产生重复**，
  中途打断再跑会接着补，所以放心分批导。
- **附件超 1MB 不会导致笔记失败**：附件记进 `metadata.attachments_skipped` 并说明原因，
  正文照常保留。想看哪些没存下来，打开那条消息的详情页就有清单。
  （导入路径的这条规则与上传接口**故意不同**：上传时 413 是硬错误，因为客户端能压缩重试；
  批量导入时谁也没法压缩那个文件，丢整条笔记才是真损失。）
- 走的是与手机端、网页版**同一个** `message_ingest`，所以限额与类型白名单没有被绕过。
- 空笔记（无正文也无附件）会跳过，不占时间线。

## 常见问题

| 现象 | 原因 |
|---|---|
| `找不到文件` | 路径错了；先 `ls -la /tmp/*.enex` |
| 大量 `未存储: xxx -> 超过 1024 KB 上限` | 该笔记的附件超限，笔记本身仍然导入了 |
| 想提高附件上限 | 服务器 `.env` 里设 `MAX_ATTACHMENT_BYTES`（例如 `5242880` 表示 5MB）后重启服务，**这是全局设置**，手机端与网页端的上限会一起变 |
| 导入很慢 | 附件要 base64 解码 + 存储，几千条笔记的资源是主要耗时；`--limit` 可以分批 |
