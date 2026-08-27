<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { errorMessage } from '@/api/http'
import { listTasks } from '@/api/tasks'
import { getTimeline } from '@/api/timeline'
import type { Task, TimelineEvent } from '@/api/types'
import { useAgents } from '@/stores/agents'
import { countExecutions, flattenExecutions, sumCounts } from '@/utils/aggregate'
import { durationBetween, formatTime, fromNow } from '@/utils/format'
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

/**
 * 表格列按「主栏实际宽度」取舍，而不是猜视口：
 * 侧栏 216px、可收起到 62px，只有量出来的宽度才靠得住。
 */
const mainPanelEl = ref<HTMLElement | null>(null)
const mainWidth = ref(1120)
let observer: ResizeObserver | null = null

const showMachineCol = computed(() => mainWidth.value >= 440)
const showMetaCol = computed(() => mainWidth.value >= 580)
const showTimeCol = computed(() => mainWidth.value >= 700)

onMounted(() => {
  void refreshAll()
  timer = window.setInterval(() => void refreshAll(), 10_000)

  if (mainPanelEl.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width ?? 0
      if (w > 0 && Math.abs(w - mainWidth.value) >= 4) mainWidth.value = w
    })
    observer.observe(mainPanelEl.value)
  }
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
  observer?.disconnect()
  observer = null
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

const agentsFirstLoad = computed(() => agentsLoading.value && !agents.value.length)
const tasksFirstLoad = computed(() => tasksLoading.value && !tasks.value.length)

