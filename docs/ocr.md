# 附件的文本提取（OCR）

## 现在的做法：服务器上的 tesseract

服务器（Ubuntu）上跑：

| 文件类型 | 怎么做 |
| --- | --- |
| PDF 有文本层 | `pdftotext -layout`，最快最准 |
| PDF 没有文本层 | `pdftoppm -r 150` 渲染页面 → `tesseract -l chi_sim+eng`（最多 10 页） |
| 图片 | `tesseract -l chi_sim+eng` |

提取是**异步**的：上传时只写消息与附件，后台 worker（30 秒轮询）补文本。
文本落在 `metadata.attachments[].extraction.text`，`status` 为
`pending / done / empty / failed / unavailable`。

> 注意：这**不是** macOS 自带 OCR。原件在服务器、识别也在服务器，
> macOS Vision 只能在 Mac 上跑、碰不到这些文件。

## 已知的短板：证件照这类图读不出来

tesseract 的默认分页模式（psm 3）**假设输入是一页文档**：它先找文本块，再逐块识别。
当一张照片里「证件只占画面中间一小块、四周是桌面背景」时，那块密集小字会被整块跳过。

实测（同一本护照的照片）：

| 引擎 | 结果 |
| --- | --- |
| tesseract @150 DPI（现状） | **43 字**，护照号和有效期都没读出来 |
| tesseract @600 DPI + psm 6 | 8,264 字，但 95% 是噪点，勉强读到护照号 |
| RapidOCR @150-300 DPI | 660 字，**护照号、签发日期、有效期、机读区全对** |
| macOS Vision | 同上，且不需要安装任何东西 |

改动前全库统计：**81 个附件的文本不足 100 字**，其中 76 个是图片，
且受害者几乎都是证件——护照、BRP、驾照、身份证、医保卡、会员卡。
这不是巧合：**证件正是"高密度小字 + 拍摄时占画面一小块"的那类东西**。

## 现在的处理：离线重跑 + 回填

服务器上的 OCR 组件**没有换**。做法是：

1. 把读不出东西的附件拉到 Mac；
2. 用 **macOS Vision**（`~/.dsh/skills/vision-understanding/scripts/ocr.sh`）重跑；
   PDF 先用 `sips -s format png --resampleWidth 2400` 渲染（本机没有 poppler）；
3. `scripts/backfill-ocr.py` 把结果写回附件记录。

```
./venv/bin/python scripts/backfill-ocr.py results.json            # 演练
./venv/bin/python scripts/backfill-ocr.py results.json --apply    # 真写
```

回填脚本的三条纪律：

1. **只在不更差时才替换**：脚本会**独立复核**一遍（比较"像字段的 token 数"），
   不看调用方的 `better` 标记。实测有 25 个附件 Vision 反而更差，全部被这条挡下。
2. **保留旧值痕迹**：覆盖前把旧的 engine / 字数 / 字段数记进
   `extraction.replaced`，痕迹保留至今，随时能查"这条文本哪来的、原来什么样"。
3. **deepcopy 后整体赋值**：SQLAlchemy 判断 JSON 列有没有变是比较新旧值，
   浅拷贝里改内层 dict 会让两者比较相等、UPDATE 根本不发出（这个坑踩过）。

**回填之后必须重建实体索引**：`pkb_extract.py --incremental` 会跳过已索引的消息，
所以文本变更要靠一次全量重建才能被重新抽取（约 80 秒）。

## 还没解决的

* 新上传的附件仍然走 tesseract——遇到同样的证件照还会读不出来，
  仍需要人工重跑 + 回填。
* 若要服务器自己解决，候选是 **RapidOCR / PaddleOCR**：它们**先跑文本检测模型**
  （图上哪儿有字就框哪儿），正是这类图的解药。代价实测：
  9–14 秒/页（tesseract 是 1–2 秒），**峰值内存 478 MB**——
  那台机总共 960 MB，而且现在 OCR 跑在 gunicorn 进程里，尖峰会打到 Web 服务上。
  所以真要换，必须同时把 OCR 挪出 Web 进程，多半还要升内存。

## 相关

* `extraction.py` — 提取的实现与 worker
* `scripts/reextract.py` — 用已存的原件重跑提取
* `scripts/backfill-ocr.py` — 外部重跑结果的回填
* `docs/attachments.md` — 附件与 blob 的整体设计
