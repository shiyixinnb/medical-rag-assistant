<template>
  <div class="chat-page">
    <div class="page-head">
      <h2>💬 模型对话</h2>
      <p>普通对话接口（不带 RAG 检索），用于对比两个模型的表现</p>
    </div>

    <div class="card chat-card">
      <!-- 工具栏 -->
      <div class="chat-toolbar">
        <div class="segmented">
          <button :class="{ active: model === 'deepseek' }" @click="model = 'deepseek'">DeepSeek</button>
          <button :class="{ active: model === 'zhipu' }" @click="model = 'zhipu'">智谱 GLM</button>
        </div>

        <label class="switch">
          <input v-model="withContext" type="checkbox" />
          <span>携带上下文</span>
        </label>

        <button class="btn btn-ghost btn-sm" :disabled="loading || !messages.length" @click="clearMessages">
          清空对话
        </button>
      </div>

      <div v-if="withContext" class="alert alert-info ctx-tip">
        后端接口是无状态的，开启后前端会把历史对话拼进 prompt 一起发送，实现多轮效果。
      </div>

      <!-- 消息列表 -->
      <div ref="listRef" class="messages">
        <div v-if="!messages.length" class="empty">
          还没有对话，下面输入一句试试 👇
          <div class="quick">
            <button v-for="q in quickAsk" :key="q" class="chip" @click="send(q)">{{ q }}</button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bubble-wrap">
            <div class="bubble">
              <span v-if="m.loading" class="dots">思考中</span>
              <template v-else>{{ m.content }}</template>
            </div>
            <div v-if="!m.loading" class="meta">
              {{ m.time }}
              <template v-if="m.elapsed"> · {{ m.elapsed }}s</template>
            </div>
          </div>
        </div>
      </div>

      <div v-if="error" class="alert alert-error">{{ error }}</div>

      <!-- 输入区 -->
      <div class="composer">
        <textarea
          v-model="input"
          rows="2"
          placeholder="输入消息，Enter 发送 / Shift + Enter 换行"
          @keydown.enter.exact.prevent="send()"
        ></textarea>
        <button class="btn btn-primary" :disabled="loading || !input.trim()" @click="send()">
          <span v-if="loading" class="spinner"></span>
          {{ loading ? '生成中' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue'
import { chatApi } from '../api'

const model = ref('deepseek')
const withContext = ref(false)
const input = ref('')
const messages = ref([])
const loading = ref(false)
const error = ref('')
const listRef = ref(null)

const quickAsk = ['用一句话介绍你自己', '中暑的表现有哪些', '被猫抓伤要不要打疫苗']

/** 把历史对话拼成带上下文的 prompt（后端无状态，靠前端补上下文） */
function buildMessage(text) {
  if (!withContext.value) return text
  const finished = messages.value.filter((m) => !m.loading)
  if (!finished.length) return text
  const history = finished
    .map((m) => `${m.role === 'user' ? '用户' : '助手'}：${m.content}`)
    .join('\n')
  return `以下是之前的对话记录：\n${history}\n\n用户：${text}\n请结合以上对话回答。`
}

async function send(preset) {
  const text = (preset ?? input.value).trim()
  if (!text || loading.value) return

  error.value = ''
  input.value = ''
  messages.value.push({ role: 'user', content: text, time: now() })
  const aiMsg = { role: 'assistant', content: '', loading: true, time: now() }
  messages.value.push(aiMsg)
  loading.value = true
  await scrollBottom()

  const start = Date.now()
  try {
    const res = await chatApi.chat(buildMessage(text), model.value)
    aiMsg.content = typeof res === 'string' ? res : JSON.stringify(res)
    aiMsg.elapsed = ((Date.now() - start) / 1000).toFixed(1)
  } catch (e) {
    aiMsg.content = '⚠️ ' + e.message
    error.value = e.message
  } finally {
    aiMsg.loading = false
    loading.value = false
    await scrollBottom()
  }
}

function clearMessages() {
  messages.value = []
  error.value = ''
}

function now() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

async function scrollBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}
</script>

<style scoped>
.chat-card {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 190px);
  min-height: 460px;
  padding: 14px 16px;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  margin-left: auto;
}

.switch input {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
  cursor: pointer;
}

.ctx-tip {
  margin: 10px 0 0;
  padding: 7px 12px;
  font-size: 12px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 4px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 12px;
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
}

.chip:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-light);
}

.msg {
  display: flex;
  gap: 10px;
}

.msg.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  background: #f2f4f7;
  color: var(--text-2);
}

.msg.user .avatar {
  background: var(--primary);
  color: #fff;
}

.bubble-wrap {
  max-width: 76%;
}

.bubble {
  padding: 9px 13px;
  border-radius: 10px;
  background: #f7f8fb;
  border: 1px solid var(--border);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.8;
}

.msg.user .bubble {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.meta {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 4px;
}

.msg.user .meta {
  text-align: right;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.composer textarea {
  flex: 1;
}
</style>
