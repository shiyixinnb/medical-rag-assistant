<template>
  <div>
    <div class="page-head">
      <h2>🩺 临床助手</h2>
      <p>
        意图判定 → 数据域判定 → 检索 → Prompt → 模型调用 → 结构化输出。
        路由、熔断、重试、降级都在服务端完成；患者隐私数据按 fail-closed 策略隔离。
      </p>
    </div>

    <div class="layout">
      <!-- ================= 左：交互主区 ================= -->
      <div class="main-col">
        <!-- 会话与授权 -->
        <div class="card ctrl-card">
          <div class="ctrl-grid">
            <div class="field">
              <label>会话 ID</label>
              <input v-model.trim="sessionId" type="text" placeholder="demo-1" />
            </div>
            <div class="field">
              <label>机构标识 tenantId</label>
              <div class="inline">
                <input v-model.trim="tenantId" type="text" placeholder="留空 = 无机构授权" />
                <button class="btn btn-ghost btn-sm" @click="tenantId = 'HOSP-A'">HOSP-A</button>
                <button class="btn btn-ghost btn-sm" @click="tenantId = 'HOSP-B'">HOSP-B</button>
              </div>
            </div>
          </div>

          <label class="switch">
            <input v-model="allowPatientDomain" type="checkbox" />
            <span>授权访问患者隐私数据域</span>
          </label>

          <div class="ctrl-note" :class="authorized ? 'ok' : 'off'">
            <template v-if="authorized">
              ✅ 当前授权：{{ tenantId }} 的患者隐私数据域 + 院内公共知识域。
              检索会按 <code>domain = PATIENT AND tenantId = {{ tenantId }}</code> 收敛，跨机构资料不会被取出。
            </template>
            <template v-else>
              🔒 当前未授权患者域：涉及具体患者的提问会<b>直接拒答</b>，
              服务端既不检索患者库也不调用模型（fail-closed）。
            </template>
          </div>
        </div>

        <!-- 提问 -->
        <div class="card ask-card">
          <div class="ask-toolbar">
            <span class="card-title">提问</span>
            <div class="toolbar-right">
              <label class="switch mini" title="开：走 SSE 逐字返回，meta 先到、正文边生成边显示；关：等全部生成完一次性返回">
                <input v-model="streamEnabled" type="checkbox" :disabled="busy" />
                <span>流式输出</span>
              </label>
              <span class="hint">Ctrl + Enter 提交</span>
            </div>
          </div>
          <textarea
            v-model="question"
            rows="3"
            placeholder="例如：成人过敏性休克的首选急救药物和给药途径是什么？"
            @keydown.ctrl.enter="submit"
          ></textarea>

          <div class="examples">
            <span class="examples-label">试试：</span>
            <button
              v-for="q in examples"
              :key="q.text"
              class="chip"
              :title="q.tip"
              @click="useExample(q)"
            >
              {{ q.text }}
            </button>
          </div>

          <div class="ask-actions">
            <button class="btn btn-primary" :disabled="busy || !question.trim()" @click="submit">
              <span v-if="loading" class="spinner"></span>
              {{ loading ? '链路执行中' : '提问' }}
            </button>
            <button v-if="streaming" class="btn btn-ghost" @click="stopStream">停止生成</button>
            <button class="btn btn-ghost" :disabled="busy" @click="clearAnswer">清空</button>
            <button class="btn btn-ghost" :disabled="busy || !sessionId" @click="clearSession">
              重置会话
            </button>
          </div>
        </div>

        <div v-if="error" class="alert alert-error">{{ error }}</div>

        <!-- 回答 -->
        <div v-if="loading || view" class="card answer-card">
          <div class="answer-head">
            <span class="card-title">{{ headTitle }}</span>
            <div class="answer-meta">
              <span v-if="streaming" class="tag">
                <span class="spinner tiny"></span> 流式生成中
              </span>
              <span v-else-if="interrupted" class="tag red">已中断</span>
              <span v-else-if="answer" class="tag" :class="answer.refused ? 'red' : 'green'">
                {{ answer.refused ? '拒绝作答' : '正常作答' }}
              </span>
              <span v-if="answer?.degraded" class="tag red">已降级</span>
              <span v-if="firstTokenMs !== null" class="tag gray">首字 {{ (firstTokenMs / 1000).toFixed(1) }} s</span>
              <span v-if="answer" class="tag gray">总耗时 {{ (answer.costMs / 1000).toFixed(1) }} s</span>
            </div>
          </div>

          <!-- 阶段一：连 meta 都还没回来，纯粹在等（最久的一段，推理模型可能十几秒） -->
          <div v-if="loading && !view" class="loading-line">
            <span class="spinner dark"></span>
            <span class="dots">正在判定意图、检索数据域并生成回答</span>
          </div>

          <template v-else-if="view">
            <!-- 路由与元信息：流式时来自 meta 事件，完成后来自 RagAnswer，字段名一致 -->
            <div class="meta-grid">
              <div class="meta-item">
                <span class="k">意图</span><span class="v">{{ view.intentLabel }}</span>
              </div>
              <div class="meta-item">
                <span class="k">路由模型</span>
                <span class="v mono">
                  {{ view.routedModelId || '（生成中）' }}
                  <em v-if="view.routedModelId && view.modelId !== view.routedModelId">
                    → {{ view.modelId }}
                  </em>
                </span>
              </div>
              <div class="meta-item">
                <span class="k">置信度 / 依据</span>
                <span class="v">{{ view.confidence || '（生成中）' }} / {{ view.basis || '—' }}</span>
              </div>
              <div class="meta-item">
                <span class="k">数据域</span><span class="v">{{ view.domainScope }}</span>
              </div>
            </div>

            <div v-if="view.routeReason" class="route-reason">
              <span class="k">路由理由</span>{{ view.routeReason }}
            </div>

            <div class="answer-body">{{ view.answer }}<span v-if="streaming" class="caret"></span></div>

            <!-- 引用清单：流式时 meta 里就已经带了，不必等生成完 -->
            <div v-if="view.citations?.length" class="cite-block">
              <div class="block-title">📚 召回资料（{{ view.citations.length }} 条）</div>
              <div class="cite-table">
                <div class="cite-row head">
                  <span>#</span><span>数据域</span><span>机构 / 患者</span>
                  <span>标题</span><span>来源</span><span>相似度</span>
                </div>
                <div v-for="c in view.citations" :key="c.index" class="cite-row">
                  <span class="mono">[{{ c.index }}]</span>
                  <span>
                    <span class="tag" :class="c.domain === 'PATIENT' ? 'red' : 'gray'">
                      {{ c.domain === 'PATIENT' ? '患者域' : '公共域' }}
                    </span>
                  </span>
                  <span class="mono small">{{ c.tenantId || '—' }}<template v-if="c.patientId"> / {{ c.patientId }}</template></span>
                  <span>{{ c.title || '（无标题）' }}</span>
                  <span class="small">{{ c.source || '—' }}</span>
                  <span class="mono small">{{ c.score == null ? '—' : c.score.toFixed(3) }}</span>
                </div>
              </div>
            </div>

            <!-- 系统注记 -->
            <div v-if="view.notes?.length" class="notes-block">
              <div class="block-title">🧭 链路注记</div>
              <ul>
                <li v-for="(n, i) in view.notes" :key="i">{{ n }}</li>
              </ul>
            </div>

            <div v-if="view.disclaimer" class="disclaimer">{{ view.disclaimer }}</div>
          </template>
        </div>
      </div>

      <!-- ================= 右：运行时信息 ================= -->
      <aside class="side-col">
        <div class="card side-card">
          <div class="side-head">
            <span class="card-title">⚙️ 模型注册表</span>
            <button class="btn btn-ghost btn-sm" @click="loadRuntime">刷新</button>
          </div>
          <p class="side-hint">
            缺省模型 <code>{{ models.defaultModel }}</code>，策略
            <code>{{ models.routingStrategy }}</code>
          </p>
          <div v-for="m in models.models" :key="m.id" class="model-item">
            <div class="model-top">
              <span class="mono">{{ m.id }}</span>
              <span class="tag gray">{{ m.capabilityLabel }}</span>
            </div>
            <div class="model-sub">
              优先级 {{ m.priority }} · {{ m.connectTimeoutSeconds }}s/{{ m.readTimeoutSeconds }}s
            </div>
            <div class="model-chain">
              <span class="chain-label">降级链</span>
              <span v-for="(f, i) in m.fallbackChain" :key="f" class="mono">
                <template v-if="i"> → </template>{{ f }}
              </span>
            </div>
          </div>
          <div v-if="skippedEntries.length" class="skipped">
            <div class="block-title">未启用</div>
            <div v-for="s in skippedEntries" :key="s.id" class="skipped-item">
              <b class="mono">{{ s.id }}</b>：{{ s.reason }}
            </div>
          </div>
        </div>

        <div class="card side-card">
          <span class="card-title">🛡️ 熔断状态</span>
          <p class="side-hint">后端：<code>{{ health.breakerBackend || '—' }}</code></p>
          <div v-for="h in health.models" :key="h.modelId" class="health-item">
            <span class="mono">{{ h.modelId }}</span>
            <span class="tag" :class="h.circuitState === 'CLOSED' ? 'green' : 'red'">
              {{ h.circuitState }}
            </span>
            <span class="small">调用 {{ h.calls }} · 失败 {{ h.failures }} · 拦截 {{ h.blocked }}</span>
          </div>
        </div>

        <div class="card side-card">
          <span class="card-title">🗂️ 数据域</span>
          <div v-for="d in domains.domains" :key="d.key" class="domain-item">
            <div class="model-top">
              <span>{{ d.label }}</span>
              <span class="tag" :class="d.key === 'PATIENT' ? 'red' : 'gray'">{{ d.key }}</span>
            </div>
            <div class="small">{{ d.description }}</div>
            <div class="mono small">{{ d.index }} · {{ d.prefix }}</div>
          </div>
          <div v-if="domains.policy" class="policy">{{ domains.policy }}</div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { assistantApi } from '../api'

