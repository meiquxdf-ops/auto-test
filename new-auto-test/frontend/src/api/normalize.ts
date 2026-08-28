import {
  CALLBACK_STATUSES,
  EXECUTION_STATUSES,
  OTHER_STATUSES,
  CONDITION_OPERATORS,
  type Agent,
  type AgentStatus,
  type CallbackStatus,
  type ConditionConfig,
  type ConditionOperator,
  type ConditionRule,
  type Execution,
  type ExecutionStatus,
  type LogLine,
  type OtherStatus,
  type Task,
  type TimelineEvent,
  type TimelineSource,
} from './types'

type Dict = Record<string, unknown>

export function asDict(v: unknown): Dict {
  return v && typeof v === 'object' ? (v as Dict) : {}
}

function pick(o: Dict, keys: string[]): unknown {
  for (const k of keys) {
    const v = o[k]
    if (v !== undefined && v !== null && v !== '') return v
  }
  return undefined
}

export function str(o: Dict, keys: string[], fallback = ''): string {
  const v = pick(o, keys)
  if (v === undefined) return fallback
  return typeof v === 'string' ? v : String(v)
}

export function optStr(o: Dict, keys: string[]): string | undefined {
  const v = pick(o, keys)
  return v === undefined ? undefined : typeof v === 'string' ? v : String(v)
}

export function num(o: Dict, keys: string[], fallback: number): number {
  const v = pick(o, keys)
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : fallback
}

export function optNum(o: Dict, keys: string[]): number | undefined {
  const v = pick(o, keys)
  if (v === undefined) return undefined
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : undefined
}

export function bool(o: Dict, keys: string[], fallback = false): boolean {
  const v = pick(o, keys)
  if (v === undefined) return fallback
  if (typeof v === 'boolean') return v
  if (typeof v === 'number') return v !== 0
  if (typeof v === 'string') return ['true', '1', 'yes', 'y'].includes(v.toLowerCase())
  return fallback
}

/** 时间字段：接受 epoch 秒 / 毫秒 / ISO 字符串 */
export function timestamp(o: Dict, keys: string[]): number | null {
  const v = pick(o, keys)
  if (v === undefined) return null
  if (typeof v === 'number') return v < 1e12 ? v * 1000 : v
  if (typeof v === 'string') {
    const asNumber = Number(v)
    if (Number.isFinite(asNumber) && v.trim() !== '') return asNumber < 1e12 ? asNumber * 1000 : asNumber
    const parsed = Date.parse(v)
    return Number.isNaN(parsed) ? null : parsed
  }
  return null
}

export function strMap(o: Dict, keys: string[]): Record<string, string> | undefined {
  const v = pick(o, keys)
  if (!v || typeof v !== 'object' || Array.isArray(v)) return undefined
  const out: Record<string, string> = {}
  for (const [k, val] of Object.entries(v as Dict)) out[k] = val === null ? '' : String(val)
  return out
}

export function strList(o: Dict, keys: string[]): string[] {
  const v = pick(o, keys)
  if (Array.isArray(v)) {
    return v
      .map((item) => {
        if (typeof item === 'string') return item
        const d = asDict(item)
        return str(d, ['displayTag', 'tag', 'agentId', 'id', 'name'])
      })
      .filter(Boolean)
  }
  if (typeof v === 'string') return v.split(',').map((s) => s.trim()).filter(Boolean)
  return []
}

/* -------------------------------------------------- status ------------- */

export function normalizeExecutionStatus(raw: unknown, fallback: ExecutionStatus = 'pending'): ExecutionStatus {
  const s = String(raw ?? '').trim().toLowerCase().replace(/[\s_-]+/g, '')
  const alias: Record<string, ExecutionStatus> = {
    pending: 'pending',
    queued: 'pending',
    waiting: 'pending',
    dispatching: 'dispatching',
    dispatch: 'dispatching',
    sending: 'dispatching',
    running: 'running',
    inprogress: 'running',
    executing: 'running',
    pass: 'pass',
    passed: 'pass',
    success: 'pass',
    ok: 'pass',
    fail: 'fail',
    failed: 'fail',
    failure: 'fail',
    block: 'block',
    blocked: 'block',
    exception: 'exception',
    error: 'exception',
    timeout: 'exception',
    canceled: 'canceled',
    cancelled: 'canceled',
    cancel: 'canceled',
  }
  if (alias[s]) return alias[s]
  const direct = EXECUTION_STATUSES.find((x) => x === s)
  return direct ?? fallback
}

