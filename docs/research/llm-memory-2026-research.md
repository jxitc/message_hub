# LLM 个人知识库 / 长期记忆系统：2025–2026 现状调研

> 调研时间锚点：检索到的资料覆盖到 **2026-09**（含 2026 年 4–8 月的产品与论文）。GitHub 数据为 2026-09-12 通过 API 实测。
> 面向场景：单用户、手机通知/SMS 流 ~200 条/天、~10k tokens/天，希望 LLM 定期批量编译成个人知识库，再用自然语言查询。

---

## 0. 一句话结论

**在 10k tokens/天这个量级，"存起来再检索"不是主要问题，"编译"才是。**
RAG（分块+向量）在 2025–2026 被反复批评的不是"检索无用"，而是"**把检索当成了知识管理的全部**"：它每次都在从原始碎片里重新推导知识，不做沉淀。对单用户小语料，主流实践已经明显偏向 **Markdown/文件系统 + 关键词检索 + LLM 编译维护**，向量库被推迟到"编译产物自己都读不完"之后。

---

## 1. 主流架构对比及各自失败模式

### 1.1 四条路线（2026 年的实际分布）

| 架构 | 代表 | 核心机制 | 何时赢 | 典型失败模式 |
|---|---|---|---|---|
| **经典 RAG**（chunk+embed+向量） | 2023 范式、多数教程 | 切块→嵌入→top-k 余弦 | 大规模、只读、语义模糊、跨语言同义词 | 分块破坏上下文；向量近≠语义对；多跳断裂；无法回答全局问题 |
| **长上下文直塞** | 1M 窗口（Gemini/GPT/Claude） | 全部塞进 prompt | 单会话 <1M tokens | token 成本线性爆炸；context rot；lost-in-the-middle；无法检索"没有的" |
| **结构化编译**（wiki/摘要树） | LLM Wiki、OpenViking、RAPTOR、GraphRAG | 后台把原料编译成结构化页面/摘要层级 | 知识需要累积、交叉引用、全局概括 | 编译有损且不可逆；编译错误会污染下游；重建成本高 |
| **智能体记忆**（工具调用） | Letta、mem0、Hindsight、Claude memory tool | Agent 自主 read/write/搜索记忆，多轮迭代检索 | 长期、动态、需要时间推理与冲突处理 | 复杂度爆炸；异步不一致；写错难删；benchmark 口径混乱 |

### 1.2 "RAG 已死"争论：双方的真实论据

**反方（批评 naive RAG）——注意这些多数来自卖"上下文工程"的人：**

