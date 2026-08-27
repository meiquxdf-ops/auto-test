<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { cancelTask, rerunTask } from '@/api/tasks'
import { toastError, toastOk } from '@/api/http'
import { SSE_STATE_TEXT } from '@/api/sse'
import { useExecutionLog } from '@/composables/useExecutionLog'
import { durationBetween, formatFullTime } from '@/utils/format'
import { CONDITION_OPERATOR_LABEL, isTerminal } from '@/utils/status'
import CopyableId from '@/components/CopyableId.vue'
import EmptyState from '@/components/EmptyState.vue'
import LogTerminal from '@/components/LogTerminal.vue'
import StatusPill from '@/components/StatusPill.vue'

const route = useRoute()
const router = useRouter()

const executeId = computed(() => String(route.params.executeId ?? ''))

const {
  execution,
  status,
  lines,
  truncated,
  droppedBytes,
  totalBytes,
  clientTrimmed,
  loading,
  logLoading,
  error,
  sseState,
  finished,
  reload,
  reconnect,
} = useExecutionLog(executeId)

const acting = ref('')
const detailOpen = ref(true)
const lastLineOpen = ref(false)

const envEntries = computed(() => Object.entries(execution.value?.env ?? {}))

const duration = computed(() =>
  durationBetween(execution.value?.startedAt, execution.value?.finishedAt ?? null),
)

const machine = computed(() => execution.value?.displayTag || execution.value?.agentId || '')

const lastLine = computed(() => execution.value?.lastLine ?? '')
const lastLineLong = computed(() => lastLine.value.length > 120)

const judgement = computed(() => {
  const exec = execution.value
  if (!exec) return ''
  if (exec.conditionHit) return exec.conditionHit
  if (!isTerminal(exec.status)) return '执行尚未结束'
  if (!exec.conditionConfig) {
    return exec.exitCode === 0
      ? '未配置判定：exitCode == 0，判为 pass'
      : `未配置判定：exitCode == ${exec.exitCode ?? '未知'}，判为 fail`
  }
  return '按 conditionConfig 对最后一行匹配得出'
})

const sseTone = computed(() => {
  if (finished.value) return 'done'
  return sseState.value === 'open' ? 'live' : 'warn'
})

const logFootNote = computed(() => (finished.value ? '执行已结束' : 'SSE 断线自动续传'))

/* 日志是这个页面的主体：概要多高，日志就占掉剩下的首屏高度 */
const summaryRef = ref<HTMLElement | null>(null)
const logHost = ref<HTMLElement | null>(null)
const logHeight = ref('60vh')
/** 底部留一条缝，提示下面还有「执行明细」 */
const BOTTOM_GAP = 44

function syncLogHeight() {
  const host = logHost.value
  if (!host) return
  const scroller = host.closest('.content') as HTMLElement | null
  const available = scroller ? scroller.clientHeight : window.innerHeight
  const top = scroller
    ? host.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop
    : host.getBoundingClientRect().top
  logHeight.value = `${Math.max(320, Math.round(available - top - BOTTOM_GAP))}px`
}

let ro: ResizeObserver | null = null
onMounted(() => {
  syncLogHeight()
  ro = new ResizeObserver(() => syncLogHeight())
  if (summaryRef.value) ro.observe(summaryRef.value)
  window.addEventListener('resize', syncLogHeight)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
  window.removeEventListener('resize', syncLogHeight)
})

watch([execution, error], () => {
  void nextTick(() => {
    if (summaryRef.value && ro) ro.observe(summaryRef.value)
    syncLogHeight()
  })
})

