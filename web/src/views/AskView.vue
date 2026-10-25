<template>
  <div>
    <div class="page-head">
      <h2>🚑 急救知识问答</h2>
      <p>问题先经过 Redis 向量库检索（TopK=4，相似度 ≥ 0.7），再把命中的知识片段交给大模型作答</p>
    </div>

    <!-- 提问区 -->
    <div class="card ask-card">
      <div class="ask-toolbar">
        <div class="segmented">
          <button :class="{ active: model === 'deepseek' }" @click="model = 'deepseek'">DeepSeek</button>
          <button :class="{ active: model === 'zhipu' }" @click="model = 'zhipu'">智谱 GLM</button>
        </div>
        <span class="hint">Ctrl + Enter 快速提交</span>
      </div>

      <textarea
        v-model="question"
        rows="4"
        placeholder="请输入急救相关问题，例如：中暑了怎么办？"
        @keydown.ctrl.enter="submit"
      ></textarea>

      <div class="examples">
        <span class="examples-label">试试：</span>
        <button v-for="q in examples" :key="q" class="chip" @click="(question = q), submit()">
          {{ q }}
        </button>
      </div>

      <div class="ask-actions">
        <button class="btn btn-primary" :disabled="loading || !question.trim()" @click="submit">
          <span v-if="loading" class="spinner"></span>
          {{ loading ? '检索并生成中' : '提问' }}
        </button>
        <button class="btn btn-ghost" :disabled="loading" @click="clear">清空</button>
      </div>
    </div>

    <div v-if="error" class="alert alert-error">{{ error }}</div>

    <!-- 回答区 -->
    <div v-if="loading || answer" class="card answer-card">
      <div class="answer-head">
        <span class="card-title">🤖 模型回答</span>
        <div v-if="answer" class="answer-meta">
          <span class="tag">{{ model === 'zhipu' ? '智谱 GLM' : 'DeepSeek' }}</span>
          <span class="tag gray">耗时 {{ elapsed }} s</span>
        </div>
      </div>

      <div v-if="loading" class="loading-line">
        <span class="spinner dark"></span>
        <span class="dots">正在检索知识库并生成回答</span>
      </div>

      <div v-else class="answer-body">{{ answer }}</div>
    </div>

    <!-- 历史记录 -->
    <div v-if="history.length" class="card history-card">
      <div class="history-head">
        <span class="card-title">🕘 历史提问</span>
        <button class="btn btn-ghost btn-sm" @click="clearHistory">清空记录</button>
      </div>
      <ul>
        <li v-for="(item, i) in history" :key="i">
          <div class="h-q" @click="(question = item.question), (answer = item.answer)">
            <span class="h-idx">{{ history.length - i }}</span>
            {{ item.question }}
          </div>
          <div class="h-meta">{{ item.time }} · {{ item.model === 'zhipu' ? '智谱' : 'DeepSeek' }}</div>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { chatApi } from '../api'

const STORAGE_KEY = 'emergency-rag-history'

const question = ref('')
const model = ref('deepseek')
const answer = ref('')
const elapsed = ref('0.0')
const loading = ref(false)
const error = ref('')
const history = ref([])

const examples = ['中暑了怎么办', '烫伤后的正确处理步骤', '心肺复苏怎么做', '被蛇咬伤如何处理']

async function submit() {
  const q = question.value.trim()
  if (!q || loading.value) return

  loading.value = true
  error.value = ''
  answer.value = ''
  const start = Date.now()

  try {
    const res = await chatApi.ask(q, model.value)
    answer.value = typeof res === 'string' ? res : JSON.stringify(res, null, 2)
    elapsed.value = ((Date.now() - start) / 1000).toFixed(1)
    saveHistory(q, answer.value, model.value, elapsed.value)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function clear() {
  question.value = ''
  answer.value = ''
  error.value = ''
}

function saveHistory(q, a, m, s) {
  history.value.unshift({
    question: q,
    answer: a,
    model: m,
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    elapsed: s
  })
  if (history.value.length > 20) history.value = history.value.slice(0, 20)
  localStorage.setItem(STORAGE_KEY, JSON.stringify(history.value))
}

function clearHistory() {
  history.value = []
  localStorage.removeItem(STORAGE_KEY)
}

onMounted(() => {
  try {
    history.value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  } catch {
    history.value = []
  }
})
</script>

<style scoped>
.ask-card {
  padding: 16px;
}

.ask-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
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

.answer-body {
  margin-top: 14px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14.5px;
  line-height: 1.9;
}

.loading-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 20px 0;
  color: var(--text-2);
  font-size: 13px;
}

.history-card {
  margin-top: 16px;
  padding: 16px;
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}

.history-card ul {
  list-style: none;
  margin: 0;
  padding: 0;
}

.history-card li {
  padding: 10px 0;
  border-bottom: 1px dashed var(--border);
}

.history-card li:last-child {
  border-bottom: none;
}

.h-q {
  font-size: 13.5px;
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.h-q:hover {
  color: var(--primary);
}

.h-idx {
  font-size: 11px;
  color: var(--text-3);
  flex-shrink: 0;
}

.h-meta {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 3px;
  padding-left: 22px;
}
</style>
