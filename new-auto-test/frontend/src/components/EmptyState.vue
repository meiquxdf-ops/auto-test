<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    desc?: string
    variant?: 'empty' | 'error' | 'search'
    size?: 'normal' | 'small'
  }>(),
  { title: '暂无数据', desc: '', variant: 'empty', size: 'normal' },
)
</script>

<template>
  <div class="empty" :class="[`empty--${size}`]">
    <div class="empty__icon" :class="`empty__icon--${variant}`">
      <el-icon v-if="variant === 'error'"><WarnTriangleFilled /></el-icon>
      <el-icon v-else-if="variant === 'search'"><Search /></el-icon>
      <el-icon v-else><Box /></el-icon>
    </div>
    <div class="empty__title">{{ title }}</div>
    <div v-if="desc" class="empty__desc">{{ desc }}</div>
    <div v-if="$slots.extra" class="empty__extra"><slot name="extra" /></div>
    <div class="empty__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  min-width: 0;
  padding: 44px 20px;
  text-align: center;
}

.empty--small {
  padding: 22px 12px;
}

.empty__icon {
  flex: none;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  margin-bottom: 12px;
  background: #eef1f6;
  color: #9aa5b5;
}

.empty--small .empty__icon {
  width: 34px;
  height: 34px;
  font-size: 17px;
  margin-bottom: 8px;
}

.empty__icon--error {
  background: rgba(220, 38, 38, 0.1);
  color: #dc2626;
}

/* 检索无结果和「本来就没有数据」是两回事，只用图标配色区分 */
.empty__icon--search {
  background: rgba(37, 99, 235, 0.08);
  color: var(--nat-accent);
}

.empty__title {
  font-size: 13.5px;
  font-weight: 560;
  line-height: 1.55;
  color: var(--nat-text-sub);
  /* 标题里可能被塞进 64 位不可断行的 requestId */
  max-width: min(42ch, 100%);
  overflow-wrap: anywhere;
}

.empty__desc {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--nat-text-weak);
  max-width: min(52ch, 460px, 100%);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.empty__extra {
  margin-top: 10px;
  max-width: 100%;
  min-width: 0;
}

.empty__actions:not(:empty) {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  max-width: 100%;
}
</style>
