<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TimelineEvent } from '@/api/types'
import { formatFullTime, formatTime, shortId } from '@/utils/format'
import EmptyState from './EmptyState.vue'
import CopyableId from './CopyableId.vue'

const props = withDefaults(
  defineProps<{
    events: TimelineEvent[]
    loading?: boolean
    /** 紧凑模式：总览页用 */
    compact?: boolean
    emptyText?: string
    showExecuteLink?: boolean
  }>(),
  { loading: false, compact: false, emptyText: '暂无事件', showExecuteLink: true },
)

const expanded = ref<Set<string>>(new Set())

function toggle(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function detailText(e: TimelineEvent): string {
  if (e.detail === undefined || e.detail === null) return ''
  if (typeof e.detail === 'string') return e.detail
  try {
    return JSON.stringify(e.detail, null, 2)
  } catch {
    return String(e.detail)
  }
}

/** 事件名 → 颜色，让关键节点一眼能扫出来 */
function toneOf(type: string): string {
  const t = type.toLowerCase()
  if (/(fail|error|exception|timeout|dead|reject|dup)/.test(t)) return '#dc2626'
  if (/(cancel|stop|kill)/.test(t)) return '#8b949e'
  if (/(finish|fin|done|complete|pass|ack)/.test(t)) return '#16a34a'
  if (/(start|exec|dispatch|run)/.test(t)) return '#2563eb'
  if (/(disconnect|lost|retry|reconnect|lease)/.test(t)) return '#ea8a04'
  return '#5b6676'
}

const grouped = computed(() =>
  props.events.map((e) => ({
    ...e,
    tone: toneOf(e.type),
    detail: detailText(e),
  })),
)
</script>

<template>
  <div class="tl" :class="{ 'tl--compact': compact, 'tl--stack': compact }">
    <div v-if="loading && !events.length" class="tl__loading">
      <el-skeleton :rows="4" animated />
    </div>

    <EmptyState v-else-if="!events.length" :title="emptyText" size="small" />

    <div v-else class="tl__body">
      <div class="tl__axis" />
      <div v-if="!compact" class="tl__legend">
        <span class="tl__legend-item"><i class="tl__legend-dot is-agent" />Agent 上报</span>
        <span class="tl__legend-item"><i class="tl__legend-dot is-server" />Server 侧</span>
      </div>

      <div
        v-for="(e, idx) in grouped"
        :key="`${e.id}-${idx}`"
        class="tl__row"
        :class="e.source === 'agent' ? 'is-left' : 'is-right'"
      >
        <div class="tl__slot">
          <div class="tl__card" :style="{ borderLeftColor: e.tone }">
            <div class="tl__card-head">
              <span class="tl__type" :style="{ color: e.tone }">{{ e.type }}</span>
              <el-tag size="small" :type="e.source === 'agent' ? 'success' : 'info'" effect="plain" round>
                {{ e.source === 'agent' ? 'agent' : 'server' }}
              </el-tag>
              <span class="spacer" />
              <el-tooltip :content="formatFullTime(e.ts)" placement="top">
                <span class="tl__time mono">{{ formatTime(e.ts) }}</span>
              </el-tooltip>
            </div>

            <div v-if="e.message" class="tl__msg">{{ e.message }}</div>

            <div class="tl__chips">
              <span v-if="e.displayTag || e.agentId" class="tl__chip">
                机器 <b>{{ e.displayTag || shortId(e.agentId) }}</b>
              </span>
              <span v-if="e.executeId" class="tl__chip">
                execution
                <router-link
                  v-if="showExecuteLink"
                  class="tl__link mono"
                  :to="`/executions/${e.executeId}`"
                >
                  {{ shortId(e.executeId) }}
                </router-link>
                <b v-else class="mono">{{ shortId(e.executeId) }}</b>
              </span>
              <span v-if="e.token" class="tl__chip">
                token <CopyableId :value="e.token" :head="6" />
              </span>
              <span v-if="e.sessionId" class="tl__chip">
                session <CopyableId :value="e.sessionId" :head="6" />
              </span>
              <span v-if="e.bootId" class="tl__chip">
                boot <CopyableId :value="e.bootId" :head="6" />
              </span>
              <span v-if="e.evtId" class="tl__chip">evtId <b class="mono">{{ e.evtId }}</b></span>
            </div>

            <div v-if="e.detail" class="tl__detail">
              <button class="link-btn" @click="toggle(`${e.id}-${idx}`)">
                {{ expanded.has(`${e.id}-${idx}`) ? '收起详情' : '展开详情' }}
              </button>
              <pre v-if="expanded.has(`${e.id}-${idx}`)" class="tl__pre mono">{{ e.detail }}</pre>
            </div>
          </div>
          <span class="tl__node" :style="{ background: e.tone }" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tl {
  position: relative;
}

.tl__loading {
  padding: 12px 16px;
}

.tl__body {
  position: relative;
  padding: 8px 0 12px;
}

.tl__axis {
  position: absolute;
  left: 50%;
  top: 0;
  bottom: 0;
  width: 2px;
  margin-left: -1px;
  background: linear-gradient(180deg, transparent, var(--nat-border-strong) 6%, var(--nat-border-strong) 94%, transparent);
}

.tl__legend {
  display: flex;
  justify-content: center;
  gap: 18px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--nat-text-weak);
  position: relative;
}

