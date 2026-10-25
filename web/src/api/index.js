import axios from 'axios'
import { createSseParser } from './sse.js'

/**
 * 统一 axios 实例。
 * baseURL 用相对路径 /api，开发环境由 vite.config.js 的 proxy 转发到 http://localhost:8080
 */
const http = axios.create({
  baseURL: '/api',
  // AI 接口可能比较慢（RAG 检索 + 大模型生成），超时给足 2 分钟
  timeout: 120000
})

// 响应拦截：成功直接返回 data；失败统一抛出可读的错误信息
http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    let msg = err.message
    if (err.code === 'ECONNABORTED') {
      msg = '请求超时（超过 120 秒），请检查大模型接口是否正常'
    } else if (err.response) {
      const data = err.response.data
      msg = typeof data === 'string' && data ? data : `HTTP ${err.response.status} ${err.response.statusText}`
    } else if (err.request) {
      msg = '连不上后端服务，请确认 Spring Boot 已在 8080 端口启动'
    }
    return Promise.reject(new Error(msg))
  }
)

/** 对话类接口 */
export const chatApi = {
  /** 急救知识问答（RAG 检索增强）：GET /api/emergency/ask */
  ask: (question, model = 'deepseek') =>
    http.get('/emergency/ask', { params: { question, model } }),

  /** 通用对话（可选模型）：GET /api/chat/chat */
  chat: (message, model = 'deepseek') =>
    http.get('/chat/chat', { params: { message, model } }),

  /** 指定 DeepSeek：GET /api/chat/deepseek */
  deepseek: (message) => http.get('/chat/deepseek', { params: { message } }),

  /** 指定智谱：GET /api/chat/zhipu */
  zhipu: (message) => http.get('/chat/zhipu', { params: { message } }),

  /** 带工具调用的对话（天气工具）：GET /api/ai/chat */
  aiChat: (message) => http.get('/ai/chat', { params: { message } })
}

/** 知识库管理接口 */
export const knowledgeApi = {
  /** 列表（可传 category） */
  list: (category) => http.get('/knowledge/list', { params: category ? { category } : {} }),
  /** 详情 */
  detail: (id) => http.get(`/knowledge/${id}`),
  /** 新增（后端会自动重建向量库） */
  add: (data) => http.post('/knowledge/add', data),
  /** 修改（后端会自动重建向量库） */
  update: (data) => http.put('/knowledge', data),
  /** 删除（后端会自动重建向量库） */
  remove: (id) => http.delete(`/knowledge/${id}`),
  /** 强制重建向量库 */
  rebuild: () => http.post('/knowledge/rebuild'),
  /** 统计 */
  stats: () => http.get('/knowledge/stats')
}

/** 临床助手接口（LLM 接入层：路由 / 熔断 / 降级 / 数据域隔离） */
export const assistantApi = {
  /**
   * 提问。
   * @param {{sessionId:string, question:string, tenantId?:string, allowPatientDomain?:boolean, model?:string}} payload
   *   tenantId 与 allowPatientDomain 是数据域授权的两个开关：
   *   不传 tenantId 或 allowPatientDomain=false 时，后端按 fail-closed 处理，
   *   涉及患者隐私的问题会直接拒答（不检索、不调用模型）。
   */
  ask: (payload) => http.post('/assistant/ask', payload),

  /**
   * 流式提问（SSE）。返回 { abort() }，调用方可通过它中断本次流。
   *
   * 事件契约（与后端 AssistantStreamEvent 一致）：
   *   meta   → { sessionId, intentLabel, domainScope, citations[], notes[] }
   *   delta  → { text }        正文增量，一次提问会有很多条
   *   done   → 完整的 RagAnswer（和非流式接口的返回结构完全一样）
   *   error  → { message }
   * 拒答路径只有 meta + done，没有 delta。
   *
   * 为什么用 fetch + ReadableStream 而不是浏览器原生的 EventSource：
   *   EventSource 只能发 GET、不能带 JSON body，而我们的提问是 POST + 结构化参数
   *   （sessionId / tenantId / allowPatientDomain），且需要能主动 abort。
   *
   * @param {object} payload 与 ask 相同
   * @param {{onEvent?:Function, onError?:Function, onClose?:Function}} handlers
   */
  askStream: (payload, handlers = {}) => {
    const { onEvent, onError, onClose } = handlers
    const controller = new AbortController()
    const parser = createSseParser(onEvent)

    const run = async () => {
      let resp
      try {
        resp = await fetch('/api/assistant/ask/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
          body: JSON.stringify(payload),
          signal: controller.signal
        })
      } catch (e) {
        if (e.name === 'AbortError') return onClose?.()
        return onError?.(new Error('连不上后端服务，请确认 Spring Boot 已在 8080 端口启动'))
      }

      if (!resp.ok) {
        return onError?.(new Error(`HTTP ${resp.status} ${resp.statusText}`))
      }
      if (!resp.body) {
        return onError?.(new Error('当前浏览器不支持流式读取（ReadableStream）'))
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      try {
        for (;;) {
          const { value, done } = await reader.read()
          if (done) break
          // stream: true —— 多字节字符（中文）可能跨 chunk 被切断，必须让解码器自己缓冲
          parser.push(decoder.decode(value, { stream: true }))
        }
        parser.end()
        onClose?.()
      } catch (e) {
        if (e.name === 'AbortError') onClose?.()
        else onError?.(new Error('流式读取中断：' + e.message))
      }
    }

    run()
    return { abort: () => controller.abort() }
  },

  /** 模型注册表：有哪些模型、能力标签、主备降级链 */
  models: () => http.get('/assistant/models'),

  /** 各模型熔断状态 */
  health: () => http.get('/assistant/health'),

  /** 数据域说明与隔离策略 */
  domains: () => http.get('/assistant/domains'),

  /** 查看会话状态（意图历史、轮次、过期时间） */
  session: (sessionId) => http.get(`/assistant/session/${encodeURIComponent(sessionId)}`),

  /** 清空会话 */
  clearSession: (sessionId) => http.delete(`/assistant/session/${encodeURIComponent(sessionId)}`)
}

export default http
