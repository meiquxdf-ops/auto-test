import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import { listAgents } from '@/api/agents'
import { errorMessage } from '@/api/http'
import { normalizeAgent } from '@/api/normalize'
import { SseClient, parseSseData, type SseState } from '@/api/sse'
import type { Agent } from '@/api/types'

/**
 * 全局机器状态：REST 打底 + `/api/sse/agents` 的 snapshot/patch 增量。
 * 多个页面共享一条 SSE 连接，用引用计数管理生命周期。
 */

const agentMap = shallowRef<Map<string, Agent>>(new Map())
const loading = ref(false)
const error = ref('')
const sseState = ref<SseState>('closed')
const lastUpdatedAt = ref<number | null>(null)

let client: SseClient | null = null
let refCount = 0
let pollTimer: number | null = null
let inflight: Promise<void> | null = null

const agents = computed(() => {
  const list = [...agentMap.value.values()]
  list.sort((a, b) => {
    const rank: Record<string, number> = { busy: 0, online: 1, disconnected: 2, offline: 3 }
    const d = (rank[a.status] ?? 9) - (rank[b.status] ?? 9)
    if (d !== 0) return d
    return (a.displayTag || a.agentId).localeCompare(b.displayTag || b.agentId, 'zh-CN')
  })
  return list
})

function setAll(list: Agent[]) {
  const map = new Map<string, Agent>()
  for (const a of list) {
    const key = a.agentId || a.displayTag
    if (key) map.set(key, a)
  }
  agentMap.value = map
  lastUpdatedAt.value = Date.now()
}

function applyPatch(items: unknown[]) {
  const map = new Map(agentMap.value)
  for (const item of items) {
    if (!item || typeof item !== 'object') continue
    const raw = item as Record<string, unknown>
    const agent = normalizeAgent(raw)
    const key = agent.agentId || agent.displayTag
    if (!key) continue
    const removed =
      raw.removed === true ||
      raw.deleted === true ||
      raw.op === 'remove' ||
      raw.type === 'remove' ||
      raw.action === 'remove'
    if (removed) {
      map.delete(key)
      continue
    }
    const prev = map.get(key)
    map.set(key, prev ? { ...prev, ...agent, raw: { ...prev.raw, ...agent.raw } } : agent)
  }
  agentMap.value = map
  lastUpdatedAt.value = Date.now()
}

function extractList(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  if (payload && typeof payload === 'object') {
    const o = payload as Record<string, unknown>
    for (const k of ['agents', 'items', 'list', 'data']) {
      if (Array.isArray(o[k])) return o[k] as unknown[]
    }
    return [payload]
  }
  return []
}

export async function refreshAgents(): Promise<void> {
  if (inflight) return inflight
  loading.value = true
  inflight = listAgents()
    .then((list) => {
      setAll(list)
      error.value = ''
    })
    .catch((e) => {
      error.value = errorMessage(e, '加载机器列表失败')
    })
    .finally(() => {
      loading.value = false
      inflight = null
    })
  return inflight
}

function startStream() {
  if (client) return
  client = new SseClient({
    path: '/api/sse/agents',
    events: ['snapshot', 'patch', 'agents', 'agent', 'update', 'delete', 'ping'],
    onStateChange: (state) => {
      sseState.value = state
    },
    onEvent: (type, data) => {
      if (type === 'ping') return
      const payload = parseSseData(data)
      if (typeof payload === 'string') return
      if (type === 'snapshot' || type === 'agents') {
        setAll(extractList(payload).map(normalizeAgent))
        error.value = ''
        return
      }
      // message / patch / agent / update / delete 一律按增量处理
      applyPatch(extractList(payload))
    },
  })
  client.start()
}

function stopStream() {
  client?.close()
  client = null
  sseState.value = 'closed'
}

function startPoll() {
  if (pollTimer !== null) return
  pollTimer = window.setInterval(() => {
    // SSE 正常时只做低频兜底对账
    if (sseState.value === 'open' && lastUpdatedAt.value && Date.now() - lastUpdatedAt.value < 60_000) return
    void refreshAgents()
  }, 8000)
}

function stopPoll() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 在组件里使用：自动 retain / release */
export function useAgents() {
  refCount += 1
  if (refCount === 1) {
    void refreshAgents()
    startStream()
    startPoll()
  } else if (!agentMap.value.size) {
    void refreshAgents()
  }

  onScopeDispose(() => {
    refCount -= 1
    if (refCount <= 0) {
      refCount = 0
      stopStream()
      stopPoll()
    }
  })

  return {
    agents,
    loading,
    error,
    sseState,
    lastUpdatedAt,
    refresh: refreshAgents,
    reconnect: () => client?.reconnectNow(),
  }
}
