<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import Sortable from 'sortablejs'
import { reorderTasks } from '@/api/tasks'
import { toastError, toastOk } from '@/api/http'
import type { Task } from '@/api/types'
import { formatTime, truncateText } from '@/utils/format'
import EmptyState from './EmptyState.vue'

const visible = defineModel<boolean>({ required: true })

const props = defineProps<{ tasks: Task[] }>()
const emit = defineEmits<{ (e: 'saved'): void }>()

const list = ref<Task[]>([])
const saving = ref(false)
const listEl = ref<HTMLElement | null>(null)
let sortable: Sortable | null = null

const dirty = ref(false)

function snapshot(): string {
  return props.tasks.map((t) => t.taskId).join(',')
}

let original = ''

watch(visible, async (open) => {
  if (open) {
    list.value = [...props.tasks]
    original = snapshot()
    dirty.value = false
    await nextTick()
    mountSortable()
  } else {
    sortable?.destroy()
    sortable = null
  }
})

function mountSortable() {
  if (!listEl.value) return
  sortable?.destroy()
  sortable = Sortable.create(listEl.value, {
    animation: 160,
    handle: '.qr__handle',
    ghostClass: 'qr__item--ghost',
    chosenClass: 'qr__item--chosen',
    onEnd(evt) {
      const from = evt.oldIndex
      const to = evt.newIndex
      if (from === undefined || to === undefined || from === to) return
      const next = [...list.value]
      const [moved] = next.splice(from, 1)
      next.splice(to, 0, moved)
      list.value = next
      dirty.value = true
    },
  })
}

function move(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= list.value.length) return
  const next = [...list.value]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item)
  list.value = next
  dirty.value = true
}

function moveEdge(index: number, edge: 'top' | 'bottom') {
  const next = [...list.value]
  const [item] = next.splice(index, 1)
  if (edge === 'top') next.unshift(item)
  else next.push(item)
  list.value = next
  dirty.value = true
}

function reset() {
  list.value = [...props.tasks]
  dirty.value = false
  void nextTick(() => mountSortable())
}

async function save() {
  saving.value = true
  try {
    await reorderTasks(list.value.map((t) => t.taskId))
    toastOk('队列顺序已更新')
    dirty.value = false
    original = list.value.map((t) => t.taskId).join(',')
    visible.value = false
    emit('saved')
  } catch (e) {
    toastError(e, '调整顺序失败')
  } finally {
    saving.value = false
  }
}

function close() {
  visible.value = false
}

watch(
  () => props.tasks,
  (next) => {
    // 抽屉开着时如果后台列表变了，且用户没有改动，就同步过来
    if (visible.value && !dirty.value && next.map((t) => t.taskId).join(',') !== original) {
      list.value = [...next]
      original = next.map((t) => t.taskId).join(',')
      void nextTick(() => mountSortable())
    }
  },
)
</script>

<template>
  <el-drawer v-model="visible" size="620px" :with-header="false">
    <div class="qr">
      <div class="qr__head">
        <div>
          <div class="qr__title">调整排队顺序</div>
          <div class="qr__sub">
            拖动左侧手柄或用按钮调整。只有 <b>pending</b> 的任务可以排序，正在执行的不会被抢占。
          </div>
        </div>
        <el-button text :icon="'Close'" @click="close" />
      </div>

      <div class="qr__body">
        <EmptyState v-if="!list.length" title="队列里没有排队中的任务" desc="pending 状态的任务才会出现在这里" />
        <div v-else ref="listEl" class="qr__list">
          <div v-for="(task, index) in list" :key="task.taskId" class="qr__item">
            <span class="qr__handle" title="拖动排序">
              <el-icon><Rank /></el-icon>
            </span>
            <span class="qr__index">{{ index + 1 }}</span>
            <div class="qr__main">
              <code class="qr__cmd">{{ truncateText(task.command, 76) }}</code>
              <div class="qr__meta">
                {{ task.targets.length || task.total }} 台目标 · 创建于 {{ formatTime(task.createdAt) }}
                <template v-if="task.operator"> · {{ task.operator }}</template>
              </div>
            </div>
            <div class="qr__btns">
              <el-button text size="small" :disabled="index === 0" title="置顶" @click="moveEdge(index, 'top')">
                <el-icon><Upload /></el-icon>
              </el-button>
              <el-button text size="small" :disabled="index === 0" title="上移" @click="move(index, -1)">
                <el-icon><Top /></el-icon>
              </el-button>
              <el-button
                text
                size="small"
                :disabled="index === list.length - 1"
                title="下移"
                @click="move(index, 1)"
              >
                <el-icon><Bottom /></el-icon>
              </el-button>
              <el-button
                text
                size="small"
                :disabled="index === list.length - 1"
                title="置底"
                @click="moveEdge(index, 'bottom')"
              >
                <el-icon><Download /></el-icon>
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="qr__foot">
        <el-button text :disabled="!dirty" @click="reset">还原</el-button>
        <span class="spacer" />
        <span v-if="dirty" class="qr__dirty">顺序已改动，未保存</span>
        <el-button @click="close">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!dirty || !list.length" @click="save">
          保存顺序
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.qr {
  display: flex;
  flex-direction: column;
  height: 100%;
  margin: -20px;
}

.qr__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 20px 12px;
  border-bottom: 1px solid var(--nat-border);
}

.qr__title {
  font-size: 16px;
  font-weight: 640;
}

.qr__sub {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-top: 4px;
  line-height: 1.6;
}

.qr__body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 20px;
}

.qr__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  background: #fff;
  margin-bottom: 8px;
  transition: box-shadow 0.15s, border-color 0.15s;
}

.qr__item:hover {
  border-color: var(--nat-border-strong);
  box-shadow: 0 2px 8px rgba(17, 24, 39, 0.06);
}

.qr__item--ghost {
  opacity: 0.4;
  background: #eef4ff;
}

.qr__item--chosen {
  border-color: var(--nat-accent);
}

.qr__handle {
  cursor: grab;
  color: var(--nat-text-weak);
  display: flex;
  align-items: center;
}

.qr__handle:active {
  cursor: grabbing;
}

.qr__index {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #eef1f6;
  color: var(--nat-text-sub);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11.5px;
  flex: none;
}

.qr__main {
  flex: 1;
  min-width: 0;
}

.qr__cmd {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  word-break: break-all;
}

.qr__meta {
  color: var(--nat-text-weak);
  font-size: 11.5px;
  margin-top: 3px;
}

.qr__btns {
  display: flex;
  gap: 0;
  flex: none;
}

.qr__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--nat-border);
  background: #fbfcfe;
}

.spacer {
  flex: 1;
}

.qr__dirty {
  color: #ea8a04;
  font-size: 12px;
  margin-right: 6px;
}
</style>
