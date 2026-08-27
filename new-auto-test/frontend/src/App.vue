<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getApiBase, getApiBaseLabel, setApiBase } from '@/api/http'
import { SSE_STATE_TEXT } from '@/api/sse'
import { useAgents } from '@/stores/agents'
import { isEmbed } from '@/utils/embed'
import { fromNow } from '@/utils/format'

const route = useRoute()
const { agents, sseState, lastUpdatedAt, reconnect, refresh } = useAgents()

/** 嵌入宿主（iframe 或 embed=1）：去掉侧栏 / 顶栏，页面占满整个 iframe */
const embedded = computed(() => isEmbed(route.query))

/** 开放查询页（含嵌入）给 html 打标，让传送到 body 的确认框/下拉也走暗色 */
const openTheme = computed(() => embedded.value || route.meta.nav === 'open')

watch(
  [embedded, openTheme],
  ([embed, open]) => {
    document.documentElement.classList.toggle('nat-embed', embed)
    document.documentElement.classList.toggle('nat-open', open)
  },
  { immediate: true },
)

const COMPACT_QUERY = '(max-width: 1100px)'

/** 宽屏：侧栏收成图标条；窄屏：侧栏改为浮层抽屉 */
const collapsed = ref(localStorage.getItem('nat.navCollapsed') === '1')
const compact = ref(false)
const drawerOpen = ref(false)

const railCollapsed = computed(() => !compact.value && collapsed.value)

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('nat.navCollapsed', collapsed.value ? '1' : '0')
}

function closeDrawer() {
  drawerOpen.value = false
}

function applyCompact(matches: boolean) {
  compact.value = matches
  if (!matches) drawerOpen.value = false
}

function onMediaChange(e: MediaQueryListEvent) {
  applyCompact(e.matches)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') closeDrawer()
}

let media: MediaQueryList | null = null

onMounted(() => {
  media = window.matchMedia(COMPACT_QUERY)
  applyCompact(media.matches)
  media.addEventListener('change', onMediaChange)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', onMediaChange)
  window.removeEventListener('keydown', onKeydown)
  document.documentElement.classList.remove('nat-embed', 'nat-open')
})

watch(() => route.fullPath, closeDrawer)

const navItems = [
  { key: 'dashboard', to: '/dashboard', label: '总览', icon: 'Odometer' },
  { key: 'agents', to: '/agents', label: '机器', icon: 'Monitor' },
  { key: 'tasks', to: '/tasks', label: '任务队列', icon: 'Tickets' },
  { key: 'timeline', to: '/timeline', label: '时间线', icon: 'Histogram' },
  { key: 'playground', to: '/playground', label: '测试下发', icon: 'MagicStick' },
  { key: 'open', to: '/open', label: '开放查询', icon: 'Link' },
]

const activeNav = computed(() => (route.meta.nav as string | undefined) ?? '')

const onlineCount = computed(
  () => agents.value.filter((a) => a.status === 'online' || a.status === 'busy').length,
)

const stateType = computed(() => {
  switch (sseState.value) {
    case 'open':
      return 'success'
    case 'reconnecting':
    case 'connecting':
      return 'warning'
    default:
      return 'info'
  }
})

const settingVisible = ref(false)
const baseInput = ref('')
const dialogWidth = 'min(520px, calc(100vw - 32px))'

function openSetting() {
  baseInput.value = getApiBase()
  settingVisible.value = true
}
function saveSetting() {
  setApiBase(baseInput.value.trim() || null)
  settingVisible.value = false
  ElMessage.success('接口地址已更新，正在重新加载数据')
  void refresh()
  reconnect()
}
function resetSetting() {
  setApiBase(null)
  baseInput.value = getApiBase()
  ElMessage.success('已恢复默认接口地址')
  void refresh()
  reconnect()
}
</script>

