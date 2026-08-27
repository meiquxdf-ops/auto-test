<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { cancelTask, createTask, fetchTaskById } from '@/api/tasks'
import { toastError, toastOk } from '@/api/http'
import { SSE_STATE_TEXT } from '@/api/sse'
import type { ConditionConfig, Execution, Task } from '@/api/types'
import { useExecutionLog } from '@/composables/useExecutionLog'
import { durationBetween, formatBytes, formatTime } from '@/utils/format'
import { isTerminal } from '@/utils/status'
import AgentPicker from '@/components/AgentPicker.vue'
import ConditionEditor from '@/components/ConditionEditor.vue'
import CopyableId from '@/components/CopyableId.vue'
import EmptyState from '@/components/EmptyState.vue'
import EnvEditor from '@/components/EnvEditor.vue'
import LogTerminal from '@/components/LogTerminal.vue'
import StatusPill from '@/components/StatusPill.vue'

const router = useRouter()

const DRAFT_KEY = 'nat.playgroundDraft'
const OPERATOR_KEY = 'nat.operator'

interface FormState {
  command: string
  cwd: string
  env: Record<string, string>
  targets: string[]
  timeoutSec: number
  operator: string
  conditionConfig: ConditionConfig | null
  /** 任务终态后 POST 一次结果，试联调回调用 */
  callbackUrl: string
}

function blank(): FormState {
  return {
    command: 'echo hello from $(hostname)',
    cwd: '',
    env: {},
    targets: [],
    timeoutSec: 300,
    operator: localStorage.getItem(OPERATOR_KEY) || '',
    conditionConfig: null,
    callbackUrl: '',
  }
}

const form = reactive<FormState>(blank())

try {
  const raw = localStorage.getItem(DRAFT_KEY)
  if (raw) Object.assign(form, blank(), JSON.parse(raw) as Partial<FormState>, { targets: [] })
} catch {
  /* 忽略坏草稿 */
}

const submitting = ref(false)
const currentTask = ref<Task | null>(null)
const executions = ref<Execution[]>([])
const selectedId = ref('')
const waiting = ref(false)
const history = ref<{ taskId: string; command: string; at: number; executeIds: string[] }[]>([])

const {
  execution,
  status,
  lines,
  truncated,
  droppedBytes,
  totalBytes,
  logLoading,
  sseState,
  finished,
  reload,
  reconnect,
} = useExecutionLog(selectedId)

const callbackUrlError = computed(() => {
  const v = form.callbackUrl.trim()
  if (!v) return ''
  return /^https?:\/\/.+/i.test(v) ? '' : '仅支持 http:// 或 https:// 地址'
})

const valid = computed(
  () => form.command.trim().length > 0 && form.targets.length > 0 && !callbackUrlError.value,
)

const quickCommands = [
  { label: '主机信息', value: 'hostname && uname -a && uptime' },
  { label: '磁盘', value: 'df -h' },
  { label: '内存', value: 'free -m' },
  { label: '负载 Top10', value: 'ps -eo pid,pcpu,pmem,comm --sort=-pcpu | head -n 11' },
  { label: '连续输出 30 秒', value: 'for i in $(seq 1 30); do echo "tick $i $(date +%T)"; sleep 1; done; echo 0' },
  { label: '故意失败', value: 'echo "boom" >&2; exit 3' },
]

function saveDraft() {
  try {
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        command: form.command,
        cwd: form.cwd,
        env: form.env,
        timeoutSec: form.timeoutSec,
        operator: form.operator,
        conditionConfig: form.conditionConfig,
        callbackUrl: form.callbackUrl,
      }),
    )
  } catch {
    /* ignore */
  }
}

const sleep = (ms: number) => new Promise((r) => window.setTimeout(r, ms))

/** 下发后 executions 可能是异步落库的，轮询等一会儿 */
async function waitForExecutions(taskId: string) {
  waiting.value = true
  try {
    for (let i = 0; i < 20; i += 1) {
      const task = await fetchTaskById(taskId).catch(() => null)
      if (task) {
        currentTask.value = task
        if (task.executions.length) {
          executions.value = task.executions
          if (!selectedId.value || !task.executions.some((e) => e.executeId === selectedId.value)) {
            selectedId.value = task.executions[0].executeId
          }
          return
        }
      }
      await sleep(1000)
    }
  } finally {
    waiting.value = false
  }
}

