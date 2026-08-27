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
  }>(),
  { single: false, placeholder: '选择目标机器（可搜索 tag / agentId）' },
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
        :max-collapse-tags="6"
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
          选中全部在线
        </el-button>
        <el-button size="small" text type="primary" :disabled="!selectableCount" @click="selectAll">
          选中全部可用（{{ selectableCount }}）
        </el-button>
        <el-button size="small" text :disabled="!modelValue.length" @click="clearAll">清空</el-button>
      </template>
      <el-button size="small" text :icon="'Refresh'" @click="refresh">刷新机器</el-button>
      <span class="spacer" />
      <span class="muted">已选 {{ modelValue.length }} 台</span>
    </div>

    <el-alert
      v-if="offlineSelected.length"
      type="warning"
      :closable="false"
      show-icon
      class="picker__warn"
      :title="`已选机器中有 ${offlineSelected.length} 台不在线（${offlineSelected.join('、')}），任务会先排队等待其上线`"
    />
  </div>
</template>

<style scoped>
.picker__row {
  display: flex;
  gap: 8px;
}

.picker__select {
  flex: 1;
  min-width: 0;
}

.picker__filter {
  width: 118px;
  flex: none;
}

.picker__actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.picker__option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.picker__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}

.picker__name {
  font-weight: 540;
}

.picker__meta {
  color: var(--nat-text-weak);
  font-size: 12px;
}

.picker__warn {
  margin-top: 8px;
}
</style>
