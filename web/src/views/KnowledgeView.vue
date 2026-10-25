<template>
  <div>
    <div class="page-head">
      <h2>📚 知识库管理</h2>
      <p>数据存在 MySQL 的 medical_knowledge 表；新增 / 修改 / 删除都会自动重建 Redis 向量库</p>
    </div>

    <!-- 统计 -->
    <div class="stats">
      <div class="card stat">
        <div class="stat-num">{{ list.length }}</div>
        <div class="stat-label">知识条数</div>
      </div>
      <div class="card stat">
        <div class="stat-num">{{ categories.length }}</div>
        <div class="stat-label">分类数量</div>
      </div>
      <div class="card stat">
        <div class="stat-num">{{ enabledCount }}</div>
        <div class="stat-label">启用中</div>
      </div>
      <div class="card stat">
        <div class="stat-num">1024</div>
        <div class="stat-label">向量维度 (bge-m3)</div>
      </div>
    </div>

    <!-- 工具栏 -->
    <div class="card toolbar">
      <input v-model="keyword" type="search" placeholder="搜索标题 / 内容 / 标签…" class="search" />
      <select v-model="filterCategory" class="select">
        <option value="">全部分类</option>
        <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
      </select>
      <div class="toolbar-right">
        <button class="btn btn-ghost" :disabled="busy" @click="load">
          <span v-if="busy === 'load'" class="spinner dark"></span>
          刷新
        </button>
        <button class="btn btn-ghost" :disabled="!!busy" @click="doRebuild">
          <span v-if="busy === 'rebuild'" class="spinner dark"></span>
          {{ busy === 'rebuild' ? '重建中' : '重建向量库' }}
        </button>
        <button class="btn btn-primary" @click="openCreate">＋ 新增知识</button>
      </div>
    </div>

    <div v-if="error" class="alert alert-error">{{ error }}</div>
    <div v-if="notice" class="alert alert-info">{{ notice }}</div>

    <!-- 表格 -->
    <div class="card table-card">
      <table>
        <thead>
          <tr>
            <th style="width: 60px">ID</th>
            <th style="width: 190px">标题</th>
            <th style="width: 130px">内容</th>
            <th style="width: 110px">分类</th>
            <th style="width: 150px">标签</th>
            <th style="width: 100px">来源</th>
            <th style="width: 76px">状态</th>
            <th style="width: 96px">创建时间</th>
            <th style="width: 120px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!filtered.length">
            <td colspan="9" class="empty">
              {{ busy === 'load' ? '加载中…' : '暂无数据，点右上角「新增知识」添加第一条' }}
            </td>
          </tr>
          <tr v-for="row in filtered" :key="row.id">
            <td class="muted">{{ row.id }}</td>
            <td class="strong">{{ row.title }}</td>
            <td class="ellipsis" :title="row.content">{{ row.content }}</td>
            <td><span class="tag">{{ row.category || '未分类' }}</span></td>
            <td class="muted small">{{ row.tags || '-' }}</td>
            <td class="muted small">{{ row.source || '-' }}</td>
            <td>
              <span class="tag" :class="row.status === 1 ? 'green' : 'gray'">
                {{ row.status === 1 ? '启用' : '停用' }}
              </span>
            </td>
            <td class="muted small">{{ fmtDate(row.createdAt) }}</td>
            <td>
              <button class="link" @click="openEdit(row)">编辑</button>
              <button class="link danger" @click="askDelete(row)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <div v-if="showForm" class="mask" @click.self="showForm = false">
      <div class="modal">
        <div class="modal-head">
          <span class="card-title">{{ form.id ? '编辑知识' : '新增知识' }}</span>
          <button class="close" @click="showForm = false">✕</button>
        </div>

        <div class="modal-body">
          <label class="field">
            <span>标题 <b class="req">*</b></span>
            <input v-model="form.title" type="text" placeholder="例如：中暑急救" />
          </label>

          <div class="field-row">
            <label class="field">
              <span>分类</span>
              <input v-model="form.category" type="text" list="cat-list" placeholder="急救指南" />
              <datalist id="cat-list">
                <option v-for="c in categories" :key="c" :value="c" />
                <option value="急救指南" />
                <option value="药品说明" />
                <option value="操作规范" />
              </datalist>
            </label>
            <label class="field">
              <span>来源</span>
              <input v-model="form.source" type="text" placeholder="急救手册 / 官网" />
            </label>
          </div>

          <label class="field">
            <span>标签（英文逗号分隔）</span>
            <input v-model="form.tags" type="text" placeholder="中暑,高温,降温" />
          </label>

          <label class="field">
            <span>内容 <b class="req">*</b></span>
            <textarea v-model="form.content" rows="7" placeholder="详细的知识内容，会被分块后向量化…"></textarea>
          </label>

          <label class="field">
            <span>状态</span>
            <div class="segmented">
              <button :class="{ active: form.status === 1 }" @click="form.status = 1">启用</button>
              <button :class="{ active: form.status === 0 }" @click="form.status = 0">停用</button>
            </div>
          </label>

          <div v-if="formError" class="alert alert-error">{{ formError }}</div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-ghost" @click="showForm = false">取消</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">
            <span v-if="saving" class="spinner"></span>
            {{ saving ? '保存并重建中' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteTarget" class="mask" @click.self="deleteTarget = null">
      <div class="modal small">
        <div class="modal-head">
          <span class="card-title">确认删除</span>
        </div>
        <div class="modal-body">
          <p class="confirm-text">
            确定要删除知识 <b>#{{ deleteTarget.id }} {{ deleteTarget.title }}</b> 吗？
          </p>
          <div class="alert alert-info">删除后会自动重建向量库，该内容将立即无法被检索到。</div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-ghost" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="!!busy" @click="doDelete">
            <span v-if="busy === 'delete'" class="spinner dark"></span>
            删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { knowledgeApi } from '../api'

const list = ref([])
const keyword = ref('')
const filterCategory = ref('')
const busy = ref('')
const error = ref('')
const notice = ref('')

const showForm = ref(false)
const saving = ref(false)
const formError = ref('')
const form = ref(emptyForm())
const deleteTarget = ref(null)

function emptyForm() {
  return { id: null, title: '', content: '', category: '', tags: '', source: '', status: 1 }
}

const categories = computed(() => [...new Set(list.value.map((r) => r.category).filter(Boolean))])
const enabledCount = computed(() => list.value.filter((r) => r.status === 1).length)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return list.value.filter((r) => {
    if (filterCategory.value && r.category !== filterCategory.value) return false
    if (!kw) return true
    return [r.title, r.content, r.tags, r.source, r.category]
      .filter(Boolean)
      .some((v) => String(v).toLowerCase().includes(kw))
  })
})

