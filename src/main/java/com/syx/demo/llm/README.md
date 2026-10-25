# `com.syx.demo.llm` —— LLM 接入层

临床助手的 AI 接入层。一句话概括：**把"医生问一句话"变成"结构化、带引用、有数据域约束、模型挂了也不崩"的回答**。

这个包不碰业务实体（患者、医嘱那些走 JPA / MyBatis-Plus），它只负责 AI 相关的六件事：**意图识别 → 数据域裁决 → 知识检索 → Prompt 组装 → 模型调用与容错 → 结果结构化**。

---

## 1. 三条设计目标

| 目标 | 含义 | 主要落点 |
|---|---|---|
| **不泄露** | 绝不把其他机构的患者数据拿给模型看。授权缺失 = 无权限，不是"跳过过滤" | `domain` 包（物理隔离 + 索引过滤 + Java 复核） |
| **不挂死** | 任何一个模型商超时、限流、欠费，回答仍然要出来 | `chat` + `guard` 包（路由 → 熔断 → 重试 → 降级） |
| **可换模型** | 换服务商、加院内私有化模型，只改 YAML，不改 Java | `model` 包（配置驱动注册表） |

三条里最容易做错的是第一条：**"过滤条件写漏一次"就是一次数据泄漏事故**，所以隔离做成了三层递进，最外层是物理隔离（两个 Redis 索引），而不是靠查询时加 `WHERE`。

---

## 2. 包结构（10 个子包 / 37 个源文件 / 56 个类型）

```
com.syx.demo.llm
├── web       对外 REST 接口（业务 + 运维）
├── service   编排层：把六件事串成一条链路  ← 想看懂调用顺序从这里开始
├── answer    出参结构 + 模型输出的 [META] 协议解析
├── domain    数据域隔离与检索（安全边界所在）
├── prompt    Prompt 模板加载与变量替换
├── route     意图识别 + 能力路由
├── chat      模型调用门面（唯一出口）
├── guard     熔断护栏（Sentinel / 自研滑动窗口双实现）
├── model     模型注册表（配置驱动的实例构建）
├── session   多轮会话上下文（Redis）
└── config    配置绑定（LlmProperties 集中定义所有可调项）
```

| 子包 | 一句话职责 | 关键类 |
|---|---|---|
| `web` | 医生调 `/ask`，运维调 `/models`、`/health`、`/domains` | `ClinicalAssistantController` |
| `service` | 编排全链路、执行三条数据域规则、组装 prompt | `ClinicalAssistantService` |
| `answer` | 结构化回答载体；解析模型输出、剥 `[META]` 行、补免责声明 | `RagAnswer`、`AnswerMetaParser` |
| `domain` | 数据域判定落到检索上；fail-closed 的唯一实现点 | `DomainAwareRetriever`、`VectorStoreConfiguration` |
| `prompt` | 从 `classpath:prompts/*.st` 加载模板并替换变量 | `PromptTemplateService` |
| `route` | 用规则（不额外调模型）判意图，再按"能力"选模型 | `IntentClassifier`、`CapabilityRoutingStrategy` |
| `chat` | 路由 → 熔断 → 重试 → 降级的唯一入口 | `ChatFacade` |
| `guard` | 决定"还要不要调某个模型" | `ModelCallGuard` 接口 + 两个实现 |
| `model` | 把 `app.llm.models.*` 配置变成可用的 Spring AI 对象 | `ModelRegistry` |
| `session` | 多轮上下文存取（Redis 挂了自动退化成"无记忆"） | `RedisConversationStore` |
| `config` | 所有可配置项的类型化绑定 | `LlmProperties` |

---

## 3. 一次问答的完整链路

入口：`POST /api/assistant/ask` → `ClinicalAssistantController.ask` → `ClinicalAssistantService.ask`