const sessionId = ref('demo-web-1')
const tenantId = ref('HOSP-A')
const allowPatientDomain = ref(true)
const question = ref('')

const loading = ref(false)
const error = ref('')
const answer = ref(null)

/* ---------------- 流式相关状态 ---------------- */
/** 是否走 SSE 流式（关掉就是原来的"等全部生成完一次性返回"，方便对比两种体验） */
const streamEnabled = ref(true)
/** 是否正在接收增量（meta 已到、done 未到） */
const streaming = ref(false)
/** 用户点了「停止生成」 */
const interrupted = ref(false)
/** meta 事件：调模型之前后端就下发的意图 / 数据域 / 召回清单 */
const streamMeta = ref(null)
/** 已收到的正文增量拼接结果 */
const partial = ref('')
/** 首字延迟（毫秒）：衡量"医生盯着屏幕干等"的真实体感，比总耗时更能说明体验 */
const firstTokenMs = ref(null)
/** askStream 返回的中断句柄 */
let streamHandle = null

const models = ref({ models: [], skipped: [] })
const health = ref({ models: [] })
const domains = ref({ domains: [] })

const authorized = computed(() => allowPatientDomain.value && !!tenantId.value)

/** 执行中（等待中或流式接收中）：用来禁用按钮，避免并发提问踩到同一个会话 */
const busy = computed(() => loading.value || streaming.value)

