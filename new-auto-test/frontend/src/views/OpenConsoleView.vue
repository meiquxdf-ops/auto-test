<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelTask, listTasksByRequestId, rerunTask } from '@/api/tasks'
import { ApiError, errorMessage, toastError, toastOk } from '@/api/http'
import {
  EXECUTION_STATUSES,
  type Execution,
  type ExecutionStatus,
  type RerunMode,
  type Task,
} from '@/api/types'
import { isEmbed } from '@/utils/embed'
import { copyText, durationBetween, formatFullTime, formatTime } from '@/utils/format'
import { callbackStatusMeta, isTerminal, statusMeta } from '@/utils/status'
import AttachmentsDialog from '@/components/AttachmentsDialog.vue'
import CopyableId from '@/components/CopyableId.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'
import OpenLogDrawer from '@/components/open/OpenLogDrawer.vue'
import OpenTimelineDrawer from '@/components/open/OpenTimelineDrawer.vue'

const route = useRoute()
const router = useRouter()

/** 嵌入宿主（iframe / embed=1）：隐藏页头，跳出页面的链接改开新窗口 */
const embedded = computed(() => isEmbed(route.query))

const LAST_KEY = 'nat.openConsole.requestId'
const RECENT_KEY = 'nat.openConsole.recentIds'
const RECENT_MAX = 8
const ID_RE = /^[A-Za-z0-9._-]{1,64}$/

/** 最近查过的 requestId（查询成功才记），坏数据当没有 */
function loadRecentIds(): string[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]')
    if (Array.isArray(raw)) {
      return raw.filter((x): x is string => typeof x === 'string' && ID_RE.test(x)).slice(0, RECENT_MAX)
    }
  } catch {
    /* ignore */
  }
  return []
}

const recentIds = ref<string[]>(loadRecentIds())

function rememberRecentId(id: string) {
  const next = [id, ...recentIds.value.filter((x) => x !== id)].slice(0, RECENT_MAX)
  recentIds.value = next
  localStorage.setItem(RECENT_KEY, JSON.stringify(next))
}

/** 旧请求页的外链带过一个 reuqestId 拼写错误，两种写法都认 */
const deepLinkId = (() => {
  for (const key of ['requestId', 'reuqestId'] as const) {
    const v = route.query[key]
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return ''
})()
const initialId = deepLinkId || localStorage.getItem(LAST_KEY) || ''

const requestIdInput = ref(initialId)
const queriedId = ref('')
const tasks = ref<Task[]>([])
// 带 requestId 进来时首屏直接是加载态，不要先闪一屏接口速览再换掉
const loading = ref(ID_RE.test(initialId))
const error = ref('')
const searched = ref(false)
const lastLoadedAt = ref<number | null>(null)
const autoRefresh = ref(true)

let timer: number | null = null
let seq = 0

const idValid = computed(() => ID_RE.test(requestIdInput.value.trim()))
const idInvalid = computed(() => requestIdInput.value.trim().length > 0 && !idValid.value)
const hint = computed(() => (idInvalid.value ? '只允许字母、数字和 . _ -，长度 1-64' : '回车即可查询'))

async function query(silent = false) {
  const id = requestIdInput.value.trim()
  if (!ID_RE.test(id)) return
  const mine = ++seq
  if (!silent) loading.value = true
  try {
    const list = await listTasksByRequestId(id)
    if (mine !== seq) return
    tasks.value = list
    queriedId.value = id
    error.value = ''
    lastLoadedAt.value = Date.now()
    localStorage.setItem(LAST_KEY, id)
    rememberRecentId(id)
    if (route.query.requestId !== id || route.query.reuqestId !== undefined) {
      void router.replace({ query: { ...route.query, requestId: id, reuqestId: undefined } })
    }
  } catch (e) {
    if (mine !== seq) return
    error.value = errorMessage(e, '查询失败')
    // 手动查询失败时不留着上一批结果，保证错误态与结果态互斥
    if (!silent) {
      tasks.value = []
      queriedId.value = id
    }
  } finally {
    if (mine === seq) {
      loading.value = false
      searched.value = true
    }
  }
}

/** 搜索框下方的最近记录：不重复展示当前这条 */
const recentShown = computed(() => recentIds.value.filter((x) => x !== queriedId.value))

function queryRecent(id: string) {
  requestIdInput.value = id
  void query()
}

/* ------------------------------------------------------------ 尺寸自适应 */

const viewportHeight = ref(900)

function syncViewport() {
  viewportHeight.value = window.innerHeight
}

/** 表头常驻，任务多也不会把页面拉长到看不到表尾；嵌入态没有页头，可以更高 */
const tableMaxHeight = computed(() =>
  Math.max(320, viewportHeight.value - (embedded.value ? 220 : 300)),
)

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport, { passive: true })
  if (idValid.value) void query()
  timer = window.setInterval(() => {
    if (!autoRefresh.value || !queriedId.value || !needsRefresh.value) return
    if (document.visibilityState !== 'visible') return
    void query(true)
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
  window.removeEventListener('resize', syncViewport)
})

/* ------------------------------------------------------------ 页面状态 */

type ViewState = 'intro' | 'loading' | 'error' | 'empty' | 'result'

const viewState = computed<ViewState>(() => {
  if (tasks.value.length) return 'result'
  if (loading.value) return 'loading'
  if (error.value) return 'error'
  if (searched.value) return 'empty'
  return 'intro'
})

/** 已有结果时的刷新失败：不清屏，只在顶部提示 */
const refreshError = computed(() => (tasks.value.length ? error.value : ''))

/* ------------------------------------------------------------ 汇总（任务级） */

const taskCounts = computed(() => {
  const map = {} as Record<ExecutionStatus, number>
  for (const s of EXECUTION_STATUSES) map[s] = 0
  for (const t of tasks.value) map[t.status] += 1
  return map
})

const hasActive = computed(() => tasks.value.some((t) => !isTerminal(t.status)))

/** 终态后回调仍会 PENDING→RUNNING→SUCCESS/FAILED，不能跟 hasActive 绑在一起（否则会把已结束批次显示成进行中） */
const hasCallbackInFlight = computed(() =>
  tasks.value.some((t) => t.callbackStatus === 'pending' || t.callbackStatus === 'running'),
)
const needsRefresh = computed(() => hasActive.value || hasCallbackInFlight.value)

