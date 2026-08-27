<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '@/api/http'
import { getTimeline } from '@/api/timeline'
import type { TimelineEvent } from '@/api/types'
import CopyableId from '@/components/CopyableId.vue'
import TimelineList from '@/components/TimelineList.vue'

/**
 * 开放查询页的「节点」抽屉：按 executeId 拉时间线。
 * 父级用 v-if 挂载、在 @closed 里卸载。
 */
const props = defineProps<{
  executeId: string
  /** 展示用：执行所在机器 */
  machine?: string
}>()

const emit = defineEmits<{ (e: 'closed'): void }>()

const router = useRouter()
const visible = ref(true)
const events = ref<TimelineEvent[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try {
    events.value = await getTimeline({ executeId: props.executeId, limit: 100 })
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e, '加载时间线失败')
    events.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

function gotoFull() {
  visible.value = false
  void router.push({ path: '/timeline', query: { executeId: props.executeId } })
}
</script>

<template>
  <el-drawer v-model="visible" :size="'min(720px, 100vw)'" :with-header="false" @closed="emit('closed')">
    <div class="drawer">
      <div class="drawer__head">
        <div class="drawer__ident">
          <div class="drawer__title">执行节点</div>
          <div class="drawer__sub">
            <span class="drawer__sub-i">executeId <CopyableId :value="executeId" :head="12" /></span>
            <span v-if="machine" class="drawer__sub-i">机器 {{ machine }}</span>
          </div>
        </div>
        <div class="drawer__actions">
          <el-button size="small" :icon="'Refresh'" :loading="loading" @click="load">刷新</el-button>
          <el-button size="small" type="primary" plain @click="gotoFull">全部时间线</el-button>
          <el-button size="small" text :icon="'Close'" @click="visible = false" />
        </div>
      </div>

      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="drawer__alert" />

      <div class="drawer__body">
        <TimelineList :events="events" :loading="loading" empty-text="该执行暂无事件" />
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.drawer__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--nat-border);
}

.drawer__ident {
  flex: 1;
  min-width: 0;
}

.drawer__title {
  font-size: 16px;
  font-weight: 640;
}

.drawer__sub {
  display: flex;
  align-items: center;
  gap: 4px 14px;
  flex-wrap: wrap;
  margin-top: 4px;
  color: var(--nat-text-weak);
  font-size: 12px;
}

.drawer__sub-i {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.drawer__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.drawer__alert {
  margin-top: 10px;
}

.drawer__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-top: 8px;
}
</style>
