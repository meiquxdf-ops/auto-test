import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { shallowRef } from 'vue'

const LS_KEY = 'nat.apiBase'
const DEFAULT_BASE = 'http://127.0.0.1:8080'

/**
 * 开发态默认走 vite proxy（同源 /api），生产态默认直连 127.0.0.1:8080。
 * 运维现场可以在页头临时改成别的 server 地址，存 localStorage。
 */
function resolveBase(): string {
  const override = typeof localStorage !== 'undefined' ? localStorage.getItem(LS_KEY) : null
  if (override !== null && override !== undefined) return override.replace(/\/+$/, '')
  if (import.meta.env.DEV) return ''
  return (import.meta.env.VITE_API_BASE || DEFAULT_BASE).replace(/\/+$/, '')
}

/** 响应式：页头改完接口地址后，拼 URL 的界面（接入调试的请求预览 / curl）要跟着重算 */
const apiBaseRef = shallowRef(resolveBase())

export function getApiBase(): string {
  return apiBaseRef.value
}

export function getApiBaseLabel(): string {
  return (
    apiBaseRef.value || `${location.origin}（vite proxy → ${import.meta.env.VITE_API_BASE || DEFAULT_BASE}）`
  )
}

export function setApiBase(base: string | null) {
  if (base === null) localStorage.removeItem(LS_KEY)
  else localStorage.setItem(LS_KEY, base.replace(/\/+$/, ''))
  apiBaseRef.value = resolveBase()
  http.defaults.baseURL = apiBaseRef.value || undefined
}

/** SSE / 下载等需要完整 URL 的场景 */
export function apiUrl(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  return `${apiBaseRef.value}${p}`
}

export const http = axios.create({
  baseURL: apiBaseRef.value || undefined,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
})

export class ApiError extends Error {
  status: number
  code?: string
  payload?: unknown

  constructor(message: string, status: number, code?: string, payload?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.payload = payload
  }
}

function pickMessage(data: unknown): string | undefined {
  if (!data) return undefined
  if (typeof data === 'string') return data.slice(0, 500)
  if (typeof data === 'object') {
    const d = data as Record<string, unknown>
    for (const k of ['message', 'msg', 'error', 'detail', 'reason']) {
      const v = d[k]
      if (typeof v === 'string' && v.trim()) return v
    }
    if (typeof d.e === 'object' && d.e) {
      const e = d.e as Record<string, unknown>
      if (typeof e.msg === 'string') return e.msg
    }
  }
  return undefined
}

function pickCode(data: unknown): string | undefined {
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>
    if (typeof d.code === 'string') return d.code
    if (typeof d.e === 'object' && d.e) {
      const c = (d.e as Record<string, unknown>).c
      if (typeof c === 'string') return c
    }
  }
  return undefined
}

http.interceptors.response.use(
  (res) => res,
  (error: AxiosError) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const status = error.response?.status ?? 0
    const data = error.response?.data
    let message = pickMessage(data)
    if (!message) {
      if (status === 0) message = '无法连接 Server，请确认服务已启动且接口地址正确'
      else if (status === 404) message = '接口不存在（404）'
      else if (status >= 500) message = `Server 内部错误（${status}）`
      else message = error.message || `请求失败（${status}）`
    }
    return Promise.reject(new ApiError(message, status, pickCode(data), data))
  },
)

/**
 * 兼容 `{code,data}` / `{data}` / 裸对象 三种返回包装。
 */
export function unwrap<T>(body: unknown): T {
  if (body && typeof body === 'object' && !Array.isArray(body)) {
    const b = body as Record<string, unknown>
    if ('data' in b && ('code' in b || 'success' in b || 'ok' in b || Object.keys(b).length <= 3)) {
      return b.data as T
    }
  }
  return body as T
}

/** 兼容 `[]` / `{items:[]}` / `{list:[]}` / `{content:[]}` / `{data:[]}` */
export function unwrapList<T = unknown>(body: unknown): T[] {
  const data = unwrap<unknown>(body)
  if (Array.isArray(data)) return data as T[]
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>
    for (const k of ['items', 'list', 'content', 'records', 'rows', 'events', 'tasks', 'agents', 'lines']) {
      if (Array.isArray(d[k])) return d[k] as T[]
    }
  }
  return []
}

export interface RawHttpResult {
  status: number
  statusText: string
  data: unknown
  durationMs: number
}

export interface RawRequestOptions {
  /** 上传/下载大附件时放宽超时（默认沿用实例 20s） */
  timeoutMs?: number
  /** 'blob'：响应体可能是二进制（附件下载），不要按 JSON/文本解析 */
  responseType?: 'blob'
}

/**
 * 接入调试页专用：原样透出 HTTP 状态码与响应体，不做 unwrap、不弹全局 toast。
 * 4xx/5xx 也走 resolve（调试页要把错误响应原样展示给调用方），
 * 只有网络层失败（连不上 Server）才会 reject（被上面的拦截器包成 status 0 的 ApiError）。
 * body 传 FormData 时按 multipart 发送：显式换掉实例默认的 JSON Content-Type，
 * 浏览器会自动补上带 boundary 的 multipart/form-data。
 */
export async function rawRequest(
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
  opts: RawRequestOptions = {},
): Promise<RawHttpResult> {
  const started = Date.now()
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData
  const res = await http.request({
    method,
    url: path,
    data: body,
    validateStatus: () => true,
    ...(isForm ? { headers: { 'Content-Type': 'multipart/form-data' } } : {}),
    ...(opts.timeoutMs ? { timeout: opts.timeoutMs } : {}),
    ...(opts.responseType ? { responseType: opts.responseType } : {}),
  })
  return {
    status: res.status,
    statusText: res.statusText,
    data: res.data,
    durationMs: Date.now() - started,
  }
}

export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const res = await http.get(url, config)
  return unwrap<T>(res.data)
}

export async function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await http.post(url, body ?? {}, config)
  return unwrap<T>(res.data)
}

export async function patch<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await http.patch(url, body ?? {}, config)
  return unwrap<T>(res.data)
}

export function errorMessage(err: unknown, fallback = '操作失败'): string {
  if (err instanceof ApiError) return err.message
  if (err instanceof Error) return err.message || fallback
  if (typeof err === 'string') return err
  return fallback
}

/** 统一的错误 toast */
export function toastError(err: unknown, fallback = '操作失败') {
  ElMessage.closeAll()
  ElMessage({ type: 'error', message: errorMessage(err, fallback), duration: 4000, showClose: true })
}

export function toastOk(message: string) {
  ElMessage({ type: 'success', message, duration: 2200 })
}