/** 进度按「到达终态的任务数 / 总数」算，运行中不计 */
const terminalCount = computed(() => tasks.value.filter((t) => isTerminal(t.status)).length)
const progressPct = computed(() =>
  tasks.value.length ? (terminalCount.value / tasks.value.length) * 100 : 0,
)

interface OverallBadge {
  label: string
  color: string
  bg: string
  border: string
}

/**
 * 整批的总体状态（任务级）：
 * 有未终态的任务 → 进行中；全部 pass → 完成；全部 fail/block → 失败；
 * 全部 canceled → 已取消；其余混合终态（含 exception）统一算部分失败。
 */
const overall = computed<OverallBadge>(() => {
  const c = taskCounts.value
  const total = tasks.value.length
  const pick = (s: ExecutionStatus, label: string): OverallBadge => {
    const m = statusMeta(s)
    return { label, color: m.color, bg: m.bg, border: m.border }
  }
  if (c.pending + c.dispatching + c.running > 0) return pick('running', '进行中')
  if (c.pass === total) return pick('pass', '完成')
  if (c.fail + c.block === total) return pick('fail', '失败')
  if (c.canceled === total) return pick('canceled', '已取消')
  return pick('block', '部分失败')
})

interface StatItem {
  key: 'total' | ExecutionStatus
  label: string
  n: number
  color: string
}

const statItems = computed<StatItem[]>(() => {
  const c = taskCounts.value
  const items: StatItem[] = [
    { key: 'total', label: '总任务', n: tasks.value.length, color: 'var(--nat-text)' },
    { key: 'pass', label: '通过', n: c.pass, color: statusMeta('pass').color },
    { key: 'fail', label: '失败', n: c.fail, color: statusMeta('fail').color },
    { key: 'block', label: '阻塞', n: c.block, color: statusMeta('block').color },
    { key: 'running', label: '执行中', n: c.running + c.dispatching, color: statusMeta('running').color },
    { key: 'pending', label: '排队', n: c.pending, color: statusMeta('pending').color },
    { key: 'exception', label: '异常', n: c.exception, color: statusMeta('exception').color },
    { key: 'canceled', label: '已取消', n: c.canceled, color: statusMeta('canceled').color },
  ]
  return items
})

const operators = computed(() => {
  const set = new Set<string>()
  for (const t of tasks.value) if (t.operator) set.add(t.operator)
  return [...set]
})

/** 起止：最早的创建/开始时间 → 最晚的结束时间；还有任务在跑时结束记为进行中 */
const timeSpan = computed(() => {
  let start: number | null = null
  let end: number | null = null
  for (const t of tasks.value) {
    const starts = [t.createdAt, ...t.executions.map((e) => e.startedAt ?? e.createdAt)]
    for (const s of starts) if (s && (start === null || s < start)) start = s
    const ends = [t.finishedAt, ...t.executions.map((e) => e.finishedAt)]
    for (const e of ends) if (e && (end === null || e > end)) end = e
  }
  if (hasActive.value) end = null
  return { start, end, duration: start ? durationBetween(start, end) : '-' }
})

const callbackSummary = computed(() => {
  const map = new Map<Task['callbackStatus'], number>()
  for (const t of tasks.value) {
    if (t.callbackStatus === 'none') continue
    map.set(t.callbackStatus, (map.get(t.callbackStatus) ?? 0) + 1)
  }
  return [...map.entries()].map(([status, n]) => ({ status, n, meta: callbackStatusMeta(status) }))
})

/* ------------------------------------------------------------ 筛选 */

const keyword = ref('')
const statusFilter = ref<ExecutionStatus | ''>('')
const machineFilter = ref('')

/** 只列出现存的状态，避免一排空选项 */
const statusOptions = computed(() =>
  EXECUTION_STATUSES.filter((s) => taskCounts.value[s] > 0 || statusFilter.value === s),
)

/** 概览筹码即筛选：点中的再点一下（或点总任务）清掉 */
function toggleStatusChip(key: 'total' | ExecutionStatus) {
  if (key === 'total') {
    statusFilter.value = ''
    return
  }
  statusFilter.value = statusFilter.value === key ? '' : key
}

/** 「执行中」一档把 dispatching 一起算进来，跟概览的数字口径一致 */
function matchesStatusFilter(t: Task): boolean {
  if (!statusFilter.value) return true
  if (t.status === statusFilter.value) return true
  return statusFilter.value === 'running' && t.status === 'dispatching'
}

/** 本批出现过的机器：已有执行的按 displayTag/agentId，还没生成执行的看 targets */
const machineOptions = computed(() => {
  const set = new Set<string>()
  for (const t of tasks.value) {
    for (const e of t.executions) {
      const m = e.displayTag || e.agentId
      if (m) set.add(m)
    }
    for (const x of t.targets) if (x) set.add(x)
  }
  return [...set].sort()
})

function matchesMachineFilter(t: Task): boolean {
  const m = machineFilter.value
  if (!m) return true
  return t.targets.includes(m) || t.executions.some((e) => (e.displayTag || e.agentId) === m)
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return tasks.value.filter((t) => {
    if (!matchesStatusFilter(t)) return false
    if (!matchesMachineFilter(t)) return false
    if (!kw) return true
    return (
      t.command.toLowerCase().includes(kw) ||
      t.taskId.toLowerCase().includes(kw) ||
      (t.operator ?? '').toLowerCase().includes(kw) ||
      t.targets.some((x) => x.toLowerCase().includes(kw)) ||
      t.executions.some(
        (e) =>
          (e.lastLine ?? '').toLowerCase().includes(kw) ||
          (e.displayTag || e.agentId || '').toLowerCase().includes(kw),
      )
    )
  })
})

function clearFilters() {
  keyword.value = ''
  statusFilter.value = ''
  machineFilter.value = ''
}

/* ------------------------------------------------------------ 行辅助 */

function machineListOf(task: Task): string[] {
  const set = new Set<string>()
  for (const e of task.executions) {
    const m = e.displayTag || e.agentId
    if (m) set.add(m)
  }
  return set.size ? [...set] : task.targets
}

function machinesOf(task: Task): string {
  return machineListOf(task).join('、') || '-'
}

interface Verdict {
  text: string
  exit: number | null
}

/**
 * 判定 / 最后一行：任务为什么停在这个状态。
 * 优先取与任务状态一致的最新执行（fail 的任务看失败那条），否则退回「最值得看」的执行。
 */