async function onCancel() {
  const exec = execution.value
  if (!exec?.taskId) return
  try {
    await ElMessageBox.confirm('取消会杀掉该执行所属任务的进程组，结果判为 canceled。确认继续？', '取消执行', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  acting.value = 'cancel'
  try {
    await cancelTask(exec.taskId)
    toastOk('已下发取消指令')
    await reload()
  } catch (e) {
    toastError(e, '取消失败')
  } finally {
    acting.value = ''
  }
}

async function onRerun(mode: 'inplace' | 'new') {
  const exec = execution.value
  if (!exec?.taskId) return
  try {
    await ElMessageBox.confirm(
      mode === 'inplace'
        ? '原地重跑会清空该任务的执行记录与日志并重新入队，历史结果不可恢复。'
        : '将复制该任务生成一条新记录，原记录保留。',
      mode === 'inplace' ? '原地重跑' : '重跑为新任务',
      {
        type: mode === 'inplace' ? 'warning' : 'info',
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        confirmButtonClass: mode === 'inplace' ? 'el-button--danger' : '',
      },
    )
  } catch {
    return
  }
  acting.value = `rerun:${mode}`
  try {
    const created = await rerunTask(exec.taskId, mode)
    if (mode === 'inplace') {
      toastOk('已原地重跑')
      await reload()
    } else {
      toastOk('已创建新任务')
      const next = created?.executions?.[0]?.executeId
      if (next) void router.push(`/executions/${next}`)
      else void router.push('/tasks')
    }
  } catch (e) {
    toastError(e, '重跑失败')
  } finally {
    acting.value = ''
  }
}

watch(executeId, () => {
  acting.value = ''
  lastLineOpen.value = false
})
</script>

<template>
  <div class="page exec">
    <header class="exec__head">
      <nav class="exec__crumb">
        <button class="exec__back" @click="router.back()">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </button>
        <span class="exec__sep">/</span>
        <router-link to="/tasks" class="link-btn">任务队列</router-link>
        <span class="exec__sep">/</span>
        <span class="muted">执行详情</span>
      </nav>

      <div class="exec__title-row">
        <h2 class="exec__title"><CopyableId :value="executeId" :short="false" /></h2>
        <StatusPill
          v-if="execution"
          :status="status"
          size="large"
          :disconnected="execution.disconnected"
        />
        <div class="exec__actions">
          <el-button size="small" :icon="'Refresh'" :loading="loading" @click="reload">刷新</el-button>
          <el-button
            size="small"
            :icon="'Histogram'"
            @click="router.push({ path: '/timeline', query: { executeId } })"
          >
            时间线
          </el-button>
          <el-button
            v-if="execution?.taskId"
            size="small"
            type="danger"
            plain
            :disabled="isTerminal(status)"
            :loading="acting === 'cancel'"
            @click="onCancel"
          >
            取消
          </el-button>
          <el-dropdown v-if="execution?.taskId" trigger="click">
            <el-button size="small" type="primary" plain>
              重跑
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :disabled="!isTerminal(status)" @click="onRerun('inplace')">
                  原地重跑（清空记录）
                </el-dropdown-item>
                <el-dropdown-item @click="onRerun('new')">重跑为新记录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <EmptyState
      v-if="!execution && !loading && error"
      variant="error"
      title="找不到这条执行记录"
      :desc="error"
    >
      <el-button size="small" @click="reload">重试</el-button>
      <router-link to="/tasks"><el-button size="small" type="primary">回到任务队列</el-button></router-link>
    </EmptyState>

    <template v-else>
      <section ref="summaryRef" class="panel exec__sum">
        <el-skeleton v-if="loading && !execution" :rows="2" animated />
        <template v-else-if="execution">
          <dl class="facts">
            <div class="facts__i">
              <dt>退出码</dt>
              <dd class="mono" :class="{ 'is-bad': (execution.exitCode ?? 0) !== 0 }">
                {{ execution.exitCode ?? '-' }}
              </dd>
            </div>
            <div class="facts__i">
              <dt>耗时</dt>
              <dd class="mono">{{ duration }}</dd>
            </div>
            <div class="facts__i">
              <dt>机器</dt>
              <dd>
                <router-link
                  v-if="machine"
                  class="link-btn"
                  :to="{ path: '/agents', query: { focus: machine } }"
                >
                  {{ machine }}
                </router-link>
                <span v-else class="muted">未分配</span>
              </dd>
            </div>
            <div class="facts__i">
              <dt>开始</dt>
              <dd class="mono">{{ formatFullTime(execution.startedAt) }}</dd>
            </div>
          </dl>

          <div class="cmd-box">
            <span class="cmd-box__label">命令</span>
            <pre class="cmd-box__pre mono">{{ execution.command || '-' }}</pre>
          </div>

          <p v-if="execution.message" class="note">{{ execution.message }}</p>
          <p v-if="execution.disconnected && !isTerminal(status)" class="note">
            机器失联中，仍属 running；租约到期且对账确认进程不在时判为 exception。
          </p>
        </template>
      </section>

      <div ref="logHost" class="exec__log" :style="{ height: logHeight }">
        <LogTerminal
          :lines="lines"
          :truncated="truncated"
          :dropped-bytes="droppedBytes"
          :total-bytes="totalBytes"
          :client-trimmed="clientTrimmed"
          :loading="logLoading"
          :error-text="error"
          height="fill"
          :file-name="`${executeId}.log`"
          :foot-note="logFootNote"
          @retry="reload"
        >
          <template #bar>
            <span class="sse" :class="`sse--${sseTone}`">
              {{ finished ? '已结束' : SSE_STATE_TEXT[sseState] }}
            </span>
            <button v-if="!finished && sseState !== 'open'" class="sse__btn" @click="reconnect">
              重连
            </button>
          </template>
        </LogTerminal>
      </div>

      <section v-if="execution" class="panel">
        <div class="panel__head">
          <div class="panel__title">执行明细</div>
          <button class="link-btn" @click="detailOpen = !detailOpen">
            {{ detailOpen ? '收起' : '展开' }}
          </button>
        </div>
        <div v-show="detailOpen" class="panel__body">
          <div class="meta-grid">
            <div class="kv">
              <span class="kv__k">agentId</span>
              <span class="kv__v"><CopyableId :value="execution.agentId" :head="12" /></span>
              <span class="kv__k">taskId</span>
              <span class="kv__v"><CopyableId :value="execution.taskId" :head="12" /></span>
              <span class="kv__k">dispatchToken</span>
              <span class="kv__v"><CopyableId :value="execution.dispatchToken" :head="12" /></span>
              <span class="kv__k">operator</span>
              <span class="kv__v">{{ execution.operator || '-' }}</span>
            </div>

            <div class="kv">
              <span class="kv__k">cwd</span>
              <span class="kv__v mono">{{ execution.cwd || '（Agent 默认）' }}</span>
              <span class="kv__k">超时</span>
              <span class="kv__v">{{ execution.timeoutSec ? `${execution.timeoutSec}s` : '-' }}</span>
              <span class="kv__k">创建</span>
              <span class="kv__v mono">{{ formatFullTime(execution.createdAt) }}</span>
              <span class="kv__k">结束</span>
              <span class="kv__v mono">{{ formatFullTime(execution.finishedAt) }}</span>
            </div>

            <div class="kv">
              <span class="kv__k">判定依据</span>
              <span class="kv__v">{{ judgement }}</span>
            </div>
          </div>

          <div class="block">
            <div class="block__k">最后一行</div>
            <pre class="block__v mono" :class="{ 'is-clamp': !lastLineOpen }">{{ lastLine || '-' }}</pre>
            <button v-if="lastLineLong" class="link-btn" @click="lastLineOpen = !lastLineOpen">
              {{ lastLineOpen ? '收起' : '展开' }}
            </button>
          </div>

          <div v-if="envEntries.length" class="block">
            <div class="block__k">环境变量</div>
            <div class="chips">
              <code v-for="[k, v] in envEntries" :key="k" class="code-inline chip">{{ k }}={{ v }}</code>
            </div>
          </div>

          <div v-if="execution.conditionConfig" class="block">
            <div class="block__k">判定配置</div>
            <div class="chips">
              <span v-for="(rule, i) in execution.conditionConfig.rules" :key="i" class="rule">
                {{ i + 1 }}. {{ CONDITION_OPERATOR_LABEL[rule.operator] }}
                <code class="code-inline chip">{{ rule.value }}</code>
                → <StatusPill :status="rule.status" />
              </span>
              <span v-if="execution.conditionConfig.other" class="rule">
                other → <StatusPill :status="execution.conditionConfig.other" />
              </span>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* 页头贴顶，日志区在首屏就能看到 */
.exec__head {
  position: sticky;
  top: 0;
  z-index: 6;
  margin: -18px -22px 12px;
  padding: 12px 22px 10px;
  background: var(--nat-bg);
  border-bottom: 1px solid var(--nat-border);
}

.exec__crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 4px;
}

.exec__back {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  color: var(--nat-accent);
  cursor: pointer;
}

.exec__back:hover {
  text-decoration: underline;
}

.exec__sep {
  color: var(--nat-text-weak);
}

.exec__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.exec__title {
  margin: 0;
  min-width: 0;
  font-size: 17px;
  font-weight: 650;
}

.exec__title :deep(.copyable__text) {
  font-size: 16px;
  font-weight: 600;
  color: var(--nat-text);
  letter-spacing: 0.2px;
}

.exec__title :deep(.copyable__btn) {
  width: 18px;
  height: 18px;
}

.exec__actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.exec__sum {
  padding: 10px 14px;
  margin-bottom: 12px;
}

.exec__log {
  min-height: 320px;
  margin-bottom: 12px;
}

.facts {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 22px;
  margin: 0 0 10px;
}

.facts__i {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.facts dt {
  color: var(--nat-text-weak);
  font-size: 12px;
}

.facts dd {
  margin: 0;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.facts dd.is-bad {
  color: #dc2626;
  font-weight: 600;
}

.cmd-box {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  padding: 6px 10px;
  background: #fafbfd;
}

.cmd-box__label {
  flex: none;
  color: var(--nat-text-weak);
  font-size: 12px;
  line-height: 1.7;
}

.cmd-box__pre {
  flex: 1;
  min-width: 0;
  margin: 0;
  /* 长脚本不能顶掉首屏的日志 */
  max-height: 64px;
  overflow: auto;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.note {
  margin: 8px 0 0;
  padding: 6px 10px;
  border-radius: 6px;
  background: #fff8e6;
  border: 1px solid #f5dfa6;
  color: #8a6100;
  font-size: 12.5px;
  line-height: 1.6;
}

.sse {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: #8b949e;
}

.sse::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.sse--live {
  color: #3fb950;
}

.sse--warn {
  color: #d29922;
}

.sse__btn {
  background: #21262d;
  border: 1px solid #30363d;
  color: #c9d1d9;
  border-radius: 6px;
  height: 20px;
  padding: 0 8px;
  font-size: 11.5px;
  cursor: pointer;
}

.sse__btn:hover {
  background: #30363d;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px 26px;
}

.block {
  margin-top: 14px;
}

.block__k {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-bottom: 5px;
}

.block__v {
  margin: 0 0 4px;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.block__v.is-clamp {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  overflow: hidden;
}

.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

/* JWT 之类不可断词的长值不能把页面撑出横向滚动 */
.chip {
  max-width: 100%;
  min-width: 0;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.rule {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  font-size: 12px;
  color: var(--nat-text-sub);
  border: 1px dashed var(--nat-border-strong);
  border-radius: 6px;
  padding: 2px 8px;
}
</style>