<template>
  <div
    class="shell"
    :class="{
      'shell--rail': railCollapsed,
      'shell--compact': compact,
      'shell--embed': embedded,
      'is-open': compact && drawerOpen,
    }"
  >
    <aside v-if="!embedded" class="side" :aria-hidden="compact && !drawerOpen">
      <div class="side__brand">
        <span class="side__logo">AT</span>
        <span class="side__brand-text">测试执行平台</span>
        <button v-if="compact" class="icon-btn side__close" title="关闭导航" @click="closeDrawer">
          <el-icon><Close /></el-icon>
        </button>
      </div>

      <nav class="side__nav">
        <el-tooltip
          v-for="item in navItems"
          :key="item.key"
          :disabled="!railCollapsed"
          :content="item.label"
          placement="right"
          :show-after="200"
        >
          <router-link :to="item.to" class="side__item" :class="{ 'is-active': activeNav === item.key }">
            <span class="side__item-icon">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
            <span class="side__item-label">{{ item.label }}</span>
          </router-link>
        </el-tooltip>
      </nav>

      <div v-if="!compact" class="side__foot">
        <button class="side__collapse" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="toggleCollapse">
          <span class="side__item-icon">
            <el-icon><component :is="collapsed ? 'DArrowRight' : 'DArrowLeft'" /></el-icon>
          </span>
          <span class="side__item-label">收起侧栏</span>
        </button>
      </div>
    </aside>

    <div v-if="compact && !embedded" class="scrim" :class="{ 'is-on': drawerOpen }" @click="closeDrawer" />

    <div class="main">
      <header v-if="!embedded" class="topbar">
        <div class="topbar__left">
          <button v-if="compact" class="icon-btn" title="打开导航" @click="drawerOpen = true">
            <el-icon><Expand /></el-icon>
          </button>
          <span class="chan">
            <span class="chan__dot" :class="`is-${stateType}`" />
            <span class="chan__text">{{ SSE_STATE_TEXT[sseState] }}</span>
            <span class="chan__meta">
              在线 {{ onlineCount }}/{{ agents.length }} · {{ fromNow(lastUpdatedAt) }}
            </span>
          </span>
        </div>
        <div class="topbar__right">
          <el-button v-if="sseState !== 'open'" size="small" text type="primary" @click="reconnect">
            <el-icon><Refresh /></el-icon>
            重连
          </el-button>
          <el-button size="small" text @click="openSetting">
            <el-icon><Setting /></el-icon>
            接口地址
          </el-button>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <el-dialog v-model="settingVisible" title="接口地址" :width="dialogWidth">
      <el-alert type="info" :closable="false" show-icon class="mb12">
        当前生效：{{ getApiBaseLabel() }}
      </el-alert>
      <el-form label-width="88px">
        <el-form-item label="Server 地址">
          <el-input v-model="baseInput" placeholder="留空表示走 vite 代理 / 同源" clearable />
        </el-form-item>
      </el-form>
      <div class="muted" style="line-height: 1.7">
        开发态默认走 vite 代理（<code class="code-inline">/api → http://127.0.0.1:8080</code>）。
        跨机调试时可填写完整地址，例如 <code class="code-inline">http://10.0.0.12:8080</code>，需要 Server 放开 CORS。
      </div>
      <template #footer>
        <el-button @click="resetSetting">恢复默认</el-button>
        <el-button @click="settingVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSetting">保存并重连</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  overflow: hidden;
}

/* ------------------------------------------------------------- 侧栏 */

.side {
  width: 216px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--nat-nav-bg);
  color: var(--nat-nav-text);
  border-right: 1px solid var(--nat-border);
  overflow: hidden;
  transition: width 0.18s ease;
}

.shell--rail .side {
  width: 56px;
}

.side__brand {
  display: flex;
  align-items: center;
  height: 56px;
  flex: none;
  padding: 0 var(--nat-space-4);
  border-bottom: 1px solid var(--nat-border);
}

