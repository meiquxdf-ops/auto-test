<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: number | string
    unit?: string
    /** 语义色，只画在标题前的小圆点上，数字保持中性 */
    color?: string
    hint?: string
    loading?: boolean
    to?: string
  }>(),
  { unit: '', color: '#64748b', hint: '', loading: false, to: '' },
)
</script>

<template>
  <component
    :is="to ? 'router-link' : 'div'"
    :to="to || undefined"
    class="stat"
    :class="{ 'stat--link': !!to }"
  >
    <div class="stat__label">
      <span class="stat__dot" :style="{ background: color }" />
      <span class="stat__label-text">{{ label }}</span>
    </div>

    <div class="stat__value">
      <span v-if="loading" class="stat__ph" />
      <template v-else>
        {{ value }}<span v-if="unit" class="stat__unit">{{ unit }}</span>
      </template>
    </div>

    <!-- 恒定占一行：加载中不显示假数据，也不让 4 张卡片的行高跳动 -->
    <div class="stat__hint" :title="loading || !hint ? undefined : hint">
      {{ loading ? '' : hint }}
    </div>
  </component>
</template>

<style scoped>
.stat {
  display: block;
  min-width: 0;
  padding: 14px 16px 13px;
  background: var(--nat-panel);
  border: 1px solid var(--nat-border);
  border-radius: var(--nat-radius);
  box-shadow: var(--nat-shadow);
  text-decoration: none;
  color: inherit;
  transition: border-color 0.16s ease, transform 0.16s ease;
}

.stat--link:hover {
  border-color: var(--nat-border-strong);
  transform: translateY(-1px);
}

.stat__label {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.stat__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}

.stat__label-text {
  font-size: 12.5px;
  color: var(--nat-text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat__value {
  display: flex;
  align-items: center;
  min-height: 34px;
  margin-top: 6px;
  font-size: 28px;
  font-weight: 640;
  line-height: 1.2;
  letter-spacing: -0.2px;
  color: var(--nat-text);
  font-variant-numeric: tabular-nums;
}

.stat__unit {
  font-size: 13px;
  font-weight: 500;
  margin-left: 5px;
  color: var(--nat-text-weak);
}

.stat__ph {
  display: block;
  width: 56px;
  height: 20px;
  border-radius: 5px;
  background: #eef1f6;
}

.stat__hint {
  min-height: 16px;
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 16px;
  color: var(--nat-text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (prefers-reduced-motion: reduce) {
  .stat {
    transition: none;
  }

  .stat--link:hover {
    transform: none;
  }
}
</style>
