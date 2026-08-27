<script setup lang="ts">
import { computed } from 'vue'
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

const display = computed(() => (props.short ? shortId(props.value, props.head) : props.value || ''))

async function onCopy() {
  if (!props.value) return
  const ok = await copyText(props.value)
  ElMessage[ok ? 'success' : 'error']({ message: ok ? '已复制' : '复制失败', duration: 1500 })
}
</script>

<template>
  <span v-if="!value" class="muted">{{ placeholder }}</span>
  <span v-else class="copyable">
    <el-tooltip :content="value" placement="top" :show-after="300" :disabled="!short">
      <span class="mono copyable__text" :class="{ 'copyable__text--full': !short }">{{ display }}</span>
    </el-tooltip>
    <button type="button" class="copyable__btn" title="复制" aria-label="复制完整 ID" @click.stop="onCopy">
      <el-icon><DocumentCopy /></el-icon>
    </button>
  </span>
</template>

<style scoped>
.copyable {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  /* 表格单元格 overflow:hidden 时先被裁掉的是右侧图标，必须能被压缩 */
  max-width: 100%;
  min-width: 0;
  vertical-align: middle;
}

.copyable__text {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 12px;
  color: var(--nat-text-sub);
}

/* short=false：完整 ID 允许换行，不撑破所在单元格 */
.copyable__text--full {
  white-space: normal;
  overflow-wrap: anywhere;
  text-overflow: clip;
}

.copyable__btn {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  border-radius: 3px;
  background: none;
  color: var(--nat-text-weak);
  /* 触屏没有 hover，常驻低透明度 */
  opacity: 0.45;
  cursor: pointer;
}

.copyable:hover .copyable__btn,
.copyable__btn:focus-visible {
  opacity: 1;
}

.copyable__btn:hover {
  color: var(--nat-accent);
}

.copyable__btn:focus-visible {
  outline: 1px solid var(--nat-accent);
  outline-offset: 1px;
}

.copyable__btn :deep(.el-icon) {
  font-size: 13px;
}
</style>
