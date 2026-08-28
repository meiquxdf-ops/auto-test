<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAgents } from '@/stores/agents'
import type { Agent, AgentStatus } from '@/api/types'
import { agentStatusMeta } from '@/utils/status'

const props = withDefaults(
  defineProps<{
    modelValue: string[]
    /** 只允许选一台 */
    single?: boolean
    placeholder?: string
    /** 允许输入机器列表之外的 tag（接入调试页：没有在线机器也能填目标） */
    allowCreate?: boolean
  }>(),
  { single: false, placeholder: '搜索 tag / agentId', allowCreate: false },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: string[]): void }>()

const { agents, loading, refresh } = useAgents()

const statusFilter = ref<AgentStatus | 'all'>('all')

const options = computed(() => {
  const list = agents.value
  return statusFilter.value === 'all' ? list : list.filter((a) => a.status === statusFilter.value)
})

/** 目标既可以是 displayTag 也可以是 agentId，Server 侧 ingest 时统一解析 */
function keyOf(a: Agent): string {
  return a.displayTag || a.agentId
}

const selected = computed({
  get: () => props.modelValue,
  set: (v: string[]) => emit('update:modelValue', props.single ? v.slice(-1) : v),
})

const selectableCount = computed(() => options.value.filter((a) => a.status !== 'offline').length)

function selectOnline() {
  const keys = options.value.filter((a) => a.status === 'online').map(keyOf)
  emit('update:modelValue', props.single ? keys.slice(0, 1) : keys)
}

function selectAll() {
  const keys = options.value.filter((a) => a.status !== 'offline').map(keyOf)
  emit('update:modelValue', props.single ? keys.slice(0, 1) : keys)
}

function clearAll() {
  emit('update:modelValue', [])
}

function statusOf(key: string): Agent | undefined {
  return agents.value.find((a) => a.displayTag === key || a.agentId === key)
}

const offlineSelected = computed(() =>
  props.modelValue.filter((k) => {
    const a = statusOf(k)
    return !a || a.status === 'offline' || a.status === 'disconnected'
  }),
)

/** 告警只列前几台，避免几十个 tag 把 alert 撑成一大块 */
const OFFLINE_PREVIEW = 3

const offlineWarn = computed(() => {
  const all = offlineSelected.value
  if (!all.length) return ''
  const head = all.slice(0, OFFLINE_PREVIEW).join('、')
  const rest = all.length - OFFLINE_PREVIEW
  const names = rest > 0 ? `${head} 等 ${all.length} 台` : head
  return `${names} 不在线，任务会排队等待上线`
})

const statusOptions: { value: AgentStatus | 'all'; label: string }[] = [
  { value: 'all', label: '全部状态' },
  { value: 'online', label: '在线' },
  { value: 'busy', label: '忙碌' },
  { value: 'disconnected', label: '失联' },
  { value: 'offline', label: '离线' },
]
</script>

<template>
  <div class="picker">
    <div class="picker__row">
      <el-select
        v-model="selected"
        :multiple="!single"
        filterable
        clearable
        collapse-tags
        collapse-tags-tooltip
        :max-collapse-tags="1"
        :allow-create="allowCreate"
        :default-first-option="allowCreate"
        :reserve-keyword="!allowCreate"
        :placeholder="placeholder"
        :loading="loading"
        class="picker__select"
      >
        <el-option
          v-for="a in options"
          :key="a.agentId || a.displayTag"
          :label="a.displayTag || a.agentId"
          :value="a.displayTag || a.agentId"
          :disabled="a.status === 'offline'"
        >
          <div class="picker__option">
            <span class="picker__dot" :style="{ background: agentStatusMeta(a.status).color }" />
            <span class="picker__name">{{ a.displayTag || a.agentId }}</span>
            <span class="picker__meta">
              {{ agentStatusMeta(a.status).label }} · {{ a.running }}/{{ a.concurrency }}
              <template v-if="a.ip"> · {{ a.ip }}</template>
            </span>
          </div>
        </el-option>
      </el-select>

      <el-select v-model="statusFilter" class="picker__filter" size="default">
        <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
    </div>

    <div class="picker__actions">
      <template v-if="!single">
        <el-button size="small" text type="primary" :disabled="!options.length" @click="selectOnline">
          全部在线
        </el-button>
        <el-button size="small" text type="primary" :disabled="!selectableCount" @click="selectAll">
          全部可用（{{ selectableCount }}）
        </el-button>
        <el-button size="small" text :disabled="!modelValue.length" @click="clearAll">清空</el-button>
      </template>
      <el-button size="small" text :icon="'Refresh'" @click="refresh">刷新</el-button>
      <span class="spacer" />
      <span class="muted picker__count">已选 {{ modelValue.length }} 台</span>
    </div>

    <el-alert
      v-if="offlineWarn"
      type="warning"
      :closable="false"
      show-icon
      class="picker__warn"
      :title="offlineWarn"
    />
  </div>
</template>

<style scoped>
.picker {
  /* 在 el-form-item 里不要收缩成内容宽度 */
  width: 100%;
  min-width: 0;
}

.picker__row {
  display: flex;
  /* 顶端对齐：选择框换行变高时筛选框不跟着拉伸 */
  align-items: flex-start;
  gap: 8px;
}

.picker__select {
  flex: 1 1 auto;
  min-width: 0;
}

/* 选中项固定单行：标签可省略号收缩，搜索框始终留出输入位置 */
.picker__select :deep(.el-select__selection) {
  flex-wrap: nowrap;
  overflow: hidden;
}

.picker__select :deep(.el-select__selected-item) {
  min-width: 0;
}

.picker__select :deep(.el-tag) {
  max-width: 100%;
}

.picker__select :deep(.el-select__input-wrapper) {
  flex: 1 0 36px;
  min-width: 36px;
}

.picker__filter {
  width: 104px;
  flex: none;
}

.picker__actions {
  display: flex;
  align-items: center;
  gap: 4px 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}

/* 用 gap 控制间距，去掉 Element Plus 相邻按钮的 12px 左边距 */
.picker__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.picker__count {
  font-size: 12px;
  line-height: 1.45;
  white-space: nowrap;
}

.spacer {
  flex: 1;
}

.picker__option {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.picker__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}

.picker__name {
  font-weight: 540;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker__meta {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-left: auto;
  padding-left: 8px;
  white-space: nowrap;
}

.picker__warn {
  margin-top: 8px;
}

.picker__warn :deep(.el-alert__title) {
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}
</style>
