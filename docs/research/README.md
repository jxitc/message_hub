# 个人知识库（PKB）方案调研 — 索引与结论

> 调研日期：**2026-09-12**。目的：为「手机通知/短信/邮件 → LLM 编译成个人知识库」选型。
>
> 本目录三份材料是**调研原文**，本文件是**结论摘要 + 我们的落地决策**。

## 材料

| 文件 | 内容 | 规模 |
|---|---|---|
| `karpathy-llm-wiki-gist-verbatim.md` | Andrej Karpathy 的 LLM Wiki gist **逐字原文** | 12KB |
| `obsidian-ai-pkm-report.md` | Obsidian 生态方法论 + AI 插件 + 检索技术调研 | 25KB |
| `llm-memory-2026-research.md` | 2025–2026 LLM 长期记忆系统（mem0/Letta/Zep/GraphRAG…）调研 | 34KB |

### ⚠️ 信源纠正（重要）

网上**普遍引错** Karpathy 的 gist ID。被到处引用的
`1dd0294ef9567971c1e4348a90d69285` 实际是他另一个 gist「Git Commit Message AI」。
**真正的 llm-wiki gist 是 `442a6bf555914893e9891c11519de94f`**。
`balukosuri/llm-wiki-karpathy`、`cask-wiki`、多篇中文解读都引错了。

另：`balukosuri/llm-wiki-karpathy` 有 209★，但**仓库只有 10 个文件、零代码**（纯模板 +
schema）；唯一写了自研检索算法的 `cask-wiki` **月下载 24 次**。这类"开源实现"基本没有
算法含量，**有价值的是 gist 本身 + `qmd`（BM25+向量+RRF+本地重排，29.7k★）**。

## 一句话结论

**在"每天 ~10k tokens"这个量级，"存起来再检索"不是主要问题，"编译"才是。**
主流实践已从"检索即一切"转向 **Markdown 文件 + 关键词检索 + LLM 持续编译维护**；
向量库被推迟到"编译产物自己都读不完"之后；知识图谱在单用户规模下基本无收益。

## 关键数据

### 规模换算（决定架构的那个数字）

| 量 | 数值 |
|---|---|
| 原始消息流 | 10k tokens/天 ≈ **3.65M tokens/年** |
| 编译成 wiki（10:1） | ~365k tokens/年 |
| 激进 wiki 化（20–50:1） | 70k–180k tokens/年 |

原话：*"原始流做全上下文直塞会在第一年末死掉（约 $11/问）；而编译产物一年 365k 仍可一次读完。
**这不是优化，是改变了问题的规模等级**——也是 LLM Wiki 对单用户有效的根本原因。"*

### 我们自己的实测（message_hub 9 天真实数据）

| 环节 | 结果 |
|---|---|
| 原始消息 | 1,798 条（8.7 天） |
| 过闸后 | **90 条唯一内容**（丢 597 噪音 + 扣 425 内容推送） |
| 编译成本 | 7.3k prompt + 4.2k completion tokens，14 秒，约 **¥0.05** |
| 产物 | 22 个 wiki 页面（8 人 + 12 机构 + 2 事件 + 1 话题） |

## 三份调研的收敛结论

四条独立得出、互不矛盾的原则：

1. **确定性结构 + LLM 只碰内容层**
   Karpathy 说 schema 是"关键配置文件"；Obsidian 生态说"绝不让 LLM 决定文件放哪、
   要不要新建笔记"；记忆系统调研说"写时抽取结构化事实反而掉分"。
   → **落位、frontmatter、索引由代码生成；LLM 只产出实体/要点/关系。**

2. **不要一上来用向量库**
   Karpathy：~100 源读 `index.md` 就够，*"avoids the need for embedding-based RAG
   infrastructure"*。Obsidian：<1000 文件 BM25 命中率 >90%（"自己写的词就是自己会搜的词"）。
   16,894 文件的实测：FTS5 BM25 + 向量 + RRF = 23ms / 83MB 单 SQLite。

3. **实体页 > 每日摘要**
   Farzapedia（2,500 条 iMessage → ~400 篇互链文章）的价值全在 `friends/`、`startups/`
   这类**跨时间聚合的实体页**；**"每日摘要"本质上就是 RAG 的 chunk，是最没价值的产出**。

4. **编译有损，raw 必须永久保留**
   wiki 是**可丢弃、可重建的缓存**，raw 才是资产。

## 明确的"不做"清单（当前规模）

| 不做 | 理由（有数据） |
|---|---|
| **向量数据库** | 语料太小；单用户几万向量远低于专用库值得其运维成本的门槛 |
| **知识图谱 / GraphRAG** | <1000 条语料带/不带 Neo4j 相关性只差 **0.83 vs 0.81**；GraphRAG 索引成本是向量 RAG 的**几百倍**（$20–40M vs $0.02/M tokens），有大规模语料烧掉 **$33,000** |
| **多租户记忆框架**（mem0/Zep 托管等） | 其卖点（跨用户隔离、企业 SLA、百万级向量）对单用户全部不适用 |
| **全局知识图谱可视化** | 社区共识否定票：>200 笔记退化成毛线球，不显示状态/新鲜度 |

## 升级触发点（满足任意两条再上向量）

1. `wiki/` 超 **1–3k 文件**或 index 单文件超长
2. 查询**字面重叠率 <40%**（同义词/换说法时明显漏）
3. 需要**跨文档概念聚合**（无共同字面的查询）

上时**不引入独立向量数据库**：用 SQLite + 向量扩展 / pgvector。

## 必须写死的纪律

1. **隐私闸 + 确定性兜底**：schema 写死禁止项（验证码、卡号**含尾号**、**余额**、身份证、
   地址门牌号），**同时**在代码里用正则擦除。本次实测证明：只靠 schema 会漏（模型把余额写了进去）。
2. **矛盾标记而非覆盖**：`superseded_by` / `## Contradictions`。
3. **每次 ingest 一个 git commit**（可 diff / 回滚 / 整库重建）。
4. **lint 拆两半**：死链/孤儿/stale 用**脚本**查（零 token）；只有"矛盾、知识空白"给 LLM。
5. **删除是一等公民**：Hindsight 上线后才发现没有 per-fact 删除 API，成了死结。
6. **别信厂商 benchmark**：Mem0 与 Zep 互相指控（75.14% vs 58.44%）；有厂商的"检索"实为
   全量倾倒；同一家 README 与迁移文档给出两个分数。**唯一可靠的是拿自己 200 条真实查询自测。**

## 中文特有坑

- **SQLite FTS5 的 `unicode61` 把连续汉字当一个 token** → 搜"消耗""索引优化"这类
  **子串必然 0 命中**。必须用 `trigram`（≥3 字有效）或 1–2 字查询回退 `LIKE '%词%'`。
- 生态里中文支持普遍是短板（Omnisearch 需额外中文分词插件；mem0 的 BM25/实体抽取硬编码英文，issue #4884）。

## 落地状态（截至 2026-09-12）

已在 `info_agent/info_agent/pkb/` 实现最小链路并跑通真实数据：

```
fetch.py    MH API → pkb/raw/inbox/YYYY-MM-DD.md      全量、不可变、带消息 id 溯源
curate.py   inbox → raw/curated/YYYY-MM-DD.md         规则过闸 + 去重
compile.py  curated → wiki/{people,orgs,events,topics} + index.md + log.md
paths.py    目录约定 + schema（单一真相来源）
```

**待做**：`lint.py`（确定性检查）、增量合并验证、FTS5 检索（trigram）、每日定时任务。
