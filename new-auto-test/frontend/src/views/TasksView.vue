<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { cancelTask, listTasks, reorderTasks, rerunTask } from '@/api/tasks'
import { errorMessage, toastError, toastOk } from '@/api/http'
import {
  CALLBACK_STATUSES,
  EXECUTION_STATUSES,
  type CallbackStatus,
  type Execution,
  type ExecutionStatus,
  type Task,
  type TaskFormPreset,
} from '@/api/types'
import { countExecutions } from '@/utils/aggregate'
import { copyText, durationBetween, formatFullTime, formatTime } from '@/utils/format'
import { callbackStatusMeta, isTerminal, statusMeta } from '@/utils/status'
import { useAgents } from '@/stores/agents'
import AttachmentsDialog from '@/components/AttachmentsDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'
import CopyableId from '@/components/CopyableId.vue'
import TaskCreateDrawer from '@/components/TaskCreateDrawer.vue'
import QueueReorderDrawer from '@/components/QueueReorderDrawer.vue'

const router = useRouter()
const route = useRoute()
const { agents } = useAgents()

const tasks = ref<Task[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const statusFilter = ref<ExecutionStatus | ''>('')
const machineFilter = ref('')
const callbackFilter = ref<CallbackStatus | ''>('')
const autoRefresh = ref(true)
const lastLoadedAt = ref<number | null>(null)
const acting = ref('')

const createVisible = ref(false)
const reorderVisible = ref(false)
const createPreset = ref<TaskFormPreset | null>(null)

/** 展开行的 key 必须是稳定引用：每次渲染都传新数组会让自动刷新把展开的行收起来 */
const expandedKeys = ref<string[]>([])

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

/* ------------------------------------------------------------ 尺寸自适应 */

const tableHost = ref<HTMLElement | null>(null)
const hostWidth = ref(1280)
const viewportHeight = ref(900)
let observer: ResizeObserver | null = null

/** 窄屏优先保命令与进度：先收起创建时间，再收起调用来源 */
const showCreatedAt = computed(() => hostWidth.value >= 1120)
const showSource = computed(() => hostWidth.value >= 920)
/** 表头常驻，300 行也不会把页面拉长到需要滚到底才看得到表尾 */
const tableMaxHeight = computed(() => Math.max(320, viewportHeight.value - 268))

function syncViewport() {
  viewportHeight.value = window.innerHeight
}

onMounted(() => {
  if (typeof route.query.status === 'string') {
    const s = route.query.status as ExecutionStatus
    if (EXECUTION_STATUSES.includes(s)) statusFilter.value = s
  }
  if (typeof route.query.machine === 'string' && route.query.machine) {
    machineFilter.value = route.query.machine
  }
  if (typeof route.query.requestId === 'string' && route.query.requestId) {
    keyword.value = route.query.requestId
  }
  if (route.query.create === '1') createVisible.value = true

  syncViewport()
  window.addEventListener('resize', syncViewport)
  if (tableHost.value) {
    hostWidth.value = tableHost.value.clientWidth
    observer = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width
      if (w) hostWidth.value = w
    })
    observer.observe(tableHost.value)
  }

  void load()
  timer = window.setInterval(() => {
    if (autoRefresh.value && !reorderVisible.value && !createVisible.value) void load(true)
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
  window.removeEventListener('resize', syncViewport)
  observer?.disconnect()
  observer = null
})

const counts = computed(() => countExecutions(tasks.value))

const statusCounts = computed(() => {
  const map = {} as Record<ExecutionStatus, number>
  for (const s of EXECUTION_STATUSES) map[s] = 0
  for (const t of tasks.value) if (map[t.status] !== undefined) map[t.status] += 1
  return map
})

/** 只列出有任务的状态，避免一排空标签把工具栏撑满 */
const statusTabs = computed(() => {
  const tabs: { value: ExecutionStatus | ''; label: string; n: number }[] = [
    { value: '', label: '全部', n: tasks.value.length },
  ]
  for (const s of EXECUTION_STATUSES) {
    const n = statusCounts.value[s]
    if (n > 0 || statusFilter.value === s) tabs.push({ value: s, label: statusMeta(s).label, n })
  }
  return tabs
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const machine = machineFilter.value
  const rows = tasks.value.filter((t) => {
    if (statusFilter.value && t.status !== statusFilter.value) return false
    if (callbackFilter.value && t.callbackStatus !== callbackFilter.value) return false
    if (machine && !taskTouchesMachine(t, machine)) return false
    if (!kw) return true
    return (
      t.command.toLowerCase().includes(kw) ||
      t.taskId.toLowerCase().includes(kw) ||
      (t.requestId ?? '').toLowerCase().includes(kw) ||
      (t.operator ?? '').toLowerCase().includes(kw) ||
      t.targets.some((x) => x.toLowerCase().includes(kw)) ||
      t.executions.some((e) => (e.displayTag || e.agentId || '').toLowerCase().includes(kw))
    )
  })
  // 排队中的行按队列位次置顶，序号才会从上往下读成 1、2、3，上移/下移也才和视觉一致；
  // 其余行保持接口给的倒序（sort 是稳定的）
  const rank = new Map(pendingTasks.value.map((t, i) => [t.taskId, i]))
  return rows.sort((a, b) => {
    const ra = rank.get(a.taskId)
    const rb = rank.get(b.taskId)
    if (ra !== undefined && rb !== undefined) return ra - rb
    if (ra !== undefined) return -1
    if (rb !== undefined) return 1
    return 0
  })
})

/**
 * 列表接口按 id 倒序返回（新的在前），而下发是按 queueOrder 升序取的。
 * 直接拿返回顺序当排队顺序会把队列读反，"上移" 还会把整条队列倒序写回服务端。
 */
const pendingTasks = computed(() =>
  tasks.value
    .filter((t) => t.status === 'pending')
    .sort(
      (a, b) =>
        (a.queueOrder ?? Number.MAX_SAFE_INTEGER) - (b.queueOrder ?? Number.MAX_SAFE_INTEGER) ||
        (a.createdAt ?? 0) - (b.createdAt ?? 0) ||
        a.taskId.localeCompare(b.taskId, undefined, { numeric: true }),
    ),
)

const machineOptions = computed(() => {
  const set = new Set<string>()
  for (const a of agents.value) {
    const key = a.displayTag || a.agentId
    if (key) set.add(key)
  }
  for (const t of tasks.value) {
    for (const x of t.targets) if (x) set.add(x)
    for (const e of t.executions) {
      const key = e.displayTag || e.agentId
      if (key) set.add(key)
    }
  }
  return [...set].sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

function taskTouchesMachine(task: Task, machine: string): boolean {
  if (task.targets.includes(machine)) return true
  return task.executions.some((e) => e.displayTag === machine || e.agentId === machine)
}

function pendingIndex(task: Task): number {
  return pendingTasks.value.findIndex((t) => t.taskId === task.taskId)
}

function onExpandChange(row: Task, state: Task[] | boolean) {
  const expanded = Array.isArray(state) ? state.some((r) => r.taskId === row.taskId) : state
  const has = expandedKeys.value.includes(row.taskId)
  if (expanded === has) return
  expandedKeys.value = expanded
    ? [...expandedKeys.value, row.taskId]
    : expandedKeys.value.filter((k) => k !== row.taskId)
}

/* ------------------------------------------------------------ 队列排序 */

async function movePending(task: Task, delta: number) {
  const queue = pendingTasks.value
  const i = queue.findIndex((t) => t.taskId === task.taskId)
  const j = i + delta
  if (i < 0 || j < 0 || j >= queue.length) return
  const nextIds = queue.map((t) => t.taskId)
  ;[nextIds[i], nextIds[j]] = [nextIds[j], nextIds[i]]

  // 乐观更新：换掉两条的队列位次，失败再换回来
  const a = queue[i]
  const b = queue[j]
  const backup: [number | undefined, number | undefined] = [a.queueOrder, b.queueOrder]
  a.queueOrder = backup[1]
  b.queueOrder = backup[0]

  acting.value = `move:${task.taskId}`
  try {
    await reorderTasks(nextIds)
    toastOk('顺序已更新')
    await load(true)
  } catch (e) {
    a.queueOrder = backup[0]
    b.queueOrder = backup[1]
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
        ? `有 ${runningN} 条执行在跑，取消会杀掉对应进程组并判为 canceled。`
        : '未开始的执行会直接置为 canceled。',
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
      ? '会清空这条任务的执行记录与日志并重新入队，历史结果不可恢复。'
      : '复制命令、目标、判定配置另起一条任务，原记录保留。'
  try {
    await ElMessageBox.confirm(text, mode === 'inplace' ? '原地重跑' : '重跑为新记录', {
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

/* ------------------------------------------------------------ 附件 */

const attachmentsFor = ref<Task | null>(null)

function openAttachments(task: Task) {
  attachmentsFor.value = task
}

function gotoTimeline(task: Task) {
  void router.push({ path: '/timeline', query: { executeId: task.executions[0]?.executeId ?? '' } })
}

function onRowCommand(command: string | number | object, task: Task) {
  switch (command) {
    case 'cancel':
      void onCancel(task)
      break
    case 'rerun-inplace':
      void onRerun(task, 'inplace')
      break
    case 'rerun-new':
      void onRerun(task, 'new')
      break
    case 'template':
      useAsTemplate(task)
      break
    case 'attachments':
      openAttachments(task)
      break
    case 'log':
      if (task.executions[0]) gotoExecution(task.executions[0])
      break
    case 'timeline':
      gotoTimeline(task)
      break
  }
}

async function copyValue(value: string, label: string) {
  if (await copyText(value)) toastOk(`${label} 已复制`)
  else toastError(null, '复制失败')
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

function targetsText(task: Task): string {
  return task.targets.join('、')
}

function clearFilters() {
  keyword.value = ''
  statusFilter.value = ''
  machineFilter.value = ''
  callbackFilter.value = ''
}

function callbackTooltip(task: Task): string {
  const meta = callbackStatusMeta(task.callbackStatus)
  const parts = [meta.desc]
  if (task.callbackAttempts) parts.push(`已尝试 ${task.callbackAttempts} 次`)
  if (task.callbackLastError) parts.push(`最近错误：${task.callbackLastError}`)
  if (task.callbackUrl) parts.push(`回调地址：${task.callbackUrl}`)
  return parts.join('；')
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">任务队列</h2>
        <p class="page-head__desc">
          {{ tasks.length }} 个任务 · 运行 {{ counts.running + counts.dispatching }} · 排队 {{ counts.pending }}
          <template v-if="lastLoadedAt"> · {{ formatTime(lastLoadedAt) }} 更新</template>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Sort'" :disabled="pendingTasks.length < 2" @click="reorderVisible = true">
          排队顺序
        </el-button>
        <el-button :icon="'Refresh'" :loading="loading" @click="load()">刷新</el-button>
        <el-button type="primary" :icon="'Plus'" @click="openCreate">新建任务</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="tools">
        <el-radio-group v-model="statusFilter" size="small">
          <el-radio-button v-for="tab in statusTabs" :key="tab.value" :value="tab.value">
            {{ tab.label }}
            <span class="tab-n">{{ tab.n }}</span>
          </el-radio-button>
        </el-radio-group>

        <div class="tools__filters">
          <el-select v-model="machineFilter" placeholder="机器" clearable filterable class="tools__machine">
            <el-option v-for="m in machineOptions" :key="m" :label="m" :value="m" />
          </el-select>
          <el-select v-model="callbackFilter" placeholder="回调" clearable class="tools__callback">
            <el-option v-for="s in CALLBACK_STATUSES" :key="s" :label="callbackStatusMeta(s).label" :value="s" />
          </el-select>
        </div>

        <div class="tools__end">
          <el-input
            v-model="keyword"
            class="tools__search"
            placeholder="搜索命令 / ID / 目标 / 操作人"
            clearable
            :prefix-icon="'Search'"
          />
          <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" title="每 5 秒刷新一次" />
        </div>
      </div>

      <div v-if="error && tasks.length" class="alert-slot">
        <el-alert type="error" :closable="false" show-icon :title="error" />
      </div>

      <div ref="tableHost" class="table-host">
        <el-table
          v-if="filtered.length"
          :data="filtered"
          row-key="taskId"
          size="default"
          :max-height="tableMaxHeight"
          :expand-row-keys="expandedKeys"
          @expand-change="onExpandChange"
        >
          <el-table-column type="expand" width="40">
            <template #default="{ row }">
              <div class="detail">
                <div class="detail__head">
                  <span class="detail__title">执行明细 {{ row.executions.length }}</span>
                  <span class="detail__meta">
                    taskId <CopyableId :value="row.taskId" :head="12" />
                    <template v-if="row.requestId">
                      · requestId <CopyableId :value="row.requestId" :short="false" />
                    </template>
                    <template v-if="row.cwd"> · cwd <code class="code-inline">{{ row.cwd }}</code></template>
                    <template v-if="row.timeoutSec"> · 超时 {{ row.timeoutSec }}s</template>
                  </span>
                </div>

                <div v-if="row.callbackUrl" class="detail__line">
                  <span class="muted">回调</span>
                  <code class="code-inline detail__url">{{ row.callbackUrl }}</code>
                  <el-tag size="small" effect="light" :type="callbackStatusMeta(row.callbackStatus).type">
                    {{ callbackStatusMeta(row.callbackStatus).label }}
                  </el-tag>
                  <span v-if="row.callbackAttempts" class="muted">尝试 {{ row.callbackAttempts }} 次</span>
                  <span v-if="row.callbackLastError" class="muted detail__err">{{ row.callbackLastError }}</span>
                </div>

                <div v-if="row.env && Object.keys(row.env).length" class="detail__line">
                  <span class="muted">env</span>
                  <code v-for="(v, k) in row.env" :key="k" class="code-inline">{{ k }}={{ v }}</code>
                </div>

                <div v-if="row.conditionConfig" class="detail__line">
                  <span class="muted">判定</span>
                  <span v-for="(rule, i) in row.conditionConfig.rules" :key="i" class="detail__rule">
                    {{ i + 1 }}. {{ rule.operator }} <code class="code-inline">{{ rule.value }}</code> →
                    <StatusPill :status="rule.status" />
                  </span>
                  <span v-if="row.conditionConfig.other" class="detail__rule">
                    other → <StatusPill :status="row.conditionConfig.other" />
                  </span>
                </div>

                <el-table v-if="row.executions.length" :data="row.executions" size="small" class="detail__table">
                  <el-table-column label="状态" width="112">
                    <template #default="{ row: ex }">
                      <StatusPill :status="ex.status" :disconnected="ex.disconnected" />
                    </template>
                  </el-table-column>
                  <el-table-column label="机器" min-width="140" show-overflow-tooltip>
                    <template #default="{ row: ex }">{{ ex.displayTag || ex.agentId || '-' }}</template>
                  </el-table-column>
                  <el-table-column label="退出码" width="76" align="center">
                    <template #default="{ row: ex }">
                      <span class="mono">{{ ex.exitCode ?? '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="最后一行" min-width="200">
                    <template #default="{ row: ex }">
                      <code class="cmd" :title="ex.lastLine || ''">{{ ex.lastLine || '-' }}</code>
                    </template>
                  </el-table-column>
                  <el-table-column label="耗时" width="88">
                    <template #default="{ row: ex }">
                      <span class="mono">{{ durationBetween(ex.startedAt, ex.finishedAt) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="" width="84" align="right">
                    <template #default="{ row: ex }">
                      <el-button size="small" text type="primary" @click="gotoExecution(ex)">查看日志</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <EmptyState
                  v-else
                  size="small"
                  title="还没有执行记录"
                  :desc="`目标：${targetsText(row) || '未指定'}`"
                />
              </div>
            </template>
          </el-table-column>

          <el-table-column label="#" width="62" align="center">
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

          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusPill :status="row.status" />
            </template>
          </el-table-column>

          <el-table-column label="命令" min-width="300">
            <template #default="{ row }">
              <el-tooltip
                :content="row.command"
                placement="top-start"
                :show-after="400"
                :persistent="false"
                popper-class="tasks-pop"
              >
                <code class="cmd">{{ row.command }}</code>
              </el-tooltip>
              <div class="row-meta">
                <CopyableId :value="row.taskId" :head="8" />
                <span v-if="row.operator" class="muted">· {{ row.operator }}</span>
                <button
                  v-if="row.attachmentCount"
                  class="att-chip"
                  :title="`${row.attachmentCount} 个附件，点击查看`"
                  @click.stop="openAttachments(row)"
                >
                  附件 {{ row.attachmentCount }}
                </button>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="进度 / 目标" width="176">
            <template #default="{ row }">
              <div class="prog">
                <div class="prog__top">
                  <span class="prog__n">{{ progressOf(row).done }}/{{ progressOf(row).total }}</span>
                  <span class="prog__targets" :title="targetsText(row)">{{ targetsText(row) || '未指定' }}</span>
                </div>
                <div class="prog__bar">
                  <span
                    v-for="seg in segmentsOf(row)"
                    :key="seg.key"
                    class="prog__seg"
                    :style="{ width: `${seg.pct}%`, background: seg.color }"
                    :title="seg.label"
                  />
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column v-if="showSource" label="调用来源" min-width="150">
            <template #default="{ row }">
              <div v-if="row.requestId || row.callbackStatus !== 'none'" class="src">
                <span
                  v-if="row.requestId"
                  class="src__id"
                  :title="`${row.requestId}（点击复制）`"
                  @click="copyValue(row.requestId, 'requestId')"
                >{{ row.requestId }}</span>
                <span v-else class="muted">运维台</span>
                <el-tooltip
                  v-if="row.callbackStatus !== 'none'"
                  :content="callbackTooltip(row)"
                  placement="top"
                  :show-after="300"
                  :persistent="false"
                  popper-class="tasks-pop"
                >
                  <el-tag size="small" effect="light" :type="callbackStatusMeta(row.callbackStatus).type">
                    回调{{ callbackStatusMeta(row.callbackStatus).label }}
                  </el-tag>
                </el-tooltip>
              </div>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>

          <el-table-column v-if="showCreatedAt" label="创建时间" width="140">
            <template #default="{ row }">
              <span class="time mono" :title="formatFullTime(row.createdAt)">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="64" align="center">
            <template #default="{ row }">
              <el-dropdown
                trigger="click"
                placement="bottom-end"
                :persistent="false"
                popper-class="tasks-menu"
                @command="(c: string | number | object) => onRowCommand(c, row)"
              >
                <el-button
                  size="small"
                  text
                  :icon="'MoreFilled'"
                  :loading="acting.endsWith(`:${row.taskId}`)"
                  title="更多操作"
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="cancel" :disabled="isTerminal(row.status)" :icon="'CircleClose'">
                      取消任务
                    </el-dropdown-item>
                    <el-dropdown-item command="rerun-inplace" :disabled="!isTerminal(row.status)" :icon="'RefreshLeft'">
                      原地重跑
                    </el-dropdown-item>
                    <el-dropdown-item command="rerun-new" :icon="'RefreshRight'">重跑为新记录</el-dropdown-item>
                    <el-dropdown-item command="attachments" divided :icon="'Folder'">
                      附件{{ row.attachmentCount ? ` (${row.attachmentCount})` : '' }}
                    </el-dropdown-item>
                    <el-dropdown-item command="template" :icon="'CopyDocument'">以此为模板</el-dropdown-item>
                    <el-dropdown-item
                      v-if="row.executions.length === 1"
                      command="log"
                      :icon="'Document'"
                    >
                      查看日志
                    </el-dropdown-item>
                    <el-dropdown-item command="timeline" :icon="'Histogram'">查看时间线</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState v-else-if="loading" title="正在加载…" size="small" />
        <EmptyState v-else-if="tasks.length" variant="search" title="没有符合条件的任务" desc="换个状态或关键字试试">
          <el-button size="small" @click="clearFilters">清空筛选</el-button>
        </EmptyState>
        <EmptyState v-else-if="error" variant="error" title="加载失败" :desc="error">
          <el-button size="small" @click="load()">重试</el-button>
        </EmptyState>
        <EmptyState v-else title="还没有任务" desc="创建后会按队列顺序调度到空闲机器">
          <el-button type="primary" size="small" :icon="'Plus'" @click="openCreate">新建任务</el-button>
          <router-link to="/playground">
            <el-button size="small">去测试下发</el-button>
          </router-link>
        </EmptyState>
      </div>
    </div>

    <TaskCreateDrawer v-model="createVisible" :preset="createPreset" @created="onCreated" />
    <QueueReorderDrawer v-model="reorderVisible" :tasks="pendingTasks" @saved="load()" />
    <AttachmentsDialog
      v-if="attachmentsFor"
      :task-id="attachmentsFor.taskId"
      :task-label="attachmentsFor.command"
      allow-upload
      @changed="load(true)"
      @closed="attachmentsFor = null"
    />
  </div>
</template>

<style scoped>
/* ------------------------------------------------------------ 工具栏 */

.tools {
  display: flex;
  align-items: center;
  gap: 10px 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--nat-border);
}

.tools__filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tools__end {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.tools__machine {
  width: 168px;
}

.tools__callback {
  width: 116px;
}

.tools__search {
  width: 248px;
}

@media (max-width: 1280px) {
  .tools__end {
    margin-left: 0;
    width: 100%;
  }

  .tools__search {
    width: auto;
    flex: 1;
  }
}

.tab-n {
  display: inline-block;
  margin-left: 4px;
  color: var(--nat-text-weak);
  font-variant-numeric: tabular-nums;
}

.alert-slot {
  padding: 12px 14px 0;
}

/* ------------------------------------------------------------ 表格 */

.table-host {
  min-width: 0;
}

.cmd {
  display: block;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: #26303d;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 11.5px;
}

/* 附件小筹码：中性描边，点开对话框看列表/下载 */
.att-chip {
  appearance: none;
  padding: 0 7px;
  border: 1px solid var(--nat-border);
  border-radius: 999px;
  background: none;
  color: var(--nat-text-sub);
  font-size: 11px;
  line-height: 17px;
  cursor: pointer;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.att-chip:hover {
  border-color: var(--nat-text-sub);
  color: var(--nat-text);
}

.ord {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
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

.time {
  font-size: 12px;
  color: var(--nat-text-sub);
  white-space: nowrap;
}

.src {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
  font-size: 12px;
}

/* 同批 requestId 前缀相同，从头部省略、保留尾部更容易分辨 */
.src__id {
  max-width: 100%;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--nat-text-sub);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  direction: rtl;
  text-align: left;
  cursor: pointer;
}

.src__id:hover {
  color: var(--nat-accent);
}

.prog__top {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.prog__n {
  flex: none;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--nat-text-sub);
}

.prog__targets {
  flex: 1;
  min-width: 0;
  font-size: 11.5px;
  color: var(--nat-text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.prog__bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  background: #eef1f6;
  margin-top: 5px;
}

.prog__seg {
  height: 100%;
}

/* ------------------------------------------------------------ 展开面板 */

:deep(.el-table__expanded-cell) {
  padding: 0;
  background: #fafbfd;
}

:deep(.el-table__expanded-cell:hover) {
  background: #fafbfd !important;
}

.detail {
  min-width: 0;
  padding: 12px 14px 14px;
}

.detail__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.detail__title {
  font-weight: 600;
}

.detail__meta {
  min-width: 0;
  color: var(--nat-text-weak);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.detail__line {
  margin-bottom: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  min-width: 0;
}

.detail__url,
.detail__err {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail__rule {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--nat-text-sub);
}

.detail__table {
  width: 100%;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
}
</style>

<style>
/* teleport 出去的浮层拿不到 scoped 属性，这里限定自有 class */
.tasks-pop.el-popper {
  max-width: 420px;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.6;
}

.tasks-menu .el-dropdown-menu__item {
  min-width: 132px;
}
</style>