```
① 载入会话           RedisConversationStore.loadOrCreate（Redis 不可用 → 无记忆，不影响本轮）
② 意图判定           IntentClassifier.classify（规则匹配 + 追问继承）
③ 澄清分支           仅当"通用问答 + 已有上文 + 输入 ≤ 8 字"才触发，多一轮往返的成本要省着花
④ 数据域判定         见 §4 三条规则；未授权 → 不检索、不调模型、直接拒答
④b 患者标识抽取       PatientIdExtractor.extractSingle → 检索收敛到该 patientId
⑤ 数据域感知检索      DomainAwareRetriever.retrieve（三层隔离，见 §5）
⑥ 患者数据缺失判定    点名了患者却查不到 PATIENT 片段 → 拒答（而不是拿通用指南顶上）
⑦ Prompt 组装        PromptTemplateService.buildSystemPrompt（角色 + 意图提示 + 资料块 + 域说明）
⑧ 模型调用           ChatFacade.call（路由 → 熔断检查 → 重试 → 降级 → 全败抛 LlmInvocationException）
⑨ 结果结构化          AnswerMetaParser：剥 [META] 行、解析置信度、校验引用编号、补免责声明
⑩ 落库会话           appendTurn（失败也要记，否则下一轮追问丢上下文）
```

关键取舍：

- **③ 澄清的条件收得很紧**，否则医生会觉得助手很笨。
- **⑤⑥ 的拒答都不调用模型** —— 没有任何数据被取出来，也就不存在泄露，同时避免模型用通用知识编出一个"像是这个患者的答案"。
- **⑧ 的降级痕迹会一路带到响应里**（`modelId` / `routedModelId` / `degraded` / `attempts`），医生能看见"这条答案其实是备用模型给的"。
- 检索不到资料**不等于**拒答（公共域场景）：仍然调用模型，但 prompt 明确告知"没有资料、不许编具体数值"。

---

## 4. 数据域规则（fail-closed）

| 场景 | 行为 | 理由 |
|---|---|---|
| 问题针对具体患者，患者域**未授权** | 拒答，不检索、不调模型 | 不取数据 = 零暴露面；也避免模型凭常识编造 |
| 患者域已授权，但**本院查不到该患者** | 拒答 | 有依据的通用建议会被误认为"针对这个患者的"，必须明确说没有 |
| 问题里**点名了别的机构的患者** | 确定性拒答 | 检索收敛到 `patientId` 后必然查空 → 落到上一条；比让模型自己判断可靠 |
| 其他情况 | 只检索公共知识域 | 少带数据 = 更小的暴露面 |

总开关 `app.llm.domain.allow-patient-by-default` **生产必须为 `false`**：患者域只能由接口显式授权（`AskRequest.allowPatientDomain`）+ 携带 `tenantId` 才开放。

---

## 5. 三层隔离（`DomainAwareRetriever`）

| 层 | 做法 | 失效时的表现 |
|---|---|---|
| **① 物理隔离** | 公共域 / 患者域各一个 Redis 索引 + 独立 key 前缀（`emergency_public_index` / `emergency_patient_index`）。未授权的域**根本不会被调用** | 配置写错会拿到混合数据，但下面两层仍会拦 |
| **② 索引过滤** | `@domain:{PATIENT} & @tenantId:{HOSP\-A} & @patientId:{P\-1001}` | 表达式抛异常 → 退化为无过滤查询，**不跳过复核** |
| **③ Java 复核** | 逐条核对 `domain` / `tenantId` / `patientId`，不匹配即丢弃并计数 | 正常恒为 0 条；非 0 说明上面两层出问题了 |

三类计数要分清，它们指向完全不同的故障：

| 计数 | 日志级别 | 含义 |
|---|---|---|
| `violations`（越域 / 跨租户） | **ERROR ⚠️** | 隔离层已失效，**安全事故信号**，立刻查索引配置 |
| `scopeMisses`（非目标患者） | WARN | 收敛没在索引层生效（仍在授权机构内，不是安全问题），查过滤表达式 |
| `filterWarningLogged` | WARN 仅一次 | 索引过滤整条不可用，已降级为"检索后 Java 复核" |

> ⚠️ **必读**：Spring AI 1.0.0-M7 的 `RedisFilterExpressionConverter` **不转义 TAG 值**，`HOSP-A` 会被拼成 `@tenantId:{HOSP-A}`，RediSearch 把 `-` 当分隔符 → `Syntax error at offset N near A`；`P-1001` 更阴，**不报错、静默返回 0 条**。所以 `DomainAwareRetriever.escapeTagValue()` 必须保留：ASCII 标点加反斜杠，**中文不能转义**。详见 `~/.workbuddy/skills/spring-ai-redis-vectorstore`。

---

