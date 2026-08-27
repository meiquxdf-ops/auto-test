<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { restartAgent, stopAgent, updateAgent } from '@/api/agents'
import { errorMessage, toastError, toastOk } from '@/api/http'
import { getTimeline } from '@/api/timeline'
import type { Agent, AgentStatus, TimelineEvent } from '@/api/types'
import { useAgents } from '@/stores/agents'
import { formatFullTime, fromNow, shortId } from '@/utils/format'
import { AGENT_STATUS_META } from '@/utils/status'
import AgentStatusLight from '@/components/AgentStatusLight.vue'
import CopyableId from '@/components/CopyableId.vue'
import EmptyState from '@/components/EmptyState.vue'
import TimelineList from '@/components/TimelineList.vue'

const router = useRouter()
const route = useRoute()
const { agents, loading, error, refresh, sseState } = useAgents()

const keyword = ref('')
const statusFilter = ref<AgentStatus | 'all'>('all')

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
  { value: 'online', label: '在线（空闲）' },
  { value: 'busy', label: '忙碌' },
  { value: 'disconnected', label: '失联' },
  { value: 'offline', label: '离线' },
]

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
  if (!/^[\w.\-:@]{1,64}$/.test(tag)) return '只允许字母、数字、下划线、点、中划线、冒号、@，长度 ≤ 64'
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
          {{ stats.offline }}；状态经 <code class="code-inline">/api/sse/agents</code> 实时推送（{{ sseState === 'open' ? '已连接' : '未连接' }}）
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Refresh'" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索 tag / agentId / IP / 版本"
          clearable
          :prefix-icon="'Search'"
          style="width: 280px"
        />
        <el-select v-model="statusFilter" style="width: 150px">
          <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <div class="legend">
          <span v-for="(meta, key) in AGENT_STATUS_META" :key="key" class="legend__item">
            <i class="legend__dot" :style="{ background: meta.color }" />{{ meta.label }}
          </span>
        </div>
        <span class="spacer" />
        <span class="muted">显示 {{ filtered.length }} / {{ agents.length }}</span>
      </div>

      <el-alert v-if="error && agents.length" type="error" :closable="false" show-icon :title="error" />

      <el-table
        v-if="filtered.length"
        :data="filtered"
        :row-class-name="rowClass"
        size="default"
        row-key="agentId"
      >
        <el-table-column label="状态" width="118" fixed>
          <template #default="{ row }">
            <AgentStatusLight :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="机器名 / tag" min-width="190" fixed>
          <template #default="{ row }">
            <div class="tag-cell">
              <button class="tag-cell__name link-btn" @click="openTimeline(row)">
                {{ row.displayTag || '（未命名）' }}
              </button>
              <el-tooltip content="改名" placement="top">
                <el-icon class="tag-cell__edit" @click.stop="openEdit(row)"><EditPen /></el-icon>
              </el-tooltip>
            </div>
            <CopyableId :value="row.agentId" :head="10" />
          </template>
        </el-table-column>

        <el-table-column label="并发" width="200">
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

        <el-table-column label="运行中执行" min-width="170">
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
            <span v-else-if="row.running > 0" class="muted">{{ row.running }} 条（未返回 ID）</span>
            <span v-else class="muted">空闲</span>
          </template>
        </el-table-column>

        <el-table-column label="版本" width="100">
          <template #default="{ row }">
            <span class="mono sub">{{ row.version || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="IP" width="130">
          <template #default="{ row }">
            <span class="mono sub">{{ row.ip || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="最后心跳" width="130">
          <template #default="{ row }">
            <el-tooltip :content="formatFullTime(row.lastSeenAt)" placement="top">
              <span class="sub">{{ fromNow(row.lastSeenAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="290" fixed="right" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openTimeline(row)">时间线</el-button>
            <el-button
              size="small"
              text
              :loading="acting === `stop:${row.agentId}`"
              :disabled="row.status === 'offline'"
              @click="onStop(row)"
            >
              停止任务
            </el-button>
            <el-button
              size="small"
              text
              type="danger"
              :loading="acting === `restart:${row.agentId}`"
              :disabled="row.status === 'offline'"
              :title="row.status === 'offline' ? '离线无法远程重启。新机器请在目标机上跑 deploy/install.sh，不要点这里。' : ''"
              @click="onRestart(row)"
            >
              重启 Agent
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-else-if="loading"
        title="正在加载机器列表…"
        size="small"
      />
      <EmptyState
        v-else-if="agents.length"
        variant="search"
        title="没有匹配的机器"
        desc="换个关键字或清空筛选条件试试"
      >
        <el-button size="small" @click="((keyword = ''), (statusFilter = 'all'))">清空筛选</el-button>
      </EmptyState>
      <EmptyState v-else-if="error" variant="error" title="机器列表加载失败" :desc="error">
        <el-button size="small" @click="refresh">重试</el-button>
      </EmptyState>
      <EmptyState
        v-else
        title="还没有机器接入"
        desc="Agent 安装后会自动向 Server 注册（TCP :9800），注册成功即出现在这里"
      >
        <el-button size="small" :icon="'Refresh'" @click="refresh">重新加载</el-button>
      </EmptyState>
    </div>

    <!-- 改名 / 改并发 -->
    <el-dialog v-model="editVisible" title="编辑机器" width="480px">
      <el-form label-width="92px">
        <el-form-item label="agentId">
          <span class="mono sub">{{ editForm.agentId }}</span>
        </el-form-item>
        <el-form-item label="机器名 tag" :error="tagError || undefined">
          <el-input v-model="editForm.displayTag" placeholder="例如 perf-node-01" spellcheck="false" />
        </el-form-item>
        <el-form-item label="最大并发">
          <el-input-number
            v-model="editForm.concurrency"
            :min="1"
            :max="4"
            :disabled="!canEditConcurrency"
            controls-position="right"
          />
          <div class="muted" style="margin-left: 10px">
            {{ canEditConcurrency ? '范围 1 - 4' : `当前有 ${editForm.running} 条执行在跑，空闲后才能修改` }}
          </div>
        </el-form-item>
      </el-form>
      <div class="muted">机器名全局唯一，可与 agentId 互相解析；重名会被 Server 拒绝。</div>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" :disabled="!!tagError" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 机器时间线 -->
    <el-drawer v-model="drawerVisible" size="720px" :with-header="false">
      <div class="drawer">
        <div class="drawer__head">
          <div>
            <div class="drawer__title">
              {{ drawerAgent?.displayTag || drawerAgent?.agentId }}
              <AgentStatusLight v-if="drawerAgent" :status="drawerAgent.status" />
            </div>
            <div class="drawer__sub mono">{{ drawerAgent?.agentId }}</div>
          </div>
          <div class="drawer__actions">
            <el-button size="small" :icon="'Refresh'" :loading="drawerLoading" @click="loadDrawer">刷新</el-button>
            <el-button size="small" type="primary" plain @click="gotoFullTimeline">在时间线页打开</el-button>
            <el-button size="small" text :icon="'Close'" @click="drawerVisible = false" />
          </div>
        </div>

        <div v-if="drawerAgent" class="drawer__meta">
          <div class="kv">
            <span class="kv__k">session</span>
            <span class="kv__v"><CopyableId :value="drawerAgent.sessionId" :head="12" /></span>
            <span class="kv__k">bootId</span>
            <span class="kv__v"><CopyableId :value="drawerAgent.bootId" :head="12" /></span>
            <span class="kv__k">并发</span>
            <span class="kv__v">{{ drawerAgent.running }} / {{ drawerAgent.concurrency }}</span>
            <span class="kv__k">最后心跳</span>
            <span class="kv__v">{{ formatFullTime(drawerAgent.lastSeenAt) }}</span>
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

.legend {
  display: flex;
  gap: 12px;
  margin-left: 4px;
}

.legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--nat-text-weak);
}

.legend__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.tag-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tag-cell__name {
  font-weight: 580;
  font-size: 13.5px;
  color: var(--nat-text);
}

.tag-cell__name:hover {
  color: var(--nat-accent);
}

.tag-cell__edit {
  color: var(--nat-text-weak);
  cursor: pointer;
}

.tag-cell__edit:hover {
  color: var(--nat-accent);
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

.drawer__title {
  font-size: 16px;
  font-weight: 640;
  display: flex;
  align-items: center;
  gap: 10px;
}

.drawer__sub {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-top: 3px;
}

.drawer__actions {
  display: flex;
  align-items: center;
  gap: 6px;
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
