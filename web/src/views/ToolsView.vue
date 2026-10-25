<template>
  <div>
    <div class="page-head">
      <h2>🔧 工具调用（Function Calling）</h2>
      <p>接口 <code>/api/ai/chat</code> 注册了 WeatherTool，模型会根据问题自行决定是否调用工具查询天气</p>
    </div>

    <div class="card tool-card">
      <div class="tool-head">
        <span class="card-title">🌤️ WeatherTool 演示</span>
        <span class="tag gray">返回模拟数据：25°C 晴朗</span>
      </div>

      <div class="alert alert-info tip">
        问“北京今天天气怎么样”会触发工具调用；问“你好”则不会。注意：返回的天气值是 WeatherTool 里的固定模拟数据，
        真实项目里把 <code>getWeather()</code> 换成和风天气 / OpenWeatherMap 的 HTTP 请求即可。
      </div>

      <textarea
        v-model="message"
        rows="3"
        placeholder="例如：帮我查一下北京和上海的天气"
        @keydown.ctrl.enter="submit"
      ></textarea>

      <div class="quick">
        <button v-for="q in quickAsk" :key="q" class="chip" @click="(message = q), submit()">{{ q }}</button>
      </div>

      <div class="actions">
        <button class="btn btn-primary" :disabled="loading || !message.trim()" @click="submit">
          <span v-if="loading" class="spinner"></span>
          {{ loading ? '调用中' : '发送' }}
        </button>
      </div>

      <div v-if="error" class="alert alert-error">{{ error }}</div>

      <div v-if="loading || answer" class="result">
        <div class="result-head">
          <span class="card-title">返回结果</span>
          <span v-if="answer" class="tag gray">耗时 {{ elapsed }} s</span>
        </div>
        <div v-if="loading" class="loading-line">
          <span class="spinner dark"></span><span class="dots">模型正在思考并可能调用工具</span>
        </div>
        <div v-else class="result-body">{{ answer }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { chatApi } from '../api'

const message = ref('')
const answer = ref('')
const elapsed = ref('0.0')
const loading = ref(false)
const error = ref('')

const quickAsk = ['北京今天天气怎么样？', '上海和广州哪个更热？', '你好，介绍一下你自己']

async function submit() {
  const text = message.value.trim()
  if (!text || loading.value) return

  loading.value = true
  error.value = ''
  answer.value = ''
  const start = Date.now()

  try {
    const res = await chatApi.aiChat(text)
    answer.value = typeof res === 'string' ? res : JSON.stringify(res, null, 2)
    elapsed.value = ((Date.now() - start) / 1000).toFixed(1)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.tool-card {
  padding: 16px;
}

.tool-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.tip {
  margin-bottom: 14px;
}

code {
  background: rgba(37, 99, 235, 0.09);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12.5px;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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

.actions {
  margin-top: 14px;
}

.result {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.result-body {
  margin-top: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14.5px;
  line-height: 1.9;
}

.loading-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 0;
  color: var(--text-2);
  font-size: 13px;
}
</style>
