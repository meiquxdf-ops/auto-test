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

/** 光晕用同色低透明度画在圆点自身的 box-shadow 上：不给父层设 opacity，圆点始终是实色 */
const halo = computed(() =>
  /^#[0-9a-f]{6}$/i.test(meta.value.color) ? `${meta.value.color}33` : meta.value.color,
)
</script>

<template>
  <el-tooltip :content="meta.desc" placement="top" :show-after="400">
    <span class="agent-light">
      <span class="agent-light__dot" :style="{ background: meta.color, boxShadow: `0 0 0 3px ${halo}` }" />
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

/* 左右各留 3px 给光晕，整体仍占 14px，行高不跳 */
.agent-light__dot {
  width: 8px;
  height: 8px;
  margin: 0 3px;
  border-radius: 50%;
  flex: none;
  transition: background-color 0.2s ease, box-shadow 0.2s ease;
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
