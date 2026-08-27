/**
 * 与 docs/protocol.md 对齐的领域模型。
 *
 * Server 返回的字段可能存在别名（例如 lastSeenAt / lastHeartbeatAt），
 * 统一在 api/normalize.ts 里做一次收敛，页面只消费这里的类型。
 */

/** execution 状态机：pending → dispatching → running → 终态 */
export type ExecutionStatus =
  | 'pending'
  | 'dispatching'
  | 'running'
  | 'pass'
  | 'fail'
  | 'block'
  | 'exception'
  | 'canceled'

export const EXECUTION_STATUSES: ExecutionStatus[] = [
  'pending',
  'dispatching',
  'running',
  'pass',
  'fail',
  'block',
  'exception',
  'canceled',
]

/** 判定算子 */
export type ConditionOperator = 'equals' | 'not-equals' | 'include' | 'regex'

export const CONDITION_OPERATORS: ConditionOperator[] = [
  'equals',
  'not-equals',
  'include',
  'regex',
]

/** other 只允许这四种终态 */
export type OtherStatus = 'pass' | 'fail' | 'block' | 'exception'

export const OTHER_STATUSES: OtherStatus[] = ['pass', 'fail', 'block', 'exception']

export interface ConditionRule {
  operator: ConditionOperator
  value: string
  status: OtherStatus
}

export interface ConditionConfig {
  rules: ConditionRule[]
  other?: OtherStatus | null
}

/** 任务终态回调投递状态：无回调 / 待触发 / 投递中 / 成功 / 已放弃 */
export type CallbackStatus = 'none' | 'pending' | 'running' | 'success' | 'failed'

export const CALLBACK_STATUSES: CallbackStatus[] = ['none', 'pending', 'running', 'success', 'failed']

/** 机器在线状态：在线 / 忙碌 / 失联 / 离线 */
export type AgentStatus = 'online' | 'busy' | 'disconnected' | 'offline'

export interface Agent {
  agentId: string
  displayTag: string
  status: AgentStatus
  concurrency: number
  running: number
  version?: string
  ip?: string
  os?: string
  bootId?: string
  sessionId?: string
  lastSeenAt?: number | null
  connectedAt?: number | null
  runningExecuteIds: string[]
  raw: Record<string, unknown>
}

export interface Execution {
  executeId: string
  taskId?: string
  agentId?: string
  displayTag?: string
  status: ExecutionStatus
  /** running 的子状态：失联 */
  disconnected?: boolean
  command?: string
  cwd?: string
  env?: Record<string, string>
  operator?: string
  timeoutSec?: number
  exitCode?: number | null
  lastLine?: string
  conditionConfig?: ConditionConfig | null
  conditionHit?: string
  dispatchToken?: string
  createdAt?: number | null
  startedAt?: number | null
  finishedAt?: number | null
  logTruncated?: boolean
  logBytes?: number
  logTotalBytes?: number
  message?: string
  raw: Record<string, unknown>
}

export interface Task {
  taskId: string
  command: string
  cwd?: string
  env?: Record<string, string>
  operator?: string
  timeoutSec?: number
  priority?: number
  status: ExecutionStatus
  targets: string[]
  conditionConfig?: ConditionConfig | null
  createdAt?: number | null
  finishedAt?: number | null
  executions: Execution[]
  counts: Record<ExecutionStatus, number>
  total: number
  /** 开放 API：创建时调用方自带的全局唯一键，一个 requestId 可挂多条任务 */
  requestId?: string
  callbackUrl?: string
  callbackStatus: CallbackStatus
  callbackAttempts?: number
  callbackLastError?: string
  callbackLastAt?: number | null
  raw: Record<string, unknown>
}

export interface LogLine {
  seq: number
  ts?: number | null
  stream?: 'stdout' | 'stderr' | string
  text: string
}

export interface LogPage {
  lines: LogLine[]
  nextSeq: number
  truncated: boolean
  /** 被丢弃的头部字节数（若 server 提供） */
  droppedBytes?: number
  totalBytes?: number
  finished?: boolean
  status?: ExecutionStatus
}

export type TimelineSource = 'agent' | 'server'

export interface TimelineEvent {
  id: string
  source: TimelineSource
  type: string
  ts?: number | null
  agentId?: string
  displayTag?: string
  executeId?: string
  token?: string
  sessionId?: string
  bootId?: string
  evtId?: number | string
  message?: string
  detail?: unknown
  raw: Record<string, unknown>
}

export interface CreateTaskPayload {
  command: string
  cwd?: string
  env?: Record<string, string>
  targets: string[]
  conditionConfig?: ConditionConfig | null
  operator?: string
  timeoutSec?: number
  priority?: number
  /** 开放 API 幂等键：^[A-Za-z0-9._-]{1,64}$，全局唯一；运维台可不填 */
  requestId?: string
  /** 任务终态（finished/canceled）后 POST 一次结果；仅 http/https */
  callbackUrl?: string
}

/** POST /api/tasks/batch 的单条任务 */
export interface BatchTaskItem {
  name?: string
  command: string
  cwd?: string
  env?: Record<string, string>
  targets: string[]
  conditionConfig?: ConditionConfig | null
  operator?: string
  timeoutSec?: number
}

export interface BatchCreatePayload {
  requestId: string
  callbackUrl?: string
  items: BatchTaskItem[]
}

/** 批量创建里被拒绝的单条：index 指向 items 下标 */
export interface BatchItemError {
  index: number
  message: string
}

export type RerunMode = 'inplace' | 'new'

/** 创建任务表单的可预填字段（「以此为模板」用） */
export interface TaskFormPreset {
  command?: string
  cwd?: string
  env?: Record<string, string>
  targets?: string[]
  timeoutSec?: number
  operator?: string
  conditionConfig?: ConditionConfig | null
}
