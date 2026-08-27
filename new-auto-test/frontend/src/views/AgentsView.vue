<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { restartAgent, stopAgent, updateAgent } from '@/api/agents'
import { errorMessage, toastError, toastOk } from '@/api/http'
import { getTimeline } from '@/api/timeline'
import type { Agent, AgentStatus, TimelineEvent } from '@/api/types'
import { useAgents } from '@/stores/agents'
import { formatFullTime, fromNow, shortId } from '@/utils/format'
import AgentInstallDrawer from '@/components/AgentInstallDrawer.vue'
import AgentStatusLight from '@/components/AgentStatusLight.vue'
import CopyableId from '@/components/CopyableId.vue'
import EmptyState from '@/components/EmptyState.vue'
import TimelineList from '@/components/TimelineList.vue'

const router = useRouter()
const route = useRoute()
const { agents, loading, error, refresh, sseState } = useAgents()

const keyword = ref('')
const statusFilter = ref<AgentStatus | 'all'>('all')
const installVisible = ref(false)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return agents.value.filter((a) => {
    if (statusFilter.value !== 'all' && a.status !== statusFilter.value) return false
    if (!kw) return true
    return (
      a.displayTag.toLowerCase().includes(kw) ||
      a.agentId.toLowerCase().includes(kw) ||
      (a.ip ?? '').toLowerCase().includes(kw) ||
      (a.version ?? '').toLowerCase().includes(kw)
    )
  })
})

const stats = computed(() => {
  const s = { online: 0, busy: 0, disconnected: 0, offline: 0 }
  for (const a of agents.value) s[a.status] += 1
  return s
})

const statusOptions: { value: AgentStatus | 'all'; label: string }[] = [
  { value: 'all', label: '全部状态' },
  { value: 'online', label: '在线' },
  { value: 'busy', label: '忙碌' },
  { value: 'disconnected', label: '失联' },
  { value: 'offline', label: '离线' },
]

/* ------------------------------------------------------ 响应式列显示 */

/** 侧边导航 216px + 页面留白约 260px，够放下的列才显示，避免整表横向滚动 */
const viewport = ref(window.innerWidth)

function onResize() {
  viewport.value = window.innerWidth
}

onMounted(() => window.addEventListener('resize', onResize, { passive: true }))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const showExec = computed(() => viewport.value >= 1100)
const showIp = computed(() => viewport.value >= 1280)
const showVersion = computed(() => viewport.value >= 1400)

/* ---------------------------------------------------------------- 编辑 */

const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({
  agentId: '',
  originalTag: '',
  displayTag: '',
  concurrency: 1,
  running: 0,
  originalConcurrency: 1,
})

const tagError = computed(() => {
  const tag = editForm.displayTag.trim()
  if (!tag) return '名字不能为空'
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(tag)) return '只允许字母数字和 . _ -，长度 ≤ 64（与 install.sh --tag 相同）'
  const dup = agents.value.find((a) => a.displayTag === tag && a.agentId !== editForm.agentId)
  if (dup) return `该名字已被 ${shortId(dup.agentId)} 占用`
  return ''
})

const canEditConcurrency = computed(() => editForm.running === 0)

function openEdit(agent: Agent) {
  editForm.agentId = agent.agentId
  editForm.originalTag = agent.displayTag
  editForm.displayTag = agent.displayTag
  editForm.concurrency = agent.concurrency
  editForm.originalConcurrency = agent.concurrency
  editForm.running = agent.running
  editVisible.value = true
}

async function saveEdit() {
  if (tagError.value) return
  const body: { displayTag?: string; concurrency?: number } = {}
  if (editForm.displayTag.trim() !== editForm.originalTag) body.displayTag = editForm.displayTag.trim()
  if (canEditConcurrency.value && editForm.concurrency !== editForm.originalConcurrency) {
    body.concurrency = editForm.concurrency
  }
  if (!Object.keys(body).length) {
    editVisible.value = false
    return
  }
  editSaving.value = true
  try {
    await updateAgent(editForm.agentId, body)
    toastOk('已保存')
    editVisible.value = false
    await refresh()
  } catch (e) {
    toastError(e, '保存失败')
  } finally {
    editSaving.value = false
  }
}