function verdictOf(task: Task): Verdict {
  const ts = (e: Execution) => e.finishedAt ?? e.startedAt ?? e.createdAt ?? 0
  const same = task.executions.filter((e) => e.status === task.status)
  const ex = same.length ? same.reduce((a, b) => (ts(b) > ts(a) ? b : a)) : pickBestExecution(task)
  if (!ex) return { text: '', exit: null }
  return { text: ex.lastLine || ex.conditionHit || ex.message || '', exit: ex.exitCode ?? null }
}

function verdictTitle(task: Task): string {
  const v = verdictOf(task)
  const parts: string[] = []
  if (v.text) parts.push(v.text)
  if (v.exit !== null) parts.push(`exitCode ${v.exit}`)
  return parts.join('\n')
}

function taskDisconnected(task: Task): boolean {
  return task.executions.some((e) => e.status === 'running' && e.disconnected)
}

function startOf(task: Task): number | null {
  let v: number | null = null
  for (const e of task.executions) {
    if (e.startedAt && (v === null || e.startedAt < v)) v = e.startedAt
  }
  return v ?? task.createdAt ?? null
}

function endOf(task: Task): number | null {
  if (!isTerminal(task.status)) return null
  if (task.finishedAt) return task.finishedAt
  let v: number | null = null
  for (const e of task.executions) {
    if (e.finishedAt && (v === null || e.finishedAt > v)) v = e.finishedAt
  }
  return v
}

function callbackDetail(task: Task): string {
  const meta = callbackStatusMeta(task.callbackStatus)
  const parts = [meta.desc]
  if (task.callbackUrl) parts.push(`地址 ${task.callbackUrl}`)
  if (task.callbackAttempts) parts.push(`已尝试 ${task.callbackAttempts} 次`)
  if (task.callbackLastError) parts.push(`最近错误：${task.callbackLastError}`)
  return parts.join('\n')
}

function gotoExecution(exec: Execution) {
  const path = `/executions/${exec.executeId}`
  // 嵌入态开新窗口，避免宿主 iframe 被整个运维台替换
  if (embedded.value) {
    window.open(router.resolve(path).href, '_blank', 'noopener')
    return
  }
  void router.push(path)
}

/* ------------------------------------------------------------ 展开行 */

/** 展开行的 key 必须是稳定引用：每次渲染都传新数组会让自动刷新把展开的行收起来 */
const expandedKeys = ref<string[]>([])

function onExpandChange(row: Task, state: Task[] | boolean) {
  const expanded = Array.isArray(state) ? state.some((r) => r.taskId === row.taskId) : state
  const has = expandedKeys.value.includes(row.taskId)
  if (expanded === has) return
  expandedKeys.value = expanded
    ? [...expandedKeys.value, row.taskId]
    : expandedKeys.value.filter((k) => k !== row.taskId)
}

/* ------------------------------------------------------------ 勾选与批量操作 */

const selected = ref<Task[]>([])

function onSelectionChange(rows: Task[]) {
  selected.value = rows
}

/** 5 秒轮询会替换整批对象，批量操作前按 taskId 换成最新数据 */
const selectedFresh = computed<Task[]>(() => {
  const byId = new Map(tasks.value.map((t) => [t.taskId, t]))
  return selected.value.map((s) => byId.get(s.taskId) ?? s)
})

const CANCELABLE = new Set<ExecutionStatus>(['pending', 'dispatching', 'running'])

const selectedCancelable = computed(() => selectedFresh.value.filter((t) => CANCELABLE.has(t.status)))

const batchCanceling = ref(false)
const batchRerunning = ref(false)

