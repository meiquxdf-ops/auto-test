<script setup lang="ts">
import { useRoute } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
</script>

<template>
  <div class="nf">
    <div class="nf__card panel">
      <EmptyState
        variant="search"
        title="页面不存在"
        desc="这个地址没有匹配到任何路由，可能是链接已失效，或者路径拼写有误。"
      >
        <template #extra>
          <code class="nf__path mono">{{ route.fullPath }}</code>
        </template>
        <router-link to="/dashboard"><el-button type="primary">回到总览</el-button></router-link>
        <router-link to="/tasks"><el-button>任务队列</el-button></router-link>
      </EmptyState>
    </div>
  </div>
</template>

<style scoped>
/* 内边距和 min-height 放在同一个元素上（box-sizing: border-box），不会额外撑出滚动条 */
.nf {
  min-height: calc(100vh - 80px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.nf__card {
  width: 100%;
  max-width: 460px;
}

.nf__path {
  display: block;
  max-width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--nat-border);
  border-radius: 6px;
  background: #f6f8fb;
  color: var(--nat-text-sub);
  font-size: 12px;
  line-height: 1.6;
  text-align: left;
  overflow-wrap: anywhere;
}
</style>
