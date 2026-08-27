<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { errorMessage } from '@/api/http'
import { listTasks } from '@/api/tasks'
import { getTimeline } from '@/api/timeline'
import type { Task, TimelineEvent } from '@/api/types'
import { useAgents } from '@/stores/agents'
import { countExecutions, flattenExecutions, sumCounts } from '@/utils/aggregate'
import { durationBetween, formatTime, fromNow, truncateText } from '@/utils/format'
import { AGENT_STATUS_META, statusMeta } from '@/utils/status'
import StatCard from '@/components/StatCard.vue'
import StatusPill from '@/components/StatusPill.vue'
import AgentStatusLight from '@/components/AgentStatusLight.vue'
import TimelineList from '@/components/TimelineList.vue'
import EmptyState from '@/components/EmptyState.vue'

const { agents, loading: agentsLoading, error: agentsError, refresh: refreshAgents } = useAgents()

const tasks = ref<Task[]>([])
const events = ref<TimelineEvent[]>([])
const tasksLoading = ref(false)
const eventsLoading = ref(false)
const tasksError = ref('')
const eventsError = ref('')
const lastLoadedAt = ref<number | null>(null)

let timer: number | null = null

async function loadTasks() {
  tasksLoading.value = true
  try {
    tasks.value = await listTasks({ limit: 200 })
    tasksError.value = ''
  } catch (e) {
    tasksError.value = errorMessage(e, '加载任务失败')
  } finally {
    tasksLoading.value = false
  }
}

async function loadEvents() {
  eventsLoading.value = true
  try {
    events.value = await getTimeline({ limit: 40 })
    eventsError.value = ''
  } catch (e) {
    eventsError.value = errorMessage(e, '加载事件失败')
  } finally {
    eventsLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([refreshAgents(), loadTasks(), loadEvents()])
  lastLoadedAt.value = Date.now()
}

onMounted(() => {
  void refreshAll()
  timer = window.setInterval(() => void refreshAll(), 10_000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})

const agentStats = computed(() => {
  const stat = { online: 0, busy: 0, disconnected: 0, offline: 0 }
  for (const a of agents.value) stat[a.status] += 1
  return stat
})

const agentStatList = computed(() =>
  (['online', 'busy', 'disconnected', 'offline'] as const).map((key) => ({
    key,
    value: agentStats.value[key],
    label: AGENT_STATUS_META[key].label,
    color: AGENT_STATUS_META[key].color,
  })),
)

const capacity = computed(() => {
  let slots = 0
  let used = 0
  for (const a of agents.value) {
    if (a.status === 'offline') continue
    slots += a.concurrency
    used += Math.min(a.running, a.concurrency)
  }
  return { slots, used, pct: slots ? Math.round((used / slots) * 100) : 0 }
})

const counts = computed(() => countExecutions(tasks.value))
const runningCount = computed(() => sumCounts(counts.value, ['running', 'dispatching']))
const badCount = computed(() => sumCounts(counts.value, ['fail', 'block', 'exception']))
const pendingCount = computed(() => counts.value.pending)

const runningExecutions = computed(() =>
  flattenExecutions(tasks.value)
    .filter((e) => e.status === 'running' || e.status === 'dispatching')
    .sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0))
    .slice(0, 8),
)

const attentionExecutions = computed(() =>
  flattenExecutions(tasks.value)
    .filter((e) => ['fail', 'block', 'exception'].includes(e.status))
    .sort((a, b) => (b.finishedAt ?? 0) - (a.finishedAt ?? 0))
    .slice(0, 6),
)

const recentTasks = computed(() =>
  [...tasks.value].sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0)).slice(0, 6),
)

const busiestAgents = computed(() =>
  [...agents.value]
    .filter((a) => a.running > 0)
    .sort((a, b) => b.running / b.concurrency - a.running / a.concurrency)
    .slice(0, 5),
)

const distribution = computed(() => {
  const total = Object.values(counts.value).reduce((a, b) => a + b, 0)
  const keys = ['pass', 'fail', 'block', 'exception', 'canceled', 'running', 'dispatching', 'pending'] as const
  return keys
    .map((k) => ({ key: k, n: counts.value[k], meta: statusMeta(k), pct: total ? (counts.value[k] / total) * 100 : 0 }))
    .filter((x) => x.n > 0)
})

