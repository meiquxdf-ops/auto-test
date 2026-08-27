import type { Execution, ExecutionStatus, Task } from '@/api/types'

export function emptyCounts(): Record<ExecutionStatus, number> {
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

/** 汇总所有任务下的执行状态；任务没带 executions 时按任务本身计一次（权重=目标数） */
export function countExecutions(tasks: Task[]): Record<ExecutionStatus, number> {
  const total = emptyCounts()
  for (const t of tasks) {
    if (t.executions.length) {
      for (const s of Object.keys(t.counts) as ExecutionStatus[]) total[s] += t.counts[s]
    } else {
      total[t.status] += Math.max(1, t.targets.length)
    }
  }
  return total
}

export function flattenExecutions(tasks: Task[]): Execution[] {
  const out: Execution[] = []
  for (const t of tasks) {
    for (const e of t.executions) out.push({ ...e, taskId: e.taskId ?? t.taskId, command: e.command ?? t.command })
  }
  return out
}

export function sumCounts(counts: Record<ExecutionStatus, number>, keys: ExecutionStatus[]): number {
  return keys.reduce((acc, k) => acc + (counts[k] ?? 0), 0)
}
