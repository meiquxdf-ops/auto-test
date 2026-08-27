import { get, http, post, patch, unwrapList } from './http'
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

/* ------------------------------------------------------------ 在线安装 */

/** GET /api/agent/install-info：curl / SSH 安装的分发状态 */
export interface InstallInfo {
  agentTcpPort: number
  distDir: string
  binaryAvailable: boolean
  binaryPath?: string
  binarySize?: number
  binarySha256?: string
  binaryModifiedAt?: string
  binaryElf?: boolean
  /** binaryAvailable 为 false 时，Server 给的填充指引 */
  hint?: string
}

export async function getInstallInfo(): Promise<InstallInfo> {
  return get<InstallInfo>('/api/agent/install-info')
}

export interface SshInstallBody {
  host: string
  port: number
  user: string
  authType: 'password' | 'key'
  password?: string
  privateKey?: string
  passphrase?: string
  skipHostKeyCheck: boolean
  tag: string
  server: string
  concurrency: number
}

export interface SshInstallResult {
  ok: boolean
  exitCode?: number | null
  output?: string
  error?: string
  errorCode?: string
  message?: string
  durationMs?: number
}

/** POST /api/agent/ssh-install：Server SSH 到目标机代装（可能要等 1-3 分钟） */
export async function sshInstall(body: SshInstallBody): Promise<SshInstallResult> {
  return post<SshInstallResult>('/api/agent/ssh-install', body, { timeout: 300_000 })
}
