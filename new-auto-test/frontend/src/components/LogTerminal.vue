<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { LogLine } from '@/api/types'
import { copyText, formatBytes, formatClock } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    lines: LogLine[]
    /** 服务端只保留末 5MB，头部被丢弃 */
    truncated?: boolean
    droppedBytes?: number
    totalBytes?: number
    /** 前端行数上限丢弃的行数 */
    clientTrimmed?: number
    /** 日志区高度；传 fill 时撑满外层容器 */
    height?: string
    loading?: boolean
    /** 拉取日志失败时的提示，直接显示在终端里 */
    errorText?: string
    /** 底部状态条右侧的补充说明 */
    footNote?: string
    emptyText?: string
    fileName?: string
  }>(),
  {
    truncated: false,
    droppedBytes: undefined,
    totalBytes: undefined,
    clientTrimmed: 0,
    height: '520px',
    loading: false,
    errorText: '',
    footNote: '',
    emptyText: '还没有日志输出',
    fileName: 'execution.log',
  },
)

const emit = defineEmits<{ (e: 'retry'): void }>()

const LINE_HEIGHT = 19
const OVERSCAN = 14
const WRAP_OVERSCAN = 6
/** 单次渲染的行数上限，防止窄视口 + 超长行把 DOM 撑爆 */
const MAX_WINDOW = 600
/** 时间戳列固定 8 个字符宽 */
const TS_CHARS = 8
/** 量字符宽度用的样本 */
const PROBE_CHARS = 50
const PROBE_TEXT = '0'.repeat(PROBE_CHARS)

const scroller = ref<HTMLDivElement | null>(null)
const probe = ref<HTMLSpanElement | null>(null)
const autoScroll = ref(true)
const wrap = ref(false)
const showTime = ref(false)
const keyword = ref('')
const scrollTop = ref(0)
const viewportHeight = ref(400)
const viewportWidth = ref(800)
const charWidth = ref(7.2)

const fill = computed(() => props.height === 'fill')

const filtered = computed<LogLine[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.lines
  return props.lines.filter((l) => l.text.toLowerCase().includes(kw))
})

const hiddenByFilter = computed(() => props.lines.length - filtered.value.length)

const gutterWidth = computed(() => {
  const max = props.lines.length ? props.lines[props.lines.length - 1].seq + 1 : 1
  return Math.max(44, String(max).length * 8 + 22)
})

const tsWidth = computed(() =>
  showTime.value ? Math.ceil(TS_CHARS * charWidth.value) + 10 : 0,
)

/** 全角按 2 列估宽，只算一次并挂在行对象上 */
const widthUnits = new WeakMap<LogLine, number>()
function visualLen(line: LogLine): number {
  let u = widthUnits.get(line)
  if (u === undefined) {
    const t = line.text
    u = 0
    for (let i = 0; i < t.length; i += 1) u += t.charCodeAt(i) > 0x2e7f ? 2 : 1
    widthUnits.set(line, u)
  }
  return u
}

/* ------------------------------------------------------------ 最宽行（撑住 scrollWidth）
 * 虚拟窗口换一批行时，若 scrollWidth 跟着变小，浏览器会把 scrollLeft 夹回 0。
 * 用一个隐藏的等宽 sizer 常驻最宽的那一行，横向滚动位置就不会被重置。 */
const widestText = ref('')
let widestUnits = 0
let scannedSeq = -1
let scannedCount = 0

function scanWidest(list: LogLine[]) {
  if (list.length < scannedCount) {
    widestUnits = 0
    widestText.value = ''
    scannedSeq = -1
  }
  let text = widestText.value
  for (let i = list.length - 1; i >= 0 && list[i].seq > scannedSeq; i -= 1) {
    const u = visualLen(list[i])
    if (u > widestUnits) {
      widestUnits = u
      text = list[i].text
    }
  }
  scannedCount = list.length
  if (list.length) scannedSeq = Math.max(scannedSeq, list[list.length - 1].seq)
  if (text !== widestText.value) widestText.value = text
}

