# RAG 项目结构与模块职责

> 以本项目为样本，讲清"一个 RAG 项目由哪些模块组成、每块具体干什么、依赖怎么走"。
> 适合：准备自己搭一个 RAG 项目，或需要向别人讲清现有系统结构。

---

## 1. 一句话结构

这个项目**不是按技术分层（controller / service / mapper）切的**，而是按"一次请求里谁负责哪一段"切的。所以你会看到 `llm/service`、`llm/route`、`llm/domain` 这种按职责命名的包，而不是一个巨大的 `service` 包。

判断分包好坏的标准很简单：**改一个需求，要动几个包？** 换模型只动 `llm/chat`，换向量库只动 `llm/domain`，换输出格式只动 `llm/answer`。

---

## 2. 目录树

```
com.syx.demo
├── DemoApplication.java          启动类
│
├── llm/                          ★ 正式接入层，全部业务在这里
│   ├── web/                      接口层
│   │   ├── ClinicalAssistantController   /api/assistant/*
│   │   └── AskRequest                    入参 DTO
│   ├── service/
│   │   └── ClinicalAssistantService      编排：prepare → 调模型 → buildAnswer
│   ├── route/                    意图与模型路由
│   │   ├── IntentClassifier              规则分类器（关键词加权）
│   │   ├── QuestionIntent                意图枚举
│   │   ├── IntentSignal                  分类结果（意图+命中词+置信度）
│   │   ├── RoutingStrategy               路由策略接口
│   │   └── CapabilityRoutingStrategy     按能力挑模型
│   ├── domain/                   数据域与检索
│   │   ├── KnowledgeDomain               PUBLIC / PATIENT
│   │   ├── VectorStoreConfiguration      两个索引、两个前缀、元数据字段
│   │   ├── DomainAwareRetriever          授权判定 + 检索 + 三层复核
│   │   ├── PatientIdExtractor            从问题里抓患者编号
│   │   ├── RetrievalResult               检索结果（片段 + 域 + 备注）
│   │   └── RetrievedSnippet              单个片段
│   ├── prompt/
│   │   └── PromptTemplateService         读 .st 模板，拼系统提示词
│   ├── chat/                     模型调用门面
│   │   ├── ChatFacade                    路由→熔断→重试→降级，同步/流式
│   │   ├── ChatRequest / ChatResult      调用入参 / 结果
│   │   ├── ChatStreamEvent / ChatStreamMeta  流式事件
│   │   └── ModelAttempt                  单次尝试记录
│   ├── guard/                    熔断
│   │   ├── ModelCallGuard                接口
│   │   ├── SentinelModelCallGuard        Sentinel 实现
│   │   ├── SimpleSlidingWindowGuard      简化版：滑动窗口
│   │   └── ModelCallGuardConfiguration   auto 选后端
│   ├── model/                    模型注册表
│   │   ├── ModelRegistry                 启动时建表，缺 key 跳过
│   │   ├── ModelDefinition               一条模型配置
│   │   └── ModelCapability               GENERAL / REASONING / FAST
│   ├── session/                  多轮会话
│   │   ├── RedisConversationStore        Redis 读写 + 裁剪 + 降级
│   │   ├── ConversationSession           会话状态
│   │   └── ChatTurn                      一轮问答
│   ├── answer/                   结构化输出
│   │   ├── RagAnswer                     最终答案（含引用、降级标记）
│   │   ├── AnswerMetaParser              解析模型输出的 [META] 行
│   │   └── AssistantStreamEvent          SSE 事件（meta/delta/done）
│   └── config/
│       ├── LlmProperties                 app.llm.* 全部配置绑定
│       └── LlmConfiguration
│
├── controller/                   知识库后台 + 早期 demo
│   ├── KnowledgeController               /api/knowledge/*（CRUD、rebuild、stats）
│   ├── AIController                      早期 demo：/api/ai/chat
│   ├── ChatController                    早期 demo：/api/chat/*
│   └── EmergencyRagController            早期 demo：/api/emergency/ask
├── service/
│   ├── KnowledgeService                  MySQL 知识表读写
│   └── KnowledgeVectorService            构建/重建向量索引
├── entity/  MedicalKnowledge、PatientKnowledge
├── mapper/  MyBatis-Plus Mapper
├── config/  MultiModelConfig、WebConfig、RedisPreflightChecker
└── tool/    WeatherTool（@Tool 示例）
```

