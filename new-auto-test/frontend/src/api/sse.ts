import { apiUrl } from './http'

export type SseState = 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface SseOptions {
  /** 形如 /api/sse/agents */
  path: string
  /** 首次连接的查询参数 */
  params?: () => Record<string, string | number | undefined | null>
  /** 需要监听的具名事件；default message 始终监听 */
  events?: string[]
  onEvent: (type: string, data: string, lastEventId: string) => void
  onStateChange?: (state: SseState, info: { attempt: number; nextRetryMs?: number }) => void
}

const MAX_BACKOFF = 15_000

/**
 * 带断线续传的 SSE 客户端。
 *
 * 关键点（协议要求）：
 * 1. onerror 不 close()。浏览器原生重连会自动带上 Last-Event-ID 头，
 *    这是最优的续传方式，粗暴 close 反而丢掉这个能力。
 * 2. 只有当 readyState 真的变成 CLOSED（浏览器放弃了）才由我们做退避重连，
 *    并把记录到的 lastEventId 通过查询参数回传，兼容不认 Last-Event-ID 头的实现。
 */
export class SseClient {
  private es: EventSource | null = null
  private timer: number | null = null
  private attempt = 0
  private stopped = false
  private lastEventId = ''
  private state: SseState = 'closed'

  constructor(private opts: SseOptions) {}

  get currentState(): SseState {
    return this.state
  }

  get lastId(): string {
    return this.lastEventId
  }

  start() {
    this.stopped = false
    this.open()
  }

  /** 手动立即重连（页面上的「重连」按钮） */
  reconnectNow() {
    if (this.timer !== null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    this.attempt = 0
    this.teardown()
    if (!this.stopped) this.open()
  }

  close() {
    this.stopped = true
    if (this.timer !== null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    this.teardown()
    this.setState('closed')
  }

  private teardown() {
    if (this.es) {
      this.es.onmessage = null
      this.es.onerror = null
      this.es.onopen = null
      this.es.close()
      this.es = null
    }
  }

  private setState(state: SseState, nextRetryMs?: number) {
    this.state = state
    this.opts.onStateChange?.(state, { attempt: this.attempt, nextRetryMs })
  }

  private buildUrl(): string {
    const query = new URLSearchParams()
    const params = this.opts.params?.() ?? {}
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') continue
      query.set(k, String(v))
    }
    if (this.lastEventId) query.set('lastEventId', this.lastEventId)
    const qs = query.toString()
    return apiUrl(this.opts.path) + (qs ? `?${qs}` : '')
  }

  private open() {
    this.teardown()
    this.setState(this.attempt === 0 ? 'connecting' : 'reconnecting')

    let es: EventSource
    try {
      es = new EventSource(this.buildUrl())
    } catch {
      this.scheduleRetry()
      return
    }
    this.es = es

    const handle = (type: string) => (ev: MessageEvent) => {
      if (ev.lastEventId) this.lastEventId = ev.lastEventId
      this.attempt = 0
      if (this.state !== 'open') this.setState('open')
      this.opts.onEvent(type, ev.data, ev.lastEventId)
    }

    es.onopen = () => {
      this.attempt = 0
      this.setState('open')
    }
    es.onmessage = handle('message')
    for (const name of this.opts.events ?? []) {
      es.addEventListener(name, handle(name) as EventListener)
    }
    es.onerror = () => {
      if (this.stopped) return
      // 不要 close：CONNECTING 说明浏览器正在自动重连（带 Last-Event-ID 头）
      if (es.readyState === EventSource.CONNECTING) {
        this.attempt += 1
        this.setState('reconnecting')
        return
      }
      if (es.readyState === EventSource.CLOSED) this.scheduleRetry()
    }
  }

  private scheduleRetry() {
    if (this.stopped || this.timer !== null) return
    this.attempt += 1
    const delay = Math.min(MAX_BACKOFF, 800 * 2 ** Math.min(this.attempt - 1, 4))
    this.setState('reconnecting', delay)
    this.timer = window.setTimeout(() => {
      this.timer = null
      if (!this.stopped) this.open()
    }, delay)
  }
}

/** SSE data 可能是 JSON，也可能是裸文本 */
export function parseSseData<T = unknown>(data: string): T | string {
  const trimmed = data?.trim()
  if (!trimmed) return ''
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return JSON.parse(trimmed) as T
    } catch {
      return data
    }
  }
  return data
}

export const SSE_STATE_TEXT: Record<SseState, string> = {
  connecting: '连接中',
  open: '实时',
  reconnecting: '重连中',
  closed: '已断开',
}