/**
 * 当前要渲染的"回答"。
 *
 * <p>流式进行中还没有完整 RagAnswer，但 meta 事件里已经带了意图、数据域、召回清单 ——
 * 而且这些字段名和后端 RagAnswer 的字段名是<b>刻意对齐</b>的
 * （sessionId / intentLabel / domainScope / citations / notes）。
 * 于是这里把 meta 和已收到的正文拼成一个临时对象，
 * 让"生成中"和"已完成"共用同一套模板，不必为中间状态再写一份。
 */
const view = computed(() => {
  if (answer.value) return answer.value
  if (!streamMeta.value) return null
  return { ...streamMeta.value, answer: partial.value }
})

const headTitle = computed(() => {
  if (streaming.value) return '🤖 回答（生成中）'
  if (answer.value?.refused) return '🚫 已拒答'
  return '🤖 回答'
})

/** 后端 /models 的 skipped 是 Map<String,String>（模型 id → 跳过原因），转成数组好渲染 */
const skippedEntries = computed(() =>
  Object.entries(models.value.skipped || {}).map(([id, reason]) => ({ id, reason }))
)

/** 示例覆盖四种典型路径，点一下就能看到隔离策略的实际表现 */
const examples = [
  {
    text: '成人过敏性休克首选急救药物？',
    tip: '公共域问题：只用院内知识库，不触碰患者数据',
    sessionId: 'demo-public',
    tenantId: '',
    allowPatientDomain: false
  },
  {
    text: '患者 P-1001 的肌钙蛋白结果是多少？',
    tip: '不带 tenantId → 应 fail-closed 直接拒答，且不调用模型',
    sessionId: 'demo-denied',
    tenantId: '',
    allowPatientDomain: false
  },
  {
    text: '患者 P-1001 的肌钙蛋白结果？结合检验怎么判断？',
    tip: '授权 HOSP-A → 正常回答，且只引用 HOSP-A 的资料',
    sessionId: 'demo-hosp-a',
    tenantId: 'HOSP-A',
    allowPatientDomain: true
  },
  {
    text: '患者 P-9001 的诊断和处理是什么？',
    tip: '仍用 HOSP-A 授权 → 该患者属 HOSP-B，应确定性拒答（跨机构查不到）',
    sessionId: 'demo-cross-tenant',
    tenantId: 'HOSP-A',
    allowPatientDomain: true
  }
]

