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

const valid = computed(
  () => form.command.trim().length > 0 && form.targets.length > 0 && !timeoutError.value,
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
    })
    if (form.operator.trim()) localStorage.setItem(OPERATOR_KEY, form.operator.trim())
    saveDraft()
    toastOk(`任务已创建，共 ${form.targets.length} 个目标`)
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
    size="740px"
    :with-header="false"
    :close-on-click-modal="false"
    :before-close="onClose"
  >
    <div class="cd">
      <div class="cd__head">
        <div>
          <div class="cd__title">创建任务</div>
          <div class="cd__sub">命令通过 <code class="code-inline">bash -c</code> 在目标机器上执行，一机一任务（可配并发上限 4）</div>
        </div>
        <el-button text :icon="'Close'" @click="onClose" />
      </div>

      <div class="cd__body">
        <el-form label-position="top">
          <el-form-item :error="commandError || undefined">
            <template #label>
              <span class="lbl">
                命令 <b class="req">*</b>
                <span class="lbl__hint">支持多行脚本，{{ commandLines }} 行</span>
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
              <el-input v-model="form.cwd" placeholder="留空则用 Agent 默认目录" spellcheck="false" class="mono" />
            </el-form-item>
            <el-form-item label="操作人 operator">
              <el-input v-model="form.operator" placeholder="选填，用于追溯" spellcheck="false" />
            </el-form-item>
          </div>

          <el-form-item :error="timeoutError || undefined">
            <template #label>
              <span class="lbl">超时时间 <span class="lbl__hint">超时会杀掉进程组并判为 exception</span></span>
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
        </el-form>
      </div>

      <div class="cd__foot">
        <el-button text @click="resetForm">重置表单</el-button>
        <span class="spacer" />
        <span class="muted cd__summary">
          {{ form.targets.length }} 台目标 · 超时 {{ form.timeoutSec }}s ·
          {{ form.conditionConfig ? `${form.conditionConfig.rules.length} 条判定规则` : '按退出码判定' }}
        </span>
        <el-button @click="onClose">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!valid" @click="submit">
          创建并入队
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.cd {
  display: flex;
  flex-direction: column;
  height: 100%;
  margin: -20px;
}

.cd__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 20px 12px;
  border-bottom: 1px solid var(--nat-border);
}

.cd__title {
  font-size: 16px;
  font-weight: 640;
}

.cd__sub {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-top: 4px;
}

.cd__body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 20px 20px;
}

.cd__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

.cd__timeout {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.cd__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--nat-border);
  background: #fbfcfe;
}

.spacer {
  flex: 1;
}

.cd__summary {
  font-size: 12px;
  margin-right: 6px;
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

.req {
  color: #dc2626;
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
