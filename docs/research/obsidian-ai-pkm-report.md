# Obsidian 生态的 AI/LLM 个人知识管理调研（检索日期 2026-09-12）

> 结论先行：Obsidian 真正的价值不在"AI 插件"，而在**把知识写成纯文本文件 + 显式链接 + 类型化元数据**，让 LLM 能像访问代码库一样访问它。2025–2026 的生态已经从"插件内的 AI 功能"转向"外部 agent 直接读写 vault（MCP / 官方 CLI / Agent Skills）"。对你的通知流水线，最值得抄的是**捕获/整理分离 + 元数据纪律 + 先用 BM25 后上向量**，最不该抄的是**原子笔记 + 全局图 + 全自动打标**。

## 一、Obsidian 的核心方法论（为什么有效）

### 1.1 数据模型：三个原语
- **本地 Markdown 文件**：vault 就是一个目录，任何编辑器可读；官方无遥测，AI 是否发送数据取决于你装的插件（[Obsidian Web Clipper 隐私说明](https://help.obsidian.md/web-clipper)）。
- **Wikilink（`[[...]]`）**：链接是双向可解析的边，反链（Backlinks）面板自动给出"谁提到我"（[官方 Link notes](https://help.obsidian.md/getting-started/link-notes)、[Backlinks](https://help.obsidian.md/plugins/backlinks)）。
- **YAML frontmatter / Properties**：类型化元数据（text/list/number/checkbox/date/datetime）。官方硬约束：**同一属性名全库只能有一种类型**；1.9 起 `tags`/`aliases`/`cssclasses` 必须是列表，单数形式被废弃（[2025-05-21 1.9.0 changelog](https://obsidian.md/changelog/2025-05-21-desktop-v1.9.0/)）。

### 1.2 原子化（atomic notes）
Andy Matuschak 的两条承重规则：**原子性**（一篇只讲一件事，且把这件事讲完整）与**概念导向**（按概念而非按作者/书/事件切分，从而逼出跨域链接）——[Evergreen notes should be atomic](https://notes.andymatuschak.org/Evergreen_notes_should_be_atomic)、[concept-oriented](https://notes.andymatuschak.org/Evergreen_notes_should_be_concept-oriented)。社区对此有争议：论坛里有"原子笔记是否被高估"的长辩（[forum](https://forum.obsidian.md/t/debating-the-usefulness-of-atomic-notes-a-novel-pragmatic-obsidian-based-approach-to-pkm-strategies/38077)）。工程意义：原子化 = 链接的**最小可寻址单元**，也是 LLM 切 chunk 时的天然边界。

### 1.3 MOC（Maps of Content）vs 文件夹 vs 标签
2026 年的技术型 vault 共识是混合制：**浅层文件夹（≤2 层）做类型分离，MOC 做导航，标签做横切过滤/状态**。

| 维度 | 文件夹 | 双链/MOC | 标签 |
|---|---|---|---|
| 一篇属于多处 | ✗ | ✓ | ✓ |
| 能携带注释/上下文 | ✗ | ✓ | ✗ |
| 重构成本 | 改名+移动 | 改 wikilink | 全局改名 |
| 是否是图节点 | ✗ | ✓ | ✗（仅过滤） |

来源：[MOC 说明](https://www.natecue.com/en/learn/productivity/map-of-content/)、[folders vs links vs tags 论坛总结](https://forum.obsidian.md/t/folders-vs-linking-vs-tags-the-definitive-guide-extremely-short-read-this/78468)、[Eleanor Konik 的文件夹辩护 + 动词型标签 #FollowUp/#addmoc](https://www.eleanorkonik.com/p/yet-another-hot-take-on-folders-versus-tags)。主流做法：**标签表状态（#unread/#seedling/#evergreen）、MOC 表主题、文件夹表工作流**，且**内容密度够了才升级成 MOC**（[wanderloots](https://wanderloots.xyz/digital-garden/tutorials/how-i-use-tags-and-topic-notes-for-structured-and-emergent-organization/)）。

### 1.4 图结构到底帮不帮检索？——要拆开讲
- **反链 / 局部图 / MOC 有用**：它们提供"拓扑导航"——从入口笔记走到精确笔记，且反链自带**人工写下的上下文链接文本**（"这篇在 X 论证里用到它"），这比 embedding 相似度噪声低得多。
- **全局 Graph View 基本没用**：社区共识是"好看不好导航"，约 200 篇以上退化成毛线球；它不显示状态/优先级/新鲜度，也不能按 frontmatter 过滤（[Obsidian's Graph View Is Beautiful and Almost Completely Useless, 2026-05](https://codeculture.store/blogs/developer-culture/obsidian-graph-view-useful)、[论坛："graph view 的意义是什么"](https://forum.obsidian.md/t/whats-the-point-of-the-graph-view-how-are-you-using-it/71316)）。有实践者报告 ~6k 笔记时原生图在 M4 Mac mini 上已不可用，需要外部工具（转引自 [Atlas 调研](https://laoujin.github.io/Atlas/research/2026-04-30-upgrading-a-personal-obsidian-workflow-in-2026/)）。
- 一条常被引用的经验尺度：**手动维护 MOC 在 ~2k 笔记后开始崩**，届时用 AutoMOC/Backlink Cache 之类自动化续命（[同上前引](https://www.navthemes.com/automatically-add-moc-links-in-obsidian-workflow-automation-tips-explained/)）。

**一句话**：有用的是"显式链接 + 反链 + 主题页"这套**可寻址图**，不是那张力导向美化图。

## 二、生态里的 AI/LLM 方案（做什么 / 怎么做 / 局限）

### 2.1 官方（Obsidian 自己）
- **没有内置 AI 聊天**。我在官方帮助文档与 changelog 里只找到一处官方 AI 能力：**Web Clipper 的 Interpreter**（截至本次检索）。它把页面上下文 + 模板里的 `{{"自然语言 prompt"}}` 一次性发给任意模型（含本地模型选项），返回值回填到模板变量/Properties，实现"剪藏时就写好摘要/标签"；context 可用 selector 收窄以省时省钱；官方同时给出耗时警告（毫秒级到 30s+）（[Interpreter 官方文档](https://help.obsidian.md/web-clipper/interpreter)）。**局限**：只作用于当前网页、不做跨库检索、不生成链接、不判断该存哪。
- **Bases（核心插件，1.9 / 2025-05）**：把 frontmatter 变成可筛选/公式/多视图的数据库（`.base` 文件格式）。它不是 AI，但**它决定了 AI 应该往哪些字段写**——Bases 只读 YAML，忽略 Dataview 的 inline `key:: value`（[1.9.0 changelog](https://obsidian.md/changelog/2025-05-21-desktop-v1.9.0/)、[Bases 概览](https://practicalpkm.com/bases-plugin-overview/)）。
- **官方 CLI（1.12 起）+ kepano/obsidian-skills**：`obsidian search / daily:append / create / tags counts / diff` 等命令，以及给 Claude Code / Codex / OpenCode 用的 Agent Skills（obsidian-markdown、obsidian-bases、json-canvas、obsidian-cli、defuddle 等），**方向是让外部 agent 直接操作 vault，而不是把 agent 塞进插件**（[官方 CLI 文档](https://help.obsidian.md/cli)、[kepano/obsidian-skills](https://github.com/kepano/obsidian-skills)）。

### 2.2 插件逐个说

**Smart Connections（生态里最主流的"发现"层）** — [README](https://github.com/brianpetro/obsidian-smart-connections)
- 做什么：本地 embedding 索引全库 → Connections 侧栏实时显示"与当前笔记语义相关的笔记"，可**拖拽生成链接**；Lookup 视图做全库语义搜索；可把一组结果打包成"Smart Context"喂给任意 chat。
- 怎么做：v4 自带本地 embedding 模型，零配置、无需 API key、离线可用；Pro 插件提供 inline 建议、Bases 集成、排序调参。官方明确警告：**语义查询不是关键词查询，含精确关键词的笔记可能不返回**。
- 局限：只做发现，**不写回、不自动打标、不生成摘要**；相似度噪音是已知问题（[issue #1287 irrelevant connections](https://github.com/brianpetro/obsidian-smart-connections/issues/1287)）；首次索引有成本（5k 笔记 20–30 分钟，索引在 `.obsidian/` 内，之后增量）；核心 source-available + Pro 订阅分层，2026 有定价/许可争议（[discussion #1294](https://github.com/brianpetro/obsidian-smart-connections/discussions/1294)）。

**Copilot for Obsidian（最成熟的"聊天/agent"层）** — [README](https://github.com/logancyang/obsidian-copilot)
- 做什么：Vault QA（基于全库的问答）、Quick Chat/Quick Ask（选区级问答）、Agent 模式（多轮、可创建/编辑文件）、Projects/Skills/Commands（可复用 prompt 与技能）、本地 Miyo 搜索。
- 怎么做：V4 把 opencode / Claude Code / Codex 作为 agent 后端拉进 Obsidian；模型可走托管（付费）、BYOK（key 存 Obsidian Keychain，不写进 vault 的 data.json）、或本地（Ollama/LM Studio，需 `OLLAMA_ORIGINS=app://obsidian.md*` 绕 CORS）。
- 局限：Agent 是**桌面专属**（依赖本地进程）；托管模式请求会发到 Brevilabs 后端，前端开源但托管后端闭源；多 agent 需付费 Plus。

**Smart Composer（编辑器内的 Cursor 式体验）** — [README](https://github.com/glowingjade/obsidian-smart-composer)
- 做什么：`@file` 精确指定上下文、`@Vault`/Cmd+Shift+Enter 触发 RAG、Apply-Edit 一键采纳改写、网站/图片/YouTube 字幕作上下文、支持 MCP。
- 局限（重要）：作者已声明**不再积极开发、单开发者维护**；订阅式 OAuth 登录有**封号风险**（README 自带警告），并建议只用于个人交互、不要自动化。Provider 覆盖 OpenAI/Anthropic/Gemini/Groq/DeepSeek/OpenRouter/Ollama/LM Studio 等。

**Text Generator（模板驱动的生成器）** — [README](https://github.com/nhaouari/obsidian-textgenerator-plugin)
- 做什么：把"摘要/标题/大纲/扩写"做成可复用模板（含社区模板库），provider 通过 frontmatter 配置（含本地/多家云）。
- 定位：**生成**而非检索；适合批量生成元数据/摘要的模板化流水线。

**纯本地栈（Ollama 系）** — [obsidian-ollama](https://github.com/hinterdupfinger/obsidian-ollama)
- 最轻的实现：选区/整篇 → 预设 prompt（摘要/解释/改写/列点）→ 结果插回光标处，连 Ollama `localhost:11434`。更完整的路线是把 Ollama 作为 Copilot/Smart Composer 的 OpenAI 兼容端点，并用 `nomic-embed-text` / `mxbai-embed-large` 做离线 embedding。

**Khoj（跨端自托管第二大脑）** — [README](https://github.com/khoj-ai/khoj)
- 做什么：自托管服务索引 md/PDF/Notion/Word/org-mode，可从 Obsidian、浏览器、桌面、手机、WhatsApp 访问，支持自定义 agent。
- 局限：Docker/服务器级运维成本，比进程内插件重得多；适合"vault 只是知识源之一"的场景。

**自动打标 / 收件箱自动化层**（本节最贴近你的需求）
| 工具 | 能力 | 局限 |
|---|---|---|
| [AI Tagger Universe](https://github.com/niehu2018/obsidian-ai-tagger-universe) | 批量给历史笔记补层级标签/写 frontmatter，15+ provider 含 Ollama/LM Studio | 需要你自备分类法，否则标签漂移 |
| [Note Companion](https://github.com/Nexus-JPF/note-companion) | 收件箱自动化：建议文件夹/标签/标题/模板，批量处理原始捕获 | 仅桌面 |
| LLM Tagger / Metadata Auto Classifier | 纯本地 or 规则式打标，轻量 | 能力窄 |
| [Frontmatter Generator](https://github.com/HananoshikaYomaru/Obsidian-Frontmatter-Generator) | **非 AI** 的确定性 frontmatter 批处理 | 只能做规则能表达的事 |

**Agent/MCP 路径（2026 的新主流）**
- [mcp-obsidian](https://github.com/MarkusPfundstein/mcp-obsidian)：通过 Local REST API 暴露 `search / get_file_contents / patch_content / append_content` 等工具（注意它 pin 了 `mcp` 1.x SDK，2.0 会 import 崩溃）。
- [claude-obsidian（AgriciDaniel）](https://github.com/AgriciDaniel/claude-obsidian)：一整套 Claude Code skill，主张保留不可变源文件副本、为每条重要主张维护"来源/新鲜度/支持/矛盾/置信度/复核状态"账本，再建链接页/MOC/Canvas——**这是"证据可追溯"最完整的开源范式**。
- [Basic Memory](https://github.com/basicmachines-co/basic-memory)：把 Markdown 文件本身当 AI 记忆存储，用 MCP 让人与 AI 双向读写同一批文件，观测/链接自然长成图。

**生态层面的共同局限**：①同一 vault 跑上 2–3 个 RAG 插件 = 3 份 embedding 索引，社区反复提醒**别重复索引**（[forum 讨论](https://forum.obsidian.md/t/alternatives-to-smart-connections/108886)）；②AI 自动写链接/打标若无人确认会污染图，社区态度是"建议 + 人确认"；③插件维护风险是结构性的：Dataview 实质停更、DB Folder 2025-07 归档、Periodic Notes 自 2022 未动、Omnivore 2024-11 关停（分别见 [Atlas 调研](https://laoujin.github.io/Atlas/research/2026-04-30-upgrading-a-personal-obsidian-workflow-in-2026/)、[obsidian-db-folder](https://github.com/RafaelGB/obsidian-db-folder)、[omnivore 关停](https://gleamr.io/blog/omnivore-shut-down-alternatives)）。

## 三、从"原始捕获流"到"结构化知识"的常见工作流

### 3.1 捕获：无脑、快速、集中式处理
元原则：**capture first, organize later**——在捕获时分类会杀死创造力（[forum: capture workflows](https://forum.obsidian.md/t/capture-workflows/31085)）。
- 一切进**今天的日记（daily note）**或单一 `Inbox/`，形式是带时间戳的 bullet；[QuickAdd](https://github.com/chhoumann/quickadd) 的 Capture 是最小原语（全局热键 → 追加到日记指定标题下）。
- 2026 官方补齐了移动侧：Mobile 1.11（iOS/Android widget、Quick Settings Tile、Siri、Capture Shortcut）与 1.12（原生 Share extension 不必启动 App 即可写入 vault）（[mobile 1.11.4](https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/)、[mobile 1.12.4](https://obsidian.md/changelog/2026-02-27-mobile-v1.12.4/)）。社区仍吐槽冷启动卡顿（[2026 Report Card: mobile 3.1/5](https://practicalpkm.com/2026-obsidian-report-card/)）；Android 上 Tasker/自建 widget 直接写 md 是常见绕路。
- 网页/文章：官方 Web Clipper 模板化抽取 metadata，**捕获时**用 Interpreter 生成摘要/标签写进 Properties（[Interpreter](https://help.obsidian.md/web-clipper/interpreter)）。

### 3.2 整理：周期 review 是核心机制
- **日→周→月→季→年** 分层 roll-up，每层汇总下一层；周 review 几乎总用 Dataview/Bases 查询把"未处理的 fleeting notes / 未完成任务"自动翻出来，园丁只做分诊而不是重新发现（[periodic review workflow](https://forum.obsidian.md/t/my-workflow-for-periodic-weekly-monthly-quarterly-yearly-reviews-in-obsidian/23310)、[daily/weekly review + dataview](https://forum.obsidian.md/t/daily-and-weekly-reviews-dataview/17021)）。
- 分诊动作固定化：Advanced URI + Templater + 热键（archive / trash / move），处理完自动开下一条（[forum](https://forum.obsidian.md/t/quick-capture-mac-ios-and-inbox-processing/21808)）；[Inbox Organiser](https://www.obsidianstats.com/plugins/inbox-organiser) 周期提醒你别攒着。
- **花园打理（garden tending）**：Matuschak 的 evergreen 迭代（原子、概念导向、持续重写）+ Maggie Appleton 的渐进式摘要循环（重读 → 加粗 → 拆分 → 再链接）（[Evergreen notes](https://notes.andymatuschak.org/Evergreen_notes)、[Maggie Appleton](https://maggieappleton.com/evergreens)）。
- 卫生检查：Find Orphaned Files & Broken Links（孤儿笔记/断链报告）、Broken Links、Nuke Orphans；2k 笔记后靠 AutoMOC/Backlink Cache 续命。

### 3.3 AI 在这条流水线里的实际位置
1. **捕获时**：Interpreter 生成摘要/标签/作者等字段（写进 frontmatter）。
2. **入库后批量补账**：AI Tagger Universe 对全库跑 tag/元数据回填（有人用本地 Gemma 3 12B + LM Studio 一小时补完数百篇，[MakeUseOf](https://www.makeuseof.com/letting-local-llm-organize-obsidian-notes/)）。
3. **收件箱自动分诊**：Note Companion 给原始捕获建议文件夹/标签/标题。
4. **写作/阅读时**：Smart Connections 侧栏推相关笔记，人**拖拽**决定是否成链（AI 提议、人确认）。
5. **问答/归纳**：Copilot Vault QA、Smart Composer `@Vault` 做跨笔记综合。
6. **周期性摘要**：[obsidian-ai-summary](https://github.com/irbull/obsidian-ai-summary) 走一遍所有链接、给每条链接出一段摘要——正好是"周报/月报"的机器版。
7. Agent 直写：Claude Code + MCP/CLI 直接创建链接页与 MOC（claude-obsidian 那套 loop）。

**注意**：生态里几乎没有人让 LLM **自动写 wikilink 到正文**并长期放任——主流是"发现问题（embedding）+ 人决定连接"。

## 四、存储与检索的技术选择

### 4.1 格式
Markdown + YAML frontmatter 是事实标准，好处是**可移植 + 可被 agent 当代码库读写**。要落地就守两条纪律：
1. **一个属性名一个类型（全库）**，`tags/aliases/cssclasses` 用复数列表；
2. 设计一个**必需的 `type` 判别字段**（`daily` / `note` / `source` / `project`…），再配 `status`（seedling/growing/evergreen/archived）、`source`、`created/updated`（ISO 8601）等，全部先声明在一篇 master-schema 笔记里。Bases 只认 YAML，不认 inline `key:: value`。

### 4.2 版本化与同步
[obsidian-git](https://github.com/Vinzent03/obsidian-git) 提供自动 commit-and-sync、diff、history，`git log/blame` 让"这条记忆何时写入"可审计（对"AI 自动写入的记忆"尤其有价值）。**官方 README 自述移动端"highly unstable"**，多设备并发写小文件也易冲突——所以 git 适合当**服务端归档/审计层**，不适合放在"手机→服务器"的热路径上。

### 4.3 检索：BM25 够不够？
关键事实与实测：
- **Obsidian 官方 Search** 是布尔/短语/正则/属性操作符的**过滤式**检索，文档未承诺任何相关性排序（[官方 Search 文档](https://help.obsidian.md/plugins/search)）——大库噪音大。
- **[Omnisearch](https://github.com/scambier/obsidian-omnisearch)** 用 [MiniSearch](https://github.com/lucaong/minisearch) 做 **BM25** 排序（词频 + 文件名 + 标题加权），纯本地、抗拼写错误、可配本地 HTTP server 供外部查询；README 明确说**中文支持需要额外插件（cm-chs-patch）**——生态自己承认 CJK 分词是短板。
- **一份 16,894 个 md 文件的实测**（[Building a Hybrid Retriever for 16,894 Obsidian Files](https://blakecrosley.com/blog/hybrid-retriever-obsidian)）：纯 grep 11–66 秒且结果相关性差；纯向量**漏精确标识符**；**FTS5 BM25 + Model2Vec 向量 + RRF 融合 = 23ms 端到端**，49,746 chunks / 83MB 单 SQLite 文件，零 API 调用，全量重建 4 分钟、增量 <10 秒。作者给的最小可行路径：**先只用 FTS5(BM25) 一张表，等关键词开始漏语义匹配再加 sqlite-vec，最后加 RRF 融合**；并指出"标签好的浅内容会压过结构深的深内容"这种 BM25 偏置。
- **反向经验**（个人尺度论）：<1000 文件时 FTS5+BM25 命中率 >90%，理由是**自己写的记忆自己会用同样的词去搜**，语义鸿沟基本不存在；向量库是在解决不存在的问题；到 10k+ 排序才开始变噪（[Why I Replaced My AI Agent's Vector Database With grep](https://dev.to/kuro_agent/why-i-replaced-my-ai-agents-vector-database-with-grep-59mm)）。另一侧的反驳是 grep-only 太烧 token（[Milvus](https://milvus.io/zh/blog/why-im-against-claude-codes-grep-only-retrieval-it-just-burns-too-many-tokens.md)）。
- **中文的硬坑（对你尤其重要）**：SQLite FTS5 的 `unicode61` tokenizer 把**连续汉字当成一个 token**，导致"消耗""索引优化"这类子串查询**必然 0 命中**；`trigram` tokenizer 能救 ≥3 字符的查询，但 1–2 字中文（最高频形态）仍需 `LIKE '%词%'` 回退（[机制与可复现验证](https://deepseek.csdn.net/6a8779bc10ee7a33f29d4c3c.html)、[SQLite FTS5 trigram 官方文档](https://www.sqlite.org/fts5.html#the_trigram_tokenizer)）。

**结论**：个人规模（≤1 万篇 / 年增几万条通知）**不需要向量数据库**。起步用 SQLite FTS5（中文必须 trigram 或自定义分词 + 短查询 LIKE 回退，或直接用 Tantivy/Meilisearch 这类带 CJK 分词的引擎）；元数据过滤（app/发件人/时间/类型）往往比排序算法更能提升可用性。**只有**"跨事件语义归纳""我记得概念但想不起关键词""多语言混排"这三类需求出现时，才上 hybrid + RRF（嵌入式 sqlite-vec，不必独立向量库）。embedding 侧最省的做法是本地小模型（Model2Vec/nomic-embed-text），别让索引依赖云端。

## 五、对「手机通知流 → 自动整理的私人知识库」：可借鉴 vs 不该照搬

### 5.1 值得借鉴
1. **捕获与整理彻底分离 + 固定 cadence**：手机只做 append（幂等、带 id、去重），LLM 批处理在服务端按点跑（日/周）。生态的 "daily note 是收件箱 + 周 review 分诊" 直接对应你的"通知流 + 周期性整理"。
2. **元数据是承重结构**：`source`(app/短信)、`thread_id`/`sender`、`ts`、`type` 判别字段、`status`/`review_state`、`confidence`。Bases 的教训是"先把 schema 定死，再让所有自动化往里写"。
3. **确定性结构 + LLM 只碰内容层**：先按 `(app, 会话, 时间窗)` 聚合成"会话段/事件"笔记（确定性、可重放、可重建），再让 LLM 产出 summary / tags / entities / 待办。**不要让 LLM 决定文件放哪、要不要新建笔记**（这类决策错了会污染全库且难回滚）。
4. **两层链接**：确定性键链接（thread_id、联系人、订单号、商户）由代码生成；语义相关链接走"embedding 建议 + 阈值 + 人工确认"，抄 Smart Connections 的 drag-to-link 交互（而不是自动写入正文）。
5. **证据账本思路**（抄 claude-obsidian）：每条提炼出的结论保留源指针（哪几条原始通知/短信），以及新鲜度/支持/矛盾标记。通知类数据经常"后来被推翻"（快递改期、账单变动），带矛盾标记的库才可信。
6. **MOC/主题页 + 视图代替全局图**：做"本月账单""快递流转""某人的所有约定"这类 Bases/Dataview 视图；**不要做全局图**（>200 笔记即不可导航）。
7. **本地优先 + git 归档**：文件即真相、`git blame` 可审计 AI 写了什么；手机端别直接跑 git。
8. **检索起步就轻**：SQLite FTS5（中文配 trigram/LIKE 回退或换带分词的引擎）+ 元数据过滤；向量作为第二期可选层。个人尺度下这几乎肯定是性价比最优解。
9. **高价值闸门**：通知流里长期有价值的是少数（订单/账单/预约/地址/联系人约定/验证码之外的事务信息）。参考 AI Tagger Universe/Note Companion 的做法，先分类 → 大部分丢弃或只留计数，**只把通过闸门的内容建成笔记**。

### 5.2 不该照搬 / 要警惕
1. **别把"原子笔记"教条搬到通知流**：通知本身是碎片且大多数无长期价值；"每条捕获都原子化并挂进 MOC"在通知场景会制造噪音工厂。颗粒度应定在 **事件/会话**级别，而不是单条消息。
2. **别做全局知识图谱可视化**：生态共识已是否定票；ROI 极低。
3. **别全自动打标/自动加链**：社区共识是"AI 建议、人确认"；自动写入会造成图污染与标签漂移，且事后清理成本高于人工确认成本。
4. **别重复建索引**：一个 chat/RAG 层 + 一个发现层足够；三套 embedding 索引是社区反复踩的坑。
5. **别把 AI 插件当架构**：Obsidian 生态本身就是反例——Dataview 停滞、DB Folder 归档、Periodic Notes 三年未动、Omnivore 直接关停。你的系统应该直连文件系统/MCP/CLI，AI 只是调用者，插件/框架是可替换件。
6. **隐私与合规是硬闸**：OTP/2FA、银行、身份证、医疗类通知不该进知识库（这也符合你自己工作区"凭据不入库"的约定）；云端托管模式（Copilot 托管、部分免费 API）会把 prompt+context 发出去，本地 Ollama 才谈得上"不出本机"。
7. **移动端同步不做热路径**：obsidian-git 官方自述移动端高度不稳定；同步/冲突解决应放在服务端。

## 六、明确的不确定项
- 本次大量结构信息来自一份 2026-04 的 AI 生成调研（[Laoujin/Atlas](https://laoujin.github.io/Atlas/research/2026-04-30-upgrading-a-personal-obsidian-workflow-in-2026/)），其 frontmatter 标注 `model: Opus 4.7`、300 条引用。我抽查核对了 Bases 1.9（官方 changelog 属实）、Interpreter（官方文档属实）、Omnisearch=BM25（README 属实）、obsidian-git 移动端不稳（官方 README 属实）；但其中**星标数、定价、版本日期、个别"社区共识"表述未逐条复核**。
- Copilot / Smart Connections 的定价与许可条款变动频繁，以官方页面为准。
- "图在 ~200 笔记后失效"属社区共识类判断，非严格测量；"~6k 笔记不可用"来自个人博客单点实测。
- 本地小模型做中文打标的实际质量、以及"16,894 文件 23ms"这类数字，我没有独立复现，属于引用单一来源。
- 截至 2026-09 检索，Obsidian 官方 AI 能力只有 Web Clipper Interpreter 一处；不排除有未被我检索到的官方 AI 功能。
