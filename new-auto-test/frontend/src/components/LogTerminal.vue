<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { LogLine } from '@/api/types'
import { copyText, formatBytes, formatClock } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    lines: LogLine[]
    /** 日志超过 5MB 被裁掉了头部 */
    truncated?: boolean
    droppedBytes?: number
    totalBytes?: number
    height?: string
    loading?: boolean
    /** 底部状态条右侧的补充说明 */
    footNote?: string
    emptyText?: string
    fileName?: string
  }>(),
  {
    truncated: false,
    height: '520px',
    loading: false,
    footNote: '',
    emptyText: '还没有日志输出',
    fileName: 'execution.log',
  },
)

const LINE_HEIGHT = 19
const OVERSCAN = 14
/** 换行模式下不做虚拟滚动，改为只渲染尾部分片 */
const WRAP_TAIL = 3000

const scroller = ref<HTMLDivElement | null>(null)
const autoScroll = ref(true)
const wrap = ref(false)
const showTime = ref(false)
const keyword = ref('')
const scrollTop = ref(0)
const viewportHeight = ref(400)

const filtered = computed<LogLine[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.lines
  return props.lines.filter((l) => l.text.toLowerCase().includes(kw))
})

const hiddenByFilter = computed(() => props.lines.length - filtered.value.length)

const totalHeight = computed(() => filtered.value.length * LINE_HEIGHT)

const startIndex = computed(() =>
  Math.max(0, Math.floor(scrollTop.value / LINE_HEIGHT) - OVERSCAN),
)

const endIndex = computed(() =>
  Math.min(
    filtered.value.length,
    Math.ceil((scrollTop.value + viewportHeight.value) / LINE_HEIGHT) + OVERSCAN,
  ),
)

const visibleLines = computed(() => {
  if (wrap.value) {
    const list = filtered.value
    return list.length > WRAP_TAIL ? list.slice(list.length - WRAP_TAIL) : list
  }
  return filtered.value.slice(startIndex.value, endIndex.value)
})

const offsetY = computed(() => startIndex.value * LINE_HEIGHT)

const wrapTailHidden = computed(() =>
  wrap.value ? Math.max(0, filtered.value.length - WRAP_TAIL) : 0,
)

