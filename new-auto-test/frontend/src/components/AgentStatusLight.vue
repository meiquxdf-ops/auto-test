<script setup lang="ts">
import { computed } from 'vue'
import type { AgentStatus } from '@/api/types'
import { agentStatusMeta } from '@/utils/status'

const props = withDefaults(
  defineProps<{
    status: AgentStatus
    /** 附加文本，例如 2/4 */
    extra?: string
    showLabel?: boolean
  }>(),
  { extra: '', showLabel: true },
)

const meta = computed(() => agentStatusMeta(props.status))
const animated = computed(() => props.status === 'busy' || props.status === 'disconnected')
</script>

<template>
  <el-tooltip :content="meta.desc" placement="top" :show-after="400">
    <span class="agent-light">
      <span class="agent-light__ring" :style="{ borderColor: meta.color }">
        <span class="agent-light__core" :class="{ pulse: animated }" :style="{ background: meta.color }" />
      </span>
      <span v-if="showLabel" class="agent-light__text" :style="{ color: meta.color }">{{ meta.label }}</span>
      <span v-if="extra" class="agent-light__extra">{{ extra }}</span>
    </span>
  </el-tooltip>
</template>

<style scoped>
.agent-light {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.agent-light__ring {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 1.5px solid;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  opacity: 0.55;
  flex: none;
}

.agent-light__core {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.agent-light__text {
  font-weight: 560;
  font-size: 12.5px;
}

.agent-light__extra {
  color: var(--nat-text-weak);
  font-size: 12px;
}
</style>