watch(() => props.lines, scanWidest, { immediate: true })

/* ------------------------------------------------------------ 换行模式的估算高度
 * 换行时每行占几个视觉行不固定，用「字符数 / 每行字符数」估算并做前缀和，
 * 这样换行模式依然是虚拟滚动，不会把几千行真实 DOM 全塞进去。 */
const charsPerRow = computed(() => {
  const usable = viewportWidth.value - gutterWidth.value - tsWidth.value - 16
  return Math.max(16, Math.floor(usable / Math.max(4, charWidth.value)))
})

let wrapKey = ''
let wrapFirstSeq = -1
let wrapCount = 0
let wrapPrefix: number[] = [0]

const wrapGeom = computed<{ prefix: number[]; rows: number } | null>(() => {
  if (!wrap.value) return null
  const list = filtered.value
  const cpr = charsPerRow.value
  const key = `${cpr}|${keyword.value.trim().toLowerCase()}`
  // 关键字/列宽变了，或者头部被裁过，缓存的前缀和就不能再续用
  const stale =
    key !== wrapKey ||
    list.length < wrapCount ||
    (list.length > 0 && wrapFirstSeq !== -1 && list[0].seq !== wrapFirstSeq)
  if (stale) {
    wrapKey = key
    wrapCount = 0
    wrapPrefix = [0]
  }
  for (let i = wrapCount; i < list.length; i += 1) {
    wrapPrefix.push(wrapPrefix[i] + Math.max(1, Math.ceil(visualLen(list[i]) / cpr)))
  }
  wrapCount = list.length
  wrapFirstSeq = list.length ? list[0].seq : -1
  return { prefix: wrapPrefix, rows: wrapPrefix[wrapPrefix.length - 1] }
})

/** 前缀和里找最后一个 prefix[i] <= row 的 i */
function rowToIndex(prefix: number[], row: number): number {
  let lo = 0
  let hi = prefix.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (prefix[mid] <= row) lo = mid
    else hi = mid - 1
  }
  return lo
}

const totalHeight = computed(
  () => (wrapGeom.value ? wrapGeom.value.rows : filtered.value.length) * LINE_HEIGHT,
)

const startIndex = computed(() => {
  const geom = wrapGeom.value
  const top = Math.floor(scrollTop.value / LINE_HEIGHT)
  if (geom) return Math.max(0, rowToIndex(geom.prefix, top) - WRAP_OVERSCAN)
  return Math.max(0, top - OVERSCAN)
})

const endIndex = computed(() => {
  const geom = wrapGeom.value
  const total = filtered.value.length
  const bottom = Math.ceil((scrollTop.value + viewportHeight.value) / LINE_HEIGHT)
  const raw = geom
    ? rowToIndex(geom.prefix, bottom) + 1 + WRAP_OVERSCAN
    : bottom + OVERSCAN
  return Math.min(total, raw, startIndex.value + MAX_WINDOW)
})

const visibleLines = computed(() => filtered.value.slice(startIndex.value, endIndex.value))

const offsetY = computed(() => {
  const geom = wrapGeom.value
  return (geom ? geom.prefix[startIndex.value] ?? 0 : startIndex.value) * LINE_HEIGHT
})

/* ------------------------------------------------------------ 截断提示（统一一条） */
const noticeText = computed(() => {
  const parts: string[] = []
  if (props.truncated) {
    parts.push(
      props.droppedBytes
        ? `服务端保留末 5MB，已丢弃开头 ${formatBytes(props.droppedBytes)}`
        : '服务端保留末 5MB',
    )
  }
  if (props.clientTrimmed) {
    parts.push(`前端已丢弃开头 ${props.clientTrimmed.toLocaleString('en-US')} 行`)
  }
  if (!parts.length) return ''
  return `第一行不是进程首行输出 · ${parts.join(' · ')}`
})

