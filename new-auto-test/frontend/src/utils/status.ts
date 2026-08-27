import type { AgentStatus, CallbackStatus, ExecutionStatus } from '@/api/types'

export interface StatusMeta {
  label: string
  color: string
  /** 浅色底 */
  bg: string
  border: string
  /** 是否终态 */
  terminal: boolean
}

export const EXECUTION_STATUS_META: Record<ExecutionStatus, StatusMeta> = {
  pending: {
    label: '排队中',
    color: '#64748b',
    bg: 'rgba(100,116,139,.12)',
    border: 'rgba(100,116,139,.32)',
    terminal: false,
  },
  dispatching: {
    label: '下发中',
    color: '#0891b2',
    bg: 'rgba(8,145,178,.12)',
    border: 'rgba(8,145,178,.32)',
    terminal: false,
  },
  running: {
    label: '执行中',
    color: '#2563eb',
    bg: 'rgba(37,99,235,.12)',
    border: 'rgba(37,99,235,.32)',
    terminal: false,
  },
  pass: {
    label: '通过',
    color: '#16a34a',
    bg: 'rgba(22,163,74,.12)',
    border: 'rgba(22,163,74,.32)',
    terminal: true,
  },
  fail: {
    label: '失败',
    color: '#dc2626',
    bg: 'rgba(220,38,38,.12)',
    border: 'rgba(220,38,38,.32)',
    terminal: true,
  },
  block: {
    label: '阻塞',
    color: '#ea8a04',
    bg: 'rgba(234,138,4,.14)',
    border: 'rgba(234,138,4,.34)',
    terminal: true,
  },
  exception: {
    label: '异常',
    color: '#9333ea',
    bg: 'rgba(147,51,234,.12)',
    border: 'rgba(147,51,234,.32)',
    terminal: true,
  },
  canceled: {
    label: '已取消',
    color: '#8b949e',
    bg: 'rgba(139,148,158,.14)',
    border: 'rgba(139,148,158,.34)',
    terminal: true,
  },
}

export function statusMeta(status: ExecutionStatus): StatusMeta {
  return EXECUTION_STATUS_META[status] ?? EXECUTION_STATUS_META.pending
}

export function isTerminal(status: ExecutionStatus): boolean {
  return statusMeta(status).terminal
}

export interface AgentStatusMeta {
  label: string
  color: string
  desc: string
}

export const AGENT_STATUS_META: Record<AgentStatus, AgentStatusMeta> = {
  online: { label: '在线', color: '#16a34a', desc: '连接正常且空闲' },
  busy: { label: '忙碌', color: '#2563eb', desc: '有执行在跑' },
  disconnected: { label: '失联', color: '#ea8a04', desc: '心跳超时，执行进入 disconnected 子状态' },
  offline: { label: '离线', color: '#8b949e', desc: '连接已断开' },
}

export function agentStatusMeta(status: AgentStatus): AgentStatusMeta {
  return AGENT_STATUS_META[status] ?? AGENT_STATUS_META.offline
}

export const CONDITION_OPERATOR_LABEL: Record<string, string> = {
  equals: '等于',
  'not-equals': '不等于',
  include: '包含',
  regex: '正则匹配',
}

export interface CallbackStatusMeta {
  label: string
  /** el-tag type */
  type: 'info' | 'primary' | 'warning' | 'success' | 'danger'
  desc: string
}

/** 任务终态回调的投递状态（不是任务本身的状态） */
export const CALLBACK_STATUS_META: Record<CallbackStatus, CallbackStatusMeta> = {
  none: { label: '无回调', type: 'info', desc: '创建时未填 callbackUrl' },
  pending: { label: '待触发', type: 'primary', desc: '任务到达终态后自动 POST 一次结果' },
  running: { label: '投递中', type: 'warning', desc: '正在尝试回调（失败按 1s/2s/4s/8s 退避重试）' },
  success: { label: '已送达', type: 'success', desc: '对方返回 2xx' },
  failed: { label: '已放弃', type: 'danger', desc: '重试 5 次仍未拿到 2xx，只放弃通知，不影响任务结果' },
}

export function callbackStatusMeta(status: CallbackStatus | undefined): CallbackStatusMeta {
  return CALLBACK_STATUS_META[status ?? 'none'] ?? CALLBACK_STATUS_META.none
}