## 6. 配置（`app.llm.*`，全部集中在 `application.yml`）

```yaml
app:
  llm:
    default-model: deepseek-chat        # 路由找不到候选时的兜底

    models:                             # 模型清单：加一段就多一个模型
      deepseek-chat:
        display-name: DeepSeek Chat（主模型）
        provider: OPENAI_COMPATIBLE     # OPENAI_COMPATIBLE | ZHIPU
        base-url: ...                   # ⚠️ 不带 /v1 或 /v4，SDK 内部会拼
        api-key: ...
        model: deepseek-chat
        capability: GENERAL             # REASONING | GENERAL | FAST
        priority: 10                    # 越小越优先
        read-timeout-seconds: 30        # 推理模型给 60
        remark: 常规问答主通道

    fallback:                           # 主备降级链；未配置的自动追加 default-model
      deepseek-reasoner: [deepseek-chat, glm]
      deepseek-chat: [glm]

    routing:
      enabled: true
      reasoning-model: deepseek-reasoner
      general-model: deepseek-chat
      fast-model: ""                    # 留空 = 不启用轻量通道
      reasoning-length-threshold: 80    # 超过这个字数视为推理密集

    breaker:
      backend: auto                     # auto | sentinel | simple
      failure-rate-threshold: 0.5       # 坏调用比例阈值
      min-request-amount: 5             # 样本不足不触发
      stat-interval-ms: 10000           # 统计窗口
      time-window-seconds: 20           # 熔断打开后静默时长，再半开探测
      slow-call-rt-ms: 15000            # 慢调用等同于失败

    retry:
      max-attempts: 2                   # 单模型最多试 2 次（1 = 不重试直接降级）
      backoff-ms: 300                   # 指数退避基数

    session:
      ttl-minutes: 120
      history-turns: 10                 # 送入模型的最近轮数
      max-stored-messages: 40
      key-prefix: "app:llm:"

    rag:
      top-k: 4
      similarity-threshold: 0.5
      max-snippet-chars: 700            # 单条片段注入 prompt 的上限

    domain:
      allow-patient-by-default: false   # ⚠️ 生产必须 false
      domain-field: domain
      tenant-field: tenantId
      patient-field: patientId
      patient-id-pattern: '(?<![A-Za-z0-9])[A-Za-z]{1,4}-[0-9]{2,}(?![0-9])'
```

**缺 `api-key` 或 `base-url` 的模型条目会在启动时被跳过**，跳过原因可以在启动日志或 `GET /api/assistant/models` 的 `skipped` 字段看到（不用翻日志猜"我配了 Qwen 为什么不用"）。

---

## 7. 对外接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/assistant/ask` | 临床问答主入口。入参 `AskRequest`：`question`、`sessionId`、`tenantId`、`allowPatientDomain`、`model` |
| GET | `/api/assistant/models` | 模型注册表 + 降级链 + 被跳过的配置 + 当前熔断后端 |
| GET | `/api/assistant/health` | 各模型熔断状态（`circuitState` / `failureRate` / `blocked`） |
| GET | `/api/assistant/domains` | 数据域定义、索引名、key 前缀、授权策略（验收用） |
| GET | `/api/assistant/session/{id}` | 会话状态：意图历史、轮次、TTL、最近几轮预览（只回预览，不把隐私内容再复制一份到浏览器） |
| DELETE | `/api/assistant/session/{id}` | 清空会话（新对话） |

响应体 `RagAnswer` 里几个字段专门为"可审计"而存在：`refused` / `refuseReason`（拒答理由）、`domainScope`（本轮实际查了哪些域）、`citations`（召回清单，即使模型没引用也返回）、`declaredSources`（模型自己声明的编号，越界会在 `notes` 里告警）、`notes`（过程说明，含隔离层是否退化）、`costMs`。

---

## 8. 常见改动该动哪里