function segments(text: string): { text: string; hit: boolean }[] {
  const kw = keyword.value.trim()
  if (!kw) return [{ text, hit: false }]
  const lower = text.toLowerCase()
  const target = kw.toLowerCase()
  const out: { text: string; hit: boolean }[] = []
  let i = 0
  while (i < text.length) {
    const idx = lower.indexOf(target, i)
    if (idx === -1) {
      out.push({ text: text.slice(i), hit: false })
      break
    }
    if (idx > i) out.push({ text: text.slice(i, idx), hit: false })
    out.push({ text: text.slice(idx, idx + kw.length), hit: true })
    i = idx + kw.length
  }
  return out
}

function onScroll() {
  const el = scroller.value
  if (!el) return
  scrollTop.value = el.scrollTop
  const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24
  // 用户往上翻就自动停掉跟随，回到底部再恢复
  if (!atBottom && autoScroll.value) autoScroll.value = false
  else if (atBottom && !autoScroll.value) autoScroll.value = true
}

function scrollToBottom(force = false) {
  const el = scroller.value
  if (!el) return
  if (!force && !autoScroll.value) return
  el.scrollTop = el.scrollHeight
  scrollTop.value = el.scrollTop
}

function scrollToTop() {
  const el = scroller.value
  if (!el) return
  autoScroll.value = false
  el.scrollTop = 0
  scrollTop.value = 0
}

function jumpBottom() {
  autoScroll.value = true
  void nextTick(() => scrollToBottom(true))
}

watch(
  () => props.lines.length,
  () => {
    void nextTick(() => scrollToBottom())
  },
)

watch([wrap, showTime], () => {
  void nextTick(() => scrollToBottom(autoScroll.value))
})

watch(keyword, () => {
  void nextTick(() => {
    const el = scroller.value
    if (el) scrollTop.value = el.scrollTop
  })
})

function measure() {
  const el = scroller.value
  if (el) {
    viewportHeight.value = el.clientHeight
    viewportWidth.value = el.clientWidth
  }
  const p = probe.value
  if (p) {
    const w = p.getBoundingClientRect().width / PROBE_CHARS
    if (w > 1) charWidth.value = w
  }
}

let ro: ResizeObserver | null = null
onMounted(() => {
  measure()
  const el = scroller.value
  if (el) {
    ro = new ResizeObserver(measure)
    ro.observe(el)
  }
  // 等宽字体异步加载完宽度会变，重新量一次
  void document.fonts?.ready?.then(measure).catch(() => {})
  scrollToBottom(true)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
})

function plainText(): string {
  return filtered.value.map((l) => l.text).join('\n')
}

async function onCopy() {
  const text = plainText()
  if (!text) {
    ElMessage.warning('没有可复制的日志')
    return
  }
  const ok = await copyText(text)
  ElMessage[ok ? 'success' : 'error']({
    message: ok ? `已复制 ${filtered.value.length} 行` : '复制失败，浏览器可能限制了剪贴板',
    duration: 1800,
  })
}

function onDownload() {
  const text = plainText()
  if (!text) {
    ElMessage.warning('没有可下载的日志')
    return
  }
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = props.fileName
  a.click()
  URL.revokeObjectURL(url)
}

defineExpose({ jumpBottom, scrollToBottom })
</script>

