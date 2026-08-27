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

/** 失联沿用机器「失联」的琥珀色，避免和正常执行中的蓝色混淆 */
const DISCONNECTED_META = {
  color: '#ea8a04',
  bg: 'rgba(234,138,4,.14)',
  border: 'rgba(234,138,4,.34)',
}

const lostConnection = computed(() => props.disconnected && props.status === 'running')

const meta = computed(() => {
  const m = statusMeta(props.status)
  return lostConnection.value ? { ...m, ...DISCONNECTED_META } : m
})

const text = computed(() => {
  const t = lostConnection.value ? '执行中·失联' : statusMeta(props.status).label
  return props.suffix ? `${t} ${props.suffix}` : t
})
</script>

<template>
  <span
    class="status-pill"
    :class="{ 'status-pill--lg': size === 'large' }"
    :style="{ color: meta.color, background: meta.bg, borderColor: meta.border }"
    :title="text"
  >
    <i class="status-pill__dot" />
    <span class="status-pill__label">{{ text }}</span>
  </span>
</template>

<style scoped>
/* 这里重复一份基础样式：即使全局 .status-pill 缺失或滞后，胶囊在窄列里也不会破版 */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  min-width: 0;
  height: 21px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 11px;
  font-size: 12px;
  font-weight: 560;
  line-height: 1;
  vertical-align: middle;
}

.status-pill__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.status-pill__label {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.status-pill--lg {
  height: 28px;
  padding: 0 12px;
  border-radius: 14px;
  font-size: 13px;
}
</style>