/* ------------------------------------------------------ 快捷改并发 */

const concurrencySaving = ref<string | null>(null)

async function changeConcurrency(agent: Agent, value: number) {
  if (value === agent.concurrency) return
  concurrencySaving.value = agent.agentId
  try {
    await updateAgent(agent.agentId, { concurrency: value })
    toastOk(`${agent.displayTag || agent.agentId} 并发已改为 ${value}`)
    await refresh()
  } catch (e) {
    toastError(e, '修改并发失败')
    await refresh()
  } finally {
    concurrencySaving.value = null
  }
}

/* ---------------------------------------------------------------- 危险操作 */

const acting = ref<string>('')

function isActing(agent: Agent): boolean {
  return acting.value.endsWith(`:${agent.agentId}`)
}

async function onRestart(agent: Agent) {
  const name = agent.displayTag || agent.agentId
  try {
    await ElMessageBox.confirm(
      agent.running > 0
        ? `【${name}】上还有 ${agent.running} 条执行在运行，重启 Agent 会中断它们并判为 exception。确认继续？`
        : `确认重启【${name}】的 Agent 进程？重启期间该机器会短暂离线。`,
      '重启 Agent',
      {
        type: 'warning',
        confirmButtonText: '确认重启',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }
  acting.value = `restart:${agent.agentId}`
  try {
    await restartAgent(agent.agentId)
    toastOk(`已向 ${name} 下发重启指令`)
    window.setTimeout(() => void refresh(), 1200)
  } catch (e) {
    toastError(e, '重启失败')
  } finally {
    acting.value = ''
  }
}

async function onStop(agent: Agent) {
  const name = agent.displayTag || agent.agentId
  if (agent.running === 0) {
    try {
      await ElMessageBox.confirm(`【${name}】当前没有运行中的执行，仍然下发 stop 指令？`, '停止当前任务', {
        type: 'info',
        confirmButtonText: '仍然下发',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  } else {
    try {
      await ElMessageBox.confirm(
        `将停止【${name}】上全部 ${agent.running} 条执行，进程组会被杀掉，结果判为 canceled。确认继续？`,
        '停止当前任务',
        {
          type: 'warning',
          confirmButtonText: '确认停止',
          cancelButtonText: '取消',
          confirmButtonClass: 'el-button--danger',
        },
      )
    } catch {
      return
    }
  }
  acting.value = `stop:${agent.agentId}`
  try {
    await stopAgent(agent.agentId)
    toastOk(`已向 ${name} 下发停止指令`)
    window.setTimeout(() => void refresh(), 1000)
  } catch (e) {
    toastError(e, '停止失败')
  } finally {
    acting.value = ''
  }
}

/* ---------------------------------------------------------------- 时间线抽屉 */

const drawerVisible = ref(false)
const drawerAgent = ref<Agent | null>(null)
const drawerEvents = ref<TimelineEvent[]>([])
const drawerLoading = ref(false)
const drawerError = ref('')

async function openTimeline(agent: Agent) {
  drawerAgent.value = agent
  drawerVisible.value = true
  await loadDrawer()
}

async function loadDrawer() {
  const agent = drawerAgent.value
  if (!agent) return
  drawerLoading.value = true
  try {
    drawerEvents.value = await getTimeline({ agentId: agent.agentId, limit: 100 })
    drawerError.value = ''
  } catch (e) {
    drawerError.value = errorMessage(e, '加载时间线失败')
    drawerEvents.value = []
  } finally {
    drawerLoading.value = false
  }
}

function gotoFullTimeline() {
  if (!drawerAgent.value) return
  void router.push({ path: '/timeline', query: { agentId: drawerAgent.value.agentId } })
}

/** 支持 /agents?focus=xxx 直接定位一台机器 */
watch(
  () => route.query.focus,
  (focus) => {
    if (typeof focus === 'string' && focus) keyword.value = focus
  },
  { immediate: true },
)

function rowClass({ row }: { row: Agent }) {
  return row.status === 'offline' ? 'is-offline' : ''
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">机器</h2>
        <p class="page-head__desc">
          共 {{ agents.length }} 台 · 在线 {{ stats.online + stats.busy }} · 失联 {{ stats.disconnected }} · 离线
          {{ stats.offline }}
          <span class="sse" :class="{ 'is-on': sseState === 'open' }">
            {{ sseState === 'open' ? '实时推送中' : '推送未连接' }}
          </span>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Refresh'" :loading="loading" @click="refresh">刷新</el-button>
        <el-button type="primary" :icon="'Plus'" @click="installVisible = true">安装 Agent</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索机器名 / agentId / IP"
          clearable
          :prefix-icon="'Search'"
          class="toolbar__search"
        />
        <el-select v-model="statusFilter" style="width: 128px">
          <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <span class="spacer" />
        <span class="muted">{{ filtered.length }} / {{ agents.length }}</span>
      </div>

      <el-alert v-if="error && agents.length" type="error" :closable="false" show-icon :title="error" />

      <el-table
        v-if="filtered.length"
        :data="filtered"
        :row-class-name="rowClass"
        size="default"
        row-key="agentId"
      >
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <AgentStatusLight :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="机器名" min-width="220">
          <template #default="{ row }">
            <div class="tag-cell">
              <el-tooltip :content="row.displayTag || row.agentId" placement="top-start" :show-after="500">
                <button class="tag-cell__name link-btn" @click="openTimeline(row)">
                  {{ row.displayTag || '（未命名）' }}
                </button>
              </el-tooltip>
              <el-tooltip content="改名" placement="top">
                <el-icon class="tag-cell__edit" @click.stop="openEdit(row)"><EditPen /></el-icon>
              </el-tooltip>
            </div>
            <CopyableId :value="row.agentId" :head="10" />
          </template>
        </el-table-column>

        <el-table-column label="并发" width="150">
          <template #default="{ row }">
            <div class="conc">
              <el-tooltip
                :disabled="row.running === 0"
                content="仅空闲时可改并发，当前有执行在跑"
                placement="top"
              >
                <span>
                  <el-input-number
                    :model-value="row.concurrency"
                    :min="1"
                    :max="4"
                    :step="1"
                    size="small"
                    controls-position="right"
                    :disabled="row.running !== 0 || concurrencySaving === row.agentId"
                    style="width: 92px"
                    @change="(v: number | undefined) => changeConcurrency(row, Number(v ?? 1))"
                  />
                </span>
              </el-tooltip>
              <span class="conc__usage mono" :class="{ 'is-full': row.running >= row.concurrency && row.running > 0 }">
                {{ row.running }}/{{ row.concurrency }}
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="showExec" label="运行中执行" min-width="160">
          <template #default="{ row }">
            <div v-if="row.runningExecuteIds.length" class="exec-links">
              <router-link
                v-for="id in row.runningExecuteIds.slice(0, 3)"
                :key="id"
                :to="`/executions/${id}`"
                class="link-btn mono"
              >
                {{ id.slice(0, 8) }}
              </router-link>
              <span v-if="row.runningExecuteIds.length > 3" class="muted">
                +{{ row.runningExecuteIds.length - 3 }}
              </span>
            </div>
            <span v-else-if="row.running > 0" class="muted">{{ row.running }} 条</span>
            <span v-else class="muted">空闲</span>
          </template>
        </el-table-column>

        <el-table-column v-if="showVersion" label="版本" width="110">
          <template #default="{ row }">
            <el-tooltip :disabled="!row.version" :content="row.version" placement="top" :show-after="400">
              <span class="mono sub cell-1">{{ row.version || '-' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column v-if="showIp" label="IP" width="150">
          <template #default="{ row }">
            <el-tooltip :disabled="!row.ip" :content="row.ip" placement="top" :show-after="400">
              <span class="mono sub cell-1">{{ row.ip || '-' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="最后心跳" width="120">
          <template #default="{ row }">
            <el-tooltip :content="formatFullTime(row.lastSeenAt)" placement="top">
              <span class="sub cell-1">{{ fromNow(row.lastSeenAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="96" align="right">
          <template #default="{ row }">
            <div class="ops">
              <el-tooltip content="时间线" placement="top">
                <el-button size="small" text :icon="'Histogram'" @click="openTimeline(row)" />
              </el-tooltip>
              <el-dropdown trigger="click" placement="bottom-end">
                <el-button size="small" text :icon="'MoreFilled'" :loading="isActing(row)" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :icon="'EditPen'" @click="openEdit(row)">改名 / 并发</el-dropdown-item>
                    <el-dropdown-item
                      divided
                      :icon="'VideoPause'"
                      :disabled="row.status === 'offline'"
                      @click="onStop(row)"
                    >
                      停止任务
                    </el-dropdown-item>
                    <el-dropdown-item
                      :icon="'RefreshRight'"
                      :disabled="row.status === 'offline'"
                      @click="onRestart(row)"
                    >
                      重启 Agent
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState v-else-if="loading" title="正在加载机器列表…" size="small" />
      <EmptyState
        v-else-if="agents.length"
        variant="search"
        title="没有匹配的机器"
        desc="换个关键字或清空筛选试试"
      >
        <el-button size="small" @click="((keyword = ''), (statusFilter = 'all'))">清空筛选</el-button>
      </EmptyState>
      <EmptyState v-else-if="error" variant="error" title="机器列表加载失败" :desc="error">
        <el-button size="small" @click="refresh">重试</el-button>
      </EmptyState>
      <EmptyState v-else title="还没有机器接入" desc="装好 Agent 会自动注册（TCP :9800），随后出现在这里">
        <el-button size="small" type="primary" :icon="'Plus'" @click="installVisible = true">安装 Agent</el-button>
        <el-button size="small" :icon="'Refresh'" @click="refresh">重新加载</el-button>
      </EmptyState>
    </div>

    <!-- 改名 / 改并发 -->
    <el-dialog v-model="editVisible" title="编辑机器" :width="'min(480px, calc(100vw - 32px))'">
      <el-form label-width="80px" class="edit-form">
        <el-form-item label="agentId">
          <span class="mono sub edit-form__id">{{ editForm.agentId }}</span>
        </el-form-item>
        <el-form-item label="机器名" :error="tagError || undefined">
          <el-input v-model="editForm.displayTag" placeholder="例如 perf-node-01" spellcheck="false" />
          <div class="form-hint">全局唯一，重名会被 Server 拒绝</div>
        </el-form-item>
        <el-form-item label="最大并发">
          <el-input-number
            v-model="editForm.concurrency"
            :min="1"
            :max="4"
            :disabled="!canEditConcurrency"
            controls-position="right"
          />
          <div class="form-hint">
            {{ canEditConcurrency ? '范围 1 - 4' : `有 ${editForm.running} 条执行在跑，空闲后才能改` }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" :disabled="!!tagError" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 安装 Agent：复制命令 / curl 一行安装 / SSH 代装 -->
    <AgentInstallDrawer v-model="installVisible" :agents="agents" @refresh="refresh" />

    <!-- 机器时间线 -->
    <el-drawer v-model="drawerVisible" :size="'min(720px, 100vw)'" :with-header="false">
      <div class="drawer">
        <div class="drawer__head">
          <div class="drawer__ident">
            <div class="drawer__title">
              <span class="drawer__name">{{ drawerAgent?.displayTag || drawerAgent?.agentId }}</span>
              <AgentStatusLight v-if="drawerAgent" :status="drawerAgent.status" />
            </div>
            <div class="drawer__sub mono">{{ drawerAgent?.agentId }}</div>
          </div>
          <div class="drawer__actions">
            <el-button size="small" :icon="'Refresh'" :loading="drawerLoading" @click="loadDrawer">刷新</el-button>
            <el-button size="small" type="primary" plain @click="gotoFullTimeline">全部时间线</el-button>
            <el-button size="small" text :icon="'Close'" @click="drawerVisible = false" />
          </div>
        </div>

        <div v-if="drawerAgent" class="drawer__meta">
          <div class="kv">
            <span class="kv__k">并发</span>
            <span class="kv__v">{{ drawerAgent.running }} / {{ drawerAgent.concurrency }}</span>
            <span class="kv__k">IP / 版本</span>
            <span class="kv__v mono">{{ drawerAgent.ip || '-' }} · {{ drawerAgent.version || '-' }}</span>
            <span class="kv__k">最后心跳</span>
            <span class="kv__v">{{ formatFullTime(drawerAgent.lastSeenAt) }}</span>
            <span class="kv__k">session</span>
            <span class="kv__v"><CopyableId :value="drawerAgent.sessionId" :head="12" /></span>
            <span class="kv__k">bootId</span>
            <span class="kv__v"><CopyableId :value="drawerAgent.bootId" :head="12" /></span>
          </div>
        </div>

        <el-alert v-if="drawerError" type="error" :closable="false" show-icon :title="drawerError" class="mb10" />

        <div class="drawer__body">
          <TimelineList :events="drawerEvents" :loading="drawerLoading" empty-text="该机器暂无事件" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.sse {
  margin-left: 8px;
  color: var(--nat-text-weak);
}

.sse::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 50%;
  background: var(--nat-text-weak);
  vertical-align: 1px;
}

.sse.is-on::before {
  background: #16a34a;
}

.toolbar__search {
  width: 260px;
  max-width: 100%;
}

/* 单行省略：表格默认 word-break 会把 IP / 版本从中间折断 */
.cell-1 {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.tag-cell__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  font-weight: 580;
  font-size: 13.5px;
  color: var(--nat-text);
}

.tag-cell__name:hover {
  color: var(--nat-accent);
}

.tag-cell__edit {
  flex: none;
  color: var(--nat-text-weak);
  cursor: pointer;
  opacity: 0.45;
  transition: opacity 0.15s ease, color 0.15s ease;
}

.tag-cell:hover .tag-cell__edit {
  opacity: 1;
}

.tag-cell__edit:hover {
  color: var(--nat-accent);
}

.ops {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.ops :deep(.el-button) {
  margin-left: 0;
}

.conc {
  display: flex;
  align-items: center;
  gap: 10px;
}

.conc__usage {
  color: var(--nat-text-sub);
  font-size: 12px;
}

.conc__usage.is-full {
  color: #2563eb;
  font-weight: 600;
}

.exec-links {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

:deep(.el-table .is-offline) {
  background: #fafbfc;
  color: var(--nat-text-weak);
}

/* 提示文案排在输入框下一行，且不跟着 label 缩进 */
.form-hint {
  width: 100%;
  margin-left: 0;
  margin-top: 4px;
  color: var(--nat-text-weak);
  font-size: 12px;
  line-height: 1.6;
}

.edit-form__id {
  word-break: break-all;
}

.drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.drawer__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--nat-border);
}

.drawer__ident {
  flex: 1;
  min-width: 0;
}

.drawer__title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-size: 16px;
  font-weight: 640;
}

/* 长机器名截断，状态灯紧跟其后而不是被推到最右 */
.drawer__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer__title :deep(.agent-light) {
  flex: none;
}

.drawer__sub {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.drawer__meta {
  padding: 12px 0;
  border-bottom: 1px solid var(--nat-border);
}

.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding-top: 8px;
}

.mb10 {
  margin: 10px 0;
}
</style>