<template>
  <div class="term" :class="{ 'term--fill': fill }">
    <div class="term__bar">
      <div class="term__bar-left">
        <span class="term__count">{{ lines.length }} 行</span>
        <span v-if="totalBytes" class="term__count">{{ formatBytes(totalBytes) }}</span>
        <span v-if="keyword.trim()" class="term__count term__count--hi">
          命中 {{ filtered.length }} · 隐藏 {{ hiddenByFilter }}
        </span>
        <slot name="bar" />
      </div>
      <div class="term__bar-right">
        <input v-model="keyword" class="term__search" placeholder="过滤关键字" spellcheck="false" />
        <label class="term__toggle">
          <input v-model="autoScroll" type="checkbox" />
          跟随
        </label>
        <label class="term__toggle">
          <input v-model="wrap" type="checkbox" />
          换行
        </label>
        <label class="term__toggle">
          <input v-model="showTime" type="checkbox" />
          时间
        </label>
        <button class="term__btn" @click="scrollToTop">顶部</button>
        <button class="term__btn" @click="jumpBottom">最新</button>
        <button class="term__btn" @click="onCopy">复制</button>
        <button class="term__btn" @click="onDownload">下载</button>
      </div>
    </div>

    <div v-if="errorText && lines.length" class="term__notice term__notice--err">
      <span class="term__notice-text">{{ errorText }}</span>
      <button class="term__btn" @click="emit('retry')">重试</button>
    </div>

    <div v-if="noticeText && lines.length" class="term__notice">{{ noticeText }}</div>

    <div
      ref="scroller"
      class="term__body"
      :class="{ 'term__body--wrap': wrap }"
      :style="fill ? undefined : { height }"
      @scroll.passive="onScroll"
    >
      <span class="term__probe" aria-hidden="true">
        <span ref="probe" class="term__probe-inner">{{ PROBE_TEXT }}</span>
      </span>

      <div v-if="loading && !lines.length" class="term__placeholder">日志加载中…</div>
      <div v-else-if="errorText && !lines.length" class="term__placeholder">
        <span class="term__placeholder-err">{{ errorText }}</span>
        <button class="term__btn" @click="emit('retry')">重试</button>
      </div>
      <div v-else-if="!lines.length" class="term__placeholder">{{ emptyText }}</div>
      <div v-else-if="!filtered.length" class="term__placeholder">没有匹配「{{ keyword }}」的日志行</div>

      <template v-else>
        <div class="term__phantom" :style="{ height: `${totalHeight}px` }" />

        <div v-if="!wrap" class="term__sizer" aria-hidden="true">
          <span class="term__sizer-pad" :style="{ width: `${gutterWidth + tsWidth}px` }" />{{ widestText }}
        </div>

        <div
          class="term__viewport"
          :class="{ 'term__viewport--wrap': wrap }"
          :style="{ transform: `translateY(${offsetY}px)` }"
        >
          <div
            v-for="line in visibleLines"
            :key="line.seq"
            class="term__line"
            :class="{ 'is-err': line.stream === 'stderr' || line.stream === '2' }"
          >
            <span class="term__gutter" :style="{ width: `${gutterWidth}px` }">{{ line.seq + 1 }}</span>
            <span v-if="showTime" class="term__ts" :style="{ left: `${gutterWidth}px` }">
              {{ formatClock(line.ts) }}
            </span>
            <span class="term__text">
              <template v-for="(seg, i) in segments(line.text)" :key="i">
                <mark v-if="seg.hit" class="term__hit">{{ seg.text }}</mark>
                <template v-else>{{ seg.text }}</template>
              </template>
            </span>
          </div>
        </div>
      </template>
    </div>

    <div class="term__foot">
      <span :class="autoScroll ? 'is-on' : 'is-off'">
        {{ autoScroll ? '跟随最新输出' : '已暂停跟随，滚到底部恢复' }}
      </span>
      <span class="term__foot-right">
        <slot name="foot">{{ footNote }}</slot>
      </span>
    </div>
  </div>
</template>

<style scoped>
.term {
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #10151d;
  background: #0d1117;
  display: flex;
  flex-direction: column;
}

.term--fill {
  height: 100%;
}

.term--fill .term__body {
  flex: 1;
  min-height: 0;
}

.term--fill .term__bar,
.term--fill .term__notice,
.term--fill .term__foot {
  flex: none;
}

.term__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 10px;
  background: #161b22;
  border-bottom: 1px solid #21262d;
  flex-wrap: wrap;
}