async function batchCancel() {
  const list = selectedCancelable.value
  if (!list.length) {
    ElMessage.warning('选中的任务没有可取消项')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将取消选中的 ${list.length} 个任务，运行中的执行会被杀掉并判为 canceled。`,
      '取消选中任务',
      {
        type: 'warning',
        confirmButtonText: '确认取消',
        cancelButtonText: '再想想',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }
  batchCanceling.value = true
  try {
    const results = await Promise.allSettled(list.map((t) => cancelTask(t.taskId)))
    const ok = results.filter((r) => r.status === 'fulfilled').length
    const failN = results.length - ok
    if (ok > 0 && failN === 0) toastOk(`已取消 ${ok} 个任务`)
    else if (ok > 0) ElMessage.warning(`已取消 ${ok} 个任务，${failN} 个失败`)
    else toastError(results.find((r): r is PromiseRejectedResult => r.status === 'rejected')?.reason, '批量取消失败')
    await query(true)
  } finally {
    batchCanceling.value = false
  }
}

async function batchRerun(mode: RerunMode) {
  const list = selectedFresh.value
  if (!list.length) return
  const text =
    mode === 'inplace'
      ? `将原地重跑选中的 ${list.length} 个任务：清空执行记录与日志并重新入队，历史结果不可恢复；还在执行中的任务会被拒绝。`
      : `将为选中的 ${list.length} 个任务复制命令、目标、判定配置各建一条新任务。新任务会换新 requestId，不会出现在当前查询里。`
  try {
    await ElMessageBox.confirm(text, mode === 'inplace' ? '重新执行选中任务' : '重跑为新记录', {
      type: mode === 'inplace' ? 'warning' : 'info',
      confirmButtonText: mode === 'inplace' ? '清空并重跑' : '创建新任务',
      cancelButtonText: '取消',
      confirmButtonClass: mode === 'inplace' ? 'el-button--danger' : '',
    })
  } catch {
    return
  }
  batchRerunning.value = true
  try {
    const results = await Promise.allSettled(list.map((t) => rerunTask(t.taskId, mode)))
    const ok = results.filter((r) => r.status === 'fulfilled').length
    const rejected = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected')
    // 原地重跑还在执行中的任务，Server 返回 409
    const conflictN = rejected.filter((r) => r.reason instanceof ApiError && r.reason.status === 409).length
    if (!rejected.length) {
      if (mode === 'new') {
        const ids = [
          ...new Set(
            results
              .filter((r): r is PromiseFulfilledResult<Task | null> => r.status === 'fulfilled')
              .map((r) => r.value?.requestId)
              .filter((id): id is string => Boolean(id)),
          ),
        ]
        toastOk(
          ids.length
            ? `已建 ${ok} 条新任务，新 requestId 不在本批：${ids.join('、')}`
            : `已建 ${ok} 条新任务（新 requestId 不在本批）`,
        )
      } else {
        toastOk(`已重跑 ${ok} 个任务`)
      }
    } else if (ok > 0) {
      ElMessage.warning(
        `已重跑 ${ok} 个，${rejected.length} 个失败${conflictN ? `（${conflictN} 个还在执行中，需先取消）` : ''}`,
      )
    } else if (conflictN === rejected.length) {
      toastError(null, '选中任务都还在执行中，先取消或等它们结束再重跑')
    } else {
      toastError(rejected[0]?.reason, '批量重跑失败')
    }
    await query(true)
  } finally {
    batchRerunning.value = false
  }
}

function onBatchRerunCommand(c: string | number | object) {
  if (c === 'inplace' || c === 'new') void batchRerun(c)
}

/* ------------------------------------------------------------ 单行取消 / 重跑 */

const acting = ref('')

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
    await query(true)
  } catch (e) {
    toastError(e, '取消失败')
  } finally {
    acting.value = ''
  }
}

async function onRerun(task: Task, mode: RerunMode) {
  const text =
    mode === 'inplace'
      ? '会清空这条任务的执行记录与日志并重新入队，历史结果不可恢复。'
      : '复制命令、目标、判定配置另起一条任务。新任务会换新 requestId，不会出现在当前查询里。'
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
    toastOk(
      mode === 'inplace'
        ? '已原地重跑'
        : created?.requestId
          ? `已创建新任务，requestId ${created.requestId}（不在本批，请另查）`
          : `已创建新任务${created?.taskId ? ` ${created.taskId.slice(0, 8)}` : ''}`,
    )
    await query(true)
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) toastError(null, '任务还在执行中，先取消或等它结束再重跑')
    else toastError(e, '重跑失败')
    await query(true)
  } finally {
    acting.value = ''
  }
}

function onRerunCommand(c: string | number | object, task: Task) {
  if (c === 'inplace' || c === 'new') void onRerun(task, c)
}

/* ------------------------------------------------------------ 日志 / 节点抽屉 */

interface LogDrawerCtx {
  executeId: string
  command: string
}

interface TimelineDrawerCtx {
  executeId: string
  machine: string
}

const logDrawer = ref<LogDrawerCtx | null>(null)
const timelineDrawer = ref<TimelineDrawerCtx | null>(null)

/** 挑「最值得看」的执行：running > dispatching > 最近开始/创建的 */
function pickBestExecution(task: Task): Execution | null {
  const list = task.executions
  if (!list.length) return null
  const ts = (e: Execution) => e.startedAt ?? e.createdAt ?? 0
  const latest = (arr: Execution[]) => arr.reduce((a, b) => (ts(b) > ts(a) ? b : a))
  const running = list.filter((e) => e.status === 'running')
  if (running.length) return latest(running)
  const dispatching = list.filter((e) => e.status === 'dispatching')
  if (dispatching.length) return latest(dispatching)
  return latest(list)
}

function openLog(task: Task) {
  const ex = pickBestExecution(task)
  if (!ex) {
    ElMessage.warning('还没有执行记录')
    return
  }
  logDrawer.value = { executeId: ex.executeId, command: task.command }
}

function openNodes(task: Task) {
  const ex = pickBestExecution(task)
  if (!ex) {
    ElMessage.warning('还没有执行记录')
    return
  }
  timelineDrawer.value = { executeId: ex.executeId, machine: ex.displayTag || ex.agentId || '' }
}

/* ------------------------------------------------------------ 附件（只读：查看 + 下载） */

const attachmentsFor = ref<Task | null>(null)

/* ------------------------------------------------------------ 分享 / 导出 */

/** hash 路由：origin+pathname 后面拼 resolve 出来的 #/open?requestId=…（嵌入态带上 embed=1） */
async function copyShareLink() {
  const query: Record<string, string> = { requestId: queriedId.value }
  if (embedded.value) query.embed = '1'
  const hash = router.resolve({ path: route.path, query }).href
  const url = `${window.location.origin}${window.location.pathname}${window.location.search}${hash}`
  const ok = await copyText(url)
  ElMessage[ok ? 'success' : 'error']({ message: ok ? '链接已复制' : '复制失败', duration: 1500 })
}

/** 把当前批次落成 requestId.json，全部来自页面已有数据，不打新接口 */
function exportJson() {
  const data = {
    requestId: queriedId.value,
    overall: overall.value.label,
    counts: taskCounts.value,
    exportedAt: new Date().toISOString(),
    tasks: tasks.value.map((t) => {
      const v = verdictOf(t)
      return {
        taskId: t.taskId,
        command: t.command,
        status: t.status,
        operator: t.operator ?? null,
        targets: t.targets,
        machines: machineListOf(t),
        lastLine: v.text || null,
        exitCode: v.exit,
        callbackStatus: t.callbackStatus,
        executions: t.executions.map((e) => ({
          executeId: e.executeId,
          machine: e.displayTag || e.agentId || null,
          status: e.status,
          exitCode: e.exitCode ?? null,
          lastLine: e.lastLine || null,
          conditionHit: e.conditionHit || null,
          message: e.message || null,
          startedAt: e.startedAt ?? null,
          finishedAt: e.finishedAt ?? null,
        })),
      }
    }),
  }
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${queriedId.value || 'tasks'}.json`
  a.click()
  URL.revokeObjectURL(url)
}

/* ------------------------------------------------------------ 速览 */

const curlSnippet = computed(
  () =>
    `curl -X POST http://<server>:8080/api/tasks/batch \\
  -H 'Content-Type: application/json' \\
  -d '{"requestId":"${queriedId.value || 'ci-20260827-001'}",
       "callbackUrl":"http://<你的服务>/notify",
       "items":[{"command":"echo hi","targets":["机器tag"],"timeoutSec":600}]}'`,
)

async function copySnippet() {
  const ok = await copyText(curlSnippet.value)
  ElMessage[ok ? 'success' : 'error']({ message: ok ? '已复制' : '复制失败', duration: 1500 })
}
</script>

<template>
  <div class="page oc theme-open" :class="{ 'oc--embed': embedded }">
    <div class="oc__wrap" :class="{ 'oc__wrap--narrow': viewState !== 'result' }">
      <div v-if="!embedded" class="page-head">
        <div>
          <h2 class="page-head__title">开放查询</h2>
          <p class="page-head__desc">用创建任务时的 requestId 查这批任务的执行进度与回调投递，无需登录。</p>
        </div>
      </div>

      <div class="panel oc__bar">
        <div class="oc__field">
          <el-input
            v-model="requestIdInput"
            placeholder="requestId，例如 ci-20260827-001"
            clearable
            class="mono"
            :prefix-icon="'Search'"
            @keyup.enter="idValid && query()"
          />
          <div class="oc__hint" :class="{ 'is-error': idInvalid }">{{ hint }}</div>
        </div>

        <el-button type="primary" :loading="loading" :disabled="!idValid" @click="query()">查询</el-button>

        <div class="oc__bar-right">
          <span v-if="queriedId" class="oc__cur">
            <span class="oc__cur-k">当前</span>
            <CopyableId :value="queriedId" :head="18" />
          </span>
          <span v-if="lastLoadedAt" class="oc__stamp">{{ formatTime(lastLoadedAt) }} 更新</span>
          <el-tooltip content="任务未结束或回调还在投递时，每 5 秒自动刷新" placement="top" popper-class="oc-pop">
            <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
          </el-tooltip>
        </div>

        <div v-if="recentShown.length" class="oc__recent">
          <span class="oc__recent-k">最近</span>
          <button
            v-for="id in recentShown"
            :key="id"
            type="button"
            class="oc__recent-id mono"
            :title="`查询 ${id}`"
            @click="queryRecent(id)"
          >
            {{ id }}
          </button>
        </div>
      </div>

      <template v-if="viewState === 'result'">
        <el-alert v-if="refreshError" type="error" :closable="false" show-icon :title="refreshError" class="oc__alert" />

        <!-- 概览 -->
        <section class="panel oc-ov">
          <div class="oc-ov__head">
            <span
              class="oc-ov__badge"
              :style="{ color: overall.color, background: overall.bg, borderColor: overall.border }"
            >
              <i class="oc-ov__badge-dot" />
              {{ overall.label }}
            </span>
            <div class="oc-ov__meta">
              <span class="oc-ov__meta-i">
                <span class="oc-ov__k">操作人</span>
                <template v-if="operators.length">
                  <el-tag v-for="op in operators" :key="op" size="small" effect="plain" class="oc-ov__op">
                    {{ op }}
                  </el-tag>
                </template>
                <span v-else class="muted">-</span>
              </span>
              <span class="oc-ov__meta-i">
                <span class="oc-ov__k">开始</span>
                <span class="mono" :title="formatFullTime(timeSpan.start)">{{ formatTime(timeSpan.start) }}</span>
              </span>
              <span class="oc-ov__meta-i">
                <span class="oc-ov__k">结束</span>
                <span v-if="timeSpan.end" class="mono" :title="formatFullTime(timeSpan.end)">
                  {{ formatTime(timeSpan.end) }}
                </span>
                <span v-else class="oc-ov__live">进行中</span>
              </span>
              <span class="oc-ov__meta-i">
                <span class="oc-ov__k">时长</span>
                <span class="mono">{{ timeSpan.duration }}</span>
              </span>
              <span class="oc-ov__meta-i oc-ov__meta-i--cb">
                <span class="oc-ov__k">回调</span>
                <template v-if="callbackSummary.length">
                  <el-tooltip
                    v-for="c in callbackSummary"
                    :key="c.status"
                    :content="c.meta.desc"
                    placement="top"
                    popper-class="oc-pop"
                  >
                    <el-tag size="small" effect="light" :type="c.meta.type" class="oc-ov__cb">
                      {{ c.meta.label }} {{ c.n }}
                    </el-tag>
                  </el-tooltip>
                </template>
                <span v-else class="muted">未配置</span>
              </span>
            </div>
          </div>

          <div class="oc-ov__stats" role="group" aria-label="按状态筛选任务">
            <button
              v-for="it in statItems"
              :key="it.key"
              type="button"
              class="oc-ov__stat"
              :class="{ 'is-on': it.key !== 'total' && statusFilter === it.key }"
              :aria-pressed="it.key === 'total' ? undefined : statusFilter === it.key"
              :title="
                it.key === 'total'
                  ? '清除状态筛选'
                  : statusFilter === it.key
                    ? '再点一下清除筛选'
                    : `只看${it.label}`
              "
              @click="toggleStatusChip(it.key)"
            >
              <b class="oc-ov__n" :style="{ color: it.n ? it.color : 'var(--nat-text-weak)' }">{{ it.n }}</b>
              <span class="oc-ov__l">{{ it.label }}</span>
            </button>
          </div>

          <div class="oc-ov__prog">
            <span class="oc-ov__l">执行进度</span>
            <el-progress
              :percentage="progressPct"
              :stroke-width="10"
              :show-text="false"
              class="oc-ov__prog-bar"
            />
            <span class="oc-ov__prog-num mono">
              {{ terminalCount }}/{{ tasks.length }} · {{ progressPct.toFixed(1) }}%
            </span>
          </div>
        </section>

        <!-- 筛选 + 批量操作 + 任务表 -->
        <section class="panel oc-list">
          <div class="oc-tools">
            <el-input
              v-model="keyword"
              class="oc-tools__kw"
              placeholder="搜索命令 / taskId / 操作人 / 目标 / 最后一行"
              clearable
              :prefix-icon="'Search'"
            />
            <el-select v-model="statusFilter" class="oc-tools__status" placeholder="全部状态" clearable>
              <el-option label="全部状态" value="" />
              <el-option
                v-for="s in statusOptions"
                :key="s"
                :label="`${statusMeta(s).label} ${s === 'running' ? taskCounts.running + taskCounts.dispatching : taskCounts[s]}`"
                :value="s"
              />
            </el-select>
            <el-select
              v-model="machineFilter"
              class="oc-tools__machine"
              placeholder="全部机器"
              clearable
              filterable
            >
              <el-option label="全部机器" value="" />
              <el-option v-for="m in machineOptions" :key="m" :label="m" :value="m" />
            </el-select>
            <span class="muted oc-tools__count">{{ filtered.length }} / {{ tasks.length }}</span>

            <div class="oc-tools__end">
              <el-button size="small" plain @click="copyShareLink">复制链接</el-button>
              <el-button size="small" plain @click="exportJson">导出</el-button>
              <el-dropdown
                split-button
                type="primary"
                size="small"
                :disabled="!selectedFresh.length || batchRerunning"
                :persistent="false"
                @click="batchRerun('inplace')"
                @command="onBatchRerunCommand"
              >
                重新执行选中 ({{ selectedFresh.length }})
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="inplace">原地重跑（清空记录）</el-dropdown-item>
                    <el-dropdown-item command="new">重跑为新记录（换新 requestId）</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button
                size="small"
                type="warning"
                plain
                :disabled="!selectedCancelable.length"
                :loading="batchCanceling"
                @click="batchCancel"
              >
                取消选中 ({{ selectedCancelable.length }})
              </el-button>
            </div>
          </div>

          <el-table
            v-if="filtered.length"
            :data="filtered"
            row-key="taskId"
            size="default"
            :max-height="tableMaxHeight"
            :expand-row-keys="expandedKeys"
            @expand-change="onExpandChange"
            @selection-change="onSelectionChange"
          >
            <el-table-column type="selection" width="40" :reserve-selection="true" />

            <el-table-column type="expand" width="36">
              <template #default="{ row }">
                <div class="detail">
                  <el-table v-if="row.executions.length" :data="row.executions" size="small" class="detail__table">
                    <el-table-column label="状态" width="112">
                      <template #default="{ row: ex }">
                        <StatusPill :status="ex.status" :disconnected="ex.disconnected" />
                      </template>
                    </el-table-column>
                    <el-table-column label="机器" min-width="130" show-overflow-tooltip>
                      <template #default="{ row: ex }">{{ ex.displayTag || ex.agentId || '-' }}</template>
                    </el-table-column>
                    <el-table-column label="executeId" width="150">
                      <template #default="{ row: ex }">
                        <CopyableId :value="ex.executeId" :head="10" />
                      </template>
                    </el-table-column>
                    <el-table-column label="退出码" width="72" align="center">
                      <template #default="{ row: ex }">
                        <span class="mono">{{ ex.exitCode ?? '-' }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="最后一行 / 原因" min-width="200">
                      <template #default="{ row: ex }">
                        <code class="cmd" :title="ex.lastLine || ex.conditionHit || ''">
                          {{ ex.lastLine || ex.conditionHit || '-' }}
                        </code>
                      </template>
                    </el-table-column>
                    <el-table-column label="耗时" width="88">
                      <template #default="{ row: ex }">
                        <span class="mono">{{ durationBetween(ex.startedAt, ex.finishedAt) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="" width="88" align="right">
                      <template #default="{ row: ex }">
                        <el-button size="small" text type="primary" @click="gotoExecution(ex)">查看详情</el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                  <p v-else class="detail__none">
                    还没有生成执行记录 · 目标 {{ row.targets.join('、') || '未指定' }}
                  </p>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="任务" min-width="280">
              <template #default="{ row }">
                <el-tooltip
                  :content="row.command"
                  placement="top-start"
                  :show-after="400"
                  :persistent="false"
                  popper-class="oc-pop"
                >
                  <code class="cmd">{{ row.command }}</code>
                </el-tooltip>
                <div class="row-meta">
                  <CopyableId :value="row.taskId" :head="8" />
                  <el-tooltip
                    v-if="row.callbackStatus !== 'none'"
                    :content="callbackDetail(row)"
                    placement="top"
                    :persistent="false"
                    popper-class="oc-pop"
                  >
                    <el-tag size="small" effect="light" :type="callbackStatusMeta(row.callbackStatus).type">
                      回调{{ callbackStatusMeta(row.callbackStatus).label }}
                    </el-tag>
                  </el-tooltip>
                  <button
                    v-if="row.attachmentCount"
                    class="att-chip"
                    :title="`${row.attachmentCount} 个附件，点击查看 / 下载`"
                    @click.stop="attachmentsFor = row"
                  >
                    附件 {{ row.attachmentCount }}
                  </button>
                  <span v-if="row.operator" class="muted">{{ row.operator }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="判定 / 最后一行" min-width="200">
              <template #default="{ row }">
                <div v-if="verdictTitle(row)" class="verdict" :title="verdictTitle(row)">
                  <span v-if="verdictOf(row).exit !== null" class="verdict__exit mono">
                    exit {{ verdictOf(row).exit }}
                  </span>
                  <code v-if="verdictOf(row).text" class="cmd verdict__text">{{ verdictOf(row).text }}</code>
                </div>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>

            <el-table-column label="执行时间" width="132">
              <template #default="{ row }">
                <div class="time-stack">
                  <span class="mono time-cell" :title="formatFullTime(startOf(row))">
                    {{ formatTime(startOf(row)) }}
                  </span>
                  <span v-if="endOf(row)" class="mono time-cell muted" :title="formatFullTime(endOf(row))">
                    {{ formatTime(endOf(row)) }}
                  </span>
                  <span v-else-if="!isTerminal(row.status)" class="time-live">进行中</span>
                  <span v-else class="muted">-</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="机器" min-width="130" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono machine">{{ machinesOf(row) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="状态" width="112">
              <template #default="{ row }">
                <StatusPill :status="row.status" :disconnected="taskDisconnected(row)" />
              </template>
            </el-table-column>

            <el-table-column label="操作" width="216" align="right">
              <template #default="{ row }">
                <div class="acts">
                  <el-button size="small" text type="primary" @click="openLog(row)">日志</el-button>
                  <el-button size="small" text type="primary" @click="openNodes(row)">节点</el-button>
                  <el-button
                    v-if="!isTerminal(row.status)"
                    size="small"
                    text
                    type="danger"
                    :loading="acting === `cancel:${row.taskId}`"
                    @click="onCancel(row)"
                  >
                    取消
                  </el-button>
                  <el-dropdown
                    trigger="click"
                    placement="bottom-end"
                    :persistent="false"
                    popper-class="oc-menu"
                    @command="(c: string | number | object) => onRerunCommand(c, row)"
                  >
                    <el-button size="small" text type="primary" :loading="acting === `rerun:${row.taskId}`">
                      重跑
                      <el-icon class="el-icon--right"><ArrowDown /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="inplace" :disabled="!isTerminal(row.status)">
                          原地重跑（清空记录）
                        </el-dropdown-item>
                        <el-dropdown-item command="new">重跑为新记录（换新 requestId）</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <EmptyState v-else variant="search" title="没有符合条件的任务" desc="换个状态或关键字试试">
            <el-button size="small" @click="clearFilters">清空筛选</el-button>
          </EmptyState>
        </section>
      </template>

      <div v-else-if="viewState === 'loading'" class="panel oc__loading">
        <el-skeleton :rows="4" animated />
      </div>

      <div v-else-if="viewState === 'error'" class="panel">
        <EmptyState variant="error" title="查询失败" :desc="error">
          <el-button size="small" @click="query()">重试</el-button>
        </EmptyState>
      </div>

      <div v-else-if="viewState === 'empty'" class="panel">
        <EmptyState
          variant="search"
          :title="`没有 requestId 为 ${queriedId} 的任务`"
          desc="确认创建请求已成功，或检查 requestId 是否拼写正确"
        />
      </div>

      <div v-else class="panel oc__doc">
        <h3 class="oc__doc-t">接口速览</h3>
        <ul class="oc__doc-l">
          <li>
            <code class="code-inline">POST /api/tasks</code>
            建单条；requestId 留空由服务端生成并回显，可带 callbackUrl
          </li>
          <li>
            <code class="code-inline">POST /api/tasks/batch</code>
            一批最多 100 条共用一个 requestId；坏条目单独进 <code class="code-inline">errors</code>，其余照建
          </li>
          <li>
            <code class="code-inline">GET /api/tasks?requestId=</code>
            查这批任务与执行明细，也就是本页
          </li>
          <li>
            脚本回传附件（≤ 32MB）：
            <code class="code-inline">curl -F "file=@产物" "$ATEST_HTTP_BASE/api/executions/$ATEST_EXECUTE_ID/files"</code>
            ，本页任务行可查看与下载
          </li>
          <li>任务到终态后向 callbackUrl POST 一次结果；2xx 算送达，否则按 1s 起退避重试 5 次</li>
          <li>
            requestId 全局唯一（<code class="code-inline">^[A-Za-z0-9._-]{1,64}$</code>），重复创建返回 409
          </li>
          <li>
            完整接入手册见仓库 <code class="code-inline">new-auto-test/docs/open-api.md</code>，逐接口联调去「接入调试」（#/open/debug）
          </li>
        </ul>
        <div class="oc__code">
          <button class="link-btn oc__copy" @click="copySnippet">复制</button>
          <pre class="oc__pre">{{ curlSnippet }}</pre>
        </div>
      </div>
    </div>

    <!-- 抽屉按需挂载：@closed 时卸载，日志抽屉的 SSE 随组件销毁断开 -->
    <OpenLogDrawer
      v-if="logDrawer"
      :execute-id="logDrawer.executeId"
      :command="logDrawer.command"
      @closed="logDrawer = null"
    />
    <OpenTimelineDrawer
      v-if="timelineDrawer"
      :execute-id="timelineDrawer.executeId"
      :machine="timelineDrawer.machine"
      @closed="timelineDrawer = null"
    />
    <AttachmentsDialog
      v-if="attachmentsFor"
      :task-id="attachmentsFor.taskId"
      :task-label="attachmentsFor.command"
      @closed="attachmentsFor = null"
    />
  </div>
</template>

<style scoped>
/* ------------------------------------------------------------ 画布 */

/* 纸面画布：纯色平底，不加网格/纹理/滤镜 */
.oc {
  min-height: 100%;
  background-color: #f7f6f3;
}

/* 嵌入态：宿主已有标题与留白，页内只留必要间距 */
.oc--embed {
  padding: 12px 14px 18px;
}

/* 结果态放开宽度让表格有空间；速览/空态维持阅读宽度 */
.oc__wrap--narrow {
  max-width: 960px;
  margin: 0 auto;
}

/* ------------------------------------------------------------ 查询条 */

.oc__bar {
  position: sticky;
  top: 0;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  border-color: var(--nat-hairline);
}

.oc--embed .oc__bar {
  padding: 8px 12px;
}

.oc__field {
  display: flex;
  flex-direction: column;
  flex: 1 1 240px;
  max-width: 360px;
  min-width: 200px;
}

/* 校验文案固定占一行，出现/消失都不改变查询条高度 */
.oc__hint {
  min-height: 16px;
  line-height: 16px;
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--nat-text-weak);
}

.oc__hint.is-error {
  color: #b91c1c;
}

.oc__bar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  min-width: 0;
}

.oc__cur {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 280px;
  min-width: 0;
  padding: 3px 8px;
  border: 1px solid rgba(20, 18, 16, 0.1);
  border-radius: 6px;
  background: rgba(20, 18, 16, 0.03);
  font-size: 12px;
}

.oc__cur-k {
  color: var(--nat-text-weak);
  flex: none;
}

.oc__stamp {
  color: var(--nat-text-weak);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 搜索条下方的最近 requestId：占满一行的小标签，点了直接查 */
.oc__recent {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  width: 100%;
  min-width: 0;
}

.oc__recent-k {
  flex: none;
  color: var(--nat-text-weak);
  font-size: 11.5px;
}

.oc__recent-id {
  appearance: none;
  max-width: 220px;
  padding: 2px 8px;
  border: 1px solid rgba(20, 18, 16, 0.14);
  border-radius: 999px;
  background: rgba(20, 18, 16, 0.03);
  color: var(--nat-text-sub);
  font-size: 11.5px;
  line-height: 1.5;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: background-color 0.12s, border-color 0.12s, color 0.12s;
}

.oc__recent-id:hover,
.oc__recent-id:focus-visible {
  border-color: #1c1917;
  background: rgba(20, 18, 16, 0.07);
  color: #1c1917;
}

.oc__alert {
  margin-bottom: 12px;
}

/* ------------------------------------------------------------ 概览 */

.oc-ov {
  padding: 16px 18px;
  margin-bottom: 12px;
}

.oc-ov__head {
  display: flex;
  align-items: center;
  gap: 10px 18px;
  flex-wrap: wrap;
}

/* 总体状态胶囊：语义色来自行内样式，只有边框 + 填充，不发光 */
.oc-ov__badge {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: 15px;
  font-size: 13px;
  font-weight: 640;
  letter-spacing: 0.02em;
}

.oc-ov__badge-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

/* 8 格数据仓：细分隔线，数字等宽对齐；每格都是按钮，点了就是状态筛选 */
.oc-ov__stats {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  margin-top: 14px;
  border: 1px solid rgba(20, 18, 16, 0.1);
  border-radius: 10px;
  background: rgba(20, 18, 16, 0.02);
  overflow: hidden;
}

.oc-ov__stat {
  appearance: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 0;
  padding: 10px 6px 9px;
  border: none;
  border-left: 1px solid rgba(20, 18, 16, 0.08);
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: pointer;
  transition: background-color 0.12s, box-shadow 0.12s;
}

.oc-ov__stat:first-child {
  border-left: none;
}

.oc-ov__stat:hover {
  background: rgba(20, 18, 16, 0.045);
}

.oc-ov__stat:focus-visible {
  outline: 2px solid #1c1917;
  outline-offset: -2px;
}

/* 选中态：墨色内描边 + 轻填充，不发光 */
.oc-ov__stat.is-on {
  box-shadow: inset 0 0 0 1.5px #1c1917;
  background: rgba(20, 18, 16, 0.06);
}

.oc-ov__n {
  font-size: 22px;
  font-weight: 640;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.oc-ov__l {
  flex: none;
  color: var(--nat-text-weak);
  font-size: 11.5px;
  white-space: nowrap;
}

@media (max-width: 880px) {
  .oc-ov__stats {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .oc-ov__stat {
    border-top: 1px solid rgba(20, 18, 16, 0.08);
  }

  .oc-ov__stat:nth-child(-n + 4) {
    border-top: none;
  }

  .oc-ov__stat:nth-child(4n + 1) {
    border-left: none;
  }
}

.oc-ov__prog {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.oc-ov__prog-bar {
  flex: 1;
  min-width: 120px;
}

/* 进度轨：纯色填充，不加渐变和光晕 */
.oc-ov__prog-bar :deep(.el-progress-bar__outer) {
  background: rgba(20, 18, 16, 0.08);
}

.oc-ov__prog-bar :deep(.el-progress-bar__inner) {
  background: #1c1917;
}

.oc-ov__prog-num {
  flex: none;
  color: var(--nat-text-sub);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.oc-ov__meta {
  display: flex;
  align-items: center;
  gap: 6px 22px;
  flex-wrap: wrap;
  margin-left: auto;
  min-width: 0;
  font-size: 12.5px;
}

.oc-ov__meta-i {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex-wrap: wrap;
}

.oc-ov__k {
  flex: none;
  color: var(--nat-text-weak);
  font-size: 12px;
}

.oc-ov__op {
  font-weight: 500;
}

.oc-ov__cb {
  cursor: default;
  font-variant-numeric: tabular-nums;
}

.oc-ov__live {
  color: #1c1917;
  font-size: 12px;
}

/* ------------------------------------------------------------ 筛选 + 批量 */

.oc-tools {
  display: flex;
  align-items: center;
  gap: 10px 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--nat-border);
}

.oc-tools__kw {
  width: 300px;
  max-width: 100%;
}

.oc-tools__status {
  width: 132px;
}

.oc-tools__machine {
  width: 156px;
}

.oc-tools__count {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.oc-tools__end {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

/* 间距交给 gap，去掉相邻按钮自带的 margin */
.oc-tools__end :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* ------------------------------------------------------------ 表格 */

.cmd {
  display: block;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: #1c1917;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  font-size: 11.5px;
  flex-wrap: wrap;
}

/* 附件小筹码：跟本页墨色调一致的中性描边 */
.att-chip {
  appearance: none;
  padding: 0 7px;
  border: 1px solid rgba(20, 18, 16, 0.14);
  border-radius: 999px;
  background: rgba(20, 18, 16, 0.03);
  color: var(--nat-text-sub);
  font-size: 11px;
  line-height: 17px;
  cursor: pointer;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.att-chip:hover {
  border-color: #1c1917;
  color: #1c1917;
}

.time-stack {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.4;
}

.time-cell {
  font-size: 12px;
  color: var(--nat-text-sub);
  white-space: nowrap;
}

.time-live {
  color: #1c1917;
  font-size: 12px;
}

.machine {
  font-size: 12px;
  color: var(--nat-text-sub);
}

/* 判定列：exit 码小标 + 最后一行/命中原因，超长省略靠 title 看全 */
.verdict {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.verdict__exit {
  flex: none;
  padding: 0 5px;
  border: 1px solid rgba(20, 18, 16, 0.14);
  border-radius: 4px;
  background: rgba(20, 18, 16, 0.04);
  color: var(--nat-text-sub);
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.verdict__text {
  flex: 1;
  min-width: 0;
  color: var(--nat-text-sub);
}

.acts {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: nowrap;
}

.acts :deep(.el-button) {
  margin-left: 0;
  padding: 5px 6px;
}

/* 行内文字按钮走墨色，白底上要有存在感；危险操作用哑光红 */
.acts :deep(.el-button.is-text),
.acts :deep(.el-button.is-link) {
  color: #1c1917;
}

.acts :deep(.el-button.is-text.el-button--danger) {
  color: #b91c1c;
}

/* ------------------------------------------------------------ 展开面板 */

:deep(.el-table__expanded-cell) {
  padding: 0;
  background: #f7f6f3;
}

:deep(.el-table__expanded-cell:hover) {
  background: #f7f6f3 !important;
}

.detail {
  min-width: 0;
  padding: 10px 14px 12px;
}

.detail__table {
  width: 100%;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
}

.detail__none {
  margin: 0;
  padding: 4px 2px;
  color: var(--nat-text-weak);
  font-size: 12px;
}

/* CopyableId 在窄列里省略文本，复制图标不参与压缩 */
.oc :deep(.copyable) {
  max-width: 100%;
  min-width: 0;
}

.oc :deep(.copyable__text) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.oc :deep(.copyable__btn) {
  flex: none;
}

.oc__cur :deep(.copyable__btn) {
  opacity: 0.55;
}

/* EmptyState 默认的冷灰/冷蓝图标底在暖纸画布上换成暖墨色调 */
.oc :deep(.empty__icon) {
  background: rgba(20, 18, 16, 0.05);
  color: #a8a29e;
}

.oc :deep(.empty__icon--error) {
  background: rgba(185, 28, 28, 0.08);
  color: #b91c1c;
}

.oc :deep(.empty__icon--search) {
  background: rgba(20, 18, 16, 0.05);
  color: #57534e;
}

/* ------------------------------------------------------------ 空态 / 速览 */

.oc__loading {
  padding: 18px 20px;
}

.oc__doc {
  padding: 16px 20px 20px;
}

.oc__doc-t {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 620;
}

.oc__doc-l {
  margin: 0 0 14px;
  padding-left: 18px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.95;
}

/* 接口示例卡：暖纸底 + 发丝线，跟浅色主题一体（真正的日志窗口仍是黑终端） */
.oc__code {
  position: relative;
  border: 1px solid var(--nat-hairline);
  border-radius: 10px;
  background: #f3f1ed;
}

.oc__copy {
  position: absolute;
  top: 8px;
  right: 12px;
  font-size: 12px;
}

.oc__pre {
  margin: 0;
  padding: 12px 56px 12px 14px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.75;
  color: #1c1917;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

<style>
/* 命令 / 回调错误可能很长，弹层必须封顶 */
.el-popper.oc-pop {
  max-width: min(420px, 78vw);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.oc-menu .el-dropdown-menu__item {
  min-width: 148px;
}
</style>