`controller/` 里除 `KnowledgeController` 外，其余三个是学习阶段的 demo，**保留价值是"最小可运行示例"和回归对照**，不是生产代码。

---

## 3. 五层职责

| 层 | 包 | 职责 | 这一层要换，动哪里 |
|---|---|---|---|
| 接口层 | `llm/web` | 收 HTTP、校验参数、把 `Flux` 包成 SSE | 换协议只动这里 |
| 编排层 | `llm/service` | 决定九步顺序，唯一知道全流程的类 | 换流程只动这一个类 |
| 能力层 | `route` / `domain` / `prompt` / `session` | 四件独立的事：判意图、找资料、写纸条、记上下文 | 各自独立替换 |
| 治理层 | `chat` / `guard` / `model` | 路由、熔断、重试、降级 | 换模型厂商只动这三个包 |
| 基础设施 | `service` / `entity` / `mapper` / `config` | MySQL、Redis、Bean 装配、启动自检 | — |

依赖只向下。**下层绝不能反过来 import 上层**——这是判断分包有没有腐化的第一指标。

---

## 4. 模块逐个说

### 4.1 `llm/web` —— 门

- 解决什么问题：把 HTTP 请求变成 Java 对象，把流式结果变成 SSE。
- 关键类：`ClinicalAssistantController`（`/ask`、`/ask/stream`、`/models`、`/health`、`/domains`、`/session/{id}`）。
- 不该做的事：业务判断、检索、拼 Prompt。Controller 只做参数转换和协议封装。
- 边界的证据：这个 Controller 有 291 行，其中绝大部分是 SSE 生命周期管理（`onCompletion`/`onTimeout`/`onError` 三处必须 `dispose`），业务只有几行委托。

### 4.2 `llm/service` —— 大脑

- 解决什么问题：把"安全判断、检索、提示词、模型调用、结果校验"按正确顺序串起来。
- 关键方法：`ask()`（同步）、`askStream()`（流式）、`prepare()`（调用前的全部判断）、`buildAnswer()`（调用后的全部收尾）。
- 设计要点：**同步和流式共用 `prepare` 与 `buildAnswer`**。如果两条路径各写一遍，迟早会出现"流式拒答了、同步没拒答"这种安全漏洞。
- 不该做的事：直接 `new` 一个 HTTP 客户端、直接拼 SQL。

### 4.3 `llm/route` —— 分诊台

- 解决什么问题：这句话属于哪一类？该用哪个模型？
- 两个职责要分清：
  - `IntentClassifier` 管**业务语义**（是不是用药问题、要不要患者资料）；
  - `CapabilityRoutingStrategy` 管**技术选型**（用推理模型还是通用模型）。
- 为什么用规则不用 LLM：延迟低、成本零、结果可预测、出错能解释。
- 扩展点：`RoutingStrategy` 是接口，想要更聪明的策略就实现它并标 `@Primary`。

### 4.4 `llm/domain` —— 带门禁的档案室

- 解决什么问题：只把"这个人有权看"的资料取出来。
- 三个类各管一段：
  - `VectorStoreConfiguration`：建两个索引、两套 key 前缀（**物理隔离**）；
  - `DomainAwareRetriever`：授权判定、过滤表达式、结果复核（**三层防御**）；
  - `PatientIdExtractor`：把问题里的 `P-1001` 抓出来，让检索收敛到这个人。
- 最容易写错的地方：把"参数为空"当成"不过滤"（fail-open）。正确做法是当成"没权限"（fail-closed）。
- 这一层的代码注释是全项目最详细的，因为每个坑都踩过：TAG 转义、索引字段声明、过滤器不生效。

### 4.5 `llm/prompt` —— 给模型的纸条

- 解决什么问题：把"角色、资料、格式要求、免责声明"拼成一段结构化的系统提示词。
- 模板放在 `resources/prompts/*.st`：`system_role`、`rag_context`、`output_constraint`、`clarify`、`no_evidence`。
- 关键设计：资料块带编号 `[1] [2]`，这样收尾阶段才能校验"模型引用的编号存不存在"。
- 类比：就是 Thymeleaf 渲染，只不过渲染结果是给模型的输入。

### 4.6 `llm/chat` —— 调度室