function useExample(ex) {
  question.value = ex.text
  sessionId.value = ex.sessionId
  tenantId.value = ex.tenantId
  allowPatientDomain.value = ex.allowPatientDomain
}

/** 重置所有"本轮结果"状态（含流式中间态） */
function resetResult() {
  answer.value = null
  streamMeta.value = null
  partial.value = ''
  firstTokenMs.value = null
  interrupted.value = false
  error.value = ''
}

async function submit() {
  const q = question.value.trim()
  if (!q || busy.value) return

  // 上一轮还没结束就再提问：先把上一条流掐掉，否则两个流会往同一个 partial 里写
  stopStream()

  loading.value = true
  resetResult()

  const payload = {
    sessionId: sessionId.value || 'demo-web-1',
    question: q,
    tenantId: tenantId.value || null,
    allowPatientDomain: allowPatientDomain.value
  }

  // 对比用：非流式路径保持原样
  if (!streamEnabled.value) {
    try {
      answer.value = await assistantApi.ask(payload)
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
    return
  }

  const t0 = performance.now()
  streaming.value = true

  streamHandle = assistantApi.askStream(payload, {
    onEvent: ({ event, data }) => {
      if (event === 'meta') {
        // meta 在调模型之前就下发了，先把它渲染出来，别让医生对着空白等
        streamMeta.value = data
        loading.value = false
      } else if (event === 'delta') {
        if (firstTokenMs.value === null) {
          firstTokenMs.value = Math.round(performance.now() - t0)
        }
        partial.value += data.text || ''
      } else if (event === 'done') {
        // 完整结果（结构和非流式返回完全一致），用它替换掉流式期间的临时视图
        answer.value = data
        partial.value = ''
        loading.value = false
        streaming.value = false
        streamHandle = null
      } else if (event === 'error') {
        error.value = data.message || '流式生成失败'
        loading.value = false
        streaming.value = false
        streamHandle = null
      }
    },
    onError: (e) => {
      error.value = e.message
      loading.value = false
      streaming.value = false
      streamHandle = null
    },
    onClose: () => {
      // 正常结束由 done 收尾；这里兜底"服务端提前关流、没发 done"的情况，
      // 保留已经收到的 partial，让用户至少能看到半截答案而不是白屏
      loading.value = false
      streaming.value = false
      streamHandle = null
    }
  })
}

/** 主动中断：关键价值是让后端 dispose 上游订阅，不再继续烧模型 token */
function stopStream() {
  const wasStreaming = streaming.value
  if (streamHandle) {
    streamHandle.abort()
    streamHandle = null
  }
  streaming.value = false
  loading.value = false
  if (wasStreaming && partial.value) {
    interrupted.value = true
  }
}

function clearAnswer() {
  question.value = ''
  resetResult()
}

async function clearSession() {
  try {
    await assistantApi.clearSession(sessionId.value)
    resetResult()
  } catch (e) {
    error.value = e.message
  }
}

/** 侧栏信息失败不该影响主流程，所以单独 try 掉 */
async function loadRuntime() {
  const [m, h, d] = await Promise.allSettled([
    assistantApi.models(),
    assistantApi.health(),
    assistantApi.domains()
  ])
  if (m.status === 'fulfilled') models.value = m.value
  if (h.status === 'fulfilled') health.value = h.value
  if (d.status === 'fulfilled') domains.value = d.value
}

onMounted(loadRuntime)

// 离开页面时掐掉未结束的流，避免模型在后端继续生成
onBeforeUnmount(stopStream)
</script>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 1080px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.ctrl-card {
  padding: 16px;
}

.ctrl-grid {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 14px;
}

.field label {
  display: block;
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 6px;
}

.inline {
  display: flex;
  gap: 6px;
  align-items: center;
}

.inline input {
  flex: 1;
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  font-size: 13.5px;
  cursor: pointer;
}

.ctrl-note {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  line-height: 1.8;
}

.ctrl-note code {
  background: rgba(0, 0, 0, 0.05);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12px;
}

.ctrl-note.ok {
  background: var(--success-light);
  color: #14532d;
  border: 1px solid #bbf7d0;
}

.ctrl-note.off {
  background: var(--warn-light);
  color: #78350f;
  border: 1px solid #fde68a;
}

.ask-card {
  margin-top: 16px;
  padding: 16px;
}

.ask-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* 工具栏里的小号开关，比 ctrl-card 里那个紧凑 */
.switch.mini {
  margin-top: 0;
  font-size: 12.5px;
  color: var(--text-2);
  gap: 6px;
}

.switch.mini input {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
  cursor: pointer;
}

.switch.mini input:disabled {
  cursor: not-allowed;
}

/* 流式输出时的光标：纯 CSS 闪烁，不需要 JS 定时器 */
.caret {
  display: inline-block;
  width: 7px;
  height: 1.05em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--primary);
  animation: caret-blink 1s step-end infinite;
}

