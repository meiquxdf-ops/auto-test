import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { getExecution, getExecutionLogs } from '@/api/executions'
import { errorMessage } from '@/api/http'
import { asDict, normalizeExecutionStatus, normalizeLogLine, optNum, bool } from '@/api/normalize'
import { SseClient, parseSseData, type SseState } from '@/api/sse'
import type { Execution, ExecutionStatus, LogLine } from '@/api/types'
import { isTerminal } from '@/utils/status'

/** 前端内存保护：单页最多保留的行数（Server 侧本身有 5MB 尾部限制） */
const MAX_LINES = 200_000

export function useExecutionLog(executeId: Ref<string>) {
  const execution = ref<Execution | null>(null)
  const lines = shallowRef<LogLine[]>([])
  const truncated = ref(false)
  const droppedBytes = ref<number | undefined>(undefined)
  const totalBytes = ref<number | undefined>(undefined)
  const clientTrimmed = ref(0)
  const loading = ref(false)
  const logLoading = ref(false)
  const error = ref('')
  const sseState = ref<SseState>('closed')
  const finished = ref(false)

  let client: SseClient | null = null
  let nextSeq = 0
  let seenMax = -1
  let buffer: LogLine[] = []
  let flushHandle: number | null = null
  let metaTimer: number | null = null
  let disposed = false

  const status = computed<ExecutionStatus>(() => execution.value?.status ?? 'pending')

  function flush() {
    flushHandle = null
    if (!buffer.length) return
    let next = lines.value.concat(buffer)
    buffer = []
    if (next.length > MAX_LINES) {
      const cut = next.length - MAX_LINES
      clientTrimmed.value += cut
      next = next.slice(cut)
    }
    lines.value = next
  }

  function scheduleFlush() {
    if (flushHandle !== null) return
    flushHandle = window.requestAnimationFrame(flush)
  }

  /** Server 的 seq 从 1 起编，负载没带 seq 时按同一起点顺延，别把首行编成 0 */
  function nextIndex() {
    return Math.max(seenMax + 1, 1)
  }

  function pushLines(items: LogLine[]) {
    for (const line of items) {
      if (line.seq <= seenMax) continue
      seenMax = line.seq
      buffer.push(line)
    }
    nextSeq = seenMax + 1
    scheduleFlush()
  }

  function resetState() {
    if (flushHandle !== null) {
      window.cancelAnimationFrame(flushHandle)
      flushHandle = null
    }
    buffer = []
    lines.value = []
    nextSeq = 0
    seenMax = -1
    truncated.value = false
    droppedBytes.value = undefined
    totalBytes.value = undefined
    clientTrimmed.value = 0
    finished.value = false
    error.value = ''
    execution.value = null
  }

  async function loadMeta(silent = false) {
    if (!executeId.value) return
    if (!silent) loading.value = true
    try {
      const exec = await getExecution(executeId.value)
      execution.value = exec
      if (exec.logTruncated) truncated.value = true
      if (exec.logTotalBytes !== undefined) totalBytes.value = exec.logTotalBytes
      if (isTerminal(exec.status)) finished.value = true
      error.value = ''
    } catch (e) {
      if (!silent) error.value = errorMessage(e, '加载执行详情失败')
    } finally {
      loading.value = false
    }
  }

  /** 首屏历史日志：分页拉到底，再交给 SSE 续传 */
  async function loadHistory() {
    if (!executeId.value) return
    logLoading.value = true
    try {
      for (let guard = 0; guard < 60; guard += 1) {
        const page = await getExecutionLogs(executeId.value, nextSeq, 2000)
        if (page.truncated) truncated.value = true
        if (page.droppedBytes !== undefined) droppedBytes.value = page.droppedBytes
        if (page.totalBytes !== undefined) totalBytes.value = page.totalBytes
        if (!page.lines.length) break
        pushLines(page.lines)
        nextSeq = Math.max(nextSeq, page.nextSeq)
        if (page.lines.length < 2000) break
      }
    } catch (e) {
      // 历史日志失败不阻断实时流
      error.value = errorMessage(e, '加载历史日志失败')
    } finally {
      logLoading.value = false
      flush()
    }
  }

  function handlePayload(payload: unknown) {
    if (payload === null || payload === undefined || payload === '') return
    if (typeof payload === 'string') {
      pushLines([{ seq: nextIndex(), text: payload }])
      return
    }
    if (Array.isArray(payload)) {
      pushLines(payload.map((item, i) => normalizeLogLine(item, nextIndex() + i)))
      return
    }
    const o = asDict(payload)
    if (Array.isArray(o.lines)) {
      const from = optNum(o, ['from', 'fromSeq', 'seq']) ?? nextIndex()
      pushLines((o.lines as unknown[]).map((item, i) => normalizeLogLine(item, from + i)))
    } else if (o.text !== undefined || o.line !== undefined || o.content !== undefined) {
      pushLines([normalizeLogLine(o, nextIndex())])
    }
    if (bool(o, ['truncated', 'logTruncated'], false)) truncated.value = true
    const dropped = optNum(o, ['droppedBytes', 'dropped'])
    if (dropped !== undefined) droppedBytes.value = dropped
    const total = optNum(o, ['totalBytes', 'logBytes'])
    if (total !== undefined) totalBytes.value = total
    if (o.status !== undefined) applyStatus(o.status)
  }

  function applyStatus(raw: unknown) {
    const st = normalizeExecutionStatus(raw, execution.value?.status ?? 'pending')
    if (execution.value) execution.value = { ...execution.value, status: st }
    if (isTerminal(st)) {
      finished.value = true
      void loadMeta(true)
    }
  }

  function startStream() {
    stopStream()
    if (!executeId.value) return
    client = new SseClient({
      path: `/api/sse/exec/${encodeURIComponent(executeId.value)}`,
      params: () => ({ from: nextSeq }),
      events: ['log', 'logs', 'line', 'append', 'status', 'state', 'truncated', 'end', 'done', 'fin', 'ping'],
      onStateChange: (s) => {
        sseState.value = s
      },
      onEvent: (type, data) => {
        if (type === 'ping') return
        const payload = parseSseData(data)
        switch (type) {
          case 'status':
          case 'state': {
            const o = typeof payload === 'string' ? payload : asDict(payload).status ?? payload
            applyStatus(o)
            break
          }
          case 'truncated':
            truncated.value = true
            if (typeof payload !== 'string') {
              const d = optNum(asDict(payload), ['droppedBytes', 'dropped', 'bytes'])
              if (d !== undefined) droppedBytes.value = d
            }
            break
          case 'end':
          case 'done':
          case 'fin':
            finished.value = true
            if (typeof payload !== 'string') applyStatus(asDict(payload).status)
            void loadMeta(true)
            client?.close()
            sseState.value = 'closed'
            break
          default:
            handlePayload(payload)
        }
      },
    })
    client.start()
  }

  function stopStream() {
    client?.close()
    client = null
    sseState.value = 'closed'
  }

  function startMetaPoll() {
    stopMetaPoll()
    metaTimer = window.setInterval(() => {
      if (disposed) return
      if (finished.value) return
      void loadMeta(true)
    }, 6000)
  }

  function stopMetaPoll() {
    if (metaTimer !== null) {
      window.clearInterval(metaTimer)
      metaTimer = null
    }
  }

  async function boot() {
    resetState()
    if (!executeId.value) return
    await loadMeta()
    await loadHistory()
    startStream()
    startMetaPoll()
  }

  async function reload() {
    stopStream()
    stopMetaPoll()
    await boot()
  }

  watch(
    executeId,
    () => {
      void boot()
    },
    { immediate: true },
  )

  onScopeDispose(() => {
    disposed = true
    stopStream()
    stopMetaPoll()
    if (flushHandle !== null) window.cancelAnimationFrame(flushHandle)
  })

  return {
    execution,
    status,
    lines,
    truncated,
    droppedBytes,
    totalBytes,
    clientTrimmed,
    loading,
    logLoading,
    error,
    sseState,
    finished,
    reload,
    refreshMeta: () => loadMeta(true),
    reconnect: () => client?.reconnectNow(),
  }
}