- 解决什么问题：把"调一次模型"这件事封装成可靠的操作。
- 一次调用的完整动作：选模型 → 查熔断 → 调（带重试）→ 失败换下一个 → 记录每次尝试。
- 为什么需要门面：不同厂商 SDK 的异常类型、超时语义、流式协议都不一样，业务层不应该知道这些差异。
- 关键坑：M7 的 `RetryTemplate` 默认重试 10 次（最高 180 秒），必须显式注入 `maxAttempts(1)`，否则外层的降级永远轮不到。

### 4.7 `llm/guard` —— 保险丝

- 解决什么问题：某个模型连续失败时，别再把请求打过去（既慢又浪费）。
- 三态：关闭 → 打开（直接拒绝）→ 半开（放一个请求试探）。
- 双后端：有 Sentinel 就用 Sentinel，没有就用自写的滑动窗口。用 `@ConditionalOnClass` 自动选。
- 流式下必须用 `asyncEntry`（回调在别的线程），且"用户主动取消"不能记失败，否则点几次停止就误熔断。

### 4.8 `llm/model` —— 花名册

- 解决什么问题：模型清单由配置决定，加模型不改代码。
- `ModelRegistry` 在 `@PostConstruct` 建表；缺 `api-key` 或 `base-url` 的条目**跳过并记日志**，而不是让应用起不来。
- 这是"配置驱动"最典型的落地：`qwen-med` 是个占位条目，配上环境变量就自动生效。

### 4.9 `llm/session` —— 记忆

- 解决什么问题：多轮对话要带上下文，但模型本身不记。
- 存什么：最近 N 轮问答 + 最近意图 + 数据域授权状态。
- 为什么放 Redis：多实例共享、TTL 自动过期、挂了还能退化（`loadOrCreate` 失败就当新会话，不影响本次回答）。
- 注意裁剪：`history-turns` 控制送模型的轮数，`max-stored-messages` 控制 Redis 里留多少——这两个值直接影响成本和延迟。

### 4.10 `llm/answer` —— 信封

- 解决什么问题：模型返回的是纯文本，业务需要的是结构化对象。
- 三件事：
  - `AnswerMetaParser`：解析模型额外输出的 `[META]` 行（置信度、引用编号）；
  - `RagAnswer`：统一出参（答案、引用、用哪个模型、是否降级、耗时）；
  - `AssistantStreamEvent`：把同一个结果拆成 `meta` / `delta` / `done` 三种 SSE 事件。
- 设计要点：`meta` 和 `RagAnswer` 的字段刻意对齐，前端用一套模板就能同时渲染"流式中间态"和"完成态"。

### 4.11 `service` + `controller` —— 入库线

- 解决什么问题：知识从 MySQL 进到向量库。
- 链路：`KnowledgeController` → `KnowledgeService`（MySQL CRUD）→ `KnowledgeVectorService`（切分、算向量、写 Redis）。
- `/rebuild` 是整库重建；增删改接口内置增量重建。
- **重要**：向量库不是数据库的从库，它不会自动同步。改完 MySQL 必须 rebuild，否则问答用的还是旧资料。

### 4.12 `config` —— 水电

- `MultiModelConfig`：手动定义 `ChatModel` Bean（早期做法，`@Qualifier` 区分）。
- `RedisPreflightChecker`：启动前自检 Redis 可达性，把"连不上"变成一条人能看懂的启动日志。
- `WebConfig`：CORS，给前端 5173 用。

---

## 5. 一次请求跨了哪些模块：数据形态链

这是理解模块边界最快的方式——看数据在每一跳变成了什么类型。

| 跳 | 模块 | 输入 | 输出 |
|---|---|---|---|
| 1 | `web` | HTTP JSON | `AskRequest` |
| 2 | `session` | `sessionId` | `ConversationSession` |
| 3 | `route` | `String question` | `IntentSignal` |
| 4 | `domain` | 问题 + 域集合 + 租户 | `RetrievalResult`（`List<RetrievedSnippet>`） |
| 5 | `prompt` | 意图 + 片段 + 域描述 | `String systemPrompt` |
| 6 | `chat` | `ChatRequest` | `ChatResult` / `Flux<ChatStreamEvent>` |
| 7 | `answer` | 原始文本 + 元信息 | `RagAnswer` |
| 8 | `web` | `AssistantStreamEvent` | SSE 文本帧 |

**判断边界有没有划对，就看这条链**：如果某一跳的输入需要它同时拿到上一跳和上上跳的东西，说明中间那层没把数据封装好。

