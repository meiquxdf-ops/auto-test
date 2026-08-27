<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CONDITION_OPERATORS,
  OTHER_STATUSES,
  type ConditionConfig,
  type ConditionOperator,
  type ConditionRule,
  type OtherStatus,
} from '@/api/types'
import { normalizeConditionConfig } from '@/api/normalize'
import { CONDITION_OPERATOR_LABEL, statusMeta } from '@/utils/status'
import StatusPill from './StatusPill.vue'

const props = defineProps<{ modelValue: ConditionConfig | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: ConditionConfig | null): void }>()

interface Row extends ConditionRule {
  id: number
}

let seed = 0
const enabled = ref(!!props.modelValue)
const rows = ref<Row[]>([])
const other = ref<OtherStatus | ''>('')
const jsonVisible = ref(false)
const jsonText = ref('')

function load(cfg: ConditionConfig | null) {
  rows.value = (cfg?.rules ?? []).map((r) => ({ ...r, id: ++seed }))
  other.value = cfg?.other ?? ''
  enabled.value = !!cfg
}

load(props.modelValue)

watch(
  () => props.modelValue,
  (next) => {
    if (JSON.stringify(next) === JSON.stringify(current())) return
    load(next)
  },
)

function current(): ConditionConfig | null {
  if (!enabled.value) return null
  const rules: ConditionRule[] = rows.value.map((r) => ({
    operator: r.operator,
    value: r.value,
    status: r.status,
  }))
  if (!rules.length && !other.value) return null
  return { rules, other: other.value || null }
}

function sync() {
  emit('update:modelValue', current())
}

function toggleEnabled(v: boolean) {
  enabled.value = v
  if (v && !rows.value.length) addRow()
  sync()
}

function addRow() {
  rows.value.push({ id: ++seed, operator: 'equals', value: '', status: 'pass' })
  sync()
}

function removeRow(id: number) {
  rows.value = rows.value.filter((r) => r.id !== id)
  sync()
}

function move(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= rows.value.length) return
  const list = [...rows.value]
  const [item] = list.splice(index, 1)
  list.splice(target, 0, item)
  rows.value = list
  sync()
}

const invalidRegex = computed(() => {
  const bad = new Set<number>()
  for (const r of rows.value) {
    if (r.operator !== 'regex' || !r.value) continue
    try {
      new RegExp(r.value)
    } catch {
      bad.add(r.id)
    }
  }
  return bad
})

const emptyValues = computed(() => rows.value.filter((r) => !r.value.trim()).length)

function openJson() {
  jsonText.value = JSON.stringify(current() ?? { rules: [], other: null }, null, 2)
  jsonVisible.value = true
}

function applyJson() {
  try {
    const parsed = JSON.parse(jsonText.value)
    const cfg = normalizeConditionConfig(parsed)
    if (!cfg) {
      ElMessage.error('解析结果为空：至少要有一条规则或 other')
      return
    }
    load(cfg)
    enabled.value = true
    sync()
    jsonVisible.value = false
    ElMessage.success('已导入判定配置')
  } catch (e) {
    ElMessage.error(`JSON 解析失败：${(e as Error).message}`)
  }
}

/** 判定预演：给一行文本，算出会命中哪条 */
const probe = ref('')
const probeResult = computed(() => {
  if (!enabled.value) {
    return { text: '未配置判定：exitCode == 0 判 pass，否则 fail', status: null as OtherStatus | null }
  }
  const line = probe.value
  for (let i = 0; i < rows.value.length; i += 1) {
    const r = rows.value[i]
    if (!r.value) continue
    let hit = false
    switch (r.operator) {
      case 'equals':
        hit = line === r.value
        break
      case 'not-equals':
        hit = line !== r.value
        break
      case 'include':
        hit = line.includes(r.value)
        break
      case 'regex':
        try {
          hit = new RegExp(r.value).test(line)
        } catch {
          hit = false
        }
        break
    }
    if (hit) return { text: `命中第 ${i + 1} 条规则（${CONDITION_OPERATOR_LABEL[r.operator]}）`, status: r.status }
  }
  if (other.value) return { text: '未命中任何规则，取 other', status: other.value }
  return {
    text: line === '0' ? '未命中且无 other，最后一行等于 "0"' : '未命中且无 other，最后一行不等于 "0"',
    status: (line === '0' ? 'pass' : 'fail') as OtherStatus,
  }
})

const operatorOptions = CONDITION_OPERATORS.map((op) => ({
  value: op as ConditionOperator,
  label: `${CONDITION_OPERATOR_LABEL[op]}（${op}）`,
}))
</script>

