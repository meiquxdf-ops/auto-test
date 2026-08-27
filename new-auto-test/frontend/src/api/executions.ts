import { http, unwrap, unwrapList } from './http'
import { asDict, bool, normalizeExecution, normalizeExecutionStatus, normalizeLogLine, optNum } from './normalize'
import type { Execution, LogPage } from './types'

export async function getExecution(executeId: string): Promise<Execution> {
  const res = await http.get(`/api/executions/${encodeURIComponent(executeId)}`)
  return normalizeExecution(unwrap<unknown>(res.data))
}

export async function getExecutionLogs(
  executeId: string,
  from = 0,
  limit = 2000,
): Promise<LogPage> {
  const res = await http.get(`/api/executions/${encodeURIComponent(executeId)}/logs`, {
    params: { from, limit },
  })
  const body = unwrap<unknown>(res.data)
  const lines = unwrapList(body).map(normalizeLogLine)
  const o = asDict(body)
  const lastSeq = lines.length ? lines[lines.length - 1].seq : from - 1
  return {
    lines,
    nextSeq: optNum(o, ['nextSeq', 'next', 'nextFrom']) ?? lastSeq + 1,
    truncated: bool(o, ['truncated', 'logTruncated'], false),
    droppedBytes: optNum(o, ['droppedBytes', 'dropped', 'skippedBytes']),
    totalBytes: optNum(o, ['totalBytes', 'bytes', 'logBytes']),
    finished: bool(o, ['finished', 'done', 'eof'], false),
    status: o.status !== undefined ? normalizeExecutionStatus(o.status) : undefined,
  }
}