.tl__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.tl__legend-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.tl__legend-dot.is-agent {
  background: #16a34a;
}

.tl__legend-dot.is-server {
  background: #2563eb;
}

.tl__row {
  display: flex;
  position: relative;
  margin-bottom: 10px;
}

.tl__row.is-left {
  justify-content: flex-start;
}

.tl__row.is-right {
  justify-content: flex-end;
}

.tl__slot {
  width: calc(50% - 18px);
  position: relative;
}

.tl__card {
  background: #fff;
  border: 1px solid var(--nat-border);
  border-left: 3px solid;
  border-radius: 8px;
  padding: 9px 12px;
  box-shadow: 0 1px 2px rgba(17, 24, 39, 0.04);
}

.tl--compact .tl__card {
  padding: 7px 10px;
}

.tl__card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}

.tl__type {
  font-weight: 620;
  font-size: 13px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.tl__time {
  color: var(--nat-text-weak);
  font-size: 11.5px;
}

.tl__msg {
  margin-top: 5px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.6;
  word-break: break-word;
}

.tl__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 6px;
}

.tl__chip {
  font-size: 11.5px;
  color: var(--nat-text-weak);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tl__chip b {
  color: var(--nat-text-sub);
  font-weight: 560;
}

.tl__link {
  color: var(--nat-accent);
  text-decoration: none;
}

.tl__link:hover {
  text-decoration: underline;
}

.tl__detail {
  margin-top: 6px;
}

.tl__pre {
  margin: 6px 0 0;
  background: #0d1117;
  color: #c9d1d9;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 11.5px;
  line-height: 1.6;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.tl__node {
  position: absolute;
  top: 14px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px currentColor;
}

.is-left .tl__node {
  right: -22px;
}

.is-right .tl__node {
  left: -22px;
}

/* 窄容器（侧栏、小屏）退化成单列，左右对齐留给时间线主页面 */
.tl--stack .tl__axis {
  left: 8px;
}

.tl--stack .tl__row.is-left,
.tl--stack .tl__row.is-right {
  justify-content: flex-end;
}

.tl--stack .tl__slot {
  width: calc(100% - 26px);
}

.tl--stack .is-left .tl__node,
.tl--stack .is-right .tl__node {
  left: -21px;
  right: auto;
}

@media (max-width: 900px) {
  .tl__axis {
    left: 8px;
  }

  .tl__row.is-left,
  .tl__row.is-right {
    justify-content: flex-end;
  }

  .tl__slot {
    width: calc(100% - 28px);
  }

  .is-left .tl__node,
  .is-right .tl__node {
    left: -22px;
    right: auto;
  }
}
</style>
