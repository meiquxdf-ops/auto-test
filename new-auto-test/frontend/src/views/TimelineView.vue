<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '@/api/http'
import { getTimeline } from '@/api/timeline'
import type { TimelineEvent, TimelineSource } from '@/api/types'
import { useAgents } from '@/stores/agents'
import { formatTime } from '@/utils/format'
import { agentStatusMeta } from '@/utils/status'
import EmptyState from '@/components/EmptyState.vue'
import TimelineList from '@/components/TimelineList.vue'

const route = useRoute()
const router = useRouter()
const { agents } = useAgents()

type Mode = 'agent' | 'execute' | 'all'

const mode = ref<Mode>('all')
const agentId = ref('')
const executeId = ref('')
const sourceFilter = ref<TimelineSource | ''>('')
const typeKeyword = ref('')
const autoRefresh = ref(false)

const events = ref<TimelineEvent[]>([])
const loading = ref(false)
const error = ref('')
const loadedAt = ref<number | null>(null)

let timer: number | null = null

function syncFromRoute() {
  const qAgent = typeof route.query.agentId === 'string' ? route.query.agentId : ''
  const qExec = typeof route.query.executeId === 'string' ? route.query.executeId : ''
  agentId.value = qAgent
  executeId.value = qExec
  mode.value = qExec ? 'execute' : qAgent ? 'agent' : 'all'
}

function syncToRoute() {
  const query: Record<string, string> = {}
  if (mode.value === 'agent' && agentId.value) query.agentId = agentId.value
  if (mode.value === 'execute' && executeId.value) query.executeId = executeId.value
  void router.replace({ path: '/timeline', query })
}

async function load() {
  loading.value = true
  try {
    events.value = await getTimeline({
      agentId: mode.value === 'agent' ? agentId.value || undefined : undefined,
      executeId: mode.value === 'execute' ? executeId.value || undefined : undefined,
      limit: 300,
    })
    error.value = ''
    loadedAt.value = Date.now()
  } catch (e) {
    error.value = errorMessage(e, '加载时间线失败')
    events.value = []
  } finally {
    loading.value = false
  }
}

function query() {
  syncToRoute()
  void load()
}

onMounted(() => {
  syncFromRoute()
  void load()
  timer = window.setInterval(() => {
    if (autoRefresh.value) void load()
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})

watch(
  () => route.fullPath,
  () => {
    syncFromRoute()
    void load()
  },
)

const filtered = computed(() => {
  const kw = typeKeyword.value.trim().toLowerCase()
  return events.value.filter((e) => {
    if (sourceFilter.value && e.source !== sourceFilter.value) return false
    if (!kw) return true
    return (
      e.type.toLowerCase().includes(kw) ||
      (e.message ?? '').toLowerCase().includes(kw) ||
      (e.executeId ?? '').toLowerCase().includes(kw) ||
      (e.token ?? '').toLowerCase().includes(kw)
    )
  })
})

const stat = computed(() => ({
  agent: events.value.filter((e) => e.source === 'agent').length,
  server: events.value.filter((e) => e.source === 'server').length,
}))

const typeList = computed(() => {
  const map = new Map<string, number>()
  for (const e of events.value) map.set(e.type, (map.get(e.type) ?? 0) + 1)
  return [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)
})

function switchMode(next: Mode) {
  mode.value = next
  if (next === 'all') {
    agentId.value = ''
    executeId.value = ''
  }
  query()
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">时间线</h2>
        <p class="page-head__desc">
          按 executeId 或 agentId 对账 agent 与 server 两侧事件，左侧 agent 上报、右侧 server 记录
          <template v-if="loadedAt"> · 更新于 {{ formatTime(loadedAt) }}</template>
        </p>
      </div>
      <div class="page-head__actions">
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        <el-button :icon="'Refresh'" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="toolbar">
        <el-radio-group :model-value="mode" size="small" @update:model-value="(v: unknown) => switchMode(v as Mode)">
          <el-radio-button value="all">全部事件</el-radio-button>
          <el-radio-button value="agent">按机器</el-radio-button>
          <el-radio-button value="execute">按执行</el-radio-button>
        </el-radio-group>

        <el-select
          v-if="mode === 'agent'"
          v-model="agentId"
          filterable
          clearable
          placeholder="选择机器"
          style="width: 260px"
          @change="query"
        >
          <el-option
            v-for="a in agents"
            :key="a.agentId"
            :label="a.displayTag || a.agentId"
            :value="a.agentId"
          >
            <span class="opt">
              <i class="opt__dot" :style="{ background: agentStatusMeta(a.status).color }" />
              {{ a.displayTag || a.agentId }}
              <span class="muted">{{ a.agentId.slice(0, 8) }}</span>
            </span>
          </el-option>
        </el-select>

        <el-input
          v-if="mode === 'execute'"
          v-model="executeId"
          placeholder="粘贴 executeId 后回车"
          clearable
          style="width: 320px"
          class="mono"
          @keyup.enter="query"
          @clear="query"
        >
          <template #append>
            <el-button :icon="'Search'" @click="query" />
          </template>
        </el-input>

        <span class="spacer" />

        <el-radio-group v-model="sourceFilter" size="small">
          <el-radio-button value="">两侧</el-radio-button>
          <el-radio-button value="agent">仅 agent ({{ stat.agent }})</el-radio-button>
          <el-radio-button value="server">仅 server ({{ stat.server }})</el-radio-button>
        </el-radio-group>

        <el-input
          v-model="typeKeyword"
          placeholder="过滤事件名 / 内容"
          clearable
          :prefix-icon="'Filter'"
          style="width: 200px"
        />
      </div>

      <div v-if="typeList.length" class="types">
        <span class="muted">事件类型：</span>
        <el-tag
          v-for="[type, n] in typeList"
          :key="type"
          size="small"
          effect="plain"
          class="types__tag"
          @click="typeKeyword = type"
        >
          {{ type }} <b>{{ n }}</b>
        </el-tag>
        <el-button v-if="typeKeyword" size="small" text @click="typeKeyword = ''">清除过滤</el-button>
      </div>

      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

      <div class="panel__body">
        <EmptyState
          v-if="mode === 'execute' && !executeId"
          variant="search"
          title="输入一个 executeId 开始查询"
          desc="可以从任务队列或执行详情页复制 executeId"
        />
        <EmptyState
          v-else-if="mode === 'agent' && !agentId"
          variant="search"
          title="选择一台机器"
          desc="查看该机器上报的 hello / hb / evt / fin 与 server 侧的调度事件"
        />
        <TimelineList
          v-else
          :events="filtered"
          :loading="loading"
          :empty-text="events.length ? '当前过滤条件下没有事件' : '暂无事件'"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.opt {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.opt__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.types {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 10px 16px;
  border-bottom: 1px solid var(--nat-border);
  font-size: 12px;
}

.types__tag {
  cursor: pointer;
}

.types__tag b {
  color: var(--nat-text-weak);
  margin-left: 3px;
}
</style>
