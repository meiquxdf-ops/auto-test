<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { createTask } from '@/api/tasks'
import { toastError, toastOk } from '@/api/http'
import type { ConditionConfig, Task, TaskFormPreset } from '@/api/types'
import AgentPicker from './AgentPicker.vue'
import ConditionEditor from './ConditionEditor.vue'
import EnvEditor from './EnvEditor.vue'

const visible = defineModel<boolean>({ required: true })

const props = withDefaults(
  defineProps<{
    /** 预填内容，例如从某个任务「以此为模板」进来 */
    preset?: TaskFormPreset | null
  }>(),
  { preset: null },
)

const emit = defineEmits<{ (e: 'created', task: Task | null): void }>()

interface FormState {
  command: string
  cwd: string
  env: Record<string, string>
  targets: string[]
  timeoutSec: number
  operator: string
  conditionConfig: ConditionConfig | null
  /** 任务终态后 POST 一次结果 */
  callbackUrl: string
}

const DRAFT_KEY = 'nat.taskDraft'
const OPERATOR_KEY = 'nat.operator'

function blank(): FormState {
  return {
    command: '',
    cwd: '',
    env: {},
    targets: [],
    timeoutSec: 1800,
    operator: localStorage.getItem(OPERATOR_KEY) || '',
    conditionConfig: null,
    callbackUrl: '',
  }
}

const form = reactive<FormState>(blank())
const submitting = ref(false)
const touched = ref(false)

function loadDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return
    const draft = JSON.parse(raw) as Partial<FormState>
    Object.assign(form, blank(), draft, { targets: draft.targets ?? [] })
  } catch {
    /* 忽略坏草稿 */
  }
}

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
    /* localStorage 满了就算了 */
  }
}

watch(visible, (open) => {
  if (!open) return
  touched.value = false
  Object.assign(form, blank())
  loadDraft()
  if (props.preset) Object.assign(form, props.preset)
})

const commandError = computed(() => {
  if (!touched.value) return ''
  return form.command.trim() ? '' : '命令不能为空'
})

const targetError = computed(() => {
  if (!touched.value) return ''
  return form.targets.length ? '' : '至少选择一台目标机器'
})

const timeoutError = computed(() => {
  if (form.timeoutSec === null || form.timeoutSec === undefined) return '超时时间必填'
  if (form.timeoutSec < 1 || form.timeoutSec > 86400) return '超时时间范围 1 - 86400 秒'
  return ''
})

const callbackUrlError = computed(() => {
  const v = form.callbackUrl.trim()
  if (!v) return ''
  return /^https?:\/\/.+/i.test(v) ? '' : '仅支持 http:// 或 https:// 地址'
})

const valid = computed(
  () =>
    form.command.trim().length > 0 &&
    form.targets.length > 0 &&
    !timeoutError.value &&
    !callbackUrlError.value,
)

const commandLines = computed(() => form.command.split('\n').filter((l) => l.trim()).length)

async function submit() {
  touched.value = true
  if (!valid.value) return

  if (form.targets.length >= 10) {
    try {
      await ElMessageBox.confirm(
        `即将向 ${form.targets.length} 台机器下发同一条命令，确认继续？`,
        '确认批量下发',
        { type: 'warning', confirmButtonText: '确认下发', cancelButtonText: '再看看' },
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
      // 运维台不填 requestId：由服务端生成，创建完回显出来方便开放查询 / 回调对账
      callbackUrl: form.callbackUrl.trim() || undefined,
    })
    if (form.operator.trim()) localStorage.setItem(OPERATOR_KEY, form.operator.trim())
    saveDraft()
    toastOk(
      task?.requestId
        ? `任务已创建，共 ${form.targets.length} 个目标 · requestId：${task.requestId}`
        : `任务已创建，共 ${form.targets.length} 个目标`,
    )
    visible.value = false
    emit('created', task)
  } catch (e) {
    toastError(e, '创建任务失败')
  } finally {
    submitting.value = false
  }
}

async function onClose() {
  if (form.command.trim() || form.targets.length) {
    try {
      await ElMessageBox.confirm('关闭后表单内容会保存为草稿，下次打开自动恢复。确认关闭？', '关闭', {
        type: 'info',
        confirmButtonText: '关闭',
        cancelButtonText: '继续编辑',
      })
    } catch {
      return
    }
    saveDraft()
  }
  visible.value = false
}

function resetForm() {
  Object.assign(form, blank())
  localStorage.removeItem(DRAFT_KEY)
  touched.value = false
}

const timeoutPresets = [
  { label: '5 分钟', value: 300 },
  { label: '30 分钟', value: 1800 },
  { label: '2 小时', value: 7200 },
  { label: '8 小时', value: 28800 },
]
</script>