@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}

/* 头部标签里的小转圈，和文字基线对齐 */
.spinner.tiny {
  width: 10px;
  height: 10px;
  border-width: 1.5px;
  border-color: rgba(37, 99, 235, 0.25);
  border-top-color: var(--primary);
  vertical-align: -1px;
  margin-right: 5px;
}

.hint {
  font-size: 12px;
  color: var(--text-3);
}

.examples {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.examples-label {
  font-size: 12px;
  color: var(--text-3);
}

.chip {
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  background: #fff;
  font-size: 12.5px;
  font-family: inherit;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s;
}

.chip:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-light);
}

.ask-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.answer-card {
  margin-top: 16px;
  padding: 16px;
}

.answer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.answer-meta {
  display: flex;
  gap: 8px;
}

.loading-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 22px 0;
  color: var(--text-2);
  font-size: 13px;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 18px;
  margin-top: 14px;
}

.meta-item {
  display: flex;
  gap: 8px;
  font-size: 13px;
  align-items: baseline;
}

.meta-item .k {
  color: var(--text-3);
  font-size: 12px;
  flex-shrink: 0;
}

.meta-item .v {
  color: var(--text);
}

.meta-item em {
  font-style: normal;
  color: var(--warn);
  font-size: 12px;
}

.route-reason {
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
}

.route-reason .k {
  color: var(--text-3);
  margin-right: 8px;
}

.answer-body {
  margin-top: 14px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14.5px;
  line-height: 1.9;
}

.block-title {
  font-size: 12.5px;
  color: var(--text-2);
  font-weight: 600;
  margin-bottom: 8px;
}

.cite-block,
.notes-block {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
}

.cite-table {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.cite-row {
  display: grid;
  grid-template-columns: 40px 76px 150px minmax(0, 1.4fr) minmax(0, 1fr) 70px;
  gap: 8px;
  padding: 8px 10px;
  font-size: 12.5px;
  align-items: center;
  border-bottom: 1px solid var(--border);
}

.cite-row:last-child {
  border-bottom: none;
}

.cite-row.head {
  background: var(--panel-2);
  color: var(--text-3);
  font-size: 11.5px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.small {
  font-size: 11.5px;
  color: var(--text-2);
}

.notes-block ul {
  margin: 0;
  padding-left: 18px;
}

.notes-block li {
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.9;
}

.disclaimer {
  margin-top: 16px;
  padding: 10px 12px;
  background: var(--warn-light);
  border: 1px solid #fde68a;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  color: #78350f;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.side-card {
  padding: 14px;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.side-hint {
  font-size: 12px;
  color: var(--text-3);
  margin: 8px 0 12px;
  line-height: 1.7;
}

.side-hint code,
.policy code {
  background: rgba(0, 0, 0, 0.05);
  padding: 1px 5px;
  border-radius: 4px;
}

.model-item,
.domain-item {
  padding: 9px 0;
  border-bottom: 1px dashed var(--border);
}

.model-item:last-child,
.domain-item:last-child {
  border-bottom: none;
}

.model-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  gap: 8px;
}

.model-sub {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 3px;
}

.model-chain {
  font-size: 11.5px;
  color: var(--text-2);
  margin-top: 4px;
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  align-items: baseline;
}

.chain-label {
  color: var(--text-3);
  margin-right: 2px;
}

.skipped {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}

.skipped-item {
  font-size: 11.5px;
  color: var(--text-2);
  line-height: 1.7;
}

.health-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  padding: 6px 0;
  flex-wrap: wrap;
}

.policy {
  margin-top: 12px;
  padding: 9px 10px;
  background: var(--primary-light);
  border-radius: var(--radius-sm);
  font-size: 11.5px;
  color: #1e3a8a;
  line-height: 1.8;
}
</style>
