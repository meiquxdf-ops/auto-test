<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { cancelTask, listTasks, reorderTasks, rerunTask } from '@/api/tasks'
import { errorMessage, toastError, toastOk } from '@/api/http'
import {
  EXECUTION_STATUSES,
  type Execution,
  type ExecutionStatus,
  type Task,
  type TaskFormPreset,
} from '@/api/types'
import { countExecutions } from '@/utils/aggregate'
import { durationBetween, formatFullTime, formatTime, truncateText } from '@/utils/format'
import { isTerminal, statusMeta } from '@/utils/status'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'
import CopyableId from '@/components/CopyableId.vue'
import TaskCreateDrawer from '@/components/TaskCreateDrawer.vue'
import QueueReorderDrawer from '@/components/QueueReorderDrawer.vue'

const router = useRouter()
const route = useRoute()

const tasks = ref<Task[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const statusFilter = ref<ExecutionStatus | ''>('')
const autoRefresh = ref(true)
const lastLoadedAt = ref<number | null>(null)
const acting = ref('')

const createVisible = ref(false)
const reorderVisible = ref(false)
const createPreset = ref<TaskFormPreset | null>(null)

let timer: number | null = null

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    tasks.value = await listTasks({ limit: 300 })
    error.value = ''
    lastLoadedAt.value = Date.now()
  } catch (e) {
    error.value = errorMessage(e, '加载任务列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (typeof route.query.status === 'string') {
    const s = route.query.status as ExecutionStatus
    if (EXECUTION_STATUSES.includes(s)) statusFilter.value = s
  }
  if (route.query.create === '1') createVisible.value = true
  void load()
  timer = window.setInterval(() => {
    if (autoRefresh.value && !reorderVisible.value && !createVisible.value) void load(true)
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})

const counts = computed(() => countExecutions(tasks.value))

const statusTabs = computed(() => {
  const taskCount = (s: ExecutionStatus) => tasks.value.filter((t) => t.status === s).length
  return [
    { value: '' as const, label: '全部', n: tasks.value.length },
    ...EXECUTION_STATUSES.map((s) => ({ value: s, label: statusMeta(s).label, n: taskCount(s) })),
  ]
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return tasks.value.filter((t) => {
    if (statusFilter.value && t.status !== statusFilter.value) return false
    if (!kw) return true
    return (
      t.command.toLowerCase().includes(kw) ||
      t.taskId.toLowerCase().includes(kw) ||
      (t.operator ?? '').toLowerCase().includes(kw) ||
      t.targets.some((x) => x.toLowerCase().includes(kw))
    )
  })
})

const pendingTasks = computed(() => tasks.value.filter((t) => t.status === 'pending'))

function pendingIndex(task: Task): number {
  return pendingTasks.value.findIndex((t) => t.taskId === task.taskId)
}

/* ------------------------------------------------------------ 队列排序 */

async function movePending(task: Task, delta: number) {
  const ids = pendingTasks.value.map((t) => t.taskId)
  const i = ids.indexOf(task.taskId)
  const j = i + delta
  if (i < 0 || j < 0 || j >= ids.length) return
  const nextIds = [...ids]
  ;[nextIds[i], nextIds[j]] = [nextIds[j], nextIds[i]]

  // 乐观更新：先在本地按新顺序摆好，失败再回滚
  const backup = [...tasks.value]
  const byId = new Map(tasks.value.map((t) => [t.taskId, t]))
  const reordered = nextIds.map((id) => byId.get(id)).filter(Boolean) as Task[]
  const others = tasks.value.filter((t) => !nextIds.includes(t.taskId))
  tasks.value = [...reordered, ...others].sort((a, b) => {
    const ap = a.status === 'pending' ? 0 : 1
    const bp = b.status === 'pending' ? 0 : 1
    return ap - bp
  })

  acting.value = `move:${task.taskId}`
  try {
    await reorderTasks(nextIds)
    toastOk('顺序已更新')
    await load(true)
  } catch (e) {
    tasks.value = backup
    toastError(e, '调整顺序失败')
  } finally {
    acting.value = ''
  }
}

/* ------------------------------------------------------------ 取消 / 重跑 */

async function onCancel(task: Task) {
  const runningN = task.counts.running + task.counts.dispatching
  try {
    await ElMessageBox.confirm(
      runningN
        ? `该任务有 ${runningN} 条执行正在运行，取消会杀掉对应进程组并判为 canceled。确认取消？`
        : '确认取消该任务？未开始的执行会直接置为 canceled。',
      '取消任务',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  acting.value = `cancel:${task.taskId}`
  try {
    await cancelTask(task.taskId)
    toastOk('已下发取消指令')
    await load(true)
  } catch (e) {
    toastError(e, '取消失败')
  } finally {
    acting.value = ''
  }
}

async function onRerun(task: Task, mode: 'inplace' | 'new') {
  const text =
    mode === 'inplace'
      ? '原地重跑会清空这条任务的执行记录与日志，并重新入队。历史结果将不可恢复。'
      : '将复制命令、目标、判定配置生成一条新的任务记录，原记录完整保留。'
  try {
    await ElMessageBox.confirm(text, mode === 'inplace' ? '原地重跑' : '重跑为新任务', {
      type: mode === 'inplace' ? 'warning' : 'info',
      confirmButtonText: mode === 'inplace' ? '清空并重跑' : '创建新任务',
      cancelButtonText: '取消',
      confirmButtonClass: mode === 'inplace' ? 'el-button--danger' : '',
    })
  } catch {
    return
  }
  acting.value = `rerun:${task.taskId}`
  try {
    const created = await rerunTask(task.taskId, mode)
    toastOk(mode === 'inplace' ? '已原地重跑' : `已创建新任务${created?.taskId ? ` ${created.taskId.slice(0, 8)}` : ''}`)
    await load(true)
  } catch (e) {
    toastError(e, '重跑失败')
  } finally {
    acting.value = ''
  }
}

function useAsTemplate(task: Task) {
  createPreset.value = {
    command: task.command,
    cwd: task.cwd ?? '',
    env: task.env ?? {},
    targets: [...task.targets],
    timeoutSec: task.timeoutSec ?? 1800,
    operator: task.operator ?? '',
    conditionConfig: task.conditionConfig ?? null,
  }
  createVisible.value = true
}

function openCreate() {
  createPreset.value = null
  createVisible.value = true
}

function onCreated() {
  createPreset.value = null
  void load()
}

function gotoExecution(exec: Execution) {
  void router.push(`/executions/${exec.executeId}`)
}

function progressOf(task: Task) {
  const done = task.counts.pass + task.counts.fail + task.counts.block + task.counts.exception + task.counts.canceled
  const total = task.executions.length || task.total || task.targets.length || 0
  return { done, total }
}

function segmentsOf(task: Task) {
  const total = task.executions.length || 1
  return (EXECUTION_STATUSES.filter((s) => task.counts[s] > 0) as ExecutionStatus[]).map((s) => ({
    key: s,
    pct: (task.counts[s] / total) * 100,
    color: statusMeta(s).color,
    label: `${statusMeta(s).label} ${task.counts[s]}`,
  }))
}

function clearFilters() {
  keyword.value = ''
  statusFilter.value = ''
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">任务队列</h2>
        <p class="page-head__desc">
          共 {{ tasks.length }} 个任务 · 执行 {{ Object.values(counts).reduce((a, b) => a + b, 0) }} 条（运行中
          {{ counts.running + counts.dispatching }}，排队 {{ counts.pending }}）
          <template v-if="lastLoadedAt"> · 更新于 {{ formatTime(lastLoadedAt) }}</template>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Sort'" :disabled="pendingTasks.length < 2" @click="reorderVisible = true">
          调整排队顺序
          <el-tag v-if="pendingTasks.length" size="small" round class="ml6">{{ pendingTasks.length }}</el-tag>
        </el-button>
        <el-button :icon="'Refresh'" :loading="loading" @click="load()">刷新</el-button>
        <el-button type="primary" :icon="'Plus'" @click="openCreate">创建任务</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar">
        <el-radio-group v-model="statusFilter" size="small">
          <el-radio-button v-for="tab in statusTabs" :key="tab.value" :value="tab.value">
            {{ tab.label }}
            <span class="tab-n">{{ tab.n }}</span>
          </el-radio-button>
        </el-radio-group>
        <span class="spacer" />
        <el-input
          v-model="keyword"
          placeholder="搜索命令 / taskId / 目标 / 操作人"
          clearable
          :prefix-icon="'Search'"
          style="width: 260px"
        />
        <el-tooltip content="每 5 秒自动刷新列表" placement="top">
          <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        </el-tooltip>
      </div>

      <el-alert v-if="error && tasks.length" type="error" :closable="false" show-icon :title="error" />

      <el-table
        v-if="filtered.length"
        :data="filtered"
        row-key="taskId"
        size="default"
        :expand-row-keys="[]"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="sub">
              <div class="sub__head">
                <span class="sub__title">执行明细（{{ row.executions.length }}）</span>
                <span class="sub__meta">
                  taskId <CopyableId :value="row.taskId" :head="12" />
                  <template v-if="row.cwd"> · cwd <code class="code-inline">{{ row.cwd }}</code></template>
                  <template v-if="row.timeoutSec"> · 超时 {{ row.timeoutSec }}s</template>
                </span>
              </div>

              <div v-if="row.env && Object.keys(row.env).length" class="sub__env">
                <span class="muted">env：</span>
                <code v-for="(v, k) in row.env" :key="k" class="code-inline">{{ k }}={{ v }}</code>
              </div>

              <div v-if="row.conditionConfig" class="sub__cond">
                <span class="muted">判定：</span>
                <span v-for="(rule, i) in row.conditionConfig.rules" :key="i" class="sub__rule">
                  {{ i + 1 }}. {{ rule.operator }} <code class="code-inline">{{ rule.value }}</code> →
                  <StatusPill :status="rule.status" />
                </span>
                <span v-if="row.conditionConfig.other" class="sub__rule">
                  other → <StatusPill :status="row.conditionConfig.other" />
                </span>
              </div>

              <el-table v-if="row.executions.length" :data="row.executions" size="small" class="sub__table">
                <el-table-column label="状态" width="120">
                  <template #default="{ row: ex }">
                    <StatusPill :status="ex.status" :disconnected="ex.disconnected" />
                  </template>
                </el-table-column>
                <el-table-column label="机器" min-width="150">
                  <template #default="{ row: ex }">{{ ex.displayTag || ex.agentId || '-' }}</template>
                </el-table-column>
                <el-table-column label="退出码" width="80" align="center">
                  <template #default="{ row: ex }">
                    <span class="mono">{{ ex.exitCode ?? '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="最后一行" min-width="200">
                  <template #default="{ row: ex }">
                    <code class="cmd">{{ truncateText(ex.lastLine || '-', 60) }}</code>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row: ex }">
                    <span class="mono">{{ durationBetween(ex.startedAt, ex.finishedAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="" width="90" align="right">
                  <template #default="{ row: ex }">
                    <el-button size="small" text type="primary" @click="gotoExecution(ex)">查看日志</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <EmptyState
                v-else
                size="small"
                title="该任务还没有生成执行记录"
                :desc="`目标：${row.targets.join('、') || '未指定'}`"
              />
            </div>
          </template>
        </el-table-column>

        <el-table-column label="#" width="72" align="center">
          <template #default="{ row }">
            <div v-if="row.status === 'pending'" class="ord">
              <span class="ord__n">{{ pendingIndex(row) + 1 }}</span>
              <span class="ord__btns">
                <button
                  class="ord__btn"
                  title="上移"
                  :disabled="pendingIndex(row) === 0 || acting === `move:${row.taskId}`"
                  @click="movePending(row, -1)"
                >
                  <el-icon><CaretTop /></el-icon>
                </button>
                <button
                  class="ord__btn"
                  title="下移"
                  :disabled="pendingIndex(row) === pendingTasks.length - 1 || acting === `move:${row.taskId}`"
                  @click="movePending(row, 1)"
                >
                  <el-icon><CaretBottom /></el-icon>
                </button>
              </span>
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="112">
          <template #default="{ row }">
            <StatusPill :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="命令" min-width="240">
          <template #default="{ row }">
            <el-tooltip :content="row.command" placement="top-start" :show-after="500">
              <code class="cmd">{{ truncateText(row.command, 88) }}</code>
            </el-tooltip>
            <div class="row-meta">
              <CopyableId :value="row.taskId" :head="8" />
              <span v-if="row.operator" class="muted">· {{ row.operator }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="目标 / 进度" width="190">
          <template #default="{ row }">
            <div class="prog">
              <span class="prog__text">
                {{ progressOf(row).done }} / {{ progressOf(row).total }} 完成
              </span>
              <div class="prog__bar">
                <span
                  v-for="seg in segmentsOf(row)"
                  :key="seg.key"
                  class="prog__seg"
                  :style="{ width: `${seg.pct}%`, background: seg.color }"
                  :title="seg.label"
                />
              </div>
              <span class="prog__targets muted">{{ truncateText(row.targets.join('、'), 28) || '-' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="164">
          <template #default="{ row }">
            <el-tooltip :content="formatFullTime(row.createdAt)" placement="top">
              <span class="sub mono nowrap time-cell">{{ formatTime(row.createdAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="318" fixed="right" align="right">
          <template #default="{ row }">
            <el-button
              size="small"
              text
              type="danger"
              :disabled="isTerminal(row.status)"
              :loading="acting === `cancel:${row.taskId}`"
              @click="onCancel(row)"
            >
              取消
            </el-button>
            <el-tooltip content="清空原执行记录与日志，在原任务上重跑" placement="top">
              <el-button
                size="small"
                text
                :disabled="!isTerminal(row.status)"
                :loading="acting === `rerun:${row.taskId}`"
                @click="onRerun(row, 'inplace')"
              >
                原地重跑
              </el-button>
            </el-tooltip>
            <el-tooltip content="保留原记录，另起一条新任务" placement="top">
              <el-button size="small" text type="primary" @click="onRerun(row, 'new')">重跑为新记录</el-button>
            </el-tooltip>
            <el-dropdown trigger="click">
              <el-button size="small" text :icon="'MoreFilled'" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item :icon="'CopyDocument'" @click="useAsTemplate(row)">以此为模板创建</el-dropdown-item>
                  <el-dropdown-item
                    v-if="row.executions.length === 1"
                    :icon="'Document'"
                    @click="gotoExecution(row.executions[0])"
                  >
                    查看日志
                  </el-dropdown-item>
                  <el-dropdown-item
                    :icon="'Histogram'"
                    @click="router.push({ path: '/timeline', query: { executeId: row.executions[0]?.executeId ?? '' } })"
                  >
                    查看时间线
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState v-else-if="loading" title="正在加载任务…" size="small" />
      <EmptyState
        v-else-if="tasks.length"
        variant="search"
        title="没有符合条件的任务"
        desc="换个状态或关键字试试"
      >
        <el-button size="small" @click="clearFilters">清空筛选</el-button>
      </EmptyState>
      <EmptyState v-else-if="error" variant="error" title="任务列表加载失败" :desc="error">
        <el-button size="small" @click="load()">重试</el-button>
      </EmptyState>
      <EmptyState v-else title="还没有任务" desc="创建一个任务，Server 会按队列顺序调度到空闲机器上执行">
        <el-button type="primary" size="small" :icon="'Plus'" @click="openCreate">创建任务</el-button>
        <router-link to="/playground">
          <el-button size="small">去测试下发</el-button>
        </router-link>
      </EmptyState>
    </div>

    <TaskCreateDrawer v-model="createVisible" :preset="createPreset" @created="onCreated" />
    <QueueReorderDrawer v-model="reorderVisible" :tasks="pendingTasks" @saved="load()" />
  </div>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.ml6 {
  margin-left: 6px;
}

.tab-n {
  display: inline-block;
  margin-left: 4px;
  color: var(--nat-text-weak);
  font-variant-numeric: tabular-nums;
}

.cmd {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: #26303d;
  word-break: break-all;
}

.row-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
  font-size: 11.5px;
}

.ord {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.ord__n {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--nat-text-sub);
}

.ord__btns {
  display: flex;
  flex-direction: column;
}

.ord__btn {
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  color: var(--nat-text-weak);
  line-height: 1;
  font-size: 11px;
}

.ord__btn:hover:not(:disabled) {
  color: var(--nat-accent);
}

.ord__btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.time-cell {
  font-size: 12px;
}

.prog__text {
  font-size: 12px;
  color: var(--nat-text-sub);
}

.prog__bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  background: #eef1f6;
  margin: 4px 0 3px;
}

.prog__seg {
  height: 100%;
}

.prog__targets {
  font-size: 11.5px;
}

.sub {
  padding: 12px 16px 14px;
  background: #fafbfd;
}

.sub__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.sub__title {
  font-weight: 600;
}

.sub__meta {
  color: var(--nat-text-weak);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.sub__env {
  margin-bottom: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.sub__cond {
  margin-bottom: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  font-size: 12px;
}

.sub__rule {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--nat-text-sub);
}

.sub__table {
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
}
</style>
