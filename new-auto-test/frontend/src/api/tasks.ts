import { http, post, unwrap, unwrapList } from './http'
import { normalizeTask } from './normalize'
import type { CreateTaskPayload, ExecutionStatus, RerunMode, Task } from './types'

export interface ListTasksQuery {
  status?: ExecutionStatus | ''
  keyword?: string
  limit?: number
  offset?: number
}

export async function listTasks(query: ListTasksQuery = {}): Promise<Task[]> {
  const params: Record<string, string | number> = {}
  if (query.status) params.status = query.status
  if (query.keyword) params.keyword = query.keyword
  // Server 分页参数是 page/size；limit 只是前端习惯写法
  const size = query.limit ?? 200
  params.size = size
  params.limit = size
  if (query.offset) params.page = Math.floor(query.offset / size)
  const res = await http.get('/api/tasks', { params })
  return unwrapList(res.data).map(normalizeTask)
}

export async function createTask(payload: CreateTaskPayload): Promise<Task | null> {
  const body: Record<string, unknown> = {
    command: payload.command,
    targets: payload.targets,
  }
  if (payload.cwd) body.cwd = payload.cwd
  if (payload.env && Object.keys(payload.env).length) body.env = payload.env
  if (payload.conditionConfig) body.conditionConfig = payload.conditionConfig
  if (payload.operator) body.operator = payload.operator
  if (payload.timeoutSec) body.timeoutSec = payload.timeoutSec
  if (payload.priority !== undefined) body.priority = payload.priority

  const res = await http.post('/api/tasks', body)
  const data = unwrap<unknown>(res.data)
  return data ? normalizeTask(data) : null
}

export async function cancelTask(taskId: string): Promise<void> {
  await post(`/api/tasks/${encodeURIComponent(taskId)}/cancel`)
}

/** inplace：原地清空重跑；new：生成一条新记录 */
export async function rerunTask(taskId: string, mode: RerunMode): Promise<Task | null> {
  const data = await post<unknown>(`/api/tasks/${encodeURIComponent(taskId)}/rerun`, { mode })
  return data && typeof data === 'object' ? normalizeTask(data) : null
}

/** 仅 pending 可调序，ids 按目标顺序从前到后 */
export async function reorderTasks(ids: string[]): Promise<void> {
  await post('/api/tasks/reorder', { ids })
}

export async function getTask(taskId: string): Promise<Task | null> {
  const res = await http.get(`/api/tasks/${encodeURIComponent(taskId)}`)
  const data = unwrap<unknown>(res.data)
  return data ? normalizeTask(data) : null
}

/** 协议里只约定了列表接口，单条详情不一定存在，取不到就回列表里捞 */
export async function fetchTaskById(taskId: string): Promise<Task | null> {
  try {
    const task = await getTask(taskId)
    if (task?.taskId) return task
  } catch {
    /* 落到列表兜底 */
  }
  const list = await listTasks({ limit: 300 })
  return list.find((t) => t.taskId === taskId) ?? null
}
