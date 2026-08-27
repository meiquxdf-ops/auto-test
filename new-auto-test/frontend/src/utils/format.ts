function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

export function formatTime(ts?: number | null): string {
  if (!ts) return '-'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '-'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function formatFullTime(ts?: number | null): string {
  if (!ts) return '-'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '-'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`
}

export function formatClock(ts?: number | null): string {
  if (!ts) return '--:--:--'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '--:--:--'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function fromNow(ts?: number | null): string {
  if (!ts) return '-'
  const diff = Date.now() - ts
  if (diff < 0) return '刚刚'
  const s = Math.floor(diff / 1000)
  if (s < 5) return '刚刚'
  if (s < 60) return `${s} 秒前`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return formatTime(ts)
}

export function formatDuration(ms?: number | null): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return '-'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rs = s % 60
  if (m < 60) return `${m}m${rs ? ` ${rs}s` : ''}`
  const h = Math.floor(m / 60)
  const rm = m % 60
  return `${h}h${rm ? ` ${rm}m` : ''}`
}

export function durationBetween(start?: number | null, end?: number | null): string {
  if (!start) return '-'
  return formatDuration((end ?? Date.now()) - start)
}

export function formatBytes(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return '-'
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(2)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

export function shortId(id?: string | null, head = 8): string {
  if (!id) return '-'
  return id.length <= head + 4 ? id : `${id.slice(0, head)}…${id.slice(-4)}`
}

export function truncateText(text: string, max = 80): string {
  if (!text) return ''
  return text.length <= max ? text : `${text.slice(0, max)}…`
}

export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 走降级 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
