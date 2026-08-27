import type { LocationQuery } from 'vue-router'

const TRUTHY = ['1', 'true', 'yes']

function truthy(value: unknown): boolean {
  return typeof value === 'string' && TRUTHY.includes(value.toLowerCase())
}

/** 是否跑在 iframe 里。引用比较跨域也安全，不会像访问 top 的属性那样抛错 */
export function isFramed(): boolean {
  return typeof window !== 'undefined' && window.self !== window.top
}

/**
 * 嵌入态判定：`?embed=1|true|yes`（hash 路由的 query）或本身在 iframe 中。
 * 路由未就绪的首帧 query 还是空对象，此时兜底直接解析 location.hash，
 * 保证 `#/open?embed=1` 独立打开时首屏不会闪一下侧栏。
 */
export function isEmbed(query?: LocationQuery): boolean {
  if (isFramed()) return true
  if (query) {
    const v = query.embed
    if (truthy(Array.isArray(v) ? v[0] : v)) return true
  }
  if (typeof window === 'undefined') return false
  const hash = window.location.hash
  const qs = hash.indexOf('?')
  if (qs < 0) return false
  return truthy(new URLSearchParams(hash.slice(qs + 1)).get('embed'))
}
