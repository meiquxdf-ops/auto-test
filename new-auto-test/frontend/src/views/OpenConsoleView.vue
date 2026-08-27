<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listTasksByRequestId } from '@/api/tasks'
import { errorMessage } from '@/api/http'
import type { Execution, Task } from '@/api/types'
import { durationBetween, formatFullTime, formatTime, truncateText } from '@/utils/format'
import { callbackStatusMeta, isTerminal } from '@/utils/status'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'
import CopyableId from '@/components/CopyableId.vue'

const route = useRoute()
const router = useRouter()

const LAST_KEY = 'nat.openConsole.requestId'

const requestIdInput = ref('')
const queriedId = ref('')
const tasks = ref<Task[]>([])
const loading = ref(false)
const error = ref('')
const searched = ref(false)
const lastLoadedAt = ref<number | null>(null)
const autoRefresh = ref(true)

let timer: number | null = null

const idValid = computed(() => /^[A-Za-z0-9._-]{1,64}$/.test(requestIdInput.value.trim()))

async function query(silent = false) {
  const id = requestIdInput.value.trim()
  if (!id) return
  if (!silent) loading.value = true
  try {
    tasks.value = await listTasksByRequestId(id)
    queriedId.value = id
    searched.value = true
    error.value = ''
    lastLoadedAt.value = Date.now()
    localStorage.setItem(LAST_KEY, id)
    if (route.query.requestId !== id) {
      void router.replace({ query: { ...route.query, requestId: id } })
    }
  } catch (e) {
    error.value = errorMessage(e, '查询失败')
    searched.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const fromUrl = typeof route.query.requestId === 'string' ? route.query.requestId : ''
  requestIdInput.value = fromUrl || localStorage.getItem(LAST_KEY) || ''
  if (requestIdInput.value) void query()
  timer = window.setInterval(() => {
    if (autoRefresh.value && queriedId.value && hasActive.value) void query(true)
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})

/* ------------------------------------------------------------ 汇总 */

const allExecutions = computed<Execution[]>(() => tasks.value.flatMap((t) => t.executions))

const runningCount = computed(
  () => allExecutions.value.filter((e) => e.status === 'running' || e.status === 'dispatching').length,
)
const pendingCount = computed(() => allExecutions.value.filter((e) => e.status === 'pending').length)
const doneCount = computed(() => allExecutions.value.filter((e) => isTerminal(e.status)).length)

const hasActive = computed(() => tasks.value.some((t) => !isTerminal(t.status)))

const callbackSummary = computed(() => {
  const map = new Map<string, number>()
  for (const t of tasks.value) {
    if (t.callbackStatus === 'none') continue
    map.set(t.callbackStatus, (map.get(t.callbackStatus) ?? 0) + 1)
  }
  return [...map.entries()].map(([status, n]) => ({
    status: status as Task['callbackStatus'],
    n,
    meta: callbackStatusMeta(status as Task['callbackStatus']),
  }))
})

function progressOf(task: Task) {
  const done = task.executions.filter((e) => isTerminal(e.status)).length
  const total = task.executions.length || task.total || 0
  return `${done} / ${total}`
}

function callbackDetail(task: Task): string {
  const meta = callbackStatusMeta(task.callbackStatus)
  const parts = [meta.desc]
  if (task.callbackAttempts) parts.push(`已尝试 ${task.callbackAttempts} 次`)
  if (task.callbackLastError) parts.push(`最近错误：${task.callbackLastError}`)
  return parts.join('；')
}

function gotoExecution(exec: Execution) {
  void router.push(`/executions/${exec.executeId}`)
}

const curlCreate = computed(() => {
  return [
    `curl -X POST http://<server>:8080/api/tasks/batch -H 'Content-Type: application/json' -d '{`,
    `  "requestId": "${queriedId.value || 'your-unique-id'}",`,
    `  "callbackUrl": "http://<你的服务>/notify",`,
    `  "items": [ { "command": "echo hi", "targets": ["机器tag"], "timeoutSec": 600 } ]`,
    `}'`,
  ].join('\n')
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">开放查询</h2>
        <p class="page-head__desc">
          输入创建任务时携带的 requestId，查看该批任务的执行进度与回调投递状态（无需登录）
        </p>
      </div>
    </div>

    <div class="panel oc__search">
      <el-input
        v-model="requestIdInput"
        placeholder="requestId，例如 ci-20260827-001"
        clearable
        class="mono oc__input"
        :prefix-icon="'Search'"
        @keyup.enter="idValid && query()"
      />
      <el-button type="primary" :loading="loading" :disabled="!idValid" @click="query()">查询</el-button>
      <el-tooltip content="有未完成任务时每 5 秒自动刷新" placement="top">
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
      </el-tooltip>
      <span v-if="requestIdInput.trim() && !idValid" class="oc__invalid">
        requestId 只允许字母、数字和 . _ -，长度 1-64
      </span>
      <span class="spacer" />
      <span v-if="lastLoadedAt" class="muted">更新于 {{ formatTime(lastLoadedAt) }}</span>
    </div>

    <template v-if="searched && queriedId">
      <div v-if="tasks.length" class="oc__summary panel">
        <div class="oc__stat">
          <span class="oc__stat-n">{{ tasks.length }}</span>
          <span class="oc__stat-l">任务</span>
        </div>
        <div class="oc__stat">
          <span class="oc__stat-n oc__stat-n--run">{{ runningCount }}</span>
          <span class="oc__stat-l">执行中</span>
        </div>
        <div class="oc__stat">
          <span class="oc__stat-n">{{ pendingCount }}</span>
          <span class="oc__stat-l">排队中</span>
        </div>
        <div class="oc__stat">
          <span class="oc__stat-n">{{ doneCount }} / {{ allExecutions.length }}</span>
          <span class="oc__stat-l">已完成执行</span>
        </div>
        <el-divider direction="vertical" />
        <div class="oc__cbs">
          <span class="muted">回调：</span>
          <template v-if="callbackSummary.length">
            <el-tooltip v-for="c in callbackSummary" :key="c.status" :content="c.meta.desc" placement="top">
              <el-tag size="small" effect="light" :type="c.meta.type" class="oc__cb-tag">
                {{ c.meta.label }} × {{ c.n }}
              </el-tag>
            </el-tooltip>
          </template>
          <span v-else class="muted">该批任务未配置回调</span>
        </div>
      </div>

      <div v-for="task in tasks" :key="task.taskId" class="panel oc__task">
        <div class="oc__task-head">
          <StatusPill :status="task.status" />
          <el-tooltip :content="task.command" placement="top-start" :show-after="500">
            <code class="oc__cmd">{{ truncateText(task.command, 96) }}</code>
          </el-tooltip>
          <span class="spacer" />
          <el-tooltip v-if="task.callbackStatus !== 'none'" :content="callbackDetail(task)" placement="top">
            <el-tag size="small" effect="light" :type="callbackStatusMeta(task.callbackStatus).type">
              回调{{ callbackStatusMeta(task.callbackStatus).label }}
            </el-tag>
          </el-tooltip>
        </div>
        <div class="oc__task-meta">
          taskId <CopyableId :value="task.taskId" :head="10" />
          <span>· 完成 {{ progressOf(task) }}</span>
          <span v-if="task.operator">· 操作人 {{ task.operator }}</span>
          <span v-if="task.createdAt">· 创建于 {{ formatFullTime(task.createdAt) }}</span>
          <span v-if="task.callbackLastError" class="oc__cb-err">
            · 回调错误：{{ truncateText(task.callbackLastError, 60) }}
          </span>
        </div>

        <el-table v-if="task.executions.length" :data="task.executions" size="small" class="oc__table">
          <el-table-column label="状态" width="116">
            <template #default="{ row: ex }">
              <StatusPill :status="ex.status" :disconnected="ex.disconnected" />
            </template>
          </el-table-column>
          <el-table-column label="机器" min-width="140">
            <template #default="{ row: ex }">{{ ex.displayTag || ex.agentId || '-' }}</template>
          </el-table-column>
          <el-table-column label="executeId" width="150">
            <template #default="{ row: ex }">
              <CopyableId :value="ex.executeId" :head="10" />
            </template>
          </el-table-column>
          <el-table-column label="退出码" width="76" align="center">
            <template #default="{ row: ex }">
              <span class="mono">{{ ex.exitCode ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最后一行 / 原因" min-width="220">
            <template #default="{ row: ex }">
              <code class="oc__cmd">{{ truncateText(ex.lastLine || ex.conditionHit || '-', 64) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="88">
            <template #default="{ row: ex }">
              <span class="mono">{{ durationBetween(ex.startedAt, ex.finishedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="92" align="right">
            <template #default="{ row: ex }">
              <el-button size="small" text type="primary" @click="gotoExecution(ex)">查看日志</el-button>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else size="small" title="该任务还没有生成执行记录" :desc="`目标：${task.targets.join('、') || '未指定'}`" />
      </div>

      <EmptyState
        v-if="!tasks.length && !error"
        variant="search"
        :title="`没有找到 requestId 为 ${queriedId} 的任务`"
        desc="确认创建请求已成功，或检查 requestId 是否拼写正确"
      />
      <EmptyState v-if="error" variant="error" title="查询失败" :desc="error">
        <el-button size="small" @click="query()">重试</el-button>
      </EmptyState>
    </template>

    <div v-else class="panel oc__intro">
      <EmptyState
        title="按 requestId 查询任务"
        desc="开放接口创建任务时必须携带全局唯一的 requestId，之后凭它在这里跟踪整批任务与回调投递"
      />
      <div class="oc__doc">
        <div class="oc__doc-title">接口速览（无需登录）</div>
        <ul class="oc__doc-list">
          <li><code class="code-inline">POST /api/tasks</code> 创建单任务，body 里带 <code class="code-inline">requestId</code>（必带）与可选 <code class="code-inline">callbackUrl</code></li>
          <li><code class="code-inline">POST /api/tasks/batch</code> 一次建多条任务（最多 100 条），不同命令/目标共用一个 requestId，任一目标不存在整单拒绝</li>
          <li><code class="code-inline">GET /api/tasks?requestId=...</code> 查询该批全部任务与执行明细</li>
          <li>任务终态（finished / canceled）后向 callbackUrl POST 一次完整结果；2xx 算送达，失败按 1s/2s/4s/8s 退避共试 5 次</li>
          <li>requestId 全局唯一（<code class="code-inline">^[A-Za-z0-9._-]{1,64}$</code>），重复创建返回 409</li>
        </ul>
        <pre class="oc__curl">{{ curlCreate }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.oc__search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  flex-wrap: wrap;
}

.oc__input {
  width: 340px;
}

.oc__invalid {
  color: #dc2626;
  font-size: 12px;
}

.oc__summary {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 14px 18px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.oc__stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.oc__stat-n {
  font-size: 20px;
  font-weight: 640;
  font-variant-numeric: tabular-nums;
}

.oc__stat-n--run {
  color: #2563eb;
}

.oc__stat-l {
  color: var(--nat-text-weak);
  font-size: 12px;
}

.oc__cbs {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.oc__cb-tag {
  cursor: default;
}

.oc__task {
  margin-top: 12px;
  padding: 14px 16px;
}

.oc__task-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.oc__cmd {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: #26303d;
  word-break: break-all;
}

.oc__task-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 6px 0 10px;
  color: var(--nat-text-weak);
  font-size: 12px;
}

.oc__cb-err {
  color: #dc2626;
}

.oc__table {
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
}

.oc__intro {
  margin-top: 12px;
  padding: 18px;
}

.oc__doc {
  max-width: 760px;
  margin: 8px auto 0;
  border-top: 1px dashed var(--nat-border);
  padding-top: 14px;
}

.oc__doc-title {
  font-weight: 620;
  margin-bottom: 8px;
}

.oc__doc-list {
  margin: 0 0 10px;
  padding-left: 18px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 2;
}

.oc__curl {
  background: #0f172a;
  color: #d3e0f0;
  border-radius: 8px;
  padding: 12px 14px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre;
  margin: 0;
}
</style>
