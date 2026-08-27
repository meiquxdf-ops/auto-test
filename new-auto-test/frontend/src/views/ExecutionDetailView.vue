<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { cancelTask, rerunTask } from '@/api/tasks'
import { toastError, toastOk } from '@/api/http'
import { SSE_STATE_TEXT } from '@/api/sse'
import { useExecutionLog } from '@/composables/useExecutionLog'
import { durationBetween, formatBytes, formatFullTime } from '@/utils/format'
import { CONDITION_OPERATOR_LABEL, isTerminal, statusMeta } from '@/utils/status'
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

const envEntries = computed(() => Object.entries(execution.value?.env ?? {}))

const duration = computed(() =>
  durationBetween(execution.value?.startedAt, execution.value?.finishedAt ?? null),
)

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

const logFootNote = computed(() => {
  const parts: string[] = [`实时通道：${SSE_STATE_TEXT[sseState.value]}`]
  if (clientTrimmed.value) parts.push(`前端已丢弃最早 ${clientTrimmed.value} 行`)
  if (finished.value) parts.push('执行已结束')
  return parts.join(' · ')
})

watch(executeId, () => {
  acting.value = ''
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="crumb">
          <button class="link-btn" @click="router.back()">
            <el-icon><ArrowLeft /></el-icon>
            返回
          </button>
          <span class="muted">/</span>
          <router-link to="/tasks" class="link-btn">任务队列</router-link>
          <span class="muted">/</span>
          <span class="muted">执行详情</span>
        </div>
        <h2 class="page-head__title">
          执行详情
          <StatusPill
            v-if="execution"
            :status="status"
            size="large"
            :disconnected="execution.disconnected"
            class="ml10"
          />
        </h2>
        <p class="page-head__desc">
          executeId <CopyableId :value="executeId" :short="false" />
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :icon="'Refresh'" :loading="loading" @click="reload">刷新</el-button>
        <el-button
          :icon="'Histogram'"
          @click="router.push({ path: '/timeline', query: { executeId } })"
        >
          时间线
        </el-button>
        <el-button
          v-if="execution?.taskId"
          type="danger"
          plain
          :disabled="isTerminal(status)"
          :loading="acting === 'cancel'"
          @click="onCancel"
        >
          取消执行
        </el-button>
        <el-dropdown v-if="execution?.taskId" trigger="click">
          <el-button type="primary" plain>
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

    <el-alert v-if="error && execution" type="error" show-icon :closable="false" :title="error" class="mb14" />

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
      <div class="panel">
        <div class="panel__head">
          <div class="panel__title">
            执行概要
            <span class="hint">
              <template v-if="execution?.displayTag || execution?.agentId">
                机器 {{ execution?.displayTag || execution?.agentId }}
              </template>
            </span>
          </div>
          <div class="badges">
            <span class="badge" :style="{ borderColor: statusMeta(status).border }">
              <span class="badge__k">结果</span>
              <StatusPill :status="status" :disconnected="execution?.disconnected" />
            </span>
            <span class="badge">
              <span class="badge__k">退出码</span>
              <b class="mono" :class="{ 'is-bad': (execution?.exitCode ?? 0) !== 0 }">
                {{ execution?.exitCode ?? '-' }}
              </b>
            </span>
            <span class="badge">
              <span class="badge__k">耗时</span>
              <b class="mono">{{ duration }}</b>
            </span>
            <span class="badge">
              <span class="badge__k">日志</span>
              <b class="mono">{{ lines.length }} 行{{ totalBytes ? ` / ${formatBytes(totalBytes)}` : '' }}</b>
            </span>
          </div>
        </div>

        <div class="panel__body">
          <el-skeleton v-if="loading && !execution" :rows="4" animated />
          <template v-else-if="execution">
            <div class="cmd-box">
              <div class="cmd-box__label">命令</div>
              <pre class="cmd-box__pre mono">{{ execution.command || '-' }}</pre>
            </div>

            <div class="meta-grid">
              <div class="kv">
                <span class="kv__k">机器</span>
                <span class="kv__v">
                  <router-link
                    v-if="execution.agentId"
                    class="link-btn"
                    :to="{ path: '/agents', query: { focus: execution.displayTag || execution.agentId } }"
                  >
                    {{ execution.displayTag || execution.agentId }}
                  </router-link>
                  <span v-else class="muted">-</span>
                </span>
                <span class="kv__k">agentId</span>
                <span class="kv__v"><CopyableId :value="execution.agentId" :head="12" /></span>
                <span class="kv__k">taskId</span>
                <span class="kv__v"><CopyableId :value="execution.taskId" :head="12" /></span>
                <span class="kv__k">dispatchToken</span>
                <span class="kv__v"><CopyableId :value="execution.dispatchToken" :head="12" /></span>
              </div>

              <div class="kv">
                <span class="kv__k">cwd</span>
                <span class="kv__v mono">{{ execution.cwd || '（Agent 默认）' }}</span>
                <span class="kv__k">operator</span>
                <span class="kv__v">{{ execution.operator || '-' }}</span>
                <span class="kv__k">超时</span>
                <span class="kv__v">{{ execution.timeoutSec ? `${execution.timeoutSec}s` : '-' }}</span>
                <span class="kv__k">最后一行</span>
                <span class="kv__v mono">{{ execution.lastLine || '-' }}</span>
              </div>

              <div class="kv">
                <span class="kv__k">创建</span>
                <span class="kv__v">{{ formatFullTime(execution.createdAt) }}</span>
                <span class="kv__k">开始</span>
                <span class="kv__v">{{ formatFullTime(execution.startedAt) }}</span>
                <span class="kv__k">结束</span>
                <span class="kv__v">{{ formatFullTime(execution.finishedAt) }}</span>
                <span class="kv__k">判定依据</span>
                <span class="kv__v">{{ judgement }}</span>
              </div>
            </div>

            <div v-if="envEntries.length" class="chips">
              <span class="chips__label">环境变量</span>
              <code v-for="[k, v] in envEntries" :key="k" class="code-inline">{{ k }}={{ v }}</code>
            </div>

            <div v-if="execution.conditionConfig" class="chips">
              <span class="chips__label">判定配置</span>
              <span
                v-for="(rule, i) in execution.conditionConfig.rules"
                :key="i"
                class="rule"
              >
                {{ i + 1 }}. {{ CONDITION_OPERATOR_LABEL[rule.operator] }}
                <code class="code-inline">{{ rule.value }}</code>
                → <StatusPill :status="rule.status" />
              </span>
              <span v-if="execution.conditionConfig.other" class="rule">
                other → <StatusPill :status="execution.conditionConfig.other" />
              </span>
            </div>

            <el-alert
              v-if="execution.message"
              class="mt10"
              type="warning"
              :closable="false"
              show-icon
              :title="execution.message"
            />

            <el-alert
              v-if="execution.disconnected && !isTerminal(status)"
              class="mt10"
              type="warning"
              :closable="false"
              show-icon
              title="该机器当前失联（running 的子状态 disconnected），不是终态；租约到期且对账确认进程不在时会判为 exception。"
            />
          </template>
        </div>
      </div>

      <div class="panel">
        <div class="panel__head">
          <div class="panel__title">
            实时日志
            <span class="hint">末 5MB 尾部保留 · SSE 断线自动续传（Last-Event-ID）</span>
          </div>
          <div class="log-actions">
            <el-tag size="small" :type="sseState === 'open' ? 'success' : finished ? 'info' : 'warning'" round>
              {{ finished ? '已结束' : SSE_STATE_TEXT[sseState] }}
            </el-tag>
            <el-button v-if="!finished && sseState !== 'open'" size="small" text type="primary" @click="reconnect">
              立即重连
            </el-button>
          </div>
        </div>
        <div class="panel__body">
          <LogTerminal
            :lines="lines"
            :truncated="truncated"
            :dropped-bytes="droppedBytes"
            :total-bytes="totalBytes"
            :loading="logLoading"
            height="560px"
            :file-name="`${executeId}.log`"
            :foot-note="logFootNote"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 6px;
}

.ml10 {
  margin-left: 10px;
  vertical-align: middle;
}

.mb14 {
  margin-bottom: 14px;
}

.mt10 {
  margin-top: 10px;
}

.badges {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  padding: 4px 10px;
  background: #fbfcfe;
}

.badge__k {
  color: var(--nat-text-weak);
  font-size: 11.5px;
}

.badge b {
  font-size: 13px;
}

.badge b.is-bad {
  color: #dc2626;
}

.cmd-box {
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 14px;
}

.cmd-box__label {
  background: #f7f9fc;
  border-bottom: 1px solid var(--nat-border);
  padding: 5px 10px;
  font-size: 12px;
  color: var(--nat-text-weak);
}

.cmd-box__pre {
  margin: 0;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  background: #fff;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px 26px;
}

.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.chips__label {
  color: var(--nat-text-weak);
  font-size: 12px;
}

.rule {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--nat-text-sub);
  border: 1px dashed var(--nat-border-strong);
  border-radius: 6px;
  padding: 2px 8px;
}

.log-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
