import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/ask' },
  {
    path: '/ask',
    name: 'ask',
    component: () => import('../views/AskView.vue'),
    meta: { title: '急救问答', desc: '基于向量检索 + 大模型的 RAG 问答' }
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '模型对话', desc: 'DeepSeek / 智谱 双模型自由切换' }
  },
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('../views/KnowledgeView.vue'),
    meta: { title: '知识库管理', desc: '增删改查 MySQL 知识，自动同步向量库' }
  },
  {
    path: '/assistant',
    name: 'assistant',
    component: () => import('../views/AssistantView.vue'),
    meta: { title: '临床助手', desc: '能力路由 · 熔断降级 · 数据域隔离' }
  },
  {
    path: '/tools',
    name: 'tools',
    component: () => import('../views/ToolsView.vue'),
    meta: { title: '工具调用', desc: 'Function Calling 示例（天气查询）' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · 急救知识库` : '急救知识库 · AI 问答系统'
})

export default router