<template>
  <el-drawer
    v-model="visible"
    size="min(740px, 100vw)"
    :with-header="false"
    :close-on-click-modal="false"
    :before-close="onClose"
  >
    <div class="cd">
      <div class="cd__head">
        <div class="cd__head-text">
          <div class="cd__title">创建任务</div>
          <div class="cd__sub">通过 <code class="code-inline">bash -c</code> 在目标机执行，一机一任务</div>
        </div>
        <el-button text :icon="'Close'" title="关闭" @click="onClose" />
      </div>

      <div class="cd__body">
        <el-form label-position="top">
          <el-form-item :error="commandError || undefined">
            <template #label>
              <span class="lbl">
                命令 <b class="req">*</b>
                <span class="lbl__hint">多行脚本，{{ commandLines }} 行</span>
              </span>
            </template>
            <el-input
              v-model="form.command"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 14 }"
              spellcheck="false"
              class="mono"
              placeholder="例如：cd /opt/app && ./run-suite.sh --case smoke"
            />
          </el-form-item>

          <div class="cd__grid">
            <el-form-item label="工作目录 cwd">
              <el-input v-model="form.cwd" placeholder="留空用 Agent 默认目录" spellcheck="false" class="mono" />
            </el-form-item>
            <el-form-item label="操作人 operator">
              <el-input v-model="form.operator" placeholder="选填，便于追溯" spellcheck="false" />
            </el-form-item>
          </div>

          <el-form-item :error="timeoutError || undefined">
            <template #label>
              <span class="lbl">超时时间 <span class="lbl__hint">超时杀进程组，判 exception</span></span>
            </template>
            <div class="cd__timeout">
              <el-input-number v-model="form.timeoutSec" :min="1" :max="86400" :step="60" controls-position="right" />
              <span class="muted">秒</span>
              <el-radio-group v-model="form.timeoutSec" size="small">
                <el-radio-button v-for="p in timeoutPresets" :key="p.value" :value="p.value">
                  {{ p.label }}
                </el-radio-button>
              </el-radio-group>
            </div>
          </el-form-item>

          <el-form-item label="环境变量">
            <EnvEditor v-model="form.env" />
          </el-form-item>

          <el-form-item :error="targetError || undefined">
            <template #label>
              <span class="lbl">目标机器 <b class="req">*</b></span>
            </template>
            <AgentPicker v-model="form.targets" />
          </el-form-item>

          <el-form-item label="判定配置 conditionConfig">
            <ConditionEditor v-model="form.conditionConfig" />
          </el-form-item>

          <el-form-item :error="callbackUrlError || undefined">
            <template #label>
              <span class="lbl lbl--stack">
                <span>回调地址 callbackUrl</span>
                <span class="lbl__hint">任务终态后 POST 一次结果，2xx 算送达</span>
              </span>
            </template>
            <el-input
              v-model="form.callbackUrl"
              placeholder="选填，如 http://10.0.0.5:9000/notify"
              spellcheck="false"
              class="mono"
              clearable
            />
            <div class="cd__extra">requestId 由服务端生成，创建后回显。</div>
          </el-form-item>
        </el-form>
      </div>

      <div class="cd__foot">
        <el-button text @click="resetForm">重置</el-button>
        <span class="muted cd__summary">
          {{ form.targets.length }} 台 · {{ form.timeoutSec }}s ·
          {{ form.conditionConfig ? `${form.conditionConfig.rules.length} 条规则` : '按退出码' }}
        </span>
        <span class="spacer" />
        <div class="cd__foot-actions">
          <el-button @click="onClose">取消</el-button>
          <el-button type="primary" :loading="submitting" :disabled="!valid" @click="submit">
            创建并入队
          </el-button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
/*
 * 抽屉正文自带 20px 内边距。负边距把它顶掉的同时，高度也要补回来，
 * 否则 height:100% 会比可用高度矮 40px，页脚底下留一条白边。
 */
.cd {
  --cd-pad: var(--el-drawer-padding-primary, 20px);

  display: flex;
  flex-direction: column;
  height: calc(100% + var(--cd-pad) * 2);
  margin: calc(var(--cd-pad) * -1);
}

.cd__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid var(--nat-border);
}

.cd__head-text {
  min-width: 0;
}

.cd__title {
  font-size: 16px;
  font-weight: 640;
}

.cd__sub {
  color: var(--nat-text-weak);
  font-size: 12px;
  line-height: 1.45;
  margin-top: 4px;
}

.cd__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 20px 20px;
}

.cd__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 14px;
}

.cd__extra {
  flex: 1 1 100%;
  color: var(--nat-text-weak);
  font-size: 12px;
  line-height: 1.45;
  margin-top: 6px;
}

.cd__timeout {
  flex: 1 1 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px 10px;
  flex-wrap: wrap;
}

.cd__foot {
  display: flex;
  align-items: center;
  gap: 8px 12px;
  flex-wrap: wrap;
  padding: 12px 20px;
  border-top: 1px solid var(--nat-border);
  background: #fbfcfe;
}

.cd__foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.cd__foot-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.spacer {
  flex: 1;
}

.cd__summary {
  font-size: 12px;
  line-height: 1.45;
}

.lbl {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  line-height: 1.45;
}

/* 提示放到标签下一行，别和标签焊在同一条 flex 线上 */
.lbl--stack {
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.lbl__hint {
  color: var(--nat-text-weak);
  font-weight: 400;
  font-size: 12px;
  line-height: 1.45;
}

:deep(.el-form-item__label) {
  font-weight: 560;
  color: var(--nat-text);
  padding-bottom: 4px;
  height: auto;
  line-height: 1.45;
}

:deep(.el-form-item__content) {
  min-width: 0;
  line-height: 1.45;
}

.req {
  color: #dc2626;
}

:deep(textarea.el-textarea__inner) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.7;
}
</style>