.term__bar-left,
.term__bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.term__count {
  color: #8b949e;
  font-size: 11.5px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.term__count--hi {
  color: #d29922;
}

.term__search {
  background: #0d1117;
  border: 1px solid #30363d;
  color: #c9d1d9;
  border-radius: 6px;
  height: 24px;
  padding: 0 8px;
  font-size: 12px;
  width: 140px;
  outline: none;
}

.term__search:focus {
  border-color: #388bfd;
}

.term__toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #8b949e;
  font-size: 11.5px;
  cursor: pointer;
  user-select: none;
}

.term__toggle input {
  accent-color: #388bfd;
  cursor: pointer;
  margin: 0;
}

.term__btn {
  flex: none;
  background: #21262d;
  border: 1px solid #30363d;
  color: #c9d1d9;
  border-radius: 6px;
  height: 24px;
  padding: 0 9px;
  font-size: 11.5px;
  cursor: pointer;
}

.term__btn:hover {
  background: #30363d;
  border-color: #444c56;
}

/* 截断 / 错误提示：跟在工具条下面，不随日志滚动 */
.term__notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 12px;
  background: #2b2413;
  border-bottom: 1px solid #4a3c17;
  color: #e3b341;
  font-size: 11.5px;
}

.term__notice--err {
  background: #2c1618;
  border-bottom-color: #5c2b30;
  color: #ff9a94;
}

.term__notice-text {
  min-width: 0;
  overflow-wrap: anywhere;
}

.term__body {
  position: relative;
  overflow: auto;
  min-height: 220px;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
  line-height: 19px;
  color: #c9d1d9;
  background: #0d1117;
  padding: 6px 0;
}

/* 量一个字符的宽度，自身不占空间 */
.term__probe {
  position: absolute;
  top: 0;
  left: 0;
  display: block;
  width: 0;
  height: 0;
  overflow: hidden;
  visibility: hidden;
  pointer-events: none;
}

.term__probe-inner {
  display: inline-block;
  white-space: pre;
}

.term__phantom {
  width: 1px;
}

/* 常驻最宽一行，锁住 scrollWidth */
.term__sizer {
  position: absolute;
  top: 0;
  left: 0;
  height: 1px;
  width: max-content;
  padding-right: 12px;
  white-space: pre;
  visibility: hidden;
  pointer-events: none;
}

.term__sizer-pad {
  display: inline-block;
}

.term__viewport {
  position: absolute;
  top: 6px;
  left: 0;
  width: max-content;
  min-width: 100%;
}

.term__viewport--wrap {
  width: 100%;
}

.term__line {
  display: flex;
  align-items: flex-start;
  min-width: 100%;
  min-height: 19px;
  padding: 0 12px 0 0;
  white-space: pre;
}

.term__body--wrap .term__line {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.term__line:hover {
  background: rgba(110, 118, 129, 0.12);
}

.term__line.is-err .term__text {
  color: #ff7b72;
}

/* 横向滚动时行号列钉在左边，背景不透明才不会串字 */
.term__gutter {
  position: sticky;
  left: 0;
  z-index: 1;
  flex: none;
  text-align: right;
  padding-right: 12px;
  color: #4d5766;
  background: #0d1117;
  user-select: none;
}

.term__ts {
  position: sticky;
  z-index: 1;
  flex: none;
  color: #6e7681;
  padding-right: 10px;
  background: #0d1117;
}

.term__line:hover .term__gutter,
.term__line:hover .term__ts {
  background: #171c24;
}

.term__text {
  flex: 1;
  min-width: 0;
}

.term__hit {
  background: #d29922;
  color: #0d1117;
  border-radius: 2px;
}

.term__placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 20px 16px;
  text-align: center;
  color: #6e7681;
  font-size: 12.5px;
}

.term__placeholder-err {
  color: #ff9a94;
  max-width: 520px;
  overflow-wrap: anywhere;
}

.term__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 12px;
  background: #161b22;
  border-top: 1px solid #21262d;
  font-size: 11.5px;
  color: #8b949e;
  flex-wrap: wrap;
}

.term__foot .is-on {
  color: #3fb950;
}

.term__foot .is-off {
  color: #d29922;
}

.term__foot-right {
  color: #8b949e;
}
</style>
