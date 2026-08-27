<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listTasksByRequestId } from '@/api/tasks'
import { errorMessage } from '@/api/http'
import { EXECUTION_STATUSES, type Execution, type ExecutionStatus, type Task } from '@/api/types'
import { copyText, durationBetween, formatFullTime, formatTime } from '@/utils/format'
import { callbackStatusMeta, isTerminal, statusMeta } from '@/utils/status'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'
import CopyableId from '@/components/CopyableId.vue'

const route = useRoute()
const router = useRouter()

const LAST_KEY = 'nat.openConsole.requestId'
const ID_RE = /^[A-Za-z0-9._-]{1,64}$/
/** 进行中的任务自动展开，但最多这些个，避免一次挂 100 张明细表 */
const AUTO_EXPAND_MAX = 5

const deepLinkId = typeof route.query.requestId === 'string' ? route.query.requestId.trim() : ''
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
const expanded = ref<Record<string, boolean>>({})

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
    if (route.query.requestId !== id) {
      void router.replace({ query: { ...route.query, requestId: id } })
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

onMounted(() => {
  if (idValid.value) void query()
  timer = window.setInterval(() => {
    if (!autoRefresh.value || !queriedId.value || !hasActive.value) return
    if (document.visibilityState !== 'visible') return
    void query(true)
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
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

/* ------------------------------------------------------------ 汇总 */

const totals = computed(() => {
  let exec = 0
  let running = 0
  let pending = 0
  let done = 0
  for (const t of tasks.value) {
    for (const s of EXECUTION_STATUSES) {
      const n = t.counts[s]
      if (!n) continue
      exec += n
      if (s === 'running' || s === 'dispatching') running += n
      else if (s === 'pending') pending += n
      else done += n
    }
  }
  return { exec, running, pending, done }
})

const hasActive = computed(() => tasks.value.some((t) => !isTerminal(t.status)))

const callbackSummary = computed(() => {
  const map = new Map<Task['callbackStatus'], number>()
  for (const t of tasks.value) {
    if (t.callbackStatus === 'none') continue
    map.set(t.callbackStatus, (map.get(t.callbackStatus) ?? 0) + 1)
  }
  return [...map.entries()].map(([status, n]) => ({ status, n, meta: callbackStatusMeta(status) }))
})

/* ------------------------------------------------------------ 任务卡片 */

interface CardVM {
  task: Task
  done: number
  total: number
  segments: { key: ExecutionStatus; pct: number; color: string; label: string }[]
}

const cards = computed<CardVM[]>(() =>
  tasks.value.map((task) => {
    const base = task.executions.length || 1
    return {
      task,
      done: EXECUTION_STATUSES.filter(isTerminal).reduce((n, s) => n + task.counts[s], 0),
      total: task.executions.length || task.total || 0,
      segments: EXECUTION_STATUSES.filter((s) => task.counts[s] > 0).map((s) => ({
        key: s,
        pct: (task.counts[s] / base) * 100,
        color: statusMeta(s).color,
        label: `${statusMeta(s).label} ${task.counts[s]}`,
      })),
    }
  }),
)

const autoOpenIds = computed(
  () => new Set(tasks.value.filter((t) => !isTerminal(t.status)).slice(0, AUTO_EXPAND_MAX).map((t) => t.taskId)),
)

function isOpen(task: Task): boolean {
  return expanded.value[task.taskId] ?? autoOpenIds.value.has(task.taskId)
}

function toggle(task: Task) {
  expanded.value = { ...expanded.value, [task.taskId]: !isOpen(task) }
}

const openCount = computed(() => tasks.value.reduce((n, t) => n + (isOpen(t) ? 1 : 0), 0))
const allOpen = computed(() => tasks.value.length > 0 && openCount.value === tasks.value.length)

function toggleAll() {
  const next = !allOpen.value
  const map: Record<string, boolean> = {}
  for (const t of tasks.value) map[t.taskId] = next
  expanded.value = map
}

function callbackDetail(task: Task): string {
  const meta = callbackStatusMeta(task.callbackStatus)
  const parts = [meta.desc]
  if (task.callbackUrl) parts.push(`地址 ${task.callbackUrl}`)
  if (task.callbackAttempts) parts.push(`已尝试 ${task.callbackAttempts} 次`)
  if (task.callbackLastError) parts.push(`最近错误：${task.callbackLastError}`)
  return parts.join('\n')
}

function machineOf(ex: Execution): string {
  return ex.displayTag || ex.agentId || '-'
}

function lineOf(ex: Execution): string {
  return ex.lastLine || ex.conditionHit || '-'
}

function gotoExecution(exec: Execution) {
  void router.push(`/executions/${exec.executeId}`)
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
  <div class="page oc">
    <div class="oc__wrap">
      <div class="page-head">
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
          <el-tooltip content="有未完成任务时每 5 秒自动刷新" placement="top" popper-class="oc-pop">
            <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
          </el-tooltip>
        </div>
      </div>

      <template v-if="viewState === 'result'">
        <el-alert v-if="refreshError" type="error" :closable="false" show-icon :title="refreshError" class="oc__alert" />

        <div class="panel oc__sum">
          <div class="oc__stat">
            <b class="oc__n">{{ cards.length }}</b>
            <span class="oc__l">任务</span>
          </div>
          <div class="oc__stat">
            <b class="oc__n oc__n--run">{{ totals.running }}</b>
            <span class="oc__l">执行中</span>
          </div>
          <div class="oc__stat">
            <b class="oc__n">{{ totals.pending }}</b>
            <span class="oc__l">排队</span>
          </div>
          <div class="oc__stat oc__stat--wide">
            <b class="oc__n">{{ totals.done }} / {{ totals.exec }}</b>
            <span class="oc__l">执行完成</span>
          </div>

          <div class="oc__cbs">
            <span class="oc__l">回调</span>
            <template v-if="callbackSummary.length">
              <el-tooltip
                v-for="c in callbackSummary"
                :key="c.status"
                :content="c.meta.desc"
                placement="top"
                popper-class="oc-pop"
              >
                <el-tag size="small" effect="light" :type="c.meta.type" class="oc__cb">
                  {{ c.meta.label }} {{ c.n }}
                </el-tag>
              </el-tooltip>
            </template>
            <span v-else class="oc__l">未配置</span>
          </div>

          <button class="link-btn oc__all" @click="toggleAll">{{ allOpen ? '全部收起' : '全部展开' }}</button>
        </div>

        <article v-for="card in cards" :key="card.task.taskId" class="panel oc-card">
          <button type="button" class="oc-card__head" @click="toggle(card.task)">
            <el-icon class="oc-card__caret" :class="{ 'is-open': isOpen(card.task) }"><ArrowRight /></el-icon>
            <StatusPill :status="card.task.status" />
            <el-tooltip :content="card.task.command" placement="top-start" :show-after="400" popper-class="oc-pop">
              <code class="oc-card__cmd">{{ card.task.command }}</code>
            </el-tooltip>
            <span class="oc-card__bar">
              <i
                v-for="seg in card.segments"
                :key="seg.key"
                class="oc-card__seg"
                :style="{ width: `${seg.pct}%`, background: seg.color }"
                :title="seg.label"
              />
            </span>
            <span class="oc-card__prog">{{ card.done }} / {{ card.total }}</span>
          </button>

          <div class="oc-card__meta">
            <span class="oc-card__id">taskId <CopyableId :value="card.task.taskId" :head="10" /></span>
            <span v-if="card.task.operator">操作人 {{ card.task.operator }}</span>
            <span v-if="card.task.createdAt" :title="formatFullTime(card.task.createdAt)">
              创建 {{ formatTime(card.task.createdAt) }}
            </span>
            <el-tooltip
              v-if="card.task.callbackStatus !== 'none'"
              :content="callbackDetail(card.task)"
              placement="top"
              popper-class="oc-pop"
            >
              <el-tag size="small" effect="light" :type="callbackStatusMeta(card.task.callbackStatus).type">
                回调{{ callbackStatusMeta(card.task.callbackStatus).label }}
              </el-tag>
            </el-tooltip>
            <span v-if="card.task.callbackLastError" class="oc-card__err" :title="card.task.callbackLastError">
              回调错误 {{ card.task.callbackLastError }}
            </span>
          </div>

          <div v-if="isOpen(card.task)" class="oc-exec">
            <template v-if="card.task.executions.length">
              <div class="oc-exec__row oc-exec__row--head">
                <span class="c-st">状态</span>
                <span class="c-machine">机器</span>
                <span class="c-id">executeId</span>
                <span class="c-exit">退出码</span>
                <span class="c-line">最后一行 / 原因</span>
                <span class="c-dur">耗时</span>
                <span class="c-act" />
              </div>
              <div v-for="ex in card.task.executions" :key="ex.executeId" class="oc-exec__row">
                <span class="c-st"><StatusPill :status="ex.status" :disconnected="ex.disconnected" /></span>
                <span class="c-machine" :title="machineOf(ex)">{{ machineOf(ex) }}</span>
                <span class="c-id"><CopyableId :value="ex.executeId" :head="10" /></span>
                <span class="c-exit mono">{{ ex.exitCode ?? '-' }}</span>
                <span class="c-line mono" :title="lineOf(ex)">{{ lineOf(ex) }}</span>
                <span class="c-dur mono">{{ durationBetween(ex.startedAt, ex.finishedAt) }}</span>
                <span class="c-act">
                  <button class="link-btn" @click="gotoExecution(ex)">查看日志</button>
                </span>
              </div>
            </template>
            <p v-else class="oc-exec__none">
              还没有生成执行记录 · 目标 {{ card.task.targets.join('、') || '未指定' }}
            </p>
          </div>
        </article>
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
          <li>任务到终态后向 callbackUrl POST 一次结果；2xx 算送达，否则按 1s 起退避重试 5 次</li>
          <li>
            requestId 全局唯一（<code class="code-inline">^[A-Za-z0-9._-]{1,64}$</code>），重复创建返回 409
          </li>
        </ul>
        <div class="oc__code">
          <button class="link-btn oc__copy" @click="copySnippet">复制</button>
          <pre class="oc__pre">{{ curlSnippet }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.oc__wrap {
  /* 阅读宽度：超宽屏也不把一行内容拉到两米长 */
  max-width: 960px;
  margin: 0 auto;
  container-type: inline-size;
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
  color: #dc2626;
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
  border: 1px solid var(--nat-border);
  border-radius: 6px;
  background: #f5f7fa;
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

/* ------------------------------------------------------------ 汇总条 */

.oc__alert {
  margin-bottom: 12px;
}

.oc__sum {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 11px 16px;
  margin-bottom: 12px;
  flex-wrap: nowrap;
}

.oc__stat {
  display: flex;
  align-items: baseline;
  gap: 5px;
  flex: none;
  /* 数字位数变化时不推挤后面的内容 */
  min-width: 68px;
  white-space: nowrap;
}

.oc__stat--wide {
  min-width: 118px;
}

.oc__n {
  font-size: 18px;
  font-weight: 640;
  font-variant-numeric: tabular-nums;
}

.oc__n--run {
  color: #2563eb;
}

.oc__l {
  flex: none;
  color: var(--nat-text-weak);
  font-size: 12px;
  white-space: nowrap;
}

.oc__cbs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  min-width: 0;
  flex-wrap: wrap;
}

.oc__cb {
  flex: none;
  cursor: default;
  font-variant-numeric: tabular-nums;
}

.oc__all {
  flex: none;
  font-size: 12px;
}

/* 一行放不下时整组换行，而不是把回调标签挤没 */
@container (max-width: 860px) {
  .oc__sum {
    flex-wrap: wrap;
  }

  .oc__cbs {
    margin-left: 0;
  }
}

/* ------------------------------------------------------------ 任务卡片 */

.oc-card {
  margin-bottom: 10px;
}

.oc-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 14px 5px;
  background: none;
  border: 0;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.oc-card__caret {
  flex: none;
  font-size: 12px;
  color: var(--nat-text-weak);
  transition: transform 0.15s ease;
}

.oc-card__caret.is-open {
  transform: rotate(90deg);
}

.oc-card__cmd {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: #26303d;
}

.oc-card__bar {
  display: flex;
  flex: none;
  width: 84px;
  height: 5px;
  border-radius: 3px;
  overflow: hidden;
  background: #eef1f6;
}

.oc-card__seg {
  height: 100%;
}

.oc-card__prog {
  flex: none;
  min-width: 56px;
  text-align: right;
  font-size: 12px;
  color: var(--nat-text-sub);
  font-variant-numeric: tabular-nums;
}

.oc-card__meta {
  display: flex;
  align-items: center;
  gap: 4px 14px;
  flex-wrap: wrap;
  padding: 0 14px 11px 36px;
  color: var(--nat-text-weak);
  font-size: 12px;
}

.oc-card__id {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.oc-card__err {
  max-width: 320px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #dc2626;
}

/* ------------------------------------------------------------ 执行明细 */

.oc-exec {
  container-type: inline-size;
  border-top: 1px solid var(--nat-border);
  padding: 4px 8px 6px;
}

.oc-exec__none {
  margin: 0;
  padding: 10px 6px;
  color: var(--nat-text-weak);
  font-size: 12px;
}

/* 窄屏：两行、四列；executeId 与耗时让位，不堆 min-width 撑出横向滚动 */
.oc-exec__row {
  display: grid;
  align-items: center;
  gap: 2px 10px;
  padding: 5px 6px;
  grid-template-columns: 132px minmax(0, 1fr) 78px 68px;
  grid-template-areas:
    'st machine exit act'
    'line line line line';
}

.oc-exec__row + .oc-exec__row {
  border-top: 1px solid #f1f3f7;
}

.oc-exec__row--head {
  display: none;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--nat-border);
  color: var(--nat-text-weak);
  font-size: 11.5px;
}

.c-st {
  grid-area: st;
}

.c-machine {
  grid-area: machine;
}

.c-id {
  grid-area: id;
  display: none;
}

.c-exit {
  grid-area: exit;
}

.c-line {
  grid-area: line;
}

.c-dur {
  grid-area: dur;
  display: none;
}

.c-act {
  grid-area: act;
  text-align: right;
}

.c-machine,
.c-id,
.c-line,
.c-exit,
.c-dur {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}

.c-line {
  color: var(--nat-text-sub);
}

.c-exit::before {
  content: '退出 ';
  color: var(--nat-text-weak);
}

.oc-exec__row--head .c-exit::before {
  content: '';
}

.c-act .link-btn {
  font-size: 12.5px;
}

@container (min-width: 640px) {
  .oc-exec__row {
    grid-template-columns: 132px minmax(88px, 1fr) 56px minmax(140px, 2fr) 66px 68px;
    grid-template-areas: 'st machine exit line dur act';
  }

  .oc-exec__row--head {
    display: grid;
  }

  .c-dur {
    display: block;
  }

  .c-exit {
    text-align: center;
  }

  .c-exit::before {
    content: '';
  }
}

@container (min-width: 880px) {
  .oc-exec__row {
    grid-template-columns: 132px minmax(88px, 1fr) 146px 56px minmax(150px, 2fr) 66px 68px;
    grid-template-areas: 'st machine id exit line dur act';
  }

  .c-id {
    display: block;
  }
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

.oc :deep(.copyable__icon) {
  flex: none;
}

.oc__cur :deep(.copyable__icon) {
  opacity: 0.55;
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

.oc__code {
  position: relative;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  background: #f7f9fc;
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
  color: #26303d;
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
</style>