- Chroma CEO Jeff Huber 在 Latent Space 直接说"**我讨厌 rag 这个词**"，主张用 context engineering 取代；Chroma 自己的技术报告《Context Rot》测了 18 个模型，证明输入变长后性能**非均匀退化**，且 NIAH（大海捞针）这类词面检索基准**严重高估**了长上下文能力（语义匹配、干扰项场景崩得更早）。[Latent Space](https://www.latent.space/p/chroma) · [Context Rot](https://research.trychroma.com/context-rot)
- Mintlify 把文档助手的 RAG 换成虚拟文件系统 + grep/`cat`/`ls`：会话创建 **46s → 100ms（460×）**，单次对话边际成本 **$0.0137 → ≈0**，跑 3 万+ 对话/天。其批评很具体：向量把文档切成 100–200 字符碎片，"像把书页打乱"，A→B→C 的多步关系跟不动，且检索是黑盒、miss 了没法 debug。[byteiota 报道](https://byteiota.com/mintlify-ditches-rag-for-filesystem-460x-faster/)
- 纯向量检索的经典失败：查询"模型训练"返回 7 条余弦 >0.72 的"数据库索引"片段——**嵌入捕捉的是语义邻近，不是语义正确性**。作者最终权重 0.7 向量 / 0.2 关键词 / 0.1 图谱，并把图谱层做成可降级。语料 <1000 条时，带/不带 Neo4j 的相关性评分只差 **0.83 → 0.81**。[dev.to](https://dev.to/kingsleyonoh/i-built-a-knowledge-graph-into-the-retrieval-pipeline-and-then-dropped-it-in-production-14fd)
- 学术侧：CAIN 2024《Seven Failure Points When Engineering a RAG System》给出可操作结论——**RAG 的验证只能在运行中做，健壮性是演化出来的，不是设计出来的**。[arXiv:2401.05856](https://arxiv.org/abs/2401.05856)
- 分块本身被证伪：NAACL 2025 Findings《Is Semantic Chunking Worth the Computational Cost?》结论是**语义分块相对固定长度分块的性能提升不足以justify其计算开销**。[ACL Anthology](https://aclanthology.org/2025.findings-naacl.114/)

**正方（RAG 未死，是幼稚实现死了）：**

- Hamel Husain + Ben Clavié 的 7 篇系列（2025-07）："2023 年的过度简化版本值得批评……但**检索本身比以往更重要**。LLM 冻结在训练时刻；百万 token 窗口不改变把一切都塞进每次查询的经济学。"系列包含：IR 评估目标应从"找到第一名"改为 **coverage / diversity / relevance**；ColBERT 类 late-interaction 保留 token 级细节，150M 参数打过 7B；"**你大概不需要图数据库**——CSV 或 Postgres 就够，向量检索本身已经在用图（HNSW）"。[hamel.dev](https://hamel.dev/notes/llm/rag/not_dead.html)
- Qdrant 的反驳（注意有商业利益）：长上下文"从来不是解决方案"，Gemini 1.5 在 1M 处 recall <0.8；企业级 200k tokens/次查询 ≈ $1/问，向量检索便宜几个数量级。[Qdrant](https://qdrant.tech/articles/rag-is-dead/)
- 学术折中方案 LDAR（ICLR 2026）明确指出长上下文替代 RAG 的三个硬伤：(i) 大而冗余的上下文 token 效率低；(ii) 加剧 lost-in-the-middle；(iii) 在有限模型能力下放大干扰项、反而降低输出质量。[arXiv:2509.21865](https://arxiv.org/abs/2509.21865)
- 36kr 的中文综述把这场争论分成三派（LlamaIndex 的"进化为 agentic retrieval"、Hamel 的"成为严肃工程学科"、Chroma 的"上下文工程当立"），归纳得比多数英文源清楚。[c114/36kr 转载](https://www.c114.net.cn/industry/29838.html)

### 1.3 长上下文 vs 结构化记忆：benchmark 给出的"分域"答案

这是 2026 年最有价值的一张表（来自一个需要谨慎对待的源，但与 BEAM/LongMemEval 原始结论方向一致）：

| 任务形态 | 长上下文基线 | 结构化记忆 |
|---|---|---|
| 单会话、<1M tokens | **通常赢** | 打平或输 |
| 多会话对话 + 时间推理 | 输 | **顶尖系统赢** |
| 长时序 agent 轨迹（AMA-Bench） | **赢**（GPT-5.2 72.26% vs 最佳记忆系统 57.22%） | 多数系统**输给"什么都不做"** |
| 超过 1M（BEAM 10M 档） | 无法处理 | **决定性赢**（BEAM 报告 1M 档提升至 75%、10M 档 >100%） |

来源：[AgentMarketCap 2026-04-17](https://agentmarketcap.ai/blog/2026/04/17/locomo-ama-bench-long-context-beats-structured-memory)（**该站疑似 AI 内容农场，数字请当作线索而非事实**）。
另一条独立的、较少营销色彩的实测（来自 agent 作者 + 厂商 Engram，同样需打折）：LOCOMO 上 **full-context 88.4% vs 检索式 80.0%**，但检索式省 **96.6% token**（776 vs 22,976 tokens/问）；作者的经验是"**召回式优于抽取式**——写入时抽取结构化事实反而掉分，因为噪声太大；智能应投在读时（查询已知）而非写时"。[hotmolts/Moltbook](https://www.hotmolts.com/post/benchmarking-agent-memory-we-ran-locomo-and-dmr-ag-0a6d3437-598b-4abd-b8a1-3bab81da7b89)
LongMemEval 原始论文则记录了反面：商用助手与长上下文 LLM 在多轮持续交互上**掉 30% 准确率**，并给出三条优化（session decomposition、fact-augmented key expansion、time-aware query expansion）。[arXiv:2410.10813](https://arxiv.org/abs/2410.10813)

### 1.4 结构化编译（wiki）路线在 2026 年为什么突然火

- **LLM Wiki**：原始资料放 `raw/`（只读），LLM 持续"编译"成 `wiki/` 下的 Markdown（实体页、概念页、综述页、`index.md`、`log.md`），交叉引用与矛盾标记由 LLM 维护；人只负责选料和提问。核心论点是"**编译一次并保持更新，而不是每次查询重新推导**"，并且明确指出：**在约 100 个源文件、数百页的规模下，读 `index.md` 就够，不需要 embedding 向量检索基础设施**。（该范式在中文语境普遍归于 Karpathy 2026-04 的推文，[雷峰网](https://www.leiphone.com/category/ai/bj5ObTLwpb0spdnL.html)；但 esolnguyen/llm-wiki 仓库把 idea file 归给 Geoffrey Litt——**归属有分歧，方法本身才是关键**。）[llm-wiki/docs/concept.md](https://github.com/esolnguyen/llm-wiki/blob/main/docs/concept.md)
- 论文侧对应物是 **RAPTOR**（递归嵌入/聚类/摘要建树，QuALITY 上 +20% 绝对准确率）与 **GraphRAG** 的 community summary 层级。[arXiv:2401.18059](https://arxiv.org/abs/2401.18059) · [arXiv:2404.16130](https://arxiv.org/abs/2404.16130)

### 1.5 失败模式速查（按架构）

- **RAG**：分块切断关系；向量相似度在领域内词汇共现时"自信地错"；top-k 召回不到就没有；多跳/全局问题结构性无解；无从 debug。
- **长上下文**：成本随 token 线性；context rot 导致非均匀退化；lost-in-the-middle；无法访问窗口外历史。
- **结构化编译**：**有损且不可逆**（编译时丢掉的细节永远找不回）；编译 hallucination 会污染整棵树；schema 漂移；重建成本高。GraphRAG 尤甚——见第 3 节成本数据。
- **智能体记忆**：组件多、状态多（mem0 自托管要 Qdrant、Graphiti 要 Neo4j）；异步整理导致"刚说的不生效"；写错的记忆**可能删不掉**（Hindsight issue #3509 就是没有 per-fact 删除）；benchmark 不可比。

---

## 2. 值得关注的工具与项目

**GitHub 数据实测于 2026-09-12**；"成熟度"综合 star/活跃度/许可证/issue 治理。

| 项目 | 数据模型 | 检索机制 | 自托管 | 成熟度 | 关键坑 |
|---|---|---|---|---|---|
| **mem0** [repo](https://github.com/mem0ai/mem0) | 事实抽取 → SQL(事实+元数据) + 向量 + 实体库；v3 改为**单遍 ADD-only**（不再 UPDATE/DELETE） | 多信号融合：语义 + BM25 + 实体匹配 + 时间感知排序 | ✅ 库 / server / cloud 三档 | 65k★，Apache-2.0，最活跃 | BM25 与实体抽取**硬编码英文**（issue #4884）；batch embedding 失败会**静默丢记忆**（#5245）；**Graph Memory 已从 OSS 移除**，只在托管平台；README 的 92.5 分是**托管平台专属**，OSS 跑不出 |
| **Letta（原 MemGPT）** [letta-code](https://github.com/letta-ai/letta-code) | **MemFS：git 版本化的记忆文件系统** + memory blocks + skills；后台 "dreaming" 子 agent | Agent 自己 read/write/重组记忆文件（文件系统即记忆） | ✅ Apache-2.0 | 老仓 24.7k★ 但已迁至 letta-code（3.3k★，极活跃） | API 大改（V1 server 已退役到 archive 分支）；概念多、学习曲线陡；强绑 LLM 工具调用能力 |
| **Zep / Graphiti** [graphiti](https://github.com/getzep/graphiti) | **双时间知识图谱**：实体 + 带 validity window 的事实边 + episode 溯源；事实被"失效"而非删除 | 混合：语义 + BM25 + 图遍历；宣称 sub-200ms | ⚠️ Graphiti 开源（需自备 Neo4j/FalkorDB），**Zep Community Edition 已废弃** | 30.8k★，Apache-2.0，Zep 公司商业化 | 自托管要额外跑图数据库；小模型不支持 structured output 会直接 ingest 失败；Zep 本体已闭源 |
| **cognee** [repo](https://github.com/topoteretes/cognee) | 知识图谱 + 向量 + "session distillation"；`remember/recall/improve/forget` | 自动路由到图 / 向量 / 代码检索 | ✅ | 30.7k★，Apache-2.0，非常活跃 | 概念/API 变动快（2026 年重构过 operations）；默认走 OpenAI |
| **Khoj** [repo](https://github.com/khoj-ai/khoj) | 文档索引（PDF/Markdown/org/Notion/Word）+ agent | 语义搜索 + agent 工具调用；可接本地或在线 LLM | ✅ AGPL-3.0 | 37.3k★，2021 年建仓，最"产品化" | AGPL；主仓 2026-08 后活跃度较前下降；功能面宽但深度一般 |
| **basic-memory** [repo](https://github.com/basicmachines-co/basic-memory) | **Markdown 文件 + wikilink 知识图**，Obsidian 双向同步 | 语义词义搜索 + 可选 cross-encoder rerank；MCP 原生 | ✅ AGPL-3.0（云版 $15/月） | 3.9k★，活跃 | 依赖 MCP 客户端；README 推销云版味道重（含促销码）；功能仍在 prerelease |
| **RAGFlow** [repo](https://github.com/infiniflow/ragflow) | 文档解析（DeepDoc）→ 模板化分块 → 索引 | 多路召回 + 融合重排；2025-12 起加了 agent Memory | ✅ Apache-2.0 | 90.6k★，企业向，极活跃 | 太重：≥16GB RAM / 50GB 磁盘 / Docker；无 ARM64 官方镜像；单用户场景是杀鸡用牛刀 |
| **reor** | Obsidian 式本地笔记 + 向量索引 | 语义搜索 + 问答 | ✅ AGPL-3.0 | **已归档（archived，最后 push 2025-05）** | **不要再选** |
| **Microsoft GraphRAG** [repo](https://github.com/microsoft/graphrag) | 实体图 + Leiden 社区层级 + 预生成 community report；Parquet + LanceDB | Global / Local / DRIFT 三种查询 | ✅ MIT | 36k★，维护中 | **索引成本是本表最高**（见 §3.2）；增量更新弱；适合"高价值、静态"语料 |
| **LightRAG / LazyGraphRAG / Fast GraphRAG** | 双层图 / 无 LLM 抽取的 NLP 图 / PageRank 探索 | 低层级检索 | ✅ | 生态活跃 | 索引成本约为 GraphRAG 的 1/100 / 1/1000 / 1/6，但关系保真度弱于 GraphRAG |
| **Hindsight** [repo](https://github.com/vectorize-io/hindsight) | 四网络：world facts / experiences / observations / **mental models**（常驻答案，纯 DB 读，零 LLM） | `retain`(LLM抽取) / `recall`(4 路并行 RRF + cross-encoder 重排，**零 LLM 成本**) / `reflect`(深度分析) | ✅ MIT，Postgres+pgvector | 23.5k★（2025-10 建仓），issue 治理好（~71 open） | **无 per-fact 删除**（错记忆清不掉）；cross-encoder 会惩罚图扩展结果；无实体 merge/alias API |
| **OpenViking（火山引擎）** [repo](https://github.com/volcengine/OpenViking) | `viking://` **虚拟文件系统**：resources / memories / skills；**L0 摘要(~100 tok) / L1 概览(~2k) / L2 全文** 三层渐进加载 | 先向量定位目录 → **目录级递归分层检索**（返回的是"带层级的碎片"）；`find`/`grep`/`ls`/`tree` CLI | ✅ 但 **AGPL-3.0**，分布式要 license key | 36.8k★（2026-01 建仓），664 open issues | 已发布 benchmark 依赖豆包模型，海外难复现；向量后端受限；中文场景是它的主场 |
| **Supermemory** [repo](https://github.com/supermemoryai/supermemory) | Postgres + Cloudflare Workers，API-first | 托管检索 API | ⚠️ MIT 引擎开源，主形态是托管 | 29.6k★，107 contributors，唯一拿到融资的 | 评测口径是 Recall@15，**与其他家 accuracy 指标不可比** |
| **LlamaIndex / LangMem** [langmem](https://github.com/langchain-ai/langmem) | LlamaIndex 有 memory modules；LangMem 是 LangChain 官方记忆库 | 各组件自选 | ✅ MIT | LlamaIndex 52k★；LangMem 仅 1.7k★ | 是**工具箱不是产品**，没有端到端方案 |
| **Holographic（Hermes 内置）** | 单文件 SQLite(WAL) + FTS5 虚表；相位编码 HRR 向量 | FTS5 取 3× 候选 → Jaccard + HRR 相似度重排 → trust 加权 | ✅ MIT，零依赖 | 随 Hermes Agent 存在，无独立仓 | numpy 缺失时**静默降级为纯关键词检索**；HRR 是词袋级、无学习型语义泛化；**1024 维下单 bank 约 256 条就到容量上限**（SNR<2.0 告警） |
| **llm-wiki（范式，非产品）** | `raw/`(只读) + `wiki/`(LLM 维护的 Markdown) + `schema` 操作手册 | 读 `index.md` 找页 → 下钻；`grep` `log.md`；规模大了再上搜索 | ✅ 就是文件 | 极轻，1★ 参考实现 | 无自动化；依赖 agent 遵守 schema；规模上限需要自己观察 |

> 横切观察（来自一份 2026-09 的中文横评，引用具体 issue 编号，可核查性较好）：**这 8 个主流"记忆数据库"里 6 个后端都是 PostgreSQL**。所谓记忆层竞争，主要是在 Postgres 之上竞争**组织方式与检索策略**，而不是竞争存储引擎。[cnblogs 横评](https://www.cnblogs.com/itech/p/22880361)

---

## 3. 关键技术清单

### 3.1 分层摘要 / 摘要树（RAPTOR、OpenViking L0/L1/L2）
- RAPTOR：递归嵌入+聚类+摘要建树，检索时跨抽象层级整合，QuALITY +20% 绝对准确率。[arXiv:2401.18059](https://arxiv.org/abs/2401.18059)
- OpenViking 的工程化版本：每个目录自带 `.abstract.md`(L0, ~100 tok) 与 `.overview.md`(L1, ~2k)，只有需要时才读 L2 全文。**这是"渐进披露"（progressive disclosure）在知识库上的落地**——对单用户场景极其实用：让小模型先看目录摘要，再决定读哪几页。
- 代价：每一层摘要都是一次有损压缩，**编译期丢的细节无法在查询期找回**；schema/摘要一旦漂移，需要重建。

### 3.2 知识图谱 + GraphRAG
- 解决的问题是真的：向量检索**无法回答全局性问题**（"这批数据的主题是什么"），GraphRAG 用社区摘要 + map-reduce 解决。[arXiv:2404.16130](https://arxiv.org/abs/2404.16130)
- **成本是真的大**（最硬的一组数据）：Microsoft GraphRAG 索引成本约 **$20–40 / 百万 tokens**（GPT-4o），而同等规模的纯向量 RAG 只要 **~$0.02**——差几百倍；2024 年有报道称给大规模语料做 GraphRAG 索引花了 **$33,000**。替代品：LightRAG ≈ 1/100、Fast GraphRAG ≈ 1/6（插入快 ~27×）、**LazyGraphRAG ≈ 1/1000**（索引成本与向量 RAG 相当，全局查询用 4% 的查询成本达到 GraphRAG 同级质量）。[zenn 成本拆解](https://zenn.dev/libercraft/articles/20260710-graphrag-production-economics) · [aicoolies 评测](https://aicoolies.com/reviews/graphrag-review)
- 失败模式：**没有 entity resolution 的图谱比调优过的 BM25+向量更差**；"Neo4j + LangChain ≠ GraphRAG"——缺了社区摘要与全局/局部分流，你只是搭了个更贵的实体查询器。[dev.to](https://dev.to/aiwithmohit/graphrag-beats-vector-search-by-86-but-92-of-teams-are-building-it-wrong-mno)
- 反面证词：小语料上图谱价值≈0（<1000 文档时 0.83 vs 0.81），且运维成本（Neo4j JVM 要 512MB 堆）在 1GB VPS 上直接不成立。

### 3.3 混合检索（BM25 + 向量 + rerank）
- 工业上最稳的组合：**RRF 融合两路召回 → cross-encoder 重排 → 截断**。Elastic 的实现给得很细：两个索引腿各取 **80 候选**，RRF `rank_constant=30`，再用 cross-encoder 重排；168 题评测 **R@10 = 0.89**。[Elastic Search Labs](https://www.elastic.co/search-labs/blog/agent-memory-elasticsearch)
- 关键细节：**Agent 常常改写用户 query，把版本号/错误码/专有名词洗掉**，导致 BM25 直接失效——Elastic 的做法是每轮对话先拿**原文**做一次 pre-recall。这是个非常真实的坑。
- 选择启发式（可操作）：抽 200–500 条真实查询，算 query 内容词与目标文档的**字面重叠率**：>70% → 单靠 BM25；40–70% → 混合 + RRF；<40% → 向量主导。[dev.to](https://dev.to/gabrielanhaia/rag-without-embeddings-when-bm25-beats-your-020-per-1k-vector-index-2140)
- 权威 IR 结论：BEIR 上 dense 平均领先 BM25 约 15–25%，混合再加 2–5%；但**逐语料看**，短查询/领域漂移大/精确串即语义的语料（BioASQ、Touché-2020）BM25 仍赢或打平。

### 3.4 时间衰减 / 时序权重
- 两条不同的路线，别混：
  1. **软重排（不删）**：mem0 2026-05 上线 Memory Decay，按"最近被检索时间"给分数乘一个衰减因子，**下限 0.3×**，陈旧记忆仍能召回，只是排序下沉。[mem0 blog](https://mem0.ai/blog/introducing-memory-decay-in-mem0)
  2. **时间有效性（双时间戳）**：Graphiti 给每条事实一个 validity window，新信息**使旧事实失效而非删除**，可以查"当时为真"。[Graphiti](https://github.com/getzep/graphiti)
- 反面意见（值得听）：有研究笔记认为**TTL/时间衰减是弱信号，基于"结果是否有用"的效用排序更强**。[davidamitchell/Research](https://github.com/davidamitchell/Research/blob/main/Research/completed/2026-03-02-agent-memory-management-context-injection.md)（二手汇编，证据强度弱于前两条）
- Elastic 的实现把**不同记忆类型用不同时间字段衰减**（episodic vs semantic 分开），并强调"近期事件密集，必须 consolidate 成持久事实，否则索引变草堆"。

### 3.5 去重与合并（这一节 2026 年出现了**方向性反转**）
- **老范式**（mem0 v1/v2、多数教程）：写时让 LLM 决定 ADD / UPDATE / DELETE。
- **新范式（mem0 v3，2026-04）**：改成**单遍 ADD-only，一个 LLM 调用，不 UPDATE 不 DELETE**——"记忆只累积，不覆盖"；去重靠检索时多信号（语义+BM25+实体+时间）自然抑制。同版本还**把图记忆从 OSS 移除**。[mem0 migration guide](https://docs.mem0.ai/migration/oss-v2-to-v3)
- 与之对立的另一派仍坚持 supersession（不删、标记失效）与后台 refine：Elastic 用 `supersedes_id` + `contradiction` 字段；Hindsight 的 observations 用 "refine 而非覆盖" + proof count。[Elastic](https://www.elastic.co/search-labs/blog/agent-memory-elasticsearch)
- 工程结论：**写入时做重活（抽取+更新判断）容易引入噪声且难回滚**；独立实测也支持"写时抽取反而掉分，读时智能更划算"。若你要在两者间选，2026 年的天平偏向 **追加 + 读时融合 + 后台合并**。

### 3.6 后台整理 / sleep-time compute
- 学术定义（arXiv:2504.13171，Letta/UC Berkeley，2025-04）：在查询到来前离线"想"，预计算有用量。结果：达到同等准确率**减少约 5× 测试时算力**；扩大 sleep-time 算力可再提 13%（GSM）/18%（AIME）；多相关查询摊薄后**平均每查询成本降 2.5×**。关键调节变量是**用户查询的可预测性**。[arXiv](https://arxiv.org/abs/2504.13171)
- 产品化两种形态：
  - **Letta "dreaming"**：后台子 agent 复盘近期会话、合并经验、更新 MemFS，可按"完成 N 步后"或"context 压缩时"触发，可选"应用前再由 agent 复核一遍"。[Letta docs](https://docs.letta.com/configuration/memory/)
  - **OpenAI "ChatGPT dreaming"**（2026-06-05 起向美区 Plus/Pro 推送）：后台跨会话综合记忆状态，并给用户一个 **memory summary 页**可以查看/修改/删除被记住的内容——**可审阅的记忆是 2026 年产品化的重点**，而不只是"记得更多"。[India Today 报道](https://www.indiatoday.in/technology/news/story/chatgpt-gets-big-memory-boost-openai-says-it-will-remember-everything-about-you-now-2922330-2026-06-05)（原厂页 openai.com 对本环境返回 403，未能直接核对）
- 工业界的 cadence 建议：**不要每轮都整理**（Elastic 明说 per-turn 会让 LLM 调用翻倍），生产上用后台 job，比如"每 24h 一次"或"episodic 新增超过 N 条时"。
- Anthropic 的官方数据（2025-09）：memory tool + context editing 在其内部 agentic search 评测上**提升 39%**（单 context editing 29%）；100 轮 web search 评测中 token 消耗**降 84%**。[Anthropic](https://claude.com/blog/context-management)

### 3.7 忘掉什么 / 记住什么
- 可用的策略分层：**类型分离**（episodic / semantic / procedural 三种生命周期完全不同，Elastic 用三个索引分别管）、**置信度门槛**（低于阈值直接丢）、**相似度≥0.90 判为重复**、**success/failure 计数**给 playbook 排序、**容量告警**（Holographic 在 SNR<2.0 时警告精度退化）。
- 反面教训：**删除能力要提前设计**。Hindsight 上线后才发现没有 per-fact 删除，错误事实写进去清不掉（issue #3509）。对个人知识库，**"忘记"必须是第一等公民**，否则半年后你的库里有毒。

---

## 4. 小规模单用户场景的实际建议

### 4.1 先算一下规模（这是全篇最重要的一节）

| 量 | 数值 |
|---|---|
| 原始流 | 10k tokens/天 ≈ **3.65M tokens/年** |
| 编译后（按 10:1 压缩） | ~1k tokens/天 ≈ **365k tokens/年** |
| 更激进的 wiki 化（20–50:1） | ~200–500 tokens/天 ≈ **70k–180k tokens/年** |

由此得到两条硬结论：

1. **原始流做"全上下文直塞"会在第一年末死掉**：3.65M tokens/问，按 frontier 模型 $3/M 输入 ≈ **$11/问**，且远超可靠注意力范围。全上下文只在"最近几周"窗口内成立。
2. **编译产物可以长期全上下文**：即使编译得不够狠，一年 365k tokens 仍可一次读完；若按 wiki 模式编译到 100k 量级，一个 200k–1M 窗口的模型**每年都能"读完整个人生"**。这不是优化，是**改变了问题的规模等级**。

这也是 LLM Wiki 之所以对单用户有效的根本原因，而不是因为它时髦。

### 4.2 分阶段架构（推荐路径）

**阶段 0（立刻，成本≈0）：文件 + 全文检索**
- 原始通知按天落成 `raw/YYYY-MM-DD.md`（不可变、可审计）。
- **SQLite FTS5 或 `grep`/`rg`** 做检索。理由（来自真实运行 8 个月的实践）：<1000 文档时 BM25 命中率 >90%，因为"你自己写的词就是你自己会搜的词"——**单人自写的语料几乎不存在语义鸿沟**；坏处要诚实：**10K+ 文档后 FTS5 排序开始变吵**。[dev.to](https://dev.to/kuro_agent/why-i-replaced-my-ai-agents-vector-database-with-grep-59mm)
- 用 `ls`/`cat`/`grep` 作为 agent 的检索工具已经是主流收敛方向（Cursor/Claude Code 内部大量依赖 grep；Mintlify 的 460× 提速就是这么来的）。
- ⚠️ **中文特有坑**：FTS5 默认 `unicode61` 对 CJK 是字符级切分，"能用但不精确"；要么用 **trigram tokenizer**，要么自己做 **char-bigram** 索引。别指望英文 BM25 配置直接套。

**阶段 1（1–4 周后）：编译层（这一步是你的核心）**
- 一个定时批处理 job：读当日/近 N 日原始通知 → 让 LLM 输出到 `wiki/` 下的结构化页面（实体页、主题页、时间线页），并**强制**：
  - 每条事实带**来源指针**（哪天的哪条通知）；
  - **矛盾不覆盖、只标记**（superseded_by 字段）；
  - 更新 `index.md`（一页一行：链接 + 一句话摘要）和 `log.md`（append-only，`## [YYYY-MM-DD] ingest | ...` 前缀，方便 `grep`）。
- 检索方式：**让 LLM 先读 `index.md` 再下钻**（progressive disclosure）。100 个源 / 数百页规模下，这就是足够的检索。
- 这等价于把"读时智能"前置成"写时编译"，并且**编译产物是可读、可 git diff、可手改的**——这是 RAG 给不了的。
- ⚠️ 编译是有损的：**原始 `raw/` 必须永久保留**，否则你无法纠正编译错误。

**阶段 2（编译产物 grep 不动时，才考虑向量）**
明确的触发条件，满足**任意两条**再上：
- `wiki/` 已超过 **~1–3k 个文件**或**几十万 tokens**，且 `index.md` 单文件已经超长；
- 你的查询**字面重叠率 <40%**（用 §3.3 的方法实测：搜同义词/换说法时关键词检索明显漏）；
- 需要**跨文档的概念聚合**（"所有和房租相关的通知"这种没有共同字面的查询）。
- 上的时候：**不要引入独立向量数据库**。用 **SQLite + 向量扩展 / Postgres + pgvector**，或者直接用 **Holographic 那种单文件 SQLite + FTS5 + 轻量向量重排**的形态。理由：单用户语料的向量数（几万到几十万量级）**远低于**专用向量库值得其运维成本的门槛（百万–十亿向量）；托管向量库还有最低消费。[vectorize 的论证](https://github.com/vectorize-io/hindsight/blob/main/hindsight-docs/blog/2026-05-12-case-against-external-vector-dbs-agent-memory.md)
- ⚠️ 容量下限别踩：Holographic 在 1024 维下单 bank 约 256 条就到容量告警线——**"单文件轻量记忆"确实有上限**，你的 10k tokens/天增长会比你想象的快。

**阶段 3（大概率不需要）**
- 知识图谱 / GraphRAG：**除非你的查询真的是多跳关系型或全局概括型**，否则索引成本（几十到几百倍）换不来收益。要上也优先 **LazyGraphRAG** 这类索引期不调 LLM 的变体。
- 多租户记忆框架（Zep/mem0 托管/Supermemory）：单用户场景里它们的核心卖点（per-tenant 隔离、跨用户记忆、合规遗忘）**对你全部不适用**。

### 4.3 成本量级参考
- 批处理：10k tokens/天输入 + 2–3k 输出，用便宜模型（DeepSeek / GPT-5-mini 档）**月成本在个位数美元以内**，可忽略。这是"小规模"的最大红利，值得把编译 prompt 做重（多轮、多视角）。
- 查询：编译产物全上下文（~10–100k tokens）每次查询成本 **cents 级**；若直塞原始流（3.65M）则 **$10+/问**。差了 2–3 个数量级。

---

## 5. 明确的坑与反模式

1. **在 <1000 文档规模上先建向量库**——运维税先付，收益为零；且日后想换存储时迁移成本很高。
2. **用英文 BM25/实体抽取处理中文**——mem0 的 BM25 与实体抽取**硬编码英文**（issue #4884）就是活例子；SQLite FTS5 默认分词对 CJK 也不友好。
3. **写时抽取结构化事实而不留原文**——独立实测显示抽取式不如召回式，噪声大且掉分；没有 raw 层，编译错误无法回溯。
4. **让 LLM 决定 UPDATE/DELETE 而不做审计**——mem0 v3 直接退回 ADD-only 是有原因的；另一侧的教训是"没有删除 API 的记忆系统"（Hindsight #3509）。**默认追加 + 可标记失效 + 后台合并**，并且**删除必须是显式可控的操作**。
5. **相信厂商 benchmark 数字**——2025–2026 的记忆 benchmark 已经实证是"罗生门"：
   - Mem0 论文声称 SOTA，Zep 发文逐条反驳（称 Mem0 给 Zep 的实现有误，正确实现下 Zep 75.14% ± 0.17，比 Mem0 最佳配置高约 10%）[Zep](https://blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory/)；Mem0 CTO 反手在 Zep 仓库开 issue，称按统一协议重跑 Zep 只有 **58.44% ± 0.20**（Zep 把应排除的对抗类题目算进分子）[Mem0/Zep issue #5](https://github.com/getzep/zep-papers/issues/5)。
   - Honcho 官方博客点名 Hindsight 的 91.4% LongMemEval-S 站不住：**Gemini 3 Pro 裸跑同一份题就有 92.0%**——加记忆层反而拖累。
   - 有厂商的"检索"步骤实际是**全量时间线倾倒**（文档里写着 "No lossy semantic retrieval step"），跑的不是生产路径。
   - 有的跨模型配置**混采对自己有利的数字**，有的把 `LLM-as-Judge accuracy` 和 `Recall@15` 放同一张榜。
   - 同一家同一版本在 README 和迁移文档里给出**两个不同的分数**（mem0：92.5 vs 91.6）。
   - 结论：**benchmark 只能当"这家在做什么"的信号，不能当选型依据**；唯一可靠的是拿自己 50–200 条真实查询建一个 gold set 自己测（成本一个晚上）。
6. **把 NIAH 满分当成"长上下文可用"**——context rot 报告与 LDAR 都证明词面检索分数不代表真实能力。
7. **异步整理却假设"刚说的马上生效"**——Honcho 的 README 自己就警告这点；你的批处理方案同理，要明确"截止时间"语义。
8. **只依赖语义检索而没有精确通道**——错误码、金额、日期、订单号这类必须能**逐字命中**。混合检索不是可选优化，是可靠性要求。
9. **把记忆当"存了就完事"**——个人知识库最常见的死法是退化成"信息坟场"（内容持续进入、无人维护、过期与矛盾堆积）。必须配 **lint/巡检**流程：找矛盾、标过期、找孤立页、找缺页的概念。这正是 LLM 最擅长且人类最不愿做的事。
10. **忽略"记忆可审阅"**——OpenAI 的新架构专门做了 memory summary 页让用户看/改/删。你自己搭的系统同样需要一个"人能读的一等公民视图"（Markdown 天然满足，向量库不满足）。

---

## 6. 结论（给这个具体场景的推荐）

**推荐架构（按优先级）**：
```
raw/    ← 原始通知/短信，按天 Markdown，不可变，永久保留（审计与回溯的唯一真相）
wiki/   ← LLM 批量编译产出的结构化 Markdown（实体页/主题页/时间线），带来源指针与矛盾标记
index.md + log.md  ← 渐进披露的入口 + append-only 变更日志
检索 = LLM 读 index.md → 下钻具体页 → 必要时 grep/rg 兜底（FTS5 建 trigram 索引）
后台 = 每日批处理 job（编译）+ 每周 lint job（查矛盾/过期/孤立页）
```
**明确不上**（当前规模）：向量数据库、知识图谱、多租户记忆框架、GraphRAG。
**明确的升级触发点**：见 §4.2 阶段 2 的三条判据。

**这个选择与 2026 年主流实践的一致性**：LLM Wiki 范式（编译而非检索）、Kuro/Mintlify（文件系统 + grep 生产验证）、Holographic/Basic Memory（单文件/Markdown 本地优先）、OpenViking（渐进披露 L0/L1/L2）都指向同一个方向；而 mem0/Zep/Supermemory 那套重型方案解决的问题（跨用户、多租户、企业级 SLA、百万级向量）与"单用户 10k tokens/天"几乎不重叠。

**诚实的不确定性**：
- 我没有找到 2025–2026 年可比的、**针对"个人通知流"这类语料**的严格评测；上述结论是从"小语料/自写语料"与"长时序个人记忆"两类证据外推的。
- 编译式 wiki 的**规模上限**缺乏公开数据：LLM Wiki 作者只说到"约 100 个源、数百页下 index.md 够用"，再往上没有实测报告。你的增长速度（~3.65M tokens/年原始）意味着这个上限大概在 1–3 年内会被触及——**建议现在就在编译产物上埋指标（文件数、index 长度、检索命中率），让升级触发点可观测**。
- 部分来源（AgentMarketCap、hotmolts/Moltbook、byteiota、Codex/AI 生成的 dev.to 帖、Hindsight 的实时看板）可靠性存疑，正文已逐条标注。
