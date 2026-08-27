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
    /** 紧凑模式：总览页用，恒定单列 */
    compact?: boolean
    emptyText?: string
    showExecuteLink?: boolean
  }>(),
  { loading: false, compact: false, emptyText: '暂无事件', showExecuteLink: true },
)

const expanded = ref<Set<string>>(new Set())

function toggle(key: string) {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

/** 详情只在展开时才序列化，300 条列表不为看不见的内容付出 JSON.stringify */
function detailText(detail: unknown): string {
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail, null, 2)
  } catch {
    return String(detail)
  }
}

const toneCache = new Map<string, string>()

/** 事件名 → 颜色，让关键节点一眼能扫出来 */
function toneOf(type: string): string {
  const cached = toneCache.get(type)
  if (cached) return cached
  const t = type.toLowerCase()
  let tone = '#5b6676'
  if (/(fail|error|exception|timeout|dead|reject|dup)/.test(t)) tone = '#dc2626'
  else if (/(cancel|stop|kill)/.test(t)) tone = '#8b949e'
  else if (/(finish|fin|done|complete|pass|ack)/.test(t)) tone = '#16a34a'
  else if (/(start|exec|dispatch|run)/.test(t)) tone = '#2563eb'
  else if (/(disconnect|lost|retry|reconnect|lease)/.test(t)) tone = '#ea8a04'
  toneCache.set(type, tone)
  return tone
}

function looksLikeJson(text: string): boolean {
  const s = text.trim()
  return (s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']'))
}

const rows = computed(() => {
  /** key 只跟事件本身有关，自动刷新时同一条事件不会因为下标位移而整块重建 */
  const seen = new Map<string, number>()
  return props.events.map((e, idx) => {
    const base = e.id || `${e.source}-${e.ts ?? idx}`
    const dup = seen.get(base) ?? 0
    seen.set(base, dup + 1)
    return {
      ...e,
      key: dup ? `${base}#${dup}` : base,
      tone: toneOf(e.type),
      jsonMsg: !!e.message && looksLikeJson(e.message),
      hasDetail: e.detail !== undefined && e.detail !== null && e.detail !== '',
      hasChips: !!(
        e.displayTag ||
        e.agentId ||
        e.executeId ||
        e.token ||
        e.sessionId ||
        e.bootId ||
        e.evtId
      ),
    }
  })
})
</script>

<template>
  <div class="tl" :class="{ 'tl--compact': compact, 'tl--dual': !compact }">
    <div v-if="loading && !events.length" class="tl__loading">
      <el-skeleton :rows="4" animated />
    </div>

    <EmptyState v-else-if="!events.length" :title="emptyText" size="small" />

    <div v-else class="tl__body">
      <div class="tl__axis" />

      <div
        v-for="e in rows"
        :key="e.key"
        class="tl__row"
        :class="e.source === 'agent' ? 'is-left' : 'is-right'"
      >
        <div class="tl__slot">
          <span class="tl__node" :style="{ background: e.tone, color: e.tone }" />
          <div class="tl__card" :style="{ borderLeftColor: e.tone }">
            <div class="tl__card-head">
              <span class="tl__type mono" :style="{ color: e.tone }" :title="e.type">{{ e.type }}</span>
              <span class="tl__src">{{ e.source }}</span>
              <span class="tl__time mono" :title="formatFullTime(e.ts)">{{ formatTime(e.ts) }}</span>
            </div>

            <div v-if="e.message" class="tl__msg" :class="{ 'tl__msg--json': e.jsonMsg }">
              {{ e.message }}
            </div>

            <div v-if="e.hasChips" class="tl__chips">
              <span v-if="e.displayTag || e.agentId" class="tl__chip">
                机器 <b>{{ e.displayTag || shortId(e.agentId) }}</b>
              </span>
              <span v-if="e.executeId" class="tl__chip">
                执行
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
              <span v-if="e.evtId" class="tl__chip">evt <b class="mono">{{ e.evtId }}</b></span>
            </div>

            <div v-if="e.hasDetail" class="tl__detail">
              <button class="link-btn" @click="toggle(e.key)">
                {{ expanded.has(e.key) ? '收起' : '详情' }}
              </button>
              <pre v-if="expanded.has(e.key)" class="tl__pre mono">{{ detailText(e.detail) }}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tl {
  position: relative;
  /* 布局跟随容器宽度，不跟随视口：抽屉 / 侧栏里同样能正确降级 */
  container-type: inline-size;
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
  left: 8px;
  top: 0;
  bottom: 0;
  width: 2px;
  margin-left: -1px;
  background: var(--nat-border-strong);
}

/* 默认单列：窄容器一律贴着左侧时间轴排 */
.tl__row {
  display: flex;
  justify-content: flex-end;
  position: relative;
  margin-bottom: 10px;
}

.tl__slot {
  position: relative;
  display: flex;
  width: calc(100% - 24px);
}

.tl__node {
  position: absolute;
  top: 14px;
  left: -16px;
  transform: translateX(-50%);
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px currentColor;
}

.tl__card {
  flex: 1 1 auto;
  min-width: 0;
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

.tl__type {
  min-width: 0;
  flex: 0 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 620;
  font-size: 13px;
}

.tl__src {
  flex: none;
  font-size: 11.5px;
  color: var(--nat-text-weak);
}

.tl__time {
  flex: none;
  margin-left: auto;
  white-space: nowrap;
  color: var(--nat-text-weak);
  font-size: 11.5px;
}

.tl__msg {
  margin-top: 5px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

/* 直接把 JSON 塞进 message 的事件：等宽 + 截断，别让一条事件吃掉整屏 */
.tl__msg--json {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Menlo, Consolas, monospace;
  font-size: 12px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  overflow: hidden;
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
  min-width: 0;
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
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  /* anywhere 只在放不下时断行，break-all 会把短 key 也切碎 */
  overflow-wrap: anywhere;
}

/* 容器够宽才分两列：agent 左 / server 右，卡片限宽贴着轴，不摊平成一整行 */
@container (min-width: 880px) {
  .tl--dual .tl__axis {
    left: 50%;
  }

  .tl--dual .tl__row.is-left {
    justify-content: flex-start;
  }

  .tl--dual .tl__slot {
    width: calc(50% - 20px);
  }

  .tl--dual .is-left .tl__slot {
    justify-content: flex-end;
  }

  .tl--dual .tl__card {
    max-width: 420px;
  }

  .tl--dual .is-left .tl__node {
    left: auto;
    right: -20px;
    transform: translateX(50%);
  }

  .tl--dual .is-right .tl__node {
    left: -20px;
  }
}

/* 不支持容器查询时退回视口断点：内容区约为视口宽 - 300px */
@supports not (container-type: inline-size) {
  @media (min-width: 1180px) {
    .tl--dual .tl__axis {
      left: 50%;
    }

    .tl--dual .tl__row.is-left {
      justify-content: flex-start;
    }

    .tl--dual .tl__slot {
      width: calc(50% - 20px);
    }

    .tl--dual .is-left .tl__slot {
      justify-content: flex-end;
    }

    .tl--dual .tl__card {
      max-width: 420px;
    }

    .tl--dual .is-left .tl__node {
      left: auto;
      right: -20px;
      transform: translateX(50%);
    }

    .tl--dual .is-right .tl__node {
      left: -20px;
    }
  }
}
</style>
