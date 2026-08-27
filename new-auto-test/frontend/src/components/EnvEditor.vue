<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps<{ modelValue: Record<string, string> }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: Record<string, string>): void }>()

interface Row {
  id: number
  k: string
  v: string
}

let seed = 0
const rows = ref<Row[]>([])
const bulkVisible = ref(false)
const bulkText = ref('')

function toRecord(list: Row[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const r of list) {
    const k = r.k.trim()
    if (k) out[k] = r.v
  }
  return out
}

function fromRecord(rec: Record<string, string>): Row[] {
  return Object.entries(rec || {}).map(([k, v]) => ({ id: ++seed, k, v }))
}

rows.value = fromRecord(props.modelValue)

watch(
  () => props.modelValue,
  (next) => {
    if (JSON.stringify(next ?? {}) === JSON.stringify(toRecord(rows.value))) return
    rows.value = fromRecord(next ?? {})
  },
)

function sync() {
  emit('update:modelValue', toRecord(rows.value))
}

function addRow() {
  rows.value.push({ id: ++seed, k: '', v: '' })
}

function removeRow(id: number) {
  rows.value = rows.value.filter((r) => r.id !== id)
  sync()
}

function clearAll() {
  rows.value = []
  sync()
}

const duplicated = computed(() => {
  const seen = new Set<string>()
  const dup = new Set<string>()
  for (const r of rows.value) {
    const k = r.k.trim()
    if (!k) continue
    if (seen.has(k)) dup.add(k)
    seen.add(k)
  }
  return dup
})

function openBulk() {
  bulkText.value = rows.value
    .filter((r) => r.k.trim())
    .map((r) => `${r.k.trim()}=${r.v}`)
    .join('\n')
  bulkVisible.value = true
}

function applyBulk() {
  const next: Row[] = []
  for (const rawLine of bulkText.value.split('\n')) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const idx = line.indexOf('=')
    if (idx <= 0) {
      ElMessage.error(`无法解析这一行：${line}`)
      return
    }
    next.push({ id: ++seed, k: line.slice(0, idx).trim(), v: line.slice(idx + 1) })
  }
  rows.value = next
  sync()
  bulkVisible.value = false
  ElMessage.success(`已导入 ${next.length} 个环境变量`)
}
</script>

<template>
  <div class="env">
    <div v-if="!rows.length" class="env__empty">
      未设置环境变量，执行时继承 Agent 进程环境。
    </div>

    <div v-for="row in rows" :key="row.id" class="env__row">
      <el-input
        v-model="row.k"
        placeholder="KEY"
        class="env__k"
        spellcheck="false"
        :class="{ 'is-dup': duplicated.has(row.k.trim()) }"
        @change="sync"
      />
      <span class="env__eq">=</span>
      <el-input v-model="row.v" placeholder="VALUE" class="env__v" spellcheck="false" @change="sync" />
      <el-button text type="danger" :icon="'Delete'" @click="removeRow(row.id)" />
    </div>

    <div v-if="duplicated.size" class="env__warn">存在重复的 KEY：{{ [...duplicated].join('、') }}，后者会覆盖前者。</div>

    <div class="env__actions">
      <el-button size="small" :icon="'Plus'" @click="addRow">添加变量</el-button>
      <el-button size="small" :icon="'Document'" @click="openBulk">批量编辑</el-button>
      <el-button v-if="rows.length" size="small" text type="danger" @click="clearAll">清空</el-button>
    </div>

    <el-dialog v-model="bulkVisible" title="批量编辑环境变量" width="520px" append-to-body>
      <el-input
        v-model="bulkText"
        type="textarea"
        :rows="10"
        spellcheck="false"
        placeholder="每行一个，形如&#10;JAVA_HOME=/usr/lib/jvm/java-17&#10;MODE=stress"
      />
      <div class="muted" style="margin-top: 8px">以 # 开头的行会被忽略。</div>
      <template #footer>
        <el-button @click="bulkVisible = false">取消</el-button>
        <el-button type="primary" @click="applyBulk">应用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.env__row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.env__k {
  width: 38%;
}

.env__v {
  flex: 1;
}

.env__eq {
  color: var(--nat-text-weak);
}

.env__empty {
  color: var(--nat-text-weak);
  font-size: 12.5px;
  padding: 6px 0 8px;
}

.env__warn {
  color: #ea8a04;
  font-size: 12px;
  margin: 2px 0 6px;
}

.env__actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}

:deep(.is-dup .el-input__wrapper) {
  box-shadow: 0 0 0 1px #ea8a04 inset;
}
</style>
