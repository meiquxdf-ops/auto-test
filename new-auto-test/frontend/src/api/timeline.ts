import { http, unwrapList } from './http'
import { normalizeTimelineEvent } from './normalize'
import type { TimelineEvent } from './types'

export interface TimelineQuery {
  agentId?: string
  executeId?: string
  limit?: number
}

export async function getTimeline(query: TimelineQuery = {}): Promise<TimelineEvent[]> {
  const params: Record<string, string | number> = {}
  if (query.agentId) params.agentId = query.agentId
  if (query.executeId) params.executeId = query.executeId
  if (query.limit) params.limit = query.limit
  const res = await http.get('/api/timeline', { params })
  return unwrapList(res.data).map(normalizeTimelineEvent)
}