export function normalizeAgentStatus(o: Dict): AgentStatus {
  const raw = String(pick(o, ['status', 'state', 'onlineStatus']) ?? '').toLowerCase().replace(/[\s_-]+/g, '')
  const alias: Record<string, AgentStatus> = {
    online: 'online',
    idle: 'online',
    connected: 'online',
    up: 'online',
    busy: 'busy',
    running: 'busy',
    working: 'busy',
    disconnected: 'disconnected',
    lost: 'disconnected',
    lostcontact: 'disconnected',
    unreachable: 'disconnected',
    stale: 'disconnected',
    offline: 'offline',
    down: 'offline',
    dead: 'offline',
  }
  if (alias[raw]) {
    // server 只给 online 时，本地按 running 数细分出忙碌
    if (alias[raw] === 'online' && num(o, ['running', 'runningCount'], 0) > 0) return 'busy'
    return alias[raw]
  }
  const online = bool(o, ['online', 'connected'], false)
  if (!online) {
    const last = timestamp(o, ['lastSeenAt', 'lastSeen', 'lastHeartbeatAt', 'lastHeartbeat', 'updatedAt'])
    if (last && Date.now() - last < 5 * 60_000) return 'disconnected'
    return 'offline'
  }
  return num(o, ['running', 'runningCount'], 0) > 0 ? 'busy' : 'online'
}

/* -------------------------------------------------- entities ----------- */

export function normalizeAgent(input: unknown): Agent {
  const o = asDict(input)
  const runningIds = strList(o, ['runningExecuteIds', 'runningExecutions', 'running_ids', 'executions'])
  return {
    agentId: str(o, ['agentId', 'agent_id', 'id', 'uuid']),
    displayTag: str(o, ['displayTag', 'display_tag', 'tag', 'name', 'alias']),
    status: normalizeAgentStatus(o),
    concurrency: Math.min(4, Math.max(1, num(o, ['concurrency', 'maxConcurrency', 'slots'], 1))),
    running: num(o, ['running', 'runningCount', 'runningTasks'], runningIds.length),
    version: optStr(o, ['ver', 'version', 'agentVersion']),
    ip: optStr(o, ['ip', 'address', 'remoteAddr', 'host']),
    os: optStr(o, ['os', 'platform', 'osName']),
    bootId: optStr(o, ['bootId', 'boot_id']),
    sessionId: optStr(o, ['sessionId', 'session_id']),
    lastSeenAt: timestamp(o, ['lastSeenAt', 'lastSeen', 'lastHeartbeatAt', 'lastHeartbeat', 'updatedAt']),
    connectedAt: timestamp(o, ['connectedAt', 'connectTime', 'since', 'createdAt']),
    runningExecuteIds: runningIds,
    raw: o,
  }
}

export function normalizeConditionConfig(input: unknown): ConditionConfig | null {
  if (input === null || input === undefined || input === '') return null
  let value: unknown = input
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      return null
    }
  }
  if (!value || typeof value !== 'object') return null
  const o = asDict(value)
  const rawRules = (Array.isArray(o.rules) ? o.rules : Array.isArray(o.conditions) ? o.conditions : []) as unknown[]
  const rules: ConditionRule[] = rawRules.map((item) => {
    const r = asDict(item)
    const op = String(pick(r, ['operator', 'op', 'type']) ?? 'equals').toLowerCase().replace(/[\s_]+/g, '-')
    const operator = (CONDITION_OPERATORS.find((x) => x === op) ?? 'equals') as ConditionOperator
    const st = String(pick(r, ['status', 'result']) ?? 'pass').toLowerCase()
    const status = (OTHER_STATUSES.find((x) => x === st) ?? 'pass') as OtherStatus
    return { operator, value: str(r, ['value', 'val', 'expect', 'pattern']), status }
  })
  const otherRaw = String(pick(o, ['other', 'otherStatus', 'default']) ?? '').toLowerCase()
  const other = (OTHER_STATUSES.find((x) => x === otherRaw) ?? null) as OtherStatus | null
  if (!rules.length && !other) return null
  return { rules, other }
}