/** 同一个接口挂了会让六个面板一起报错，页面只留一条 */
const pageError = computed(() => agentsError.value || tasksError.value || eventsError.value)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">总览</h2>
        <p class="page-head__desc">
          每 10 秒自动刷新<template v-if="lastLoadedAt"> · 更新于 {{ fromNow(lastLoadedAt) }}</template>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Refresh'" :loading="tasksLoading || agentsLoading" @click="refreshAll">刷新</el-button>
        <router-link to="/tasks">
          <el-button type="primary" :icon="'Plus'">新建任务</el-button>
        </router-link>
      </div>
    </div>

    <el-alert v-if="pageError" class="page-error" type="error" show-icon :closable="false" :title="pageError">
      <template #default>检查 Server 是否已启动，或在右上角修改接口地址。</template>
    </el-alert>

    <div class="cards">
      <StatCard
        label="在线机器"
        :value="agentStats.online + agentStats.busy"
        :unit="`/ ${agents.length}`"
        color="#16a34a"
        :loading="agentsFirstLoad"
        :hint="`空闲 ${agentStats.online} · 忙碌 ${agentStats.busy} · 不可用 ${agentStats.disconnected + agentStats.offline}`"
        to="/agents"
      />
      <StatCard
        label="运行中"
        :value="runningCount"
        color="#2563eb"
        :loading="tasksFirstLoad"
        :hint="`槽位 ${capacity.used}/${capacity.slots} · ${capacity.pct}%`"
        to="/tasks"
      />
      <StatCard
        label="需要关注"
        :value="badCount"
        color="#dc2626"
        :loading="tasksFirstLoad"
        :hint="`失败 ${counts.fail} · 阻塞 ${counts.block} · 异常 ${counts.exception}`"
        to="/tasks"
      />
      <StatCard
        label="排队中"
        :value="pendingCount"
        color="#64748b"
        :loading="tasksFirstLoad"
        :hint="pendingCount ? '可在任务队列调整顺序' : '队列已清空'"
        to="/tasks"
      />
    </div>

    <div class="cols">
      <div class="col">
        <div ref="mainPanelEl" class="panel p-running">
          <div class="panel__head">
            <div class="panel__title">
              正在执行
              <span class="hint">{{ runningExecutions.length }} 条</span>
            </div>
            <router-link to="/tasks" class="link-btn">查看全部</router-link>
          </div>
          <div class="panel__body panel__body--flush table-slot">
            <div v-if="tasksFirstLoad" class="skel skel--table">
              <div v-for="i in 5" :key="i" class="skel__row">
                <span class="skel__bar skel__bar--pill" />
                <span class="skel__bar skel__bar--wide" />
                <span class="skel__bar skel__bar--short" />
              </div>
            </div>
            <el-table v-else-if="runningExecutions.length" :data="runningExecutions" size="small">
              <el-table-column label="状态" width="104">
                <template #default="{ row }">
                  <StatusPill :status="row.status" :disconnected="row.disconnected" />
                </template>
              </el-table-column>
              <el-table-column v-if="showMachineCol" label="机器" min-width="120">
                <template #default="{ row }">
                  <span class="cell-1" :title="row.displayTag || row.agentId || ''">
                    {{ row.displayTag || row.agentId || '-' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="命令" min-width="220">
                <template #default="{ row }">
                  <span class="cell-1" :title="row.command || ''">
                    <code class="cmd">{{ row.command || '-' }}</code>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="已运行" width="82">
                <template #default="{ row }">
                  <span class="mono">{{ durationBetween(row.startedAt, null) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="64" align="right">
                <template #default="{ row }">
                  <router-link :to="`/executions/${row.executeId}`" class="link-btn">日志</router-link>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-else size="small" title="当前没有执行在跑" />
          </div>
        </div>

        <div class="panel p-attention">
          <div class="panel__head">
            <div class="panel__title">
              需要关注
              <span class="hint">失败 / 阻塞 / 异常</span>
            </div>
          </div>
          <div class="panel__body panel__body--flush table-slot">
            <div v-if="tasksFirstLoad" class="skel skel--table">
              <div v-for="i in 4" :key="i" class="skel__row">
                <span class="skel__bar skel__bar--pill" />
                <span class="skel__bar skel__bar--wide" />
                <span class="skel__bar skel__bar--short" />
              </div>
            </div>
            <el-table v-else-if="attentionExecutions.length" :data="attentionExecutions" size="small">
              <el-table-column label="结果" width="92">
                <template #default="{ row }">
                  <StatusPill :status="row.status" />
                </template>
              </el-table-column>
              <el-table-column v-if="showMachineCol" label="机器" min-width="120">
                <template #default="{ row }">
                  <span class="cell-1" :title="row.displayTag || row.agentId || ''">
                    {{ row.displayTag || row.agentId || '-' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="最后一行 / 命令" min-width="220">
                <template #default="{ row }">
                  <span class="cell-1" :title="row.lastLine || row.command || ''">
                    <code class="cmd">{{ row.lastLine || row.command || '-' }}</code>
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="showMetaCol" label="退出码" width="70" align="center">
                <template #default="{ row }">
                  <span class="mono">{{ row.exitCode ?? '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="showTimeCol" label="结束时间" width="128">
                <template #default="{ row }">
                  <span class="mono sub nowrap">{{ formatTime(row.finishedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="64" align="right">
                <template #default="{ row }">
                  <router-link :to="`/executions/${row.executeId}`" class="link-btn">详情</router-link>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-else size="small" title="近期没有失败或阻塞" />
          </div>
        </div>

        <div class="panel p-recent">
          <div class="panel__head">
            <div class="panel__title">最近任务</div>
            <router-link to="/tasks" class="link-btn">任务队列</router-link>
          </div>
          <div class="panel__body panel__body--flush table-slot table-slot--sm">
            <div v-if="tasksFirstLoad" class="skel skel--table">
              <div v-for="i in 4" :key="i" class="skel__row">
                <span class="skel__bar skel__bar--pill" />
                <span class="skel__bar skel__bar--wide" />
                <span class="skel__bar skel__bar--short" />
              </div>
            </div>
            <el-table v-else-if="recentTasks.length" :data="recentTasks" size="small">
              <el-table-column label="状态" width="92">
                <template #default="{ row }">
                  <StatusPill :status="row.status" />
                </template>
              </el-table-column>
              <el-table-column label="命令" min-width="220">
                <template #default="{ row }">
                  <span class="cell-1" :title="row.command || ''">
                    <code class="cmd">{{ row.command || '-' }}</code>
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="showMetaCol" label="目标" width="70" align="center">
                <template #default="{ row }">{{ row.total || row.targets.length }} 台</template>
              </el-table-column>
              <el-table-column v-if="showTimeCol" label="创建时间" width="128">
                <template #default="{ row }">
                  <span class="mono sub nowrap">{{ formatTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-else size="small" title="还没有任务" />
          </div>
        </div>
      </div>

      <div class="col">
        <div class="panel p-agents">
          <div class="panel__head">
            <div class="panel__title">机器状态</div>
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
            <div v-else-if="agentsFirstLoad" class="skel skel--dist">
              <span v-for="i in 4" :key="i" class="skel__tile" />
            </div>
            <EmptyState v-else size="small" title="还没有机器接入" />

            <template v-if="busiestAgents.length">
              <div class="side-sub">负载最高</div>
              <div v-for="a in busiestAgents" :key="a.agentId" class="busy-row">
                <AgentStatusLight :status="a.status" :show-label="false" />
                <span class="busy-row__name" :title="a.displayTag || a.agentId">
                  {{ a.displayTag || a.agentId }}
                </span>
                <el-progress
                  class="busy-row__bar"
                  :percentage="Math.min(100, Math.round((a.running / Math.max(1, a.concurrency)) * 100))"
                  :stroke-width="6"
                  :show-text="false"
                />
                <span class="mono busy-row__n">{{ a.running }}/{{ a.concurrency }}</span>
              </div>
            </template>
          </div>
        </div>

        <div class="panel p-dist">
          <div class="panel__head">
            <div class="panel__title">执行结果</div>
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
            <div v-else-if="tasksFirstLoad" class="skel skel--bar">
              <span class="skel__bar skel__bar--full" />
              <span class="skel__bar skel__bar--wide" />
            </div>
            <EmptyState v-else size="small" title="还没有执行记录" />
          </div>
        </div>

        <div class="panel p-events">
          <div class="panel__head">
            <div class="panel__title">
              近期事件
              <span class="hint">agent / server</span>
            </div>
            <router-link to="/timeline" class="link-btn">完整时间线</router-link>
          </div>
          <!-- 不做内层滚动：宁可少展示几条，也不要把卡片从中间切开 -->
          <div class="panel__body panel__body--flush events">
            <TimelineList :events="events.slice(0, 8)" :loading="eventsLoading" compact empty-text="暂无事件" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-error {
  margin-bottom: 14px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

@media (max-width: 1080px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .cards {
    grid-template-columns: minmax(0, 1fr);
  }
}

.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.75fr) minmax(300px, 1fr);
  gap: 14px;
  align-items: start;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.panel + .panel {
  margin-top: 0;
}

/* 侧栏 216px + 页面留白 44px：1400 视口下主栏才够放完整表格 */
@media (max-width: 1400px) {
  .cols {
    grid-template-columns: minmax(0, 1fr);
  }

  .col {
    display: contents;
  }

  .p-running {
    order: 1;
  }

  .p-attention {
    order: 2;
  }

  .p-events {
    order: 3;
  }

  .p-agents {
    order: 4;
  }

  .p-dist {
    order: 5;
  }

  .p-recent {
    order: 6;
  }
}

/* 加载态先把高度占住，数据回来时页面不再跳 */
.table-slot {
  display: flex;
  flex-direction: column;
  min-height: 224px;
}

.table-slot--sm {
  min-height: 188px;
}

/* 表格贴顶，占位/空态居中，刷新前后高度不变 */
.table-slot > .skel,
.table-slot > .empty {
  margin: auto 0;
}

.skel {
  animation: skel-fade 1.4s ease-in-out infinite;
}

.skel--table {
  padding: 14px 16px;
}

.skel__row {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 34px;
}

.skel__bar {
  height: 10px;
  border-radius: 5px;
  background: #eef1f6;
}

.skel__bar--pill {
  width: 72px;
  height: 18px;
  border-radius: 9px;
}

.skel__bar--wide {
  flex: 1;
}

.skel__bar--short {
  width: 56px;
}

.skel__bar--full {
  display: block;
  width: 100%;
  height: 10px;
}

.skel--bar .skel__bar--wide {
  display: block;
  width: 60%;
  margin-top: 12px;
}

.skel--dist {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.skel__tile {
  height: 36px;
  border-radius: 8px;
  background: #f4f6fa;
}

@keyframes skel-fade {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skel {
    animation: none;
  }
}

/* 单元格统一走 CSS 省略号 + title，不再按字符数截断 */
.cell-1 {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cmd {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  color: #26303d;
  word-break: normal;
  overflow-wrap: anywhere;
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
  margin: 16px 0 8px;
  font-size: 12px;
  color: var(--nat-text-weak);
}

.busy-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.busy-row__name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.busy-row__bar {
  flex: 0 0 84px;
  width: 84px;
}

.busy-row__n {
  flex: none;
  font-size: 11.5px;
  color: var(--nat-text-weak);
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
  padding: 2px 12px 8px;
}
</style>
