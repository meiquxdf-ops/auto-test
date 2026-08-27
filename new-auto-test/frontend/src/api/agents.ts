import { http, post, patch, unwrapList } from './http'
import { normalizeAgent } from './normalize'
import type { Agent } from './types'

export async function listAgents(): Promise<Agent[]> {
  const res = await http.get('/api/agents')
  return unwrapList(res.data).map(normalizeAgent)
}

export interface AgentPatch {
  displayTag?: string
  concurrency?: number
}

export async function updateAgent(agentId: string, body: AgentPatch): Promise<Agent | null> {
  const data = await patch<unknown>(`/api/agents/${encodeURIComponent(agentId)}`, body)
  return data ? normalizeAgent(data) : null
}

export async function restartAgent(agentId: string): Promise<void> {
  await post(`/api/agents/${encodeURIComponent(agentId)}/restart`)
}

export async function stopAgent(agentId: string): Promise<void> {
  await post(`/api/agents/${encodeURIComponent(agentId)}/stop`)
}