---

## 6. 通用 RAG 项目必备的 8 个模块

框架无关——Spring AI、LangChain、LlamaIndex 或手写，都逃不掉：

| # | 模块 | 职责 | 缺了会怎样 |
|---|---|---|---|
| 1 | 接入层 | 对外 API、流式输出 | 没有入口 |
| 2 | 编排层 | 决定流程顺序 | 逻辑散在 Controller，改一处崩三处 |
| 3 | 检索层 | embedding + 向量库 + 过滤 | 等于没用 RAG |
| 4 | 提示词层 | 角色、资料、格式约束 | 模型自由发挥，格式不可控 |
| 5 | 生成层 | 模型调用的统一封装 | 厂商差异污染业务代码 |
| 6 | 治理层 | 重试、熔断、降级、限流 | 模型一抖整条链路 500 |
| 7 | 会话层 | 多轮上下文 | 追问失效 |
| 8 | 知识入库层 | ETL、切分、重建索引 | 知识更新靠手写脚本 |

横切关注点（不在上面 8 个里，但生产必须有）：

- **权限与数据隔离**：多租户、患者隐私、行级权限。
- **可观测性**：健康检查、每步耗时、检索命中情况、模型调用记录。
- **评测**：一套问题 + 期望答案，改 Prompt 或换模型后跑一遍。**这是 demo 和产品的分界线**。

---

## 7. 从零搭建的顺序

这个项目自己就是这么长出来的（早期三个 demo Controller 就是第 1、3 步的产物）：

| 步骤 | 做什么 | 验收 |
|---|---|---|
| 1 | Controller → `ChatClient` 直调模型 | 能回答"你好" |
| 2 | MySQL → 切分 → embedding → 写入向量库 | `/rebuild` 后索引里有数据 |
| 3 | 挂 `QuestionAnswerAdvisor` 做检索增强 | 回答里出现资料内容 |
| 4 | 需要过滤/复核时，换成自己的 Retriever | 过滤生效，越权查不到 |
| 5 | 加会话 + Prompt 模板 + 结构化输出 | 多轮能追问，出参有引用 |
| 6 | 加治理（重试/降级/熔断）和权限 | 断网/改错 key 时优雅降级 |

**每一步都要能独立跑通再往下走。** 否则出问题时你分不清是检索的锅、Prompt 的锅，还是模型的锅——这是新手做 RAG 最常见的困境。

---

## 8. 五个常见的分包错误

1. **按技术分层切**（`controller` / `service` / `util` 三件套）→ 业务逻辑散落，改一个功能翻五个包。
   → 正确做法：先按"请求里的职责段"分，每段内部再谈技术分层。
2. **Controller 里写编排** → 同步和流式各写一遍，行为必然不一致。
3. **把重试写在 Controller** → 换了调用方（定时任务、MQ）就没有重试了。
4. **工具类里藏业务规则**（比如把"这个租户能不能看"写进 `StringUtils`）→ 权限逻辑不可审计。
5. **一个 Service 既管入库又管问答** → 入库慢查询会拖垮在线问答，无法独立扩容。

---

## 9. 自测

1. 为什么 `prepare()` 和 `buildAnswer()` 要被同步、流式两条路径共用？
2. 换一个大模型厂商，需要改哪几个包？为什么不用改 `llm/service`？
3. 知识库新增一条资料，为什么接口要顺带触发向量库重建？
4. `llm/domain` 为什么不能直接依赖 `llm/answer`？
5. 如果你要加一个"回答满意度打分"的功能，应该加在哪一层？

<details>
<summary>参考答案</summary>

1. 这两段包含安全边界与输出契约；各写一遍必然出现行为分叉（比如流式漏了拒答）。
2. 只改 `llm/chat`（调用实现）、`llm/model`（注册条目）、`app.llm.models` 配置；`llm/service` 面对的是 `ChatFacade` 抽象，不需要知道厂商。
3. 向量库不会自动同步 MySQL，它是离线构建的索引；不重建则检索到的还是旧内容。
4. 依赖只能向下：`domain` 是能力层，`answer` 是输出层；让检索知道答案长什么样会造成循环依赖和职责混乱。
5. 横切能力，放 `llm/answer` 之后的新模块（如 `feedback`），由编排层在收尾后调用；不要塞进 `chat` 或 `domain`。

</details>
