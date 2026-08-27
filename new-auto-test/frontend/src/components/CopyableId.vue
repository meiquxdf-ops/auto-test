<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { copyText, shortId } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    value?: string | null
    short?: boolean
    head?: number
    placeholder?: string
  }>(),
  { value: '', short: true, head: 8, placeholder: '-' },
)

async function onCopy() {
  if (!props.value) return
  const ok = await copyText(props.value)
  ElMessage[ok ? 'success' : 'error']({ message: ok ? '已复制' : '复制失败', duration: 1500 })
}
</script>

<template>
  <span v-if="!value" class="muted">{{ placeholder }}</span>
  <span v-else class="copyable">
    <el-tooltip :content="value" placement="top" :show-after="300">
      <span class="mono copyable__text">{{ short ? shortId(value, head) : value }}</span>
    </el-tooltip>
    <el-icon class="copyable__icon" title="复制" @click.stop="onCopy"><DocumentCopy /></el-icon>
  </span>
</template>

<style scoped>
.copyable {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.copyable__text {
  font-size: 12px;
  color: var(--nat-text-sub);
}

.copyable__icon {
  cursor: pointer;
  color: var(--nat-text-weak);
  opacity: 0;
  transition: opacity 0.15s;
}

.copyable:hover .copyable__icon {
  opacity: 1;
}

.copyable__icon:hover {
  color: var(--nat-accent);
}
</style>