export function normalizeExecution(input: unknown): Execution {
  const o = asDict(input)
  const status = normalizeExecutionStatus(pick(o, ['status', 'state', 'result']))
  const subState = String(pick(o, ['subStatus', 'subState']) ?? '').toLowerCase()
  return {
    executeId: str(o, ['executeId', 'execute_id', 'executionId', 'id']),
    taskId: optStr(o, ['taskId', 'task_id']),
    agentId: optStr(o, ['agentId', 'agent_id']),
    displayTag: optStr(o, ['displayTag', 'display_tag', 'tag', 'agentTag', 'target']),
    status,
    disconnected: bool(o, ['disconnected', 'lost'], subState === 'disconnected'),
    command: optStr(o, ['command', 'cmd', 'script']),
    cwd: optStr(o, ['cwd', 'workDir', 'workingDir']),
    env: strMap(o, ['env', 'environment', 'envs']),
    operator: optStr(o, ['operator', 'creator', 'owner', 'user']),
    timeoutSec: optNum(o, ['timeoutSec', 'timeout', 'timeout_sec']),
    exitCode: optNum(o, ['exitCode', 'exit_code', 'code']) ?? null,
    lastLine: optStr(o, ['lastLine', 'last_line', 'lastOutput']),
    conditionConfig: normalizeConditionConfig(pick(o, ['conditionConfig', 'condition_config', 'condition'])),
    conditionHit: optStr(o, ['conditionHit', 'hitRule', 'matchedRule', 'judgeReason', 'reason']),
    dispatchToken: optStr(o, ['dispatchToken', 'token']),
    createdAt: timestamp(o, ['createdAt', 'created_at', 'createTime', 'enqueuedAt']),
    startedAt: timestamp(o, ['startedAt', 'started_at', 'startTime', 'beginAt']),
    finishedAt: timestamp(o, ['finishedAt', 'finished_at', 'endTime', 'completedAt']),
    logTruncated: bool(o, ['logTruncated', 'truncated', 'log_truncated'], false),
    logBytes: optNum(o, ['logBytes', 'logSize', 'bytes']),
    logTotalBytes: optNum(o, ['logTotalBytes', 'totalBytes', 'originalBytes']),
    message: optStr(o, ['message', 'msg', 'error', 'errorMessage']),
    raw: o,
  }
}

function emptyCounts(): Record<ExecutionStatus, number> {
  return {
    pending: 0,
    dispatching: 0,
    running: 0,
    pass: 0,
    fail: 0,
    block: 0,
    exception: 0,
    canceled: 0,
  }
}

/** 由子执行聚合出任务状态：只要还有未终态的就是进行中 */
export function aggregateStatus(counts: Record<ExecutionStatus, number>, fallback: ExecutionStatus): ExecutionStatus {
  const total = Object.values(counts).reduce((a, b) => a + b, 0)
  if (!total) return fallback
  if (counts.running || counts.dispatching) return 'running'
  if (counts.pending) return counts.pending === total ? 'pending' : 'running'
  if (counts.exception) return 'exception'
  if (counts.fail) return 'fail'
  if (counts.block) return 'block'
  if (counts.canceled && counts.canceled === total) return 'canceled'
  if (counts.pass) return 'pass'
  return fallback
}