.side__logo {
  width: 24px;
  height: 24px;
  flex: none;
  margin-right: var(--nat-space-3);
  border-radius: var(--nat-radius-sm);
  background: var(--nat-accent);
  color: #fff;
  font-size: var(--nat-fs-12);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.side__brand-text {
  font-size: var(--nat-fs-15);
  font-weight: 600;
  color: var(--nat-text);
  white-space: nowrap;
}

.side__close {
  margin-left: auto;
}

.side__nav {
  flex: 1;
  min-height: 0;
  padding: var(--nat-space-2) 0;
  overflow-y: auto;
}

.side__item,
.side__collapse {
  display: flex;
  align-items: center;
  height: 36px;
  margin: 0 var(--nat-space-2) 2px;
  padding: 0 var(--nat-space-2);
  border: none;
  border-radius: var(--nat-radius);
  background: transparent;
  color: var(--nat-nav-text);
  font-family: inherit;
  font-size: var(--nat-fs-13);
  font-weight: 400;
  text-decoration: none;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.side__item:hover,
.side__collapse:hover {
  background: var(--nat-nav-bg-soft);
  color: var(--nat-text);
}

.side__item.is-active {
  background: var(--nat-accent-soft);
  color: var(--nat-nav-active);
  font-weight: 500;
}

/* 8px 外距 + 8px 内距 + 12px 半宽 = 28px，收起态图标正好落在 56px 侧栏中轴 */
.side__item-icon {
  width: 24px;
  flex: none;
  margin-right: var(--nat-space-3);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

/* 文字不换行；宽度动画期间用透明度过渡，避免 v-show 与 width 失步 */
.side__item-label,
.side__brand-text {
  white-space: nowrap;
  opacity: 1;
  transition: opacity 0.12s ease 0.08s;
}

.shell--rail .side__item-label,
.shell--rail .side__brand-text {
  opacity: 0;
  transition-delay: 0s;
}

.side__foot {
  flex: none;
  padding: var(--nat-space-2) 0;
  border-top: 1px solid var(--nat-border);
  color: var(--nat-text-weak);
}

.side__collapse {
  width: calc(100% - var(--nat-space-4));
  color: var(--nat-text-weak);
}

/* ------------------------------------------------------------- 窄屏抽屉 */

.shell--compact .side {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 21;
  width: 232px;
  transform: translateX(-100%);
  transition: transform 0.18s ease;
}

.shell--compact.is-open .side {
  transform: translateX(0);
}

.scrim {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(16, 20, 28, 0.32);
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.18s ease, visibility 0.18s;
}

.scrim.is-on {
  opacity: 1;
  visibility: visible;
}

/* ------------------------------------------------------------- 主区 */

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  min-height: 56px;
  flex: none;
  background: var(--nat-panel);
  border-bottom: 1px solid var(--nat-border);
  display: flex;
  align-items: center;
  gap: var(--nat-space-3);
  /* 右内边距 = 页面留白 24 + 滚动条留白 10 - 文字按钮自带 8，让操作与内容右边线对齐 */
  padding: 0 calc(var(--nat-space-5) + 2px) 0 var(--nat-space-5);
}

.topbar__left {
  display: flex;
  align-items: center;
  gap: var(--nat-space-3);
  min-width: 0;
  flex: 1 1 auto;
}

.topbar__right {
  display: flex;
  align-items: center;
  gap: var(--nat-space-1);
  margin-left: auto;
  flex: none;
}

.chan {
  display: flex;
  align-items: center;
  gap: var(--nat-space-2);
  min-width: 0;
  font-size: var(--nat-fs-13);
  color: var(--nat-text-sub);
}

.chan__dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--nat-text-weak);
}

.chan__dot.is-success {
  background: #16a34a;
}

.chan__dot.is-warning {
  background: #d97706;
}

.chan__text {
  flex: none;
  color: var(--nat-text);
  font-weight: 500;
}

.chan__meta {
  min-width: 0;
  color: var(--nat-text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.icon-btn {
  width: 32px;
  height: 32px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--nat-border);
  border-radius: var(--nat-radius);
  background: var(--nat-panel);
  color: var(--nat-text-sub);
  font-size: 16px;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.icon-btn:hover {
  background: var(--nat-nav-bg-soft);
  color: var(--nat-text);
}

.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

/* 嵌入态：侧栏 / 顶栏都不渲染，内容占满 iframe，不给滚动条预留空隙 */
.shell--embed .content {
  scrollbar-gutter: auto;
}

.mb12 {
  margin-bottom: var(--nat-space-3);
}

@media (max-width: 720px) {
  .topbar {
    padding: 0 calc(var(--nat-space-4) + 2px) 0 var(--nat-space-4);
  }
}

@media (max-width: 560px) {
  .chan__meta {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .side,
  .side__item-label,
  .side__brand-text,
  .scrim {
    transition: none;
  }
}
</style>
