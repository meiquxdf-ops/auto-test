<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getApiBase, getApiBaseLabel, setApiBase } from '@/api/http'
import { SSE_STATE_TEXT } from '@/api/sse'
import { useAgents } from '@/stores/agents'
import { fromNow } from '@/utils/format'

const route = useRoute()
const { agents, sseState, lastUpdatedAt, reconnect, refresh } = useAgents()

const collapsed = ref(localStorage.getItem('nat.navCollapsed') === '1')
function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('nat.navCollapsed', collapsed.value ? '1' : '0')
}

const navItems = [
  { key: 'dashboard', to: '/dashboard', label: '总览', icon: 'Odometer', desc: '集群健康与近期事件' },
  { key: 'agents', to: '/agents', label: '机器', icon: 'Monitor', desc: 'Agent 状态与运维操作' },
  { key: 'tasks', to: '/tasks', label: '任务队列', icon: 'Tickets', desc: '创建、排序、取消、重跑' },
  { key: 'timeline', to: '/timeline', label: '时间线', icon: 'Histogram', desc: 'agent / server 事件对账' },
  { key: 'playground', to: '/playground', label: '测试下发', icon: 'MagicStick', desc: '单机实验与实时跟日志' },
]

const activeNav = computed(() => (route.meta.nav as string | undefined) ?? '')
const pageTitle = computed(() => (route.meta.title as string | undefined) ?? '')

const onlineCount = computed(() => agents.value.filter((a) => a.status === 'online' || a.status === 'busy').length)

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
  <div class="shell" :class="{ 'shell--collapsed': collapsed }">
    <aside class="side">
      <div class="side__brand">
        <div class="side__logo">AT</div>
        <div v-show="!collapsed" class="side__brand-text">
          <div class="side__brand-title">测试执行平台</div>
          <div class="side__brand-sub">new-auto-test 运维台</div>
        </div>
      </div>

      <nav class="side__nav">
        <router-link
          v-for="item in navItems"
          :key="item.key"
          :to="item.to"
          class="side__item"
          :class="{ 'is-active': activeNav === item.key }"
        >
          <el-tooltip :disabled="!collapsed" :content="item.label" placement="right">
            <span class="side__item-icon">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
          </el-tooltip>
          <span v-show="!collapsed" class="side__item-body">
            <span class="side__item-label">{{ item.label }}</span>
            <span class="side__item-desc">{{ item.desc }}</span>
          </span>
        </router-link>
      </nav>

      <div class="side__foot">
        <div v-show="!collapsed" class="side__stat">
          <span class="side__stat-dot" :class="`is-${stateType}`" />
          <span>实时通道 {{ SSE_STATE_TEXT[sseState] }}</span>
        </div>
        <div v-show="!collapsed" class="side__stat side__stat--muted">
          在线 {{ onlineCount }} / {{ agents.length }} 台 · {{ fromNow(lastUpdatedAt) }}
        </div>
        <button class="side__collapse" @click="toggleCollapse">
          <el-icon><component :is="collapsed ? 'DArrowRight' : 'DArrowLeft'" /></el-icon>
          <span v-show="!collapsed">收起侧栏</span>
        </button>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar__left">
          <h1 class="topbar__title">{{ pageTitle }}</h1>
        </div>
        <div class="topbar__right">
          <el-tag :type="stateType" size="small" effect="light" round>
            实时通道：{{ SSE_STATE_TEXT[sseState] }}
          </el-tag>
          <el-button v-if="sseState !== 'open'" size="small" text type="primary" @click="reconnect">
            <el-icon><Refresh /></el-icon>
            重连
          </el-button>
          <el-divider direction="vertical" />
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

    <el-dialog v-model="settingVisible" title="接口地址" width="520px">
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

.side {
  width: 216px;
  flex: none;
  background: var(--nat-nav-bg);
  color: var(--nat-nav-text);
  display: flex;
  flex-direction: column;
  transition: width 0.18s ease;
}

.shell--collapsed .side {
  width: 62px;
}

.side__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.side__logo {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #4c9aff, #2563eb);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.5px;
}

.side__brand-title {
  color: #fff;
  font-size: 14px;
  font-weight: 620;
  line-height: 1.3;
}

.side__brand-sub {
  font-size: 11px;
  color: #7c8ba1;
  margin-top: 2px;
}

.side__nav {
  flex: 1;
  padding: 10px 8px;
  overflow-y: auto;
}

.side__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  color: var(--nat-nav-text);
  text-decoration: none;
  margin-bottom: 2px;
  transition: background 0.15s, color 0.15s;
}

.side__item:hover {
  background: var(--nat-nav-bg-soft);
  color: #fff;
}

.side__item.is-active {
  background: rgba(76, 154, 255, 0.16);
  color: #fff;
  box-shadow: inset 2px 0 0 var(--nat-nav-active);
}

.side__item-icon {
  width: 24px;
  display: flex;
  justify-content: center;
  font-size: 16px;
  flex: none;
}

.side__item-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.side__item-label {
  font-size: 13.5px;
  font-weight: 540;
  line-height: 1.35;
}

.side__item-desc {
  font-size: 11px;
  color: #6f7f95;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.side__item.is-active .side__item-desc {
  color: #9db6da;
}

.side__foot {
  padding: 10px 12px 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
  font-size: 11.5px;
}

.side__stat {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
  color: #a7b5c8;
}

.side__stat--muted {
  color: #6f7f95;
}

.side__stat-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #8b949e;
  flex: none;
}

.side__stat-dot.is-success {
  background: #34d399;
  box-shadow: 0 0 0 3px rgba(52, 211, 153, 0.16);
}

.side__stat-dot.is-warning {
  background: #fbbf24;
  box-shadow: 0 0 0 3px rgba(251, 191, 36, 0.16);
}

.side__collapse {
  margin-top: 8px;
  width: 100%;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.09);
  color: #94a3b8;
  border-radius: 7px;
  padding: 6px 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 11.5px;
}

.side__collapse:hover {
  background: var(--nat-nav-bg-soft);
  color: #fff;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 52px;
  flex: none;
  background: #fff;
  border-bottom: 1px solid var(--nat-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  gap: 16px;
}

.topbar__title {
  font-size: 15px;
  font-weight: 620;
  margin: 0;
}

.topbar__right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.content {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.mb12 {
  margin-bottom: 12px;
}
</style>
