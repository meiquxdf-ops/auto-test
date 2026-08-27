<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { SSE_STATE_TEXT } from '@/api/sse'
import { useExecutionLog } from '@/composables/useExecutionLog'
import CopyableId from '@/components/CopyableId.vue'
import LogTerminal from '@/components/LogTerminal.vue'
import StatusPill from '@/components/StatusPill.vue'

/**
 * 开放查询页的日志抽屉。
 *
 * 组件挂载即开始拉历史日志并接 SSE；父级用 v-if 挂载、在 @closed 里卸载，
 * useExecutionLog 的 onScopeDispose 会随组件销毁把 SSE 与轮询一并停掉。
 */
const props = defineProps<{
  executeId: string
  /** 展示用：所属任务的命令 */
  command?: string
}>()

const emit = defineEmits<{ (e: 'closed'): void }>()

const router = useRouter()
const visible = ref(true)
const executeIdRef = computed(() => props.executeId)

const {
  execution,
  status,
  lines,
  truncated,
  droppedBytes,
  totalBytes,
  clientTrimmed,
  logLoading,
  error,
  sseState,
  finished,
  reload,
  reconnect,
} = useExecutionLog(executeIdRef)

const machine = computed(() => execution.value?.displayTag || execution.value?.agentId || '-')
const connType = computed(() => (finished.value ? 'info' : sseState.value === 'open' ? 'success' : 'warning'))
const connText = computed(() => (finished.value ? '已结束' : SSE_STATE_TEXT[sseState.value]))

function openFull() {
  visible.value = false
  void router.push(`/executions/${props.executeId}`)
}
</script>

<template>
  <el-drawer v-model="visible" :size="'min(880px, 100vw)'" :with-header="false" @closed="emit('closed')">
    <div class="drawer">
      <div class="drawer__head">
        <div class="drawer__ident">
          <div class="drawer__title">
            <span class="drawer__name">执行日志</span>
            <el-tag size="small" round :type="connType">{{ connText }}</el-tag>
            <StatusPill v-if="execution" :status="status" :disconnected="execution.disconnected" />
            <button v-if="!finished && sseState !== 'open'" class="link-btn" @click="reconnect">重连</button>
          </div>
          <div class="drawer__sub">
            <span class="drawer__sub-i">executeId <CopyableId :value="executeId" :head="12" /></span>
            <span class="drawer__sub-i">机器 {{ machine }}</span>
            <span class="drawer__sub-i">共 {{ lines.length }} 行</span>
          </div>
          <div v-if="command" class="drawer__cmd mono" :title="command">{{ command }}</div>
        </div>
        <div class="drawer__actions">
          <el-button size="small" type="primary" plain @click="openFull">在执行页打开</el-button>
          <el-button size="small" text :icon="'Close'" @click="visible = false" />
        </div>
      </div>

      <div class="drawer__body">
        <LogTerminal
          :lines="lines"
          :truncated="truncated"
          :dropped-bytes="droppedBytes"
          :total-bytes="totalBytes"
          :client-trimmed="clientTrimmed"
          :loading="logLoading"
          :error-text="error"
          height="fill"
          :file-name="`${executeId}.log`"
          :foot-note="finished ? '执行已结束' : 'SSE 断线自动续传'"
          @retry="reload"
        />
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
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-size: 16px;
  font-weight: 640;
  flex-wrap: wrap;
}

.drawer__title .link-btn {
  font-size: 12px;
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

.drawer__cmd {
  margin-top: 5px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--nat-text-sub);
  font-size: 12px;
}

.drawer__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.drawer__body {
  flex: 1;
  min-height: 0;
  padding-top: 10px;
}
</style>
