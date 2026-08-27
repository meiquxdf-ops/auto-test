<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: number | string
    unit?: string
    color?: string
    icon?: string
    hint?: string
    loading?: boolean
    to?: string
  }>(),
  { unit: '', color: '#2563eb', icon: 'DataLine', hint: '', loading: false, to: '' },
)
</script>

<template>
  <component
    :is="to ? 'router-link' : 'div'"
    :to="to || undefined"
    class="stat"
    :class="{ 'stat--link': !!to }"
  >
    <div class="stat__icon" :style="{ background: `${color}14`, color }">
      <el-icon><component :is="icon" /></el-icon>
    </div>
    <div class="stat__body">
      <div class="stat__label">{{ label }}</div>
      <div class="stat__value" :style="{ color }">
        <el-skeleton v-if="loading" animated :rows="0" style="width: 60px">
          <template #template><el-skeleton-item variant="text" style="width: 52px; height: 24px" /></template>
        </el-skeleton>
        <template v-else>
          {{ value }}<span v-if="unit" class="stat__unit">{{ unit }}</span>
        </template>
      </div>
      <div v-if="hint" class="stat__hint">{{ hint }}</div>
    </div>
  </component>
</template>

<style scoped>
.stat {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  background: var(--nat-panel);
  border: 1px solid var(--nat-border);
  border-radius: var(--nat-radius);
  padding: 14px 16px;
  box-shadow: var(--nat-shadow);
  text-decoration: none;
  color: inherit;
  transition: border-color 0.15s, transform 0.15s;
}

.stat--link:hover {
  border-color: var(--nat-accent);
  transform: translateY(-1px);
}

.stat__icon {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  flex: none;
}

.stat__body {
  min-width: 0;
}

.stat__label {
  color: var(--nat-text-sub);
  font-size: 12.5px;
}

.stat__value {
  font-size: 24px;
  font-weight: 660;
  line-height: 1.3;
  margin-top: 2px;
  font-variant-numeric: tabular-nums;
}

.stat__unit {
  font-size: 12.5px;
  font-weight: 500;
  margin-left: 3px;
  color: var(--nat-text-weak);
}

.stat__hint {
  color: var(--nat-text-weak);
  font-size: 11.5px;
  margin-top: 3px;
}
</style>