| 我想…… | 改哪里 |
|---|---|
| 加一个模型（如院内 Qwen） | 只改 `application.yml` 的 `app.llm.models` + `fallback`；填上环境变量即生效 |
| 换 embedding 模型 | `spring.ai.openai.embedding.*`（当前走硅基流动 BAAI/bge-m3，1024 维）。⚠️ 换模型必须重建向量库 |
| 加一个数据域（如"科研脱敏域"） | `KnowledgeDomain` 加枚举 + `VectorStoreConfiguration` 加第三个索引 bean + `DomainAwareRetriever` 的 `buildFilter` / 复核分支 |
| 调整拒答话术 | `ClinicalAssistantService` 的 ④ / ⑥ 分支 + `finishRefusal` |
| 改 Prompt 措辞 | `src/main/resources/prompts/*.st`（`system_role` / `rag_context` / `output_constraint` / `clarify` / `no_evidence`） |
| 改熔断参数 | `application.yml` 的 `app.llm.breaker.*`；**不要**为了省事把 `backend` 改成 `simple`（生产用 Sentinel） |
| 换路由策略 | 实现 `RoutingStrategy` 接口并注册为 bean（默认 `CapabilityRoutingStrategy`） |
| 加一层护栏（如内容安全） | 实现 `ModelCallGuard` 或包一层 `ChatFacade` —— **不要绕过 `ChatFacade` 直接调 `ChatModel`**，否则熔断与尝试记录都会失效 |

---

## 9. 排查手册

先看 `GET /api/assistant/health` 和响应里的 `notes`，比翻日志快。

| 现象 | 大概率原因 |
|---|---|
| `所有候选模型均调用失败` | 看响应 `attempts` 每条的错误。`401/403` = key 错；`429` = 限流；超时 = `read-timeout-seconds` 太短 |
| 回答总是 `degraded=true` | 主模型被熔断或持续失败，查 `/health` 的 `failureRate` |
| 引用清单标题/来源为 `null` | RediSearch 索引没声明 `title` / `source` 元数据字段 → 改 `VectorStoreConfiguration` 后 **`FT.DROPINDEX <idx> DD` 再重启**（schema 变更不会自动迁移） |
| 日志出现 `索引过滤不可用` | 过滤表达式失效 → 检查索引 TAG 字段声明 + `escapeTagValue` 是否被绕过 |
| 日志出现 `⚠️ 跨租户片段已被丢弃` | **安全事故信号**，隔离已失效，立即检查两个索引的 key 前缀有没有被写混 |
| 明明有资料却回答"未检索到" | 相似度阈值太高 / 片段没写 `domain` 元数据（`readDomain` 返回 null 会被当越域丢掉）/ 向量库未重建 |
| `Failed to connect to any host resolved for DNS name` | **与 DNS 无关**，是 Redis 没起来（见 `MEMORY.md` 的 WSL2 排查） |

---

## 10. 踩坑备忘（本机实测，改代码前先看）

1. **Spring AI 1.0.0-M7 是里程碑版**，API 与 GA / 网上教程差异很大，勿照抄。
2. **`base-url` 一律不带 `/v1`（OpenAI 兼容）/ `/v4`（智谱）**，SDK 内部会自己拼，带前缀必 404。
3. **`ChatModel` 注入必须 `@Qualifier`**：多 starter（zhipuai / openai / deepseek / siliconflow）并存，不限定会注入错。
4. **`spring.data.redis.host` 写 `127.0.0.1` 字面量**，别写 `localhost`（mirrored 模式可能解析到 `::1`，而 Redis 只监听 IPv4）。
5. **知识库数据变更后必须重建向量库**（增删改接口已内置自动 rebuild；批量 SQL 导入后调 `POST /api/knowledge/rebuild`）。
6. **空响应要当失败**：网关限流常返回 `200 + 空 body`，`ChatFacade` 已把它当异常处理，别去掉这个判断。
7. **`[META]` 行只认最后一条**：模型有时会先写草稿再补元信息，解析器已按"取最后一条"处理。
8. **SLF4J 只认 `{}` 占位符**，写 `{:<10}` 这类格式说明符不会生效（会原样输出）。

---

## 11. 阅读顺序建议

1. `service/ClinicalAssistantService#ask` —— 看整条链路（有完整步骤注释 ①~⑩）
2. `domain/DomainAwareRetriever` —— 看安全边界怎么落地（三层隔离 + 三类计数）
3. `chat/ChatFacade` —— 看容错怎么设计（熔断、可重试判定、降级链）
4. `model/ModelRegistry` + `config/LlmProperties` —— 看配置怎么驱动实例构建
5. `application.yml` 的 `app.llm.*` 段 —— 与上面每个类一一对应
