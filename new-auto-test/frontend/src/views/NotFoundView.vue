<script setup lang="ts">
import { useRoute } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
</script>

<template>
  <div class="page">
    <div class="panel">
      <EmptyState
        variant="error"
        title="页面不存在"
        :desc="`没有匹配的路由：${route.fullPath}`"
      >
        <router-link to="/dashboard"><el-button type="primary">回到总览</el-button></router-link>
        <router-link to="/tasks"><el-button>任务队列</el-button></router-link>
      </EmptyState>
    </div>
  </div>
</template>
