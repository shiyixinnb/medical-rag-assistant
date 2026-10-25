# 医疗 RAG 问答系统

基于 **Spring Boot 3 + Spring AI** 的院内急诊知识库问答系统。目标不是"能回答问题"，而是**回答可信、行为可控**：每条结论都能追溯到资料，没资料就明确拒答，不同机构的数据绝不串。

> 个人项目，用于学习与实践 RAG 工程化。业务背景来自医院急救信息化场景，代码与数据均已脱敏。

---

## 它和普通 RAG demo 的区别

| | 常见 demo | 本项目 |
|---|---|---|
| 检索 | 一个向量库，全量搜 | 公共域 / 患者域**两个独立索引**，物理隔离 + 索引过滤 + Java 复核 |
| 没检索到资料 | 让模型"尽量回答" | 明确拒答，或不带数值地解释原因 |
| 模型故障 | 直接 500 | 能力路由 → 熔断 → 重试 → 主备降级，全链路可观测 |
| 引用 | 只把片段塞进 Prompt | 要求模型标注 `[1][2]`，收尾时校验编号是否越界 |
| 流式 | 直接返回 `Flux` | SSE 事件协议（`meta` / `delta` / `done`），首 token 前才允许降级 |
| 配置 | 写死在代码里 | 模型清单、熔断阈值、检索参数全部配置驱动 |

---

## 核心能力

- **RAG 检索**：bge-m3（1024 维）向量化，Redis Stack / RediSearch 存储与检索
- **数据域隔离**：`fail-closed` —— 授权信息缺失时当作无权限，连检索都不做
- **模型治理**：配置驱动的模型注册表、能力路由、熔断（Sentinel / 自研滑窗双后端）、重试与降级链
- **SSE 流式**：`meta → delta… → done`，每个事件的 `data` 都是 JSON 对象
- **多轮会话**：Redis 存历史与授权状态，Redis 故障时自动退化为"无记忆"
- **知识入库**：MySQL 为唯一真相，增删改后自动/手动重建向量索引

---

## 架构

```
HTTP
 │
 ├─ ClinicalAssistantController        /api/assistant/*
 │
 ├─ ClinicalAssistantService          编排：prepare → 调用 → buildAnswer
 │   ├─ RedisConversationStore        会话、租户、授权
 │   ├─ IntentClassifier              意图判定（规则加权）
 │   ├─ DomainAwareRetriever          授权 + 检索 + 复核
 │   ├─ PromptTemplateService         提示词装配（.st 模板）
 │   └─ ChatFacade                    模型治理
 │       ├─ RoutingStrategy           能力路由
 │       ├─ ModelCallGuard            熔断
 │       └─ ModelRegistry             模型注册表（启动时构建）
 │
 └─ AnswerMetaParser / RagAnswer      结构化输出
```

两条路径（同步 `/ask` 与流式 `/ask/stream`）**共用 `prepare()` 与 `buildAnswer()`**，差异只在中间的模型调用方式。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 3.3.6、Spring AI 1.0.0-M7 |
| 模型 | DeepSeek（主）、智谱 GLM（备）、SiliconFlow bge-m3（embedding） |
| 向量库 | Redis Stack（RediSearch） |
| 持久化 | MySQL 8 + MyBatis-Plus |
| 熔断 | Sentinel（可选）+ 自研滑动窗口 |
| 前端 | Vue 3 + Vite |

---

## 快速开始

### 1. 前置依赖

- JDK 17、Maven 3.6+
- MySQL 8
- Redis Stack（需带 RediSearch 模块，普通 Redis 不够）
- Node 18+（前端）

### 2. 建库

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

### 3. 配置密钥

`application.yml` 里的敏感项全部走环境变量占位符。在项目根目录新建 **`application-local.yml`**（已被 `.gitignore` 排除）：

```yaml
spring:
  ai:
    zhipuai:
      api-key: 你的智谱Key
    openai:
      api-key: 你的DeepSeekKey
      embedding:
        api-key: 你的SiliconFlowKey
  datasource:
    password: 你的MySQL密码
```

也可以直接设环境变量：`ZHIPU_API_KEY` / `DEEPSEEK_API_KEY` / `SILICONFLOW_API_KEY` / `MYSQL_PASSWORD`。

### 4. 启动

```bash
# 后端（默认 8080）
mvn spring-boot:run

# 前端（默认 5173，通过 Vite proxy 访问后端）
cd web && npm install && npm run dev
```

### 5. 建索引并验证

```bash
# 把 MySQL 里的知识写进向量库
curl -X POST http://127.0.0.1:8080/api/knowledge/rebuild

# 问一句
curl -s -X POST http://127.0.0.1:8080/api/assistant/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"被狗咬出血了怎么处理","sessionId":"demo-1","tenantId":"HOSP-A"}'
```

---

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/assistant/ask` | 同步问答 |
| POST | `/api/assistant/ask/stream` | SSE 流式问答 |
| GET | `/api/assistant/models` | 已注册模型与降级链（含被跳过的配置及原因） |
| GET | `/api/assistant/health` | 健康检查与熔断快照 |
| GET | `/api/assistant/domains` | 数据域说明 |
| GET/DELETE | `/api/assistant/session/{id}` | 查看 / 清空会话 |
| POST | `/api/knowledge/rebuild` | 重建向量索引 |
| GET | `/api/knowledge/list` | 知识列表 |
| POST/PUT/DELETE | `/api/knowledge/**` | 知识增删改（内置重建） |

---

## 目录结构

```
src/main/java/com/syx/demo/
├── llm/                    正式接入层
│   ├── web/                接口与 SSE
│   ├── service/            编排（ClinicalAssistantService）
│   ├── route/              意图判定与模型路由
│   ├── domain/             数据域隔离与检索
│   ├── prompt/             提示词模板
│   ├── chat/               模型调用门面
│   ├── guard/              熔断
│   ├── model/              模型注册表
│   ├── session/            多轮会话
│   └── answer/             结构化输出
├── controller/             知识库接口 + 早期 demo
├── service/                知识入库
├── entity/ mapper/         数据层
└── config/                 基础配置与启动自检
```

---

## 几个关键设计取舍

1. **意图用规则不用模型**：急诊场景要求零延迟、零额外成本、结果可复现。规则分类的漏判风险由"兜底意图 + 数据域 fail-closed"兜住。
2. **两个索引而不是一个索引加过滤**：隔离强度依赖"访问路径"而非"过滤条件写对"。过滤表达式失效时，Java 侧复核仍能拦住越域数据。
3. **所有模型调用只走 `ChatFacade`**：厂商 SDK 的异常、超时、流式协议差异全部收敛在一处，业务层不感知。
4. **流式降级只能在首 token 之前**：已经吐字后换模型，用户会看到两段答案拼接。
5. **向量库是副本，不是从库**：MySQL 改动后必须重建索引，否则检索到的还是旧内容。

---

## 文档

- [`docs/零基础读懂主链路.md`](docs/零基础读懂主链路.md) —— 从 LLM / embedding / 向量检索 / RAG 讲起
- [`docs/方法调用链与调用方式.md`](docs/方法调用链与调用方式.md) —— 同步与流式两棵调用树、六种调用形态
- [`docs/RAG项目结构与模块职责.md`](docs/RAG项目结构与模块职责.md) —— 分层、模块边界、从零搭建顺序
- [`docs/吃透这个项目-学习路线.md`](docs/吃透这个项目-学习路线.md) —— 14 天学习路径与实验清单

---

## 免责声明

本项目为个人学习作品，回答内容由 AI 生成，**不构成任何医疗建议**，不可用于实际诊疗决策。