async function submit() {
  if (!valid.value) return
  if (form.targets.length > 5) {
    try {
      await ElMessageBox.confirm(
        `测试下发一次选了 ${form.targets.length} 台机器，右侧只会跟随其中一条日志。确认继续？`,
        '确认下发',
        { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    const task = await createTask({
      command: form.command.trim(),
      cwd: form.cwd.trim() || undefined,
      env: form.env,
      targets: form.targets,
      conditionConfig: form.conditionConfig,
      operator: form.operator.trim() || undefined,
      timeoutSec: form.timeoutSec,
      callbackUrl: form.callbackUrl.trim() || undefined,
    })
    if (form.operator.trim()) localStorage.setItem(OPERATOR_KEY, form.operator.trim())
    saveDraft()
    toastOk('已下发，正在等待调度')

    currentTask.value = task
    executions.value = task?.executions ?? []
    selectedId.value = task?.executions?.[0]?.executeId ?? ''

    if (task?.taskId) {
      history.value.unshift({
        taskId: task.taskId,
        command: form.command.trim(),
        at: Date.now(),
        executeIds: (task.executions ?? []).map((e) => e.executeId),
      })
      history.value = history.value.slice(0, 8)
      if (!executions.value.length) await waitForExecutions(task.taskId)
    }
  } catch (e) {
    toastError(e, '下发失败')
  } finally {
    submitting.value = false
  }
}

async function refreshTask() {
  const taskId = currentTask.value?.taskId
  if (!taskId) return
  const task = await fetchTaskById(taskId).catch(() => null)
  if (task) {
    currentTask.value = task
    if (task.executions.length) executions.value = task.executions
  }
}

async function onCancel() {
  const taskId = currentTask.value?.taskId
  if (!taskId) return
  try {
    await ElMessageBox.confirm('取消会杀掉该次下发的进程组，结果判为 canceled。确认继续？', '取消本次下发', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await cancelTask(taskId)
    toastOk('已下发取消指令')
    await refreshTask()
    await reload()
  } catch (e) {
    toastError(e, '取消失败')
  }
}

function pickExecution(id: string) {
  selectedId.value = id
}

function resetForm() {
  Object.assign(form, blank())
  localStorage.removeItem(DRAFT_KEY)
}

function clearResult() {
  currentTask.value = null
  executions.value = []
  selectedId.value = ''
}

// 执行结束后同步一次任务状态，让右侧徽章跟上
watch(finished, (done) => {
  if (done) void refreshTask()
})

const running = computed(() => !!selectedId.value && !isTerminal(status.value))

const logFootNote = computed(() => {
  const parts = [`实时通道：${SSE_STATE_TEXT[sseState.value]}`]
  if (execution.value?.exitCode !== null && execution.value?.exitCode !== undefined) {
    parts.push(`exitCode ${execution.value.exitCode}`)
  }
  if (totalBytes.value) parts.push(formatBytes(totalBytes.value))
  return parts.join(' · ')
})
</script>

<template>
  <div class="page pg">
    <div class="page-head">
      <div>
        <h2 class="page-head__title">测试下发</h2>
        <p class="page-head__desc">
          实验用的一键下发页：左边填命令选机器，右边立刻跟日志和状态。走的是和正式任务完全一样的
          <code class="code-inline">POST /api/tasks</code> 通道。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button text @click="resetForm">重置表单</el-button>
        <router-link to="/tasks"><el-button :icon="'Tickets'">任务队列</el-button></router-link>
      </div>
    </div>

    <div class="pg__cols">
      <!-- 左：表单 -->
      <div class="panel pg__form">
        <div class="panel__head">
          <div class="panel__title">下发配置</div>
          <span class="muted">{{ form.targets.length }} 台目标</span>
        </div>
        <div class="panel__body pg__form-body">
          <el-form label-position="top">
            <el-form-item label="目标机器">
              <AgentPicker v-model="form.targets" />
            </el-form-item>

            <el-form-item>
              <template #label>
                <span class="lbl">命令 <span class="lbl__hint">bash -c 执行，支持多行</span></span>
              </template>
              <el-input
                v-model="form.command"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 12 }"
                spellcheck="false"
                placeholder="echo hello"
              />
              <div class="quick">
                <span class="muted">快捷命令</span>
                <el-tag
                  v-for="q in quickCommands"
                  :key="q.label"
                  size="small"
                  effect="plain"
                  class="quick__tag"
                  @click="form.command = q.value"
                >
                  {{ q.label }}
                </el-tag>
              </div>
            </el-form-item>

            <div class="pg__grid">
              <el-form-item label="工作目录 cwd">
                <el-input v-model="form.cwd" placeholder="留空用默认目录" spellcheck="false" class="mono" />
              </el-form-item>
              <el-form-item label="超时（秒）">
                <el-input-number v-model="form.timeoutSec" :min="1" :max="86400" :step="30" controls-position="right" />
              </el-form-item>
            </div>

            <el-form-item label="操作人 operator">
              <el-input v-model="form.operator" placeholder="选填，会记录到执行上" />
            </el-form-item>

            <el-form-item :error="callbackUrlError || undefined">
              <template #label>
                <span>
                  回调地址 callbackUrl
                  <span class="muted">（选填，任务终态后 POST 一次结果，联调回调用）</span>
                </span>
              </template>
              <el-input
                v-model="form.callbackUrl"
                placeholder="http://10.0.0.5:9000/notify"
                spellcheck="false"
                class="mono"
                clearable
              />
            </el-form-item>

            <el-collapse class="pg__collapse">
              <el-collapse-item name="env">
                <template #title>
                  <span class="collapse-title">
                    环境变量
                    <el-tag v-if="Object.keys(form.env).length" size="small" round>
                      {{ Object.keys(form.env).length }}
                    </el-tag>
                  </span>
                </template>
                <EnvEditor v-model="form.env" />
              </el-collapse-item>
              <el-collapse-item name="cond">
                <template #title>
                  <span class="collapse-title">
                    判定配置（可选）
                    <el-tag v-if="form.conditionConfig" size="small" type="warning" round>
                      {{ form.conditionConfig.rules.length }} 条规则
                    </el-tag>
                  </span>
                </template>
                <ConditionEditor v-model="form.conditionConfig" />
              </el-collapse-item>
            </el-collapse>
          </el-form>
        </div>
        <div class="pg__form-foot">
          <el-button
            type="primary"
            size="large"
            :icon="'Promotion'"
            :loading="submitting"
            :disabled="!valid"
            class="pg__submit"
            @click="submit"
          >
            立即下发
          </el-button>
          <div v-if="!valid" class="muted pg__tip">请先填写命令并选择至少一台机器</div>
        </div>
      </div>

      <!-- 右：结果 -->
      <div class="pg__result">
        <div class="panel">
          <div class="panel__head">
            <div class="panel__title">
              本次下发
              <span v-if="currentTask" class="hint">
                taskId <CopyableId :value="currentTask.taskId" :head="10" />
              </span>
            </div>
            <div class="pg__result-actions">
              <el-button v-if="currentTask" size="small" :icon="'Refresh'" @click="refreshTask">刷新</el-button>
              <el-button
                v-if="currentTask && running"
                size="small"
                type="danger"
                plain
                @click="onCancel"
              >
                取消本次下发
              </el-button>
              <el-button v-if="currentTask" size="small" text @click="clearResult">清空</el-button>
            </div>
          </div>

          <div class="panel__body">
            <EmptyState
              v-if="!currentTask"
              title="还没有下发"
              desc="左侧填好命令和目标机器，点「立即下发」，这里会实时显示状态与日志"
              size="small"
            />
            <template v-else>
              <div v-if="waiting && !executions.length" class="pg__waiting">
                <el-icon class="is-loading"><Loading /></el-icon>
                正在等待 Server 生成执行记录…
              </div>

              <div v-if="executions.length" class="pg__targets">
                <button
                  v-for="ex in executions"
                  :key="ex.executeId"
                  class="pg__target"
                  :class="{ 'is-active': ex.executeId === selectedId }"
                  @click="pickExecution(ex.executeId)"
                >
                  <StatusPill
                    :status="ex.executeId === selectedId ? status : ex.status"
                    :disconnected="ex.disconnected"
                  />
                  <span class="pg__target-name">{{ ex.displayTag || ex.agentId || '未分配' }}</span>
                </button>
              </div>

              <div v-if="execution" class="pg__meta">
                <span class="pg__meta-item">
                  executeId
                  <router-link :to="`/executions/${execution.executeId}`" class="link-btn mono">
                    {{ execution.executeId.slice(0, 10) }}
                  </router-link>
                </span>
                <span class="pg__meta-item">退出码 <b class="mono">{{ execution.exitCode ?? '-' }}</b></span>
                <span class="pg__meta-item">
                  耗时 <b class="mono">{{ durationBetween(execution.startedAt, execution.finishedAt) }}</b>
                </span>
                <span class="pg__meta-item">开始 <b class="mono">{{ formatTime(execution.startedAt) }}</b></span>
                <span class="spacer" />
                <el-button
                  size="small"
                  text
                  type="primary"
                  @click="router.push(`/executions/${execution.executeId}`)"
                >
                  打开完整详情
                </el-button>
              </div>
            </template>
          </div>
        </div>

        <div class="panel pg__log-panel">
          <div class="panel__head">
            <div class="panel__title">
              实时日志
              <span v-if="selectedId" class="hint mono">{{ selectedId.slice(0, 12) }}</span>
            </div>
            <div class="pg__result-actions">
              <el-tag
                v-if="selectedId"
                size="small"
                round
                :type="sseState === 'open' ? 'success' : finished ? 'info' : 'warning'"
              >
                {{ finished ? '已结束' : SSE_STATE_TEXT[sseState] }}
              </el-tag>
              <el-button v-if="selectedId && !finished && sseState !== 'open'" size="small" text @click="reconnect">
                重连
              </el-button>
            </div>
          </div>
          <div class="panel__body">
            <EmptyState
              v-if="!selectedId"
              size="small"
              title="日志会在下发后自动出现"
              desc="下发成功且 Server 分配到机器后，这里会实时推送 stdout / stderr"
            />
            <LogTerminal
              v-else
              :lines="lines"
              :truncated="truncated"
              :dropped-bytes="droppedBytes"
              :total-bytes="totalBytes"
              :loading="logLoading"
              height="420px"
              :file-name="`${selectedId}.log`"
              :foot-note="logFootNote"
            />
          </div>
        </div>

        <div v-if="history.length" class="panel">
          <div class="panel__head">
            <div class="panel__title">
              本次会话的下发历史
              <span class="hint">{{ history.length }} 条</span>
            </div>
          </div>
          <div class="panel__body panel__body--flush">
            <div v-for="h in history" :key="h.taskId + h.at" class="hist">
              <span class="hist__time mono">{{ formatTime(h.at) }}</span>
              <code class="hist__cmd">{{ h.command }}</code>
              <router-link
                v-if="h.executeIds[0]"
                :to="`/executions/${h.executeIds[0]}`"
                class="link-btn"
              >
                日志
              </router-link>
              <button class="link-btn" @click="form.command = h.command">再来一次</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pg__cols {
  display: grid;
  grid-template-columns: minmax(380px, 0.85fr) minmax(0, 1.15fr);
  gap: 14px;
  align-items: start;
}

@media (max-width: 1200px) {
  .pg__cols {
    grid-template-columns: 1fr;
  }
}

.pg__form {
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
}

.pg__form-body {
  max-height: calc(100vh - 240px);
  overflow-y: auto;
}

.pg__form-foot {
  padding: 12px 16px;
  border-top: 1px solid var(--nat-border);
  background: #fbfcfe;
}

.pg__submit {
  width: 100%;
}

.pg__tip {
  text-align: center;
  margin-top: 6px;
  font-size: 12px;
}

.pg__grid {
  display: grid;
  grid-template-columns: 1fr 160px;
  gap: 0 12px;
}

.pg__collapse {
  border-top: 1px solid var(--nat-border);
  margin-top: 4px;
}

.collapse-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 560;
}

.quick {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.quick__tag {
  cursor: pointer;
}

.quick__tag:hover {
  border-color: var(--nat-accent);
  color: var(--nat-accent);
}

.pg__result > .panel + .panel {
  margin-top: 14px;
}

.pg__result-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.pg__waiting {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  padding: 6px 0 10px;
}

.pg__targets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.pg__target {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.pg__target:hover {
  border-color: var(--nat-border-strong);
}

.pg__target.is-active {
  border-color: var(--nat-accent);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}

.pg__target-name {
  font-weight: 540;
  font-size: 12.5px;
}

.pg__meta {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--nat-border);
  font-size: 12px;
  color: var(--nat-text-weak);
}

.pg__meta-item b {
  color: var(--nat-text);
}

.spacer {
  flex: 1;
}

.hist {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--nat-border);
  font-size: 12px;
}

.hist:last-child {
  border-bottom: none;
}

.hist__time {
  color: var(--nat-text-weak);
  flex: none;
}

.hist__cmd {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.lbl {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.lbl__hint {
  color: var(--nat-text-weak);
  font-weight: 400;
  font-size: 12px;
}

:deep(.el-form-item__label) {
  font-weight: 560;
  color: var(--nat-text);
  padding-bottom: 4px;
}

:deep(textarea.el-textarea__inner) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.7;
}
</style>
