<script setup lang="ts">
import { computed } from 'vue'
import type { ExecutionStatus } from '@/api/types'
import { statusMeta } from '@/utils/status'

const props = withDefaults(
  defineProps<{
    status: ExecutionStatus
    size?: 'normal' | 'large'
    /** running 且失联时加标注 */
    disconnected?: boolean
    suffix?: string
  }>(),
  { size: 'normal', disconnected: false, suffix: '' },
)

const meta = computed(() => statusMeta(props.status))
const animated = computed(() => ['running', 'dispatching'].includes(props.status))
const text = computed(() => {
  let t = meta.value.label
  if (props.disconnected && props.status === 'running') t = '执行中 · 失联'
  return props.suffix ? `${t} ${props.suffix}` : t
})
</script>

<template>
  <span
    class="status-pill"
    :class="{ 'status-pill--lg': size === 'large' }"
    :style="{ color: meta.color, background: meta.bg, borderColor: meta.border }"
  >
    <i class="status-pill__dot" :class="{ pulse: animated }" />
    {{ text }}
  </span>
</template>
