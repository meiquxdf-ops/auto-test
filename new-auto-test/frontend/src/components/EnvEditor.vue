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
    <div v-if="!rows.length" class="env__empty">未设变量，执行时继承 Agent 环境。</div>

    <div v-for="row in rows" :key="row.id" class="env__row">
      <el-input
        v-model="row.k"
        placeholder="KEY"
        class="env__k"
        spellcheck="false"
        :class="{ 'is-dup': duplicated.has(row.k.trim()) }"
        @change="sync"
      />
      <el-input v-model="row.v" placeholder="VALUE" class="env__v" spellcheck="false" @change="sync" />
      <el-button class="env__del" text type="danger" :icon="'Delete'" title="删除" @click="removeRow(row.id)" />
    </div>

    <div v-if="duplicated.size" class="env__warn">重复 KEY：{{ [...duplicated].join('、') }}，后者覆盖前者</div>

    <div class="env__actions">
      <el-button size="small" :icon="'Plus'" @click="addRow">添加</el-button>
      <el-button size="small" :icon="'Document'" @click="openBulk">批量</el-button>
      <el-button v-if="rows.length" size="small" text type="danger" @click="clearAll">清空</el-button>
    </div>

    <el-dialog
      v-model="bulkVisible"
      title="批量编辑环境变量"
      width="min(520px, calc(100vw - 32px))"
      append-to-body
    >
      <el-input
        v-model="bulkText"
        type="textarea"
        :rows="10"
        spellcheck="false"
        placeholder="每行一个，形如&#10;JAVA_HOME=/usr/lib/jvm/java-17&#10;MODE=stress"
      />
      <div class="muted env__dialog-hint"># 开头的行会忽略。</div>
      <template #footer>
        <el-button @click="bulkVisible = false">取消</el-button>
        <el-button type="primary" @click="applyBulk">应用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.env {
  /* 在 el-form-item 里不要收缩成内容宽度 */
  width: 100%;
  min-width: 0;
  container-type: inline-size;
}

/* 窄容器：KEY 与删除同行，VALUE 独占一行；宽容器再并成一行 */
.env__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas:
    'k del'
    'v v';
  align-items: center;
  /* 行内 4px、行间 14px：窄屏下 KEY / VALUE 才读得出是一组 */
  gap: 4px 8px;
  margin-bottom: 14px;
}

.env__k {
  grid-area: k;
  min-width: 0;
}

.env__v {
  grid-area: v;
  min-width: 0;
}

.env__del {
  grid-area: del;
}

@container (min-width: 460px) {
  .env__row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto;
    grid-template-areas: 'k v del';
    margin-bottom: 6px;
  }
}

.env__empty {
  color: var(--nat-text-weak);
  font-size: 12.5px;
  line-height: 1.45;
  padding: 6px 0 8px;
}

.env__warn {
  color: #ea8a04;
  font-size: 12px;
  line-height: 1.45;
  margin: 2px 0 6px;
  word-break: break-word;
}

.env__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 2px;
}

/* 用 gap 控制间距，去掉 Element Plus 相邻按钮的 12px 左边距 */
.env__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.env__dialog-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.45;
}

:deep(.is-dup .el-input__wrapper) {
  box-shadow: 0 0 0 1px #ea8a04 inset;
}
</style>