export function normalizeTask(input: unknown): Task {
  const o = asDict(input)
  const rawExecs =
    (Array.isArray(o.executions) && o.executions) ||
    (Array.isArray(o.execs) && o.execs) ||
    (Array.isArray(o.items) && o.items) ||
    []
  const executions = (rawExecs as unknown[]).map(normalizeExecution)
  const counts = emptyCounts()
  if (executions.length) {
    for (const e of executions) counts[e.status] += 1
  } else {
    const rawCounts = pick(o, ['statusCounts', 'counts', 'status_counts'])
    if (rawCounts && typeof rawCounts === 'object' && !Array.isArray(rawCounts)) {
      for (const [k, v] of Object.entries(rawCounts as Dict)) {
        const n = typeof v === 'number' ? v : Number(v)
        if (!Number.isFinite(n) || n <= 0) continue
        counts[normalizeExecutionStatus(k)] += n
      }
    }
  }

  const declared = String(pick(o, ['status', 'state']) ?? '').trim().toLowerCase()
  let fallback: ExecutionStatus = 'pending'
  if (declared === 'canceled' || declared === 'cancelled') fallback = 'canceled'
  else if (declared === 'running') fallback = 'running'
  else if (declared === 'dispatching' || declared === 'dispatch') fallback = 'dispatching'
  else if (declared === 'pending' || declared === 'queued' || declared === 'waiting') fallback = 'pending'
  else if (declared === 'finished' || declared === 'complete' || declared === 'completed' || declared === 'done') {
    // 任务级 FINISHED 不是 execution 状态；无子执行时不能回落到 pending
    fallback = 'pass'
  } else if (declared) {
    fallback = normalizeExecutionStatus(declared)
  }

  const hasCounts = Object.values(counts).some((n) => n > 0)
  // 有子执行/计数时必须按执行结果聚合。否则 Server 的 status=finished
  // 会被当成未知值落到 pending，队列页就会「跑完仍显示排队中」。
  const status = hasCounts ? aggregateStatus(counts, fallback) : fallback

  const callbackRaw = String(pick(o, ['callbackStatus', 'callback_status']) ?? '').toLowerCase()
  const callbackStatus = (CALLBACK_STATUSES.find((x) => x === callbackRaw) ?? 'none') as CallbackStatus

  const targets = strList(o, ['targets', 'target', 'agents', 'displayTags'])
  return {
    taskId: str(o, ['taskId', 'task_id', 'id']),
    command: str(o, ['command', 'cmd', 'script']),
    cwd: optStr(o, ['cwd', 'workDir', 'workingDir']),
    env: strMap(o, ['env', 'environment', 'envs']),
    operator: optStr(o, ['operator', 'creator', 'owner', 'user']),
    timeoutSec: optNum(o, ['timeoutSec', 'timeout', 'timeout_sec']),
    priority: optNum(o, ['priority', 'order', 'seq', 'queueIndex']),
    queueOrder: optNum(o, ['queueOrder', 'queue_order']),
    status,
    targets: targets.length ? targets : executions.map((e) => e.displayTag || e.agentId || '').filter(Boolean),
    conditionConfig: normalizeConditionConfig(pick(o, ['conditionConfig', 'condition_config', 'condition'])),
    createdAt: timestamp(o, ['createdAt', 'created_at', 'createTime']),
    finishedAt: timestamp(o, ['finishedAt', 'finished_at', 'endTime']),
    executions,
    counts,
    total: executions.length || targets.length,
    requestId: optStr(o, ['requestId', 'request_id']),
    callbackUrl: optStr(o, ['callbackUrl', 'callback_url']),
    callbackStatus,
    callbackAttempts: optNum(o, ['callbackAttempts', 'callback_attempts']),
    callbackLastError: optStr(o, ['callbackLastError', 'callback_last_error']),
    callbackLastAt: timestamp(o, ['callbackLastAt', 'callback_last_at']),
    attachmentCount: num(o, ['attachmentCount', 'attachment_count'], 0),
    raw: o,
  }
}

export function normalizeLogLine(input: unknown, index: number): LogLine {
  if (typeof input === 'string') return { seq: index, text: input }
  const o = asDict(input)
  return {
    seq: num(o, ['seq', 'index', 'n', 'lineNo', 'id'], index),
    ts: timestamp(o, ['ts', 'time', 'timestamp', 'at']),
    stream: optStr(o, ['stream', 'fd', 'channel']),
    text: str(o, ['text', 'line', 'content', 'msg', 'message'], ''),
  }
}

export function normalizeTimelineEvent(input: unknown, index: number): TimelineEvent {
  const o = asDict(input)
  const srcRaw = String(pick(o, ['source', 'src', 'from', 'side', 'origin']) ?? '').toLowerCase()
  let source: TimelineSource = srcRaw.startsWith('a') ? 'agent' : srcRaw.startsWith('s') ? 'server' : 'server'
  if (!srcRaw) {
    // 没给 source 时按事件名兜底：agent 上报的事件通常带 evtId
    source = o.evtId !== undefined || o.evt_id !== undefined ? 'agent' : 'server'
  }
  return {
    id: str(o, ['id', 'eventId', 'evtId', 'seq'], `evt-${index}`),
    source,
    type: str(o, ['type', 'event', 'name', 'kind', 'action'], 'event'),
    ts: timestamp(o, ['ts', 'time', 'timestamp', 'at', 'createdAt']),
    agentId: optStr(o, ['agentId', 'agent_id']),
    displayTag: optStr(o, ['displayTag', 'tag', 'agentTag']),
    executeId: optStr(o, ['executeId', 'execute_id', 'executionId']),
    token: optStr(o, ['token', 'dispatchToken']),
    sessionId: optStr(o, ['sessionId', 'session_id']),
    bootId: optStr(o, ['bootId', 'boot_id']),
    evtId: optStr(o, ['evtId', 'evt_id']),
    message: optStr(o, ['message', 'msg', 'text', 'desc', 'description']),
    detail: pick(o, ['detail', 'data', 'payload', 'extra', 'attrs']),
    raw: o,
  }
}
