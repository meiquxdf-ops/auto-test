import { get } from './http'
import { asDict, normalizeExecutionStatus } from './normalize'
import { EXECUTION_STATUSES, type ExecutionStatus } from './types'

export interface DashboardStats {
  /** 全库执行数，按状态分组（不是「最近 N 条任务」的窗口） */
  executions: Record<ExecutionStatus, number>
  tasks: number
}

function emptyCounts(): Record<ExecutionStatus, number> {
  const out = {} as Record<ExecutionStatus, number>
  for (const s of EXECUTION_STATUSES) out[s] = 0
  return out
}

/**
 * GET /api/dashboard：Server 侧的全量聚合。
 * 任务列表接口最多只给 200 条（size 被 Server 截断），拿它累加出来的
 * 「运行中 / 需要关注 / 排队中」只覆盖最新的一页，总览必须用这里的数。
 */
export async function getDashboardStats(): Promise<DashboardStats> {
  const raw = asDict(await get<unknown>('/api/dashboard'))
  const executions = emptyCounts()
  for (const [k, v] of Object.entries(asDict(raw.executions))) {
    const n = typeof v === 'number' ? v : Number(v)
    if (!Number.isFinite(n) || n <= 0) continue
    executions[normalizeExecutionStatus(k)] += n
  }
  const tasks = Number(raw.tasks)
  return { executions, tasks: Number.isFinite(tasks) ? tasks : 0 }
}
