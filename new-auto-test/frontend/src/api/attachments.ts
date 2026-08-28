import { apiUrl, http, unwrapList } from './http'
import { asDict, optStr, str, num, timestamp } from './normalize'
import type { TaskFile } from './types'

function normalizeFile(input: unknown): TaskFile {
  const o = asDict(input)
  return {
    id: str(o, ['id', 'fileId']),
    taskId: optStr(o, ['taskId', 'task_id']),
    executeId: optStr(o, ['executeId', 'execute_id']),
    name: str(o, ['name', 'fileName', 'file_name'], 'file'),
    size: num(o, ['size', 'sizeBytes', 'size_bytes'], 0),
    contentType: optStr(o, ['contentType', 'content_type']),
    createdAt: timestamp(o, ['createdAt', 'created_at']),
  }
}

/** GET /api/tasks/{taskId}/files：附件元数据列表 */
export async function listTaskFiles(taskId: string): Promise<TaskFile[]> {
  const res = await http.get(`/api/tasks/${encodeURIComponent(taskId)}/files`)
  return unwrapList(res.data).map(normalizeFile)
}

/**
 * POST /api/tasks/{taskId}/files：运维台给任务补附件（multipart 字段 file，单文件 ≤ 32MB）。
 * Server 侧上传并发有准入水位，超出 429；给足超时让 32MB 在慢网络也能传完。
 */
export async function uploadTaskFile(taskId: string, file: File): Promise<TaskFile> {
  const body = new FormData()
  body.append('file', file)
  const res = await http.post(`/api/tasks/${encodeURIComponent(taskId)}/files`, body, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180_000,
  })
  return normalizeFile(res.data)
}

/** 下载地址（Content-Disposition: attachment）；预览图片/文本用 inline=true 走同一地址 */
export function fileUrl(fileId: string, inline = false): string {
  return apiUrl(`/api/files/${encodeURIComponent(fileId)}${inline ? '?inline=1' : ''}`)
}

/** 预览用：小文本文件直接拉内容 */
export async function fetchFileText(fileId: string): Promise<string> {
  const res = await http.get(`/api/files/${encodeURIComponent(fileId)}`, {
    params: { inline: 1 },
    responseType: 'text',
    transformResponse: [(d: unknown) => d],
  })
  return typeof res.data === 'string' ? res.data : String(res.data ?? '')
}