<template>
  <div class="cond">
    <div class="cond__head">
      <el-switch
        :model-value="enabled"
        active-text="启用判定配置"
        inline-prompt
        @update:model-value="(v: unknown) => toggleEnabled(!!v)"
      />
      <span class="muted cond__hint">
        {{
          enabled
            ? '按顺序匹配最后一行，先匹配先赢'
            : '不配置时按退出码判定：exitCode == 0 → pass，否则 fail'
        }}
      </span>
      <span class="spacer" />
      <el-button v-if="enabled" size="small" text @click="openJson">
        <el-icon><Document /></el-icon>
        JSON
      </el-button>
    </div>

    <template v-if="enabled">
      <div class="cond__table">
        <div class="cond__row cond__row--head">
          <span class="cond__col-idx">顺序</span>
          <span class="cond__col-op">算子</span>
          <span class="cond__col-val">匹配值（对最后一行生效）</span>
          <span class="cond__col-st">判定为</span>
          <span class="cond__col-act" />
        </div>

        <div v-for="(row, index) in rows" :key="row.id" class="cond__row">
          <span class="cond__col-idx">
            <span class="cond__seq">{{ index + 1 }}</span>
          </span>
          <el-select v-model="row.operator" class="cond__col-op" @change="sync">
            <el-option v-for="op in operatorOptions" :key="op.value" :label="op.label" :value="op.value" />
          </el-select>
          <el-input
            v-model="row.value"
            class="cond__col-val"
            spellcheck="false"
            :placeholder="row.operator === 'regex' ? '正则，如 ^ERROR.*' : '例如 0 / SUCCESS'"
            :class="{ 'is-bad': invalidRegex.has(row.id) }"
            @change="sync"
          />
          <el-select v-model="row.status" class="cond__col-st" @change="sync">
            <el-option v-for="st in OTHER_STATUSES" :key="st" :label="statusMeta(st).label" :value="st">
              <StatusPill :status="st" />
            </el-option>
          </el-select>
          <span class="cond__col-act">
            <el-button text :disabled="index === 0" title="上移" @click="move(index, -1)">
              <el-icon><Top /></el-icon>
            </el-button>
            <el-button text :disabled="index === rows.length - 1" title="下移" @click="move(index, 1)">
              <el-icon><Bottom /></el-icon>
            </el-button>
            <el-button text type="danger" title="删除" @click="removeRow(row.id)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </span>
        </div>

        <div v-if="!rows.length" class="cond__empty">还没有规则，添加一条或只设置 other。</div>
      </div>

      <div class="cond__foot">
        <el-button size="small" :icon="'Plus'" @click="addRow">添加规则</el-button>
        <span class="cond__other">
          <span class="muted">都不匹配时（other）</span>
          <el-select
            v-model="other"
            class="cond__other-select"
            clearable
            placeholder="不设置"
            @change="sync"
          >
            <el-option v-for="st in OTHER_STATUSES" :key="st" :label="statusMeta(st).label" :value="st" />
          </el-select>
        </span>
      </div>

      <div v-if="invalidRegex.size" class="cond__warn">有 {{ invalidRegex.size }} 条正则无法编译，请检查。</div>
      <div v-if="emptyValues" class="cond__warn">有 {{ emptyValues }} 条规则匹配值为空，会被忽略。</div>

      <div class="cond__probe">
        <span class="muted nowrap">判定预演</span>
        <el-input v-model="probe" placeholder="粘贴一行输出，看看会判成什么" spellcheck="false" size="small" />
        <StatusPill v-if="probeResult.status" :status="probeResult.status" />
        <span class="muted cond__probe-text">{{ probeResult.text }}</span>
      </div>

      <div v-if="!other" class="cond__tip">
        未设置 other：未命中时按协议兜底 —— 最后一行等于
        <code class="code-inline">"0"</code> 判 pass，否则 fail。
      </div>
    </template>

    <el-dialog v-model="jsonVisible" title="判定配置 JSON" width="560px" append-to-body>
      <el-input v-model="jsonText" type="textarea" :rows="14" spellcheck="false" class="mono" />
      <template #footer>
        <el-button @click="jsonVisible = false">关闭</el-button>
        <el-button type="primary" @click="applyJson">导入并覆盖</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cond {
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  padding: 10px 12px 12px;
  background: #fbfcfe;
}

.cond__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cond__hint {
  font-size: 12px;
}

.spacer {
  flex: 1;
}

.cond__table {
  margin-top: 10px;
}

.cond__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.cond__row--head {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-bottom: 4px;
}

.cond__col-idx {
  width: 42px;
  flex: none;
  text-align: center;
}

.cond__seq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #eef1f6;
  color: var(--nat-text-sub);
  font-size: 11.5px;
}

.cond__col-op {
  width: 148px;
  flex: none;
}

.cond__col-val {
  flex: 1;
  min-width: 0;
}

.cond__col-st {
  width: 110px;
  flex: none;
}

.cond__col-act {
  width: 96px;
  flex: none;
  display: flex;
  gap: 0;
}

.cond__empty {
  color: var(--nat-text-weak);
  font-size: 12.5px;
  padding: 6px 0;
}

.cond__foot {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.cond__other {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cond__other-select {
  width: 130px;
}

.cond__warn {
  color: #ea8a04;
  font-size: 12px;
  margin-top: 6px;
}

.cond__probe {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--nat-border);
  flex-wrap: wrap;
}

.cond__probe .el-input {
  flex: 1;
  min-width: 180px;
}

.cond__probe-text {
  font-size: 12px;
}

.cond__tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--nat-text-weak);
}

:deep(.is-bad .el-input__wrapper) {
  box-shadow: 0 0 0 1px #dc2626 inset;
}
</style>
