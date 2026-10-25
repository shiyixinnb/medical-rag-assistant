<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="logo">✚</div>
        <div>
          <div class="brand-name">急救知识库</div>
          <div class="brand-sub">Spring AI · RAG</div>
        </div>
      </div>

      <nav class="nav">
        <router-link v-for="item in navs" :key="item.path" :to="item.path" class="nav-item">
          <span class="nav-icon">{{ item.icon }}</span>
          <span class="nav-text">
            <span class="nav-title">{{ item.title }}</span>
            <span class="nav-desc">{{ item.desc }}</span>
          </span>
        </router-link>
      </nav>

      <div class="sidebar-foot">
        <div class="status" :class="{ ok: online, bad: online === false }">
          <span class="dot"></span>
          <span v-if="online === null">检测后端中…</span>
          <span v-else-if="online">后端已连接 · 8080</span>
          <span v-else>后端未连接</span>
        </div>
        <div class="foot-tip">向量库：Redis Stack (WSL2)</div>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main">
      <router-view v-slot="{ Component }">
        <component :is="Component" />
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { knowledgeApi } from './api'

const navs = [
  { path: '/ask', icon: '🚑', title: '急救问答', desc: 'RAG 检索增强问答' },
  { path: '/assistant', icon: '🩺', title: '临床助手', desc: '路由 / 熔断 / 数据域' },
  { path: '/chat', icon: '💬', title: '模型对话', desc: '双模型自由切换' },
  { path: '/knowledge', icon: '📚', title: '知识库管理', desc: '增删改查 + 重建' },
  { path: '/tools', icon: '🔧', title: '工具调用', desc: 'Function Calling' }
]

const online = ref(null)

onMounted(async () => {
  try {
    await knowledgeApi.stats()
    online.value = true
  } catch (e) {
    online.value = false
  }
})
</script>

<style scoped>
.layout {
  display: flex;
  height: 100%;
}

/* 侧边栏 */
.sidebar {
  width: 236px;
  flex-shrink: 0;
  background: var(--panel);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 0 12px 14px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 8px 16px;
}

.logo {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: linear-gradient(135deg, #ef4444, #f97316);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.brand-name {
  font-size: 15px;
  font-weight: 650;
  line-height: 1.3;
}

.brand-sub {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: 0.3px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  margin-top: 6px;
}

.nav-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 10px;
  border-radius: var(--radius-sm);
  text-decoration: none;
  color: var(--text);
  transition: background 0.15s;
}

.nav-item:hover {
  background: var(--panel-2);
}

.nav-item.router-link-active {
  background: var(--primary-light);
}

.nav-item.router-link-active .nav-title {
  color: var(--primary);
}

.nav-icon {
  font-size: 17px;
  line-height: 1.4;
}

.nav-text {
  display: flex;
  flex-direction: column;
}

.nav-title {
  font-size: 14px;
  font-weight: 550;
}

.nav-desc {
  font-size: 11px;
  color: var(--text-3);
}

.sidebar-foot {
  padding: 10px 8px 0;
  border-top: 1px solid var(--border);
}

.status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-2);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-3);
}

.status.ok .dot {
  background: var(--success);
  box-shadow: 0 0 0 3px rgba(22, 163, 74, 0.15);
}

.status.bad .dot {
  background: var(--danger);
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.15);
}

.status.bad {
  color: var(--danger);
}

.foot-tip {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 5px;
}

/* 主内容 */
.main {
  flex: 1;
  overflow-y: auto;
  padding: 26px 30px 40px;
}
</style>