async function load() {
  busy.value = 'load'
  error.value = ''
  try {
    const data = await knowledgeApi.list()
    list.value = Array.isArray(data) ? data : []
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function doRebuild() {
  busy.value = 'rebuild'
  error.value = ''
  notice.value = ''
  try {
    const res = await knowledgeApi.rebuild()
    notice.value = `${res}（旧向量已清空并按 MySQL 最新数据重新生成）`
    setTimeout(() => (notice.value = ''), 6000)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

function openCreate() {
  form.value = emptyForm()
  formError.value = ''
  showForm.value = true
}

function openEdit(row) {
  form.value = { ...row }
  formError.value = ''
  showForm.value = true
}

async function save() {
  if (!form.value.title?.trim()) return (formError.value = '标题不能为空')
  if (!form.value.content?.trim()) return (formError.value = '内容不能为空')

  saving.value = true
  formError.value = ''
  try {
    if (form.value.id) {
      await knowledgeApi.update(form.value)
      notice.value = '修改成功，向量库已重建'
    } else {
      await knowledgeApi.add(form.value)
      notice.value = '新增成功，向量库已重建，可以立刻在「急救问答」里检索到'
    }
    setTimeout(() => (notice.value = ''), 6000)
    showForm.value = false
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}

function askDelete(row) {
  deleteTarget.value = row
}

async function doDelete() {
  busy.value = 'delete'
  error.value = ''
  try {
    const res = await knowledgeApi.remove(deleteTarget.value.id)
    if (!res?.success) throw new Error(res?.message || '删除失败')
    notice.value = res.message
    setTimeout(() => (notice.value = ''), 6000)
    deleteTarget.value = null
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

function fmtDate(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}

onMounted(load)
</script>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}

.stat {
  padding: 14px 16px;
}

.stat-num {
  font-size: 24px;
  font-weight: 650;
  color: var(--primary);
  line-height: 1.2;
}

.stat-label {
  font-size: 12px;
  color: var(--text-2);
  margin-top: 2px;
}

.toolbar {
  display: flex;
  gap: 10px;
  padding: 12px;
  margin-bottom: 14px;
  align-items: center;
}

.search {
  flex: 1;
}

.select {
  width: 140px;
}

.toolbar-right {
  display: flex;
  gap: 8px;
}

.table-card {
  overflow: hidden;
}

table {
  width: 100%;
  border-collapse: collapse;
}

th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  background: var(--panel-2);
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

td {
  padding: 11px 12px;
  border-bottom: 1px solid var(--border);
  font-size: 13.5px;
  vertical-align: middle;
}

tbody tr:last-child td {
  border-bottom: none;
}

tbody tr:hover {
  background: #fbfcfe;
}

.strong {
  font-weight: 550;
}

.muted {
  color: var(--text-2);
}

.small {
  font-size: 12.5px;
}

.ellipsis {
  max-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-2);
}

.link {
  border: none;
  background: none;
  color: var(--primary);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  padding: 2px 6px;
}

.link:hover {
  text-decoration: underline;
}

.link.danger {
  color: var(--danger);
}

/* 弹窗 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(16, 24, 40, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
  padding: 20px;
}

.modal {
  width: 620px;
  max-width: 100%;
  max-height: 88vh;
  overflow-y: auto;
  background: var(--panel);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}

.modal.small {
  width: 440px;
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 18px;
  border-bottom: 1px solid var(--border);
}

.close {
  border: none;
  background: none;
  font-size: 16px;
  color: var(--text-3);
  cursor: pointer;
}

.modal-body {
  padding: 18px;
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 13px 18px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.confirm-text {
  margin: 0 0 12px;
  font-size: 14px;
}
</style>