const gutterWidth = computed(() => {
  const max = props.lines.length ? props.lines[props.lines.length - 1].seq + 1 : 1
  return Math.max(44, String(max).length * 8 + 22)
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

watch(wrap, () => {
  void nextTick(() => scrollToBottom(autoScroll.value))
})

watch(keyword, () => {
  void nextTick(() => {
    const el = scroller.value
    if (el) {
      scrollTop.value = el.scrollTop
    }
  })
})

let ro: ResizeObserver | null = null
onMounted(() => {
  const el = scroller.value
  if (!el) return
  viewportHeight.value = el.clientHeight
  ro = new ResizeObserver(() => {
    viewportHeight.value = el.clientHeight
  })
  ro.observe(el)
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
  <div class="term">
    <div class="term__bar">
      <div class="term__bar-left">
        <span class="term__dot term__dot--r" />
        <span class="term__dot term__dot--y" />
        <span class="term__dot term__dot--g" />
        <span class="term__count">{{ lines.length }} 行</span>
        <span v-if="totalBytes" class="term__count">{{ formatBytes(totalBytes) }}</span>
        <span v-if="keyword.trim()" class="term__count term__count--hi">
          过滤命中 {{ filtered.length }} 行，隐藏 {{ hiddenByFilter }} 行
        </span>
      </div>
      <div class="term__bar-right">
        <input v-model="keyword" class="term__search" placeholder="过滤关键字" spellcheck="false" />
        <label class="term__toggle">
          <input v-model="autoScroll" type="checkbox" />
          自动滚动
        </label>
        <label class="term__toggle">
          <input v-model="wrap" type="checkbox" />
          自动换行
        </label>
        <label class="term__toggle">
          <input v-model="showTime" type="checkbox" />
          时间戳
        </label>
        <button class="term__btn" title="回到顶部" @click="scrollToTop">顶部</button>
        <button class="term__btn" title="跳到最新" @click="jumpBottom">最新</button>
        <button class="term__btn" @click="onCopy">复制</button>
        <button class="term__btn" @click="onDownload">下载</button>
      </div>
    </div>

    <div v-if="truncated" class="term__banner">
      <el-icon><WarnTriangleFilled /></el-icon>
      <span>
        日志已截断：单次执行仅保留末 5MB
        <template v-if="droppedBytes"> ，已丢弃前 {{ formatBytes(droppedBytes) }} </template>
        。上方第一行不是进程的第一行输出。
      </span>
    </div>

    <div
      v-if="wrapTailHidden > 0"
      class="term__banner term__banner--soft"
    >
      <el-icon><InfoFilled /></el-icon>
      <span>换行模式下只渲染最近 {{ WRAP_TAIL }} 行（前面还有 {{ wrapTailHidden }} 行），关闭换行可查看全部。</span>
    </div>

    <div
      ref="scroller"
      class="term__body"
      :class="{ 'term__body--wrap': wrap }"
      :style="{ height }"
      @scroll.passive="onScroll"
    >
      <div v-if="loading && !lines.length" class="term__placeholder">日志加载中…</div>
      <div v-else-if="!lines.length" class="term__placeholder">{{ emptyText }}</div>
      <div v-else-if="!filtered.length" class="term__placeholder">没有匹配「{{ keyword }}」的日志行</div>

      <template v-else>
        <div v-if="!wrap" class="term__phantom" :style="{ height: `${totalHeight}px` }" />
        <div
          class="term__viewport"
          :class="{ 'term__viewport--static': wrap }"
          :style="wrap ? undefined : { transform: `translateY(${offsetY}px)` }"
        >
          <div
            v-for="line in visibleLines"
            :key="line.seq"
            class="term__line"
            :class="{ 'is-err': line.stream === 'stderr' || line.stream === '2' }"
          >
            <span class="term__gutter" :style="{ width: `${gutterWidth}px` }">{{ line.seq + 1 }}</span>
            <span v-if="showTime" class="term__ts">{{ formatClock(line.ts) }}</span>
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
        {{ autoScroll ? '● 正在跟随最新输出' : '○ 已暂停跟随（滚到底部自动恢复）' }}
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

.term__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 10px;
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

.term__dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}

.term__dot--r {
  background: #ff5f57;
}
.term__dot--y {
  background: #febc2e;
}
.term__dot--g {
  background: #28c840;
}

.term__count {
  color: #8b949e;
  font-size: 11.5px;
  margin-left: 4px;
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

.term__banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: linear-gradient(90deg, rgba(210, 153, 34, 0.22), rgba(210, 153, 34, 0.07));
  border-bottom: 1px solid rgba(210, 153, 34, 0.4);
  color: #f0c674;
  font-size: 12.5px;
  font-weight: 560;
}

.term__banner--soft {
  background: rgba(56, 139, 253, 0.12);
  border-bottom-color: rgba(56, 139, 253, 0.35);
  color: #79c0ff;
  font-weight: 460;
}

.term__body {
  position: relative;
  overflow: auto;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
  line-height: 19px;
  color: #c9d1d9;
  background: #0d1117;
  padding: 6px 0;
}

.term__phantom {
  width: 1px;
}

.term__viewport {
  position: absolute;
  top: 6px;
  left: 0;
  right: 0;
  will-change: transform;
}

.term__viewport--static {
  position: static;
}

.term__line {
  display: flex;
  align-items: flex-start;
  min-height: 19px;
  padding: 0 12px 0 0;
  white-space: pre;
}

.term__body--wrap .term__line {
  white-space: pre-wrap;
  word-break: break-all;
}

.term__line:hover {
  background: rgba(110, 118, 129, 0.12);
}

.term__line.is-err .term__text {
  color: #ff7b72;
}

.term__gutter {
  flex: none;
  text-align: right;
  padding-right: 12px;
  color: #4d5766;
  user-select: none;
}

.term__ts {
  flex: none;
  color: #6e7681;
  padding-right: 10px;
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
  color: #6e7681;
  padding: 26px 14px;
  text-align: center;
  font-size: 12.5px;
}

.term__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 12px;
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
