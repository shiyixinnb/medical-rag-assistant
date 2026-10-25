# 急救知识库前端（Vue 3 + Vite）

配合 `D:\develop\code\springAi\demo`（Spring Boot 3.3.6 + Spring AI 1.0.0-M7）使用。

## 技术栈

| 依赖 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5 | 组合式 API (`<script setup>`) |
| Vite | 5.4 | 构建 + 开发服务器（含 `/api` 代理） |
| vue-router | 4.5 | 路由 |
| axios | 1.7 | HTTP 请求（统一封装 + 超时 120s） |

## 启动步骤

**1. 先启动后端依赖**（WSL 里 Redis Stack + 本机 MySQL）

```bash
# 桌面双击 start-redis.bat，或 WSL 里手动执行：
wsl -d Ubuntu
redis-stack-server --daemonize yes --bind 0.0.0.0
```

**2. 启动 Spring Boot**（IDEA 里跑 DemoApplication，端口 8080）
启动日志出现 `Tomcat started on port 8080` 即可。

**3. 启动前端**

```bash
cd D:\develop\code\springAi\demo\web
npm run dev
```

浏览器自动打开 <http://localhost:5173>。左下角显示「后端已连接 · 8080」说明链路通了。

> 首次运行需先 `npm install`（可用国内镜像：`npm install --registry=https://registry.npmmirror.com`）

## 页面与接口对应关系

| 页面 | 路由 | 调用的后端接口 |
|---|---|---|
| 急救问答 | `/ask` | `GET /api/emergency/ask?question=&model=` |
| 模型对话 | `/chat` | `GET /api/chat/chat?message=&model=` |
| 知识库管理 | `/knowledge` | `GET /api/knowledge/list`、`POST /api/knowledge/add`、`PUT /api/knowledge`、`DELETE /api/knowledge/{id}`、`POST /api/knowledge/rebuild` |
| 工具调用 | `/tools` | `GET /api/ai/chat?message=` |

## 跨域说明

开发环境靠 `vite.config.js` 里的 proxy 把 `/api` 转发到 `http://localhost:8080`（浏览器视角同源，无跨域问题）；
后端另外加了 `WebConfig.java` 放开 `localhost:*` 的 CORS，所以直接打开打包后的 `dist/index.html` 也能用（但此时需要把接口地址改成绝对地址 `http://localhost:8080/api`）。

## 目录结构

```
web/
├─ index.html
├─ vite.config.js          # /api 代理配置
├─ package.json
└─ src/
   ├─ main.js
   ├─ App.vue              # 侧边栏布局 + 后端连通状态检测
   ├─ style.css            # 全局设计变量与通用样式
   ├─ api/index.js         # axios 封装 + 全部接口方法
   ├─ router/index.js      # 4 个页面路由
   └─ views/
      ├─ AskView.vue        # 急救问答（RAG）
      ├─ ChatView.vue       # 模型对话（可携带上下文）
      ├─ KnowledgeView.vue  # 知识库增删改查 + 重建
      └─ ToolsView.vue      # 天气工具调用演示
```

## 打包

```bash
npm run build     # 产物在 dist/
npm run preview   # 本地预览打包结果（端口 4173）
```