const hasError = computed(() => !!(agentsError.value || tasksError.value || eventsError.value))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">总览</h2>
        <p class="page-head__desc">
          集群实时状态与近期事件，每 10 秒自动刷新
          <template v-if="lastLoadedAt"> · 最后更新 {{ fromNow(lastLoadedAt) }}</template>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Refresh'" :loading="tasksLoading || agentsLoading" @click="refreshAll">刷新</el-button>
        <router-link to="/tasks">
          <el-button type="primary" :icon="'Plus'">新建任务</el-button>
        </router-link>
      </div>
    </div>

    <el-alert
      v-if="hasError"
      class="mb14"
      type="error"
      show-icon
      :closable="false"
      :title="agentsError || tasksError || eventsError"
    >
      <template #default>
        请确认 Server（默认 <code class="code-inline">http://127.0.0.1:8080</code>）已启动，或在右上角调整接口地址。
      </template>
    </el-alert>

    <div class="cards">
      <StatCard
        label="在线机器"
        :value="agentStats.online + agentStats.busy"
        :unit="`/ ${agents.length}`"
        color="#16a34a"
        icon="Monitor"
        :loading="agentsLoading && !agents.length"
        :hint="`空闲 ${agentStats.online} · 忙碌 ${agentStats.busy} · 失联 ${agentStats.disconnected} · 离线 ${agentStats.offline}`"
        to="/agents"
      />
      <StatCard
        label="运行中执行"
        :value="runningCount"
        color="#2563eb"
        icon="VideoPlay"
        :loading="tasksLoading && !tasks.length"
        :hint="`并发水位 ${capacity.used}/${capacity.slots} 槽位（${capacity.pct}%）`"
        to="/tasks"
      />
      <StatCard
        label="失败 / 阻塞 / 异常"
        :value="badCount"
        color="#dc2626"
        icon="WarningFilled"
        :loading="tasksLoading && !tasks.length"
        :hint="`失败 ${counts.fail} · 阻塞 ${counts.block} · 异常 ${counts.exception}`"
        to="/tasks"
      />
      <StatCard
        label="排队中"
        :value="pendingCount"
        color="#64748b"
        icon="Clock"
        :loading="tasksLoading && !tasks.length"
        :hint="pendingCount ? '可在任务队列页调整 pending 顺序' : '队列已清空'"
        to="/tasks"
      />
    </div>

    <div class="cols">
      <div class="col-main">
        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">
              正在执行
              <span class="hint">{{ runningExecutions.length }} 条（最多展示 8 条）</span>
            </div>
            <router-link to="/tasks" class="link-btn">查看全部</router-link>
          </div>
          <div class="panel__body panel__body--flush">
            <el-table v-if="runningExecutions.length" :data="runningExecutions" size="small">
              <el-table-column label="状态" width="118">
                <template #default="{ row }">
                  <StatusPill :status="row.status" :disconnected="row.disconnected" />
                </template>
              </el-table-column>
              <el-table-column label="机器" width="150">
                <template #default="{ row }">
                  <span class="nowrap">{{ row.displayTag || row.agentId || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="命令" min-width="240">
                <template #default="{ row }">
                  <el-tooltip :content="row.command" placement="top" :show-after="400">
                    <code class="cmd">{{ truncateText(row.command || '-', 70) }}</code>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="已运行" width="92">
                <template #default="{ row }">
                  <span class="mono">{{ durationBetween(row.startedAt, null) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="76" align="right">
                <template #default="{ row }">
                  <router-link :to="`/executions/${row.executeId}`" class="link-btn">日志</router-link>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState
              v-else-if="tasksError"
              size="small"
              variant="error"
              title="任务数据加载失败"
              :desc="tasksError"
            />
            <EmptyState v-else size="small" title="当前没有执行在跑" desc="创建任务后会自动调度到空闲机器" />
          </div>
        </div>

        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">
              需要关注
              <span class="hint">最近的失败 / 阻塞 / 异常</span>
            </div>
          </div>
          <div class="panel__body panel__body--flush">
            <el-table v-if="attentionExecutions.length" :data="attentionExecutions" size="small">
              <el-table-column label="结果" width="100">
                <template #default="{ row }"><StatusPill :status="row.status" /></template>
              </el-table-column>
              <el-table-column label="机器" width="150">
                <template #default="{ row }">{{ row.displayTag || row.agentId || '-' }}</template>
              </el-table-column>
              <el-table-column label="最后一行 / 命令" min-width="260">
                <template #default="{ row }">
                  <code class="cmd">{{ truncateText(row.lastLine || row.command || '-', 80) }}</code>
                </template>
              </el-table-column>
              <el-table-column label="退出码" width="76" align="center">
                <template #default="{ row }">
                  <span class="mono">{{ row.exitCode ?? '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="结束时间" width="130">
                <template #default="{ row }">
                  <span class="mono sub">{{ formatTime(row.finishedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="76" align="right">
                <template #default="{ row }">
                  <router-link :to="`/executions/${row.executeId}`" class="link-btn">详情</router-link>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState
              v-else-if="tasksError"
              size="small"
              variant="error"
              title="任务数据加载失败"
              :desc="tasksError"
            />
            <EmptyState v-else size="small" title="近期没有失败或阻塞" desc="集群状态良好" />
          </div>
        </div>

        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">最近任务</div>
            <router-link to="/tasks" class="link-btn">任务队列</router-link>
          </div>
          <div class="panel__body panel__body--flush">
            <el-table v-if="recentTasks.length" :data="recentTasks" size="small">
              <el-table-column label="状态" width="100">
                <template #default="{ row }"><StatusPill :status="row.status" /></template>
              </el-table-column>
              <el-table-column label="命令" min-width="260">
                <template #default="{ row }">
                  <code class="cmd">{{ truncateText(row.command, 70) }}</code>
                </template>
              </el-table-column>
              <el-table-column label="目标" width="90" align="center">
                <template #default="{ row }">{{ row.total || row.targets.length }} 台</template>
              </el-table-column>
              <el-table-column label="创建时间" width="130">
                <template #default="{ row }">
                  <span class="mono sub">{{ formatTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState
              v-else-if="tasksError"
              size="small"
              variant="error"
              title="任务数据加载失败"
              :desc="tasksError"
            />
            <EmptyState v-else size="small" title="还没有任务" desc="从任务队列页或测试下发页创建第一个任务" />
          </div>
        </div>
      </div>

      <div class="col-side">
        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">机器状态分布</div>
            <router-link to="/agents" class="link-btn">机器列表</router-link>
          </div>
          <div class="panel__body">
            <div v-if="agents.length" class="dist">
              <div v-for="item in agentStatList" :key="item.key" class="dist__item" :style="{ '--c': item.color }">
                <span class="dist__dot" />
                <span class="dist__label">{{ item.label }}</span>
                <span class="dist__value">{{ item.value }}</span>
              </div>
            </div>
            <EmptyState
              v-else-if="agentsError"
              size="small"
              variant="error"
              title="机器数据加载失败"
              :desc="agentsError"
            />
            <EmptyState v-else size="small" title="还没有机器接入" desc="Agent 启动后会自动注册到 Server" />

            <template v-if="busiestAgents.length">
              <div class="side-sub">负载最高</div>
              <div v-for="a in busiestAgents" :key="a.agentId" class="busy-row">
                <AgentStatusLight :status="a.status" :show-label="false" />
                <span class="busy-row__name text-ellipsis">{{ a.displayTag || a.agentId }}</span>
                <el-progress
                  class="busy-row__bar"
                  :percentage="Math.min(100, Math.round((a.running / Math.max(1, a.concurrency)) * 100))"
                  :stroke-width="6"
                  :show-text="false"
                />
                <span class="mono muted">{{ a.running }}/{{ a.concurrency }}</span>
              </div>
            </template>
          </div>
        </div>

        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">执行结果分布</div>
          </div>
          <div class="panel__body">
            <div v-if="distribution.length">
              <div class="bar">
                <span
                  v-for="d in distribution"
                  :key="d.key"
                  class="bar__seg"
                  :style="{ width: `${d.pct}%`, background: d.meta.color }"
                  :title="`${d.meta.label} ${d.n}`"
                />
              </div>
              <div class="legend">
                <span v-for="d in distribution" :key="d.key" class="legend__item">
                  <i class="legend__dot" :style="{ background: d.meta.color }" />
                  {{ d.meta.label }}
                  <b>{{ d.n }}</b>
                </span>
              </div>
            </div>
            <EmptyState
              v-else
              size="small"
              :variant="tasksError ? 'error' : 'empty'"
              :title="tasksError ? '执行数据加载失败' : '还没有执行记录'"
            />
          </div>
        </div>

        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">
              近期事件
              <span class="hint">agent / server</span>
            </div>
            <router-link to="/timeline" class="link-btn">完整时间线</router-link>
          </div>
          <div class="panel__body panel__body--flush events">
            <TimelineList
              :events="events.slice(0, 12)"
              :loading="eventsLoading"
              compact
              :empty-text="eventsError ? '事件加载失败' : '暂无事件上报'"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mb14 {
  margin-bottom: 14px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(320px, 1fr);
  gap: 14px;
  align-items: start;
}

@media (max-width: 1180px) {
  .cols {
    grid-template-columns: 1fr;
  }
}

.col-main > .panel + .panel,
.col-side > .panel + .panel {
  margin-top: 14px;
}

.cmd {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  color: #26303d;
  word-break: break-all;
}

.dist {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.dist__item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  background: #fbfcfe;
}

.dist__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c);
}

.dist__label {
  color: var(--nat-text-sub);
  font-size: 12.5px;
  flex: 1;
}

.dist__value {
  font-weight: 660;
  font-size: 15px;
  color: var(--c);
  font-variant-numeric: tabular-nums;
}

.side-sub {
  margin: 14px 0 8px;
  font-size: 12px;
  color: var(--nat-text-weak);
}

.busy-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}

.busy-row__name {
  width: 96px;
  font-size: 12.5px;
}

.busy-row__bar {
  flex: 1;
}

.bar {
  display: flex;
  height: 10px;
  border-radius: 5px;
  overflow: hidden;
  background: #eef1f6;
}

.bar__seg {
  height: 100%;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--nat-text-sub);
}

.legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.legend b {
  color: var(--nat-text);
  font-variant-numeric: tabular-nums;
}

.events {
  max-height: 520px;
  overflow-y: auto;
  padding: 0 10px;
}
</style>
