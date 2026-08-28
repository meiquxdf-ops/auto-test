<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorMessage, getApiBase, rawRequest, type RawHttpResult } from '@/api/http'
import type { ConditionConfig, RerunMode } from '@/api/types'
import { useAgents } from '@/stores/agents'
import { isEmbed } from '@/utils/embed'
import { copyText, formatBytes, formatTime } from '@/utils/format'
import AgentPicker from '@/components/AgentPicker.vue'
import ConditionEditor from '@/components/ConditionEditor.vue'
import CopyableId from '@/components/CopyableId.vue'
import EnvEditor from '@/components/EnvEditor.vue'

const route = useRoute()

/** 嵌入宿主（iframe / embed=1）：隐藏页头，正文占满 iframe */
const embedded = computed(() => isEmbed(route.query))

const ID_RE = /^[A-Za-z0-9._-]{1,64}$/

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** 生成一个符合 ^[A-Za-z0-9._-]{1,64}$ 的调试用唯一 id */
function genRequestId(): string {
  const d = new Date()
  const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  const rand = Math.random().toString(36).slice(2, 6)
  return `dbg-${stamp}-${rand}`
}

const SAMPLE_COMMAND = 'echo hello from $(hostname)'

/* ------------------------------------------------------------ 操作定义 */

type OpKey =
  | 'create'
  | 'batch'
  | 'query'
  | 'cancel'
  | 'rerun'
  | 'upExec'
  | 'upTask'
  | 'files'
  | 'download'

const OPS: { key: OpKey; method: 'GET' | 'POST'; label: string; brief: string }[] = [
  { key: 'create', method: 'POST', label: '单条创建', brief: '/api/tasks' },
  { key: 'batch', method: 'POST', label: '批量创建', brief: '/api/tasks/batch' },
  { key: 'query', method: 'GET', label: '按 requestId 查询', brief: '/api/tasks?requestId=' },
  { key: 'cancel', method: 'POST', label: '取消任务', brief: '/api/tasks/{id}/cancel' },
  { key: 'rerun', method: 'POST', label: '重跑任务', brief: '/api/tasks/{id}/rerun' },
  { key: 'upExec', method: 'POST', label: '执行回传附件', brief: '/api/executions/{executeId}/files' },
  { key: 'upTask', method: 'POST', label: '任务补附件', brief: '/api/tasks/{taskId}/files' },
  { key: 'files', method: 'GET', label: '附件列表', brief: '/api/tasks/{taskId}/files' },
  { key: 'download', method: 'GET', label: '附件下载', brief: '/api/files/{fileId}' },
]

const activeOp = ref<OpKey>('create')

/* ------------------------------------------------------------ 各操作表单 */

const createForm = reactive({
  requestId: genRequestId(),
  callbackUrl: '',
  command: SAMPLE_COMMAND,
  targets: [] as string[],
  cwd: '',
  env: {} as Record<string, string>,
  timeoutSec: undefined as number | undefined,
  operator: '',
  conditionConfig: null as ConditionConfig | null,
})

interface BatchItemRow {
  id: number
  name: string
  command: string
  targets: string[]
  timeoutSec: number | undefined
}

let itemSeed = 0

function newBatchItem(command: string, targets: string[] = []): BatchItemRow {
  return { id: ++itemSeed, name: '', command, targets, timeoutSec: undefined }
}

const batchForm = reactive({
  requestId: genRequestId(),
  callbackUrl: '',
  items: [newBatchItem(SAMPLE_COMMAND), newBatchItem('uname -a')] as BatchItemRow[],
})

function addBatchItem() {
  batchForm.items.push(newBatchItem(SAMPLE_COMMAND, firstOnline.value ? [firstOnline.value] : []))
}

function removeBatchItem(id: number) {
  if (batchForm.items.length <= 1) return
  batchForm.items = batchForm.items.filter((it) => it.id !== id)
}

const queryForm = reactive({ requestId: '' })
const cancelForm = reactive({ taskId: '', operator: '' })
const rerunForm = reactive({ taskId: '', mode: 'inplace' as RerunMode })

/* ------------------------------------------------------------ 附件操作表单 */

const upExecForm = reactive({ executeId: '' })
const upTaskForm = reactive({ taskId: '' })
const filesForm = reactive({ taskId: '' })
const downloadForm = reactive({ fileId: '', inline: false })

/** 附件单文件硬上限，与 Server 一致（超限 Server 返回 413）；浏览器侧超限直接拦下不发送 */
const MAX_FILE_BYTES = 32 * 1024 * 1024

const upExecFile = ref<File | null>(null)
const upTaskFile = ref<File | null>(null)
const fileInputEl = ref<HTMLInputElement | null>(null)
let filePickTarget: 'upExec' | 'upTask' = 'upExec'

function pickFile(target: 'upExec' | 'upTask') {
  filePickTarget = target
  fileInputEl.value?.click()
}

function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file) return
  if (file.size > MAX_FILE_BYTES) {
    ElMessage.error(
      `「${file.name}」${formatBytes(file.size)}，超过单附件 32MB 上限（Server 会拒绝 413）：请压缩或拆分后再传`,
    )
    return
  }
  if (filePickTarget === 'upExec') upExecFile.value = file
  else upTaskFile.value = file
}

function clearFile(target: 'upExec' | 'upTask') {
  if (target === 'upExec') upExecFile.value = null
  else upTaskFile.value = null
}

/** 从 URL 预填 requestId（旧外链有过 reuqestId 的拼写错误，两种都认），并直接切到查询操作 */
for (const key of ['requestId', 'reuqestId'] as const) {
  const v = route.query[key]
  if (typeof v === 'string' && v.trim()) {
    queryForm.requestId = v.trim()
    activeOp.value = 'query'
    break
  }
}

/* ------------------------------------------------------------ 目标机器默认值 */

const { agents } = useAgents()

const firstOnline = computed(() => {
  const a =
    agents.value.find((x) => x.status === 'online') ?? agents.value.find((x) => x.status === 'busy')
  return a ? a.displayTag || a.agentId : ''
})

/** 机器列表就绪后给示例请求预填第一台在线机器，只做一次，不覆盖用户清空后的选择 */
let targetsPrefilled = false
watch(
  firstOnline,
  (t) => {
    if (!t || targetsPrefilled) return
    targetsPrefilled = true
    if (!createForm.targets.length) createForm.targets = [t]
    for (const it of batchForm.items) if (!it.targets.length) it.targets = [t]
  },
  { immediate: true },
)

/* ------------------------------------------------------------ 表单 → 请求 */

interface BuiltRequest {
  method: 'GET' | 'POST'
  path: string
  body: Record<string, unknown> | null
  /** multipart 上传的文件（此时 body 为 null，字段名固定 file） */
  upload?: File | null
  /** 响应体可能是二进制（附件下载），不按 JSON 解析 */
  binary?: boolean
  /** 非空表示缺少拼 URL 必需的字段，禁止发送 */
  blocked: string
}

const built = computed<BuiltRequest>(() => {
  switch (activeOp.value) {
    case 'create': {
      const f = createForm
      const body: Record<string, unknown> = { command: f.command, targets: [...f.targets] }
      if (f.requestId.trim()) body.requestId = f.requestId.trim()
      if (f.callbackUrl.trim()) body.callbackUrl = f.callbackUrl.trim()
      if (f.cwd.trim()) body.cwd = f.cwd.trim()
      if (Object.keys(f.env).length) body.env = { ...f.env }
      if (f.timeoutSec) body.timeoutSec = f.timeoutSec
      if (f.operator.trim()) body.operator = f.operator.trim()
      if (f.conditionConfig) body.conditionConfig = f.conditionConfig
      return { method: 'POST', path: '/api/tasks', body, blocked: '' }
    }
    case 'batch': {
      const f = batchForm
      const body: Record<string, unknown> = { requestId: f.requestId.trim() }
      if (f.callbackUrl.trim()) body.callbackUrl = f.callbackUrl.trim()
      body.items = f.items.map((it) => {
        const o: Record<string, unknown> = { command: it.command, targets: [...it.targets] }
        if (it.name.trim()) o.name = it.name.trim()
        if (it.timeoutSec) o.timeoutSec = it.timeoutSec
        return o
      })
      return { method: 'POST', path: '/api/tasks/batch', body, blocked: '' }
    }
    case 'query': {
      const id = queryForm.requestId.trim()
      return {
        method: 'GET',
        path: `/api/tasks?requestId=${encodeURIComponent(id)}`,
        body: null,
        blocked: id ? '' : '先填 requestId 才能拼出查询 URL',
      }
    }
    case 'cancel': {
      const id = cancelForm.taskId.trim()
      const op = cancelForm.operator.trim()
      return {
        method: 'POST',
        path: `/api/tasks/${encodeURIComponent(id)}/cancel${op ? `?operator=${encodeURIComponent(op)}` : ''}`,
        body: null,
        blocked: id ? '' : '先填 taskId 才能拼出取消 URL',
      }
    }
    case 'rerun': {
      const id = rerunForm.taskId.trim()
      return {
        method: 'POST',
        path: `/api/tasks/${encodeURIComponent(id)}/rerun`,
        body: { mode: rerunForm.mode },
        blocked: id ? '' : '先填 taskId 才能拼出重跑 URL',
      }
    }
    case 'upExec': {
      const id = upExecForm.executeId.trim()
      const f = upExecFile.value
      return {
        method: 'POST',
        path: `/api/executions/${encodeURIComponent(id)}/files`,
        body: null,
        upload: f,
        blocked: !id
          ? '先填 executeId 才能拼出上传 URL'
          : !f
            ? '先选择要上传的文件'
            : f.size > MAX_FILE_BYTES
              ? '文件超过 32MB 上限，Server 会拒绝（413），请压缩或拆分'
              : '',
      }
    }
    case 'upTask': {
      const id = upTaskForm.taskId.trim()
      const f = upTaskFile.value
      return {
        method: 'POST',
        path: `/api/tasks/${encodeURIComponent(id)}/files`,
        body: null,
        upload: f,
        blocked: !id
          ? '先填 taskId 才能拼出上传 URL'
          : !f
            ? '先选择要上传的文件'
            : f.size > MAX_FILE_BYTES
              ? '文件超过 32MB 上限，Server 会拒绝（413），请压缩或拆分'
              : '',
      }
    }
    case 'files': {
      const id = filesForm.taskId.trim()
      return {
        method: 'GET',
        path: `/api/tasks/${encodeURIComponent(id)}/files`,
        body: null,
        blocked: id ? '' : '先填 taskId 才能拼出列表 URL',
      }
    }
    case 'download': {
      const id = downloadForm.fileId.trim()
      return {
        method: 'GET',
        path: `/api/files/${encodeURIComponent(id)}${downloadForm.inline ? '?inline=1' : ''}`,
        body: null,
        binary: true,
        blocked: id ? '' : '先填 fileId 才能拼出下载 URL（fileId 在附件列表响应的 id 字段）',
      }
    }
  }
})

const hasBody = computed(() => built.value.body !== null)
const isUploadOp = computed(() => activeOp.value === 'upExec' || activeOp.value === 'upTask')
const uploadFile = computed(() => built.value.upload ?? null)

/**
 * 开发态 apiBase 为空（走 vite 代理），curl 拼当前源同样可用。
 * 页头能随时改接口地址，这里必须跟着重算：否则预览 / 复制的 URL 与 curl 会指向旧 Server，
 * 而请求实际已经发去新地址。
 */
const apiOrigin = computed(() => getApiBase() || window.location.origin)
const fullUrl = computed(() => `${apiOrigin.value}${built.value.path}`)

/* ------------------------------------------------------------ 请求体 JSON（表单生成，可手动改） */

const builtBodyText = computed(() =>
  built.value.body === null ? '' : JSON.stringify(built.value.body, null, 2),
)

const jsonText = ref(builtBodyText.value)
const jsonDirty = ref(false)
const jsonParseError = ref('')

watch(builtBodyText, (t) => {
  if (!jsonDirty.value) {
    jsonText.value = t
    jsonParseError.value = ''
  }
})

watch(activeOp, () => {
  jsonDirty.value = false
  jsonText.value = builtBodyText.value
  jsonParseError.value = ''
})

function onJsonInput() {
  jsonDirty.value = jsonText.value !== builtBodyText.value
  jsonParseError.value = ''
}

function resetJson() {
  jsonDirty.value = false
  jsonText.value = builtBodyText.value
  jsonParseError.value = ''
}

/* ------------------------------------------------------------ curl */

const curlText = computed(() => {
  if (isUploadOp.value) {
    const name = (uploadFile.value?.name || '产物文件').replace(/'/g, `'\\''`)
    return `curl -X POST '${fullUrl.value}' \\\n  -F 'file=@${name}'`
  }
  if (built.value.binary) {
    // -OJ：按 Content-Disposition 里的原始文件名落盘
    return `curl -OJ '${fullUrl.value}'`
  }
  const parts = [`curl -X ${built.value.method} '${fullUrl.value}'`]
  if (hasBody.value) {
    parts.push(`-H 'Content-Type: application/json'`)
    parts.push(`-d '${(jsonText.value || '{}').replace(/'/g, `'\\''`)}'`)
  }
  return parts.join(' \\\n  ')
})

async function copy(text: string, okTip = '已复制') {
  const ok = await copyText(text)
  ElMessage[ok ? 'success' : 'error']({ message: ok ? okTip : '复制失败', duration: 1500 })
}

/* ------------------------------------------------------------ 发送与响应 */

interface DebugResponse {
  method: string
  url: string
  status: number
  statusText: string
  durationMs: number
  data: unknown
  at: number
}

const sending = ref(false)
const resp = ref<DebugResponse | null>(null)
const netError = ref('')

async function send() {
  const req = built.value
  if (req.blocked) {
    ElMessage.warning(req.blocked)
    return
  }
  let body: unknown
  if (req.body !== null) {
    if (jsonDirty.value) {
      try {
        body = JSON.parse(jsonText.value)
      } catch (e) {
        jsonParseError.value = `请求体不是合法 JSON：${e instanceof Error ? e.message : String(e)}`
        return
      }
    } else {
      body = req.body
    }
  }
  sending.value = true
  netError.value = ''
  const method = req.method
  const url = fullUrl.value
  try {
    let r: RawHttpResult
    if (req.upload) {
      const fd = new FormData()
      fd.append('file', req.upload)
      // 32MB 在慢网络也要能传完，放宽到 3 分钟
      r = await rawRequest(req.method, req.path, fd, { timeoutMs: 180_000 })
    } else if (req.binary) {
      r = await rawRequest(req.method, req.path, undefined, {
        responseType: 'blob',
        timeoutMs: 180_000,
      })
      if (r.data instanceof Blob) r = { ...r, data: await blobDisplayable(r.data) }
    } else {
      r = await rawRequest(req.method, req.path, body)
    }
    resp.value = { ...r, method, url, at: Date.now() }
  } catch (e) {
    resp.value = null
    netError.value = errorMessage(e, '请求失败')
  } finally {
    sending.value = false
  }
}

/** 附件下载响应：小的文本/JSON（含 404 等错误体）转出来展示，其余保持 Blob 只展示元信息 */
async function blobDisplayable(b: Blob): Promise<unknown> {
  const type = (b.type || '').toLowerCase()
  const texty = type.includes('json') || type.startsWith('text/') || type.includes('xml')
  if (!texty || b.size > 256 * 1024) return b
  const text = await b.text()
  if (type.includes('json')) {
    try {
      return JSON.parse(text)
    } catch {
      // 不是合法 JSON 就按原文展示
    }
  }
  return text
}

const respText = computed(() => {
  if (!resp.value) return ''
  const d = resp.value.data
  if (d instanceof Blob) {
    return `（二进制响应体：${formatBytes(d.size)}${d.type ? ` · ${d.type}` : ''}，不在页面展示，点上方「在新标签页下载」保存）`
  }
  if (d === undefined || d === null || d === '') return '（空响应体）'
  return typeof d === 'string' ? d : JSON.stringify(d, null, 2)
})

/** 2xx 墨绿 / 4xx 琥珀 / 5xx 与网络错误哑光红 */
const respTone = computed(() => {
  if (!resp.value) return ''
  const s = resp.value.status
  if (s < 300) return 'ok'
  if (s < 500) return 'warn'
  return 'err'
})

function digRequestId(v: unknown, depth = 0): string {
  if (!v || typeof v !== 'object' || depth > 2) return ''
  const o = v as Record<string, unknown>
  if (typeof o.requestId === 'string' && o.requestId) return o.requestId
  return digRequestId(o.data, depth + 1)
}

/** 创建/批量/重跑成功后从响应里挖 requestId，突出展示并给出开放查询直达链接 */
const respRequestId = computed(() =>
  resp.value && resp.value.status < 300 ? digRequestId(resp.value.data) : '',
)

const watchLink = computed(() => {
  if (!respRequestId.value) return null
  const query: Record<string, string> = { requestId: respRequestId.value }
  if (embedded.value) query.embed = '1'
  return { path: '/open', query }
})

/** 手册区指向开放查询页的入口 */
const openLink = computed(() => ({
  path: '/open',
  query: embedded.value ? { embed: '1' } : {},
}))

/** 批量创建响应里的 tasks / errors 数量（部分成功一眼可见） */
const respBatchStat = computed(() => {
  const r = resp.value
  if (!r || r.status >= 300 || !r.data || typeof r.data !== 'object') return null
  const o = r.data as Record<string, unknown>
  const src = o.data && typeof o.data === 'object' ? (o.data as Record<string, unknown>) : o
  if (!Array.isArray(src.tasks) && !Array.isArray(src.errors)) return null
  return {
    ok: Array.isArray(src.tasks) ? src.tasks.length : 0,
    bad: Array.isArray(src.errors) ? src.errors.length : 0,
  }
})

const respHint = computed(() => {
  const r = resp.value
  if (!r) return ''
  if (r.status === 409)
    return 'requestId 已被占用（全局唯一）：换一个或点「生成」；已存在的批次可直接去开放查询看进度。'
  if (r.status === 400 && r.url.includes('/api/tasks/batch'))
    return '整单 400：requestId 缺失/格式错，或所有条目都无效。此时 requestId 未被占用，修正后可原样重试。'
  if (r.status === 413)
    return '附件超过单文件 32MB 硬上限（413）：压缩或拆分后重试，Server 侧容器层与应用层双保险，不会放行。'
  if (r.status === 429)
    return '附件上传并发已满（429，默认 8 个在写 + 8 个排队）：稍后重试即可，Server 不排队拖死连接。'
  if (r.status === 503)
    return '附件写盘失败（503）：Server 磁盘/IO 故障，请联系运维检查 attachments 目录所在磁盘。'
  if (r.status === 404 && r.url.includes('/files'))
    return '404：executeId / taskId / fileId 不存在。executeId 在创建/查询响应的 executions[].executeId 里，fileId 在附件列表响应的 id 字段。'
  return ''
})

function fillQuery() {
  queryForm.requestId = respRequestId.value
  activeOp.value = 'query'
}

/* ------------------------------------------------------------ 附件响应的便捷动线 */

/** 附件下载成功后给一个真实链接：浏览器直接按 Server 返回的文件名保存 */
const respFileUrl = computed(() => {
  const r = resp.value
  return r && r.status < 300 && r.url.includes('/api/files/') ? r.url : ''
})

function digTaskId(v: unknown, depth = 0): string {
  if (!v || typeof v !== 'object' || depth > 2) return ''
  const o = v as Record<string, unknown>
  if (typeof o.taskId === 'number' || (typeof o.taskId === 'string' && o.taskId)) {
    return String(o.taskId)
  }
  return digTaskId(o.data, depth + 1)
}

/** 上传成功后从响应里挖 taskId，一键切到附件列表核对 */
const respTaskId = computed(() =>
  resp.value && resp.value.status < 300 && isUploadOp.value ? digTaskId(resp.value.data) : '',
)

function fillFilesList() {
  filesForm.taskId = respTaskId.value
  activeOp.value = 'files'
}

/* ------------------------------------------------------------ 表单校验提示（软提示，不拦发送） */

const createIdHint = computed(() => {
  const v = createForm.requestId.trim()
  if (v && !ID_RE.test(v)) return { text: '格式需匹配 ^[A-Za-z0-9._-]{1,64}$，发送会得到 400', error: true }
  return { text: '调用方自带的全局唯一幂等键，重复会得到 409；留空则由服务端生成 UUID 并回显', error: false }
})

const batchIdHint = computed(() => {
  const v = batchForm.requestId.trim()
  if (!v) return { text: '批量创建必须由调用方提供 requestId，留空发送会得到 400', error: true }
  if (!ID_RE.test(v)) return { text: '格式需匹配 ^[A-Za-z0-9._-]{1,64}$，发送会得到 400', error: true }
  return { text: '整批共用这一个 requestId，之后用它查询整批进度', error: false }
})

const createTargetsHint = computed(() =>
  createForm.targets.length ? '' : '未选目标：发送会得到 400（也可以用它观察错误响应）',
)
</script>

<template>
  <div class="page od theme-open" :class="{ 'od--embed': embedded }">
    <div class="od__wrap">
      <div v-if="!embedded" class="page-head">
        <div>
          <h2 class="page-head__title">接入调试</h2>
          <p class="page-head__desc">
            面向接入方的 Open API 请求台：拼 requestId / callbackUrl / 附件上传等参数，向 Server
            发送真实 HTTP 请求，查看原始状态码与响应体，并复制等价 curl 带回自己的系统。
            语义速读见下方「接入手册」，完整版在仓库
            <code class="code-inline">new-auto-test/docs/open-api.md</code>。
          </p>
        </div>
      </div>

      <!-- 操作选择 -->
      <div class="panel od__ops" role="tablist" aria-label="选择要调试的接口">
        <button
          v-for="op in OPS"
          :key="op.key"
          type="button"
          role="tab"
          class="od__op"
          :class="{ 'is-on': activeOp === op.key }"
          :aria-selected="activeOp === op.key"
          @click="activeOp = op.key"
        >
          <span class="od__op-method mono" :data-m="op.method">{{ op.method }}</span>
          <span class="od__op-label">{{ op.label }}</span>
          <span class="od__op-path mono">{{ op.brief }}</span>
        </button>
      </div>

      <div class="od__grid">
        <!-- 左：参数表单 -->
        <section class="panel od__form">
          <el-form label-position="top" @submit.prevent>
            <!-- 单条创建 -->
            <template v-if="activeOp === 'create'">
              <el-form-item label="requestId">
                <el-input v-model="createForm.requestId" class="mono" placeholder="留空由服务端生成" clearable>
                  <template #append>
                    <el-button @click="createForm.requestId = genRequestId()">生成</el-button>
                  </template>
                </el-input>
                <div class="od__hint" :class="{ 'is-error': createIdHint.error }">{{ createIdHint.text }}</div>
              </el-form-item>
              <el-form-item label="callbackUrl（可选）">
                <el-input v-model="createForm.callbackUrl" class="mono" placeholder="http://你的服务/notify" clearable />
                <div class="od__hint">任务到终态后向该地址 POST 一次结果，2xx 算送达，失败退避重试 5 次</div>
              </el-form-item>
              <el-form-item label="command">
                <el-input v-model="createForm.command" type="textarea" :rows="2" class="mono" placeholder="要执行的 shell 命令" />
              </el-form-item>
              <el-form-item label="targets（目标机器）">
                <AgentPicker v-model="createForm.targets" allow-create placeholder="选机器，或直接输入 tag 回车" />
                <div v-if="createTargetsHint" class="od__hint is-error">{{ createTargetsHint }}</div>
                <div v-else class="od__hint">没有在线机器时也可以手输 tag，任务会排队等它上线</div>
              </el-form-item>
              <el-collapse class="od__adv">
                <el-collapse-item name="adv" title="高级参数（cwd / env / 超时 / 操作人 / 判定）">
                  <el-form-item label="cwd（工作目录）">
                    <el-input v-model="createForm.cwd" class="mono" placeholder="默认 Agent 工作目录" clearable />
                  </el-form-item>
                  <el-form-item label="env（环境变量）">
                    <EnvEditor v-model="createForm.env" />
                  </el-form-item>
                  <el-form-item label="timeoutSec（超时秒数）">
                    <el-input-number v-model="createForm.timeoutSec" :min="1" :max="86400" placeholder="默认 3600" />
                  </el-form-item>
                  <el-form-item label="operator（操作人，可留空）">
                    <el-input v-model="createForm.operator" placeholder="例如 ci-bot" clearable />
                  </el-form-item>
                  <el-form-item label="conditionConfig（输出判定）">
                    <ConditionEditor v-model="createForm.conditionConfig" />
                  </el-form-item>
                </el-collapse-item>
              </el-collapse>
            </template>

            <!-- 批量创建 -->
            <template v-else-if="activeOp === 'batch'">
              <el-form-item label="requestId（必填）">
                <el-input v-model="batchForm.requestId" class="mono" placeholder="整批共用的全局唯一键" clearable>
                  <template #append>
                    <el-button @click="batchForm.requestId = genRequestId()">生成</el-button>
                  </template>
                </el-input>
                <div class="od__hint" :class="{ 'is-error': batchIdHint.error }">{{ batchIdHint.text }}</div>
              </el-form-item>
              <el-form-item label="callbackUrl（可选，整批共用）">
                <el-input v-model="batchForm.callbackUrl" class="mono" placeholder="http://你的服务/notify" clearable />
              </el-form-item>

              <div class="od__items">
                <div v-for="(it, idx) in batchForm.items" :key="it.id" class="od__item">
                  <div class="od__item-head">
                    <span class="od__item-no">条目 {{ idx + 1 }}</span>
                    <el-button
                      size="small"
                      text
                      type="danger"
                      :disabled="batchForm.items.length <= 1"
                      @click="removeBatchItem(it.id)"
                    >
                      删除
                    </el-button>
                  </div>
                  <el-form-item label="command">
                    <el-input v-model="it.command" type="textarea" :rows="2" class="mono" placeholder="要执行的 shell 命令" />
                  </el-form-item>
                  <el-form-item label="targets（目标机器）">
                    <AgentPicker v-model="it.targets" allow-create placeholder="选机器，或直接输入 tag 回车" />
                  </el-form-item>
                  <div class="od__item-row">
                    <el-form-item label="name（可选）" class="od__item-half">
                      <el-input v-model="it.name" placeholder="条目备注名" clearable />
                    </el-form-item>
                    <el-form-item label="timeoutSec（可选）" class="od__item-half">
                      <el-input-number v-model="it.timeoutSec" :min="1" :max="86400" placeholder="默认 3600" />
                    </el-form-item>
                  </div>
                </div>
              </div>
              <div class="od__items-foot">
                <el-button size="small" plain @click="addBatchItem">+ 加一条</el-button>
                <span class="od__hint">逐条部分成功：清空某条的 command 或 targets 再发送，可观察 errors[] 行为</span>
              </div>
            </template>

            <!-- 按 requestId 查询 -->
            <template v-else-if="activeOp === 'query'">
              <el-form-item label="requestId">
                <el-input
                  v-model="queryForm.requestId"
                  class="mono"
                  placeholder="创建任务时用的 requestId"
                  clearable
                  @keyup.enter="send"
                />
                <div class="od__hint">返回这批全部任务与执行明细（Server 上限 200 条）</div>
              </el-form-item>
            </template>

            <!-- 取消 -->
            <template v-else-if="activeOp === 'cancel'">
              <el-form-item label="taskId">
                <el-input v-model="cancelForm.taskId" class="mono" placeholder="创建/查询响应里的 taskId" clearable />
              </el-form-item>
              <el-form-item label="operator（可选，记录取消人）">
                <el-input v-model="cancelForm.operator" placeholder="拼到 URL 查询参数上" clearable />
              </el-form-item>
              <div class="od__hint">未开始的执行直接置为 canceled；运行中的会被杀掉进程组后判为 canceled</div>
            </template>

            <!-- 重跑 -->
            <template v-else-if="activeOp === 'rerun'">
              <el-form-item label="taskId">
                <el-input v-model="rerunForm.taskId" class="mono" placeholder="创建/查询响应里的 taskId" clearable />
              </el-form-item>
              <el-form-item label="mode">
                <el-radio-group v-model="rerunForm.mode">
                  <el-radio value="inplace">inplace（原地重跑）</el-radio>
                  <el-radio value="new">new（重跑为新记录）</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-alert
                v-if="rerunForm.mode === 'new'"
                type="warning"
                :closable="false"
                show-icon
                title="mode=new 会复制出一条新任务并铸一个新的 requestId，不会出现在原批次的查询结果里"
                class="od__alert"
              />
              <div v-else class="od__hint">
                inplace 会清空该任务的执行记录与日志并重新入队；任务还在执行中时 Server 返回 409
              </div>
            </template>

            <!-- 执行回传附件 -->
            <template v-else-if="activeOp === 'upExec'">
              <el-form-item label="executeId">
                <el-input
                  v-model="upExecForm.executeId"
                  class="mono"
                  placeholder="创建/查询响应 executions[].executeId"
                  clearable
                />
                <div class="od__hint">脚本在测试机上回传时直接用注入的 $ATEST_EXECUTE_ID，不用手填</div>
              </el-form-item>
              <el-form-item label="file（multipart 字段，固定名 file）">
                <div class="od__file">
                  <el-button plain @click="pickFile('upExec')">选择文件</el-button>
                  <template v-if="upExecFile">
                    <span class="od__file-name mono">{{ upExecFile.name }}</span>
                    <span class="od__file-size">{{ formatBytes(upExecFile.size) }}</span>
                    <button class="link-btn" @click="clearFile('upExec')">清除</button>
                  </template>
                  <span v-else class="muted">未选择</span>
                </div>
                <div class="od__hint">单文件 ≤ 32MB：超限本页直接拦下不发送，Server 侧同样会拒绝（413）</div>
              </el-form-item>
              <div class="od__hint">
                这是「脚本回传产物」的同一个接口，脚本里一行即可：
                <code class="code-inline">curl -sf -F "file=@产物" "$ATEST_HTTP_BASE/api/executions/$ATEST_EXECUTE_ID/files"</code>
              </div>
            </template>

            <!-- 任务补附件 -->
            <template v-else-if="activeOp === 'upTask'">
              <el-form-item label="taskId">
                <el-input v-model="upTaskForm.taskId" class="mono" placeholder="创建/查询响应里的 taskId" clearable />
              </el-form-item>
              <el-form-item label="file（multipart 字段，固定名 file）">
                <div class="od__file">
                  <el-button plain @click="pickFile('upTask')">选择文件</el-button>
                  <template v-if="upTaskFile">
                    <span class="od__file-name mono">{{ upTaskFile.name }}</span>
                    <span class="od__file-size">{{ formatBytes(upTaskFile.size) }}</span>
                    <button class="link-btn" @click="clearFile('upTask')">清除</button>
                  </template>
                  <span v-else class="muted">未选择</span>
                </div>
                <div class="od__hint">单文件 ≤ 32MB：超限本页直接拦下不发送，Server 侧同样会拒绝（413）</div>
              </el-form-item>
              <div class="od__hint">
                运维台 / 接入系统直接给任务挂文件（测试计划、说明书等），不经过测试机脚本
              </div>
            </template>

            <!-- 附件列表 -->
            <template v-else-if="activeOp === 'files'">
              <el-form-item label="taskId">
                <el-input
                  v-model="filesForm.taskId"
                  class="mono"
                  placeholder="创建/查询响应里的 taskId"
                  clearable
                  @keyup.enter="send"
                />
                <div class="od__hint">
                  返回该任务全部附件元数据 [{ id, name, size, contentType, executeId, createdAt }]；
                  id 即下载用的 fileId，executeId 非空表示来自那次执行的脚本回传
                </div>
              </el-form-item>
            </template>

            <!-- 附件下载 -->
            <template v-else>
              <el-form-item label="fileId">
                <el-input
                  v-model="downloadForm.fileId"
                  class="mono"
                  placeholder="附件列表响应里的 id"
                  clearable
                  @keyup.enter="send"
                />
              </el-form-item>
              <el-form-item>
                <el-checkbox v-model="downloadForm.inline">
                  inline=1（浏览器内预览图片/文本，不带 attachment 下载头）
                </el-checkbox>
              </el-form-item>
              <div class="od__hint">
                发送后本页展示状态码与响应元信息；文件本体点响应区的「在新标签页下载」由浏览器按原始文件名保存
              </div>
            </template>
          </el-form>

          <input ref="fileInputEl" type="file" class="od__file-hidden" @change="onFilePicked" />

          <div class="od__send">
            <el-button type="primary" :loading="sending" :disabled="!!built.blocked" @click="send">
              发送请求
            </el-button>
            <span v-if="built.blocked" class="od__hint is-error">{{ built.blocked }}</span>
          </div>
        </section>

        <!-- 右：请求预览 + 响应 -->
        <div class="od__right">
          <section class="panel od__wire">
            <div class="od__sec-head">
              <h3 class="od__sec-title">请求</h3>
              <button class="link-btn od__sec-act" @click="copy(fullUrl, 'URL 已复制')">复制 URL</button>
            </div>
            <div class="od__url">
              <span class="od__op-method mono" :data-m="built.method">{{ built.method }}</span>
              <code class="od__url-text mono">{{ fullUrl }}</code>
            </div>

            <template v-if="hasBody">
              <div class="od__sec-head od__sec-head--sub">
                <span class="od__sec-sub">请求体 JSON</span>
                <template v-if="jsonDirty">
                  <span class="od__dirty">已手动编辑，以下方文本为准</span>
                  <button class="link-btn od__sec-act" @click="resetJson">还原为表单</button>
                </template>
                <span v-else class="muted od__sec-note">由左侧表单生成，可直接改</span>
              </div>
              <el-input
                v-model="jsonText"
                type="textarea"
                :autosize="{ minRows: 6, maxRows: 16 }"
                class="od__json mono"
                spellcheck="false"
                @input="onJsonInput"
              />
              <el-alert
                v-if="jsonParseError"
                type="error"
                :closable="false"
                show-icon
                :title="jsonParseError"
                class="od__alert"
              />
            </template>
            <template v-else-if="isUploadOp">
              <div class="od__sec-head od__sec-head--sub">
                <span class="od__sec-sub">请求体 multipart/form-data</span>
                <span class="muted od__sec-note">浏览器自动生成 boundary，不要手拼 Content-Type</span>
              </div>
              <div class="od__upload-brief">
                <span class="mono">file</span> =
                <template v-if="uploadFile">
                  <span class="mono">{{ uploadFile.name }}</span>（{{ formatBytes(uploadFile.size) }}）
                </template>
                <span v-else class="muted">未选择文件</span>
              </div>
            </template>
            <p v-else class="od__nobody muted">该操作没有请求体{{ activeOp === 'cancel' ? '（operator 拼在 URL 上）' : '' }}</p>

            <div class="od__sec-head od__sec-head--sub">
              <span class="od__sec-sub">等价 curl</span>
              <button class="link-btn od__sec-act" @click="copy(curlText, 'curl 已复制')">复制</button>
            </div>
            <pre class="od__pre">{{ curlText }}</pre>
          </section>

          <section class="panel od__res">
            <div class="od__sec-head">
              <h3 class="od__sec-title">响应</h3>
              <button v-if="resp" class="link-btn od__sec-act" @click="copy(respText, '响应已复制')">复制响应</button>
            </div>

            <el-alert
              v-if="netError"
              type="error"
              :closable="false"
              show-icon
              :title="netError"
              description="网络层失败，未收到 HTTP 响应：确认 Server 已启动、页头「接口地址」正确"
              class="od__alert"
            />

            <template v-else-if="resp">
              <div class="od__res-line">
                <span class="od__res-code mono" :class="`is-${respTone}`">
                  HTTP {{ resp.status }}{{ resp.statusText ? ` ${resp.statusText}` : '' }}
                </span>
                <span class="od__res-meta mono">{{ resp.durationMs }} ms</span>
                <span class="od__res-meta">{{ formatTime(resp.at) }}</span>
                <code class="od__res-url mono" :title="`${resp.method} ${resp.url}`">
                  {{ resp.method }} {{ resp.url }}
                </code>
              </div>

              <el-alert v-if="respHint" type="warning" :closable="false" show-icon :title="respHint" class="od__alert" />

              <div v-if="respRequestId" class="od__rid">
                <span class="od__rid-k">requestId</span>
                <CopyableId :value="respRequestId" :head="24" />
                <router-link v-if="watchLink" :to="watchLink" class="od__rid-link">
                  在开放查询中跟踪这批任务 →
                </router-link>
                <button v-if="activeOp !== 'query'" class="link-btn" @click="fillQuery">填入查询</button>
              </div>

              <div v-if="respTaskId" class="od__rid">
                <span class="od__rid-k">上传成功</span>
                <span>附件已挂到任务 <b class="mono">{{ respTaskId }}</b></span>
                <button class="link-btn" @click="fillFilesList">查看该任务的附件列表 →</button>
              </div>

              <div v-if="respFileUrl" class="od__rid">
                <span class="od__rid-k">附件</span>
                <a class="od__rid-link" :href="respFileUrl" target="_blank" rel="noopener">
                  在新标签页下载（浏览器按 Server 返回的文件名保存）→
                </a>
              </div>

              <p v-if="respBatchStat" class="od__stat">
                成功创建 <b>{{ respBatchStat.ok }}</b> 条 · 被拒绝 <b>{{ respBatchStat.bad }}</b> 条
                <span v-if="respBatchStat.bad" class="muted">（逐条原因见响应里的 errors[]）</span>
              </p>

              <pre class="od__pre od__pre--res">{{ respText }}</pre>
            </template>

            <p v-else class="od__nobody muted">还没有发送请求：左侧填好参数，点「发送请求」</p>
          </section>
        </div>
      </div>

      <!-- 接入手册（速读版） -->
      <section class="panel od__hb">
        <div class="od__sec-head">
          <h3 class="od__sec-title">接入手册（速读版）</h3>
          <span class="muted od__sec-note">
            完整版在仓库 <code class="code-inline">new-auto-test/docs/open-api.md</code>，本节只讲动线与最容易踩的坑
          </span>
        </div>
        <div class="od__hb-grid">
          <div class="od__hb-col">
            <h4 class="od__hb-t">接入动线</h4>
            <ol class="od__hb-list">
              <li>
                创建：单条 <code class="code-inline">POST /api/tasks</code>，多条一批
                <code class="code-inline">POST /api/tasks/batch</code>（最多 100 条，整批一个 requestId）。
                Base 是 <code class="code-inline">http://&lt;server&gt;:8080</code>，第一期内网无鉴权。
              </li>
              <li>
                等结果：创建时带 callbackUrl，任务到终态（含取消）Server 向它 POST 一次 JSON
                （<code class="code-inline">event: task.terminal</code>，含 taskId / requestId / status /
                statusCounts / executions[]），2xx 算送达；不用回调就轮询
                <code class="code-inline">GET /api/tasks?requestId=</code>。
              </li>
              <li>
                拉日志：回调里每台机器只有最后一行（lastLine），完整日志按 executeId 拉
                <code class="code-inline">GET /api/executions/{executeId}/logs?from=0&amp;limit=1000</code>。
              </li>
              <li>收产物：脚本把报告等文件回传成任务附件，再用附件列表 + 下载接口取走（见右侧）。</li>
            </ol>
            <h4 class="od__hb-t">requestId</h4>
            <p class="od__hb-p">
              调用方自带的全局唯一幂等键，格式
              <code class="code-inline">^[A-Za-z0-9._-]{1,64}$</code>，重复创建 409。批量必填；
              单条可省略、由 Server 铸 UUID 并在响应回显。批量逐条部分成功（坏条目在
              <code class="code-inline">errors[]</code>），全部条目无效才整单 400 且 requestId
              不占用、可原样重试。重跑 <code class="code-inline">mode=new</code> 会铸新
              requestId，不出现在原批次查询里。
            </p>
          </div>
          <div class="od__hb-col">
            <h4 class="od__hb-t">附件（新）</h4>
            <ul class="od__hb-list">
              <li>
                脚本回传：<code class="code-inline">POST /api/executions/{executeId}/files</code>，
                multipart 字段固定名 <code class="code-inline">file</code>；执行环境已注入
                <code class="code-inline">ATEST_HTTP_BASE</code> /
                <code class="code-inline">ATEST_EXECUTE_ID</code> /
                <code class="code-inline">ATEST_TASK_ID</code>（以及既有 ATEST_* 身份变量）。
              </li>
              <li>
                调用方直传：<code class="code-inline">POST /api/tasks/{taskId}/files</code>；列表
                <code class="code-inline">GET /api/tasks/{taskId}/files</code>；下载
                <code class="code-inline">GET /api/files/{fileId}</code>（浏览器预览加
                <code class="code-inline">?inline=1</code>）。
              </li>
              <li>单文件 ≤ 32MB（超限 413）；上传并发满 429，稍后重试；磁盘/IO 故障 503。</li>
            </ul>
            <div class="od__hb-warn">
              <b>⚠️ 脚本回传能不能通，取决于 Server 配置 <code class="code-inline">atest.http.public-base</code>。</b>
              它会注入为 <code class="code-inline">$ATEST_HTTP_BASE</code>，默认
              <code class="code-inline">http://127.0.0.1:8080</code>——Agent 与 Server 不同机时，
              脚本会往<b>测试机自己的 localhost</b> 上传，必然 Connection refused。多机部署必须改成
              Agent 能访问到的 Server 地址（如 <code class="code-inline">http://10.0.0.5:8080</code>）
              并重启 Server，之后新下发的任务才生效。
            </div>
            <pre class="od__pre od__hb-curl">curl -sf -F "file=@report.tar.gz" \
  "$ATEST_HTTP_BASE/api/executions/$ATEST_EXECUTE_ID/files"</pre>
          </div>
        </div>
        <p class="od__hb-foot">
          页面入口：<router-link :to="openLink" class="od__rid-link">#/open 开放查询</router-link>
          （按 requestId 看整批进度与附件）· 本页 #/open/debug 接入调试。
          本系统是全新实现，与旧 auto-test 的 /upload、MinIO、RocketMQ 均不兼容：附件走上面的
          files 接口，结果通知一律走 callbackUrl HTTP 回调。
        </p>
      </section>

      <!-- 接入注意事项 -->
      <section class="panel od__tips">
        <h3 class="od__sec-title">接入注意事项</h3>
        <ul class="od__tips-list">
          <li>
            <code class="code-inline">requestId</code> 需匹配
            <code class="code-inline">^[A-Za-z0-9._-]{1,64}$</code> 且全局唯一，重复创建返回 409
          </li>
          <li>
            批量创建逐条部分成功，坏条目落在 <code class="code-inline">errors[]</code>；
            全部条目失败时整单 400 且 requestId <b>不占用</b>，修正后可原样重试
          </li>
          <li>任务到终态（含取消）后向 callbackUrl POST 一次结果，2xx 算送达，失败按 1s 起退避重试 5 次</li>
          <li>
            回调体里每台机器只有 <code class="code-inline">executions[].lastLine</code>
            （最后一行，截 4096 字符），<b>不含完整日志</b>；全量日志收到回调后按 executeId 拉
            <code class="code-inline">GET /api/executions/{executeId}/logs?from=0&amp;limit=1000</code>
          </li>
          <li>
            operator 可留空；重跑 <code class="code-inline">mode=new</code> 会生成新任务并铸新的 requestId
          </li>
          <li>
            附件单文件 ≤ 32MB，超限 <b>413</b>；上传并发满 <b>429</b>（稍后重试即可）；磁盘/IO 故障 <b>503</b>
          </li>
          <li>
            脚本回传附件前，确认 <code class="code-inline">atest.http.public-base</code> 已配成
            <b>Agent（测试机）能访问到的 Server 地址</b>：默认 127.0.0.1:8080 只在 Agent 与 Server
            同机时可用，否则脚本会往测试机自己的 localhost 上传
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ------------------------------------------------------------ 画布：纸面平底，无纹理无滤镜 */

.od {
  min-height: 100%;
  background-color: #f7f6f3;
}

.od--embed {
  padding: 12px 14px 18px;
}

.od__wrap {
  max-width: 1280px;
  margin: 0 auto;
}

/* ------------------------------------------------------------ 操作选择条 */

.od__ops {
  display: flex;
  align-items: stretch;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 12px;
  margin-bottom: 12px;
}

.od__op {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 7px 12px;
  border: 1px solid rgba(20, 18, 16, 0.14);
  border-radius: 8px;
  background: #fff;
  color: var(--nat-text-sub);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.12s, background-color 0.12s, color 0.12s;
}

.od__op:hover {
  border-color: rgba(20, 18, 16, 0.3);
  color: #1c1917;
}

.od__op:focus-visible {
  outline: 2px solid #1c1917;
  outline-offset: 1px;
}

/* 选中态：墨色描边 + 轻填充，不发光 */
.od__op.is-on {
  border-color: #1c1917;
  box-shadow: inset 0 0 0 1px #1c1917;
  background: rgba(20, 18, 16, 0.03);
  color: #1c1917;
  font-weight: 560;
}

.od__op-method {
  flex: none;
  padding: 1px 6px;
  border: 1px solid transparent;
  border-radius: 4px;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  line-height: 1.5;
}

.od__op-method[data-m='GET'] {
  color: #15803d;
  border-color: rgba(21, 128, 61, 0.3);
  background: rgba(21, 128, 61, 0.07);
}

.od__op-method[data-m='POST'] {
  color: #b45309;
  border-color: rgba(180, 83, 9, 0.3);
  background: rgba(180, 83, 9, 0.07);
}

.od__op-label {
  white-space: nowrap;
}

.od__op-path {
  color: var(--nat-text-weak);
  font-size: 11px;
  white-space: nowrap;
}

@media (max-width: 900px) {
  .od__op-path {
    display: none;
  }
}

/* ------------------------------------------------------------ 双栏 */

.od__grid {
  display: grid;
  grid-template-columns: minmax(360px, 5fr) minmax(400px, 6fr);
  gap: 12px;
  align-items: start;
}

@media (max-width: 1020px) {
  .od__grid {
    grid-template-columns: 1fr;
  }
}

.od__right {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* .panel + .panel 的全局 margin 交给 gap 控制 */
.od__right .panel + .panel {
  margin-top: 0;
}

/* ------------------------------------------------------------ 表单 */

.od__form {
  padding: 14px 16px 16px;
  min-width: 0;
}

.od__form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.od__form :deep(.el-form-item__label) {
  padding-bottom: 4px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.5;
}

/* 校验/说明文案：固定行高，出现消失不跳版 */
.od__hint {
  width: 100%;
  min-height: 16px;
  margin-top: 3px;
  color: var(--nat-text-weak);
  font-size: 11.5px;
  line-height: 1.5;
}

.od__hint.is-error {
  color: #b91c1c;
}

.od__adv {
  border-top: 1px solid var(--nat-hairline);
  border-bottom: none;
  --el-collapse-header-height: 40px;
}

.od__adv :deep(.el-collapse-item__header) {
  color: var(--nat-text-sub);
  font-size: 12.5px;
  border-bottom: none;
}

.od__adv :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

/* 批量条目卡片 */
.od__items {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.od__item {
  padding: 10px 12px 2px;
  border: 1px solid rgba(20, 18, 16, 0.12);
  border-radius: 10px;
  background: #fbfaf8;
}

.od__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.od__item-no {
  font-size: 12px;
  font-weight: 600;
  color: var(--nat-text-sub);
}

.od__item-row {
  display: flex;
  gap: 10px;
}

.od__item-half {
  flex: 1 1 0;
  min-width: 0;
}

.od__items-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.od__items-foot .od__hint {
  width: auto;
  margin-top: 0;
}

.od__send {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  padding-top: 12px;
  border-top: 1px solid var(--nat-hairline);
}

.od__send .od__hint {
  width: auto;
  margin-top: 0;
}

.od__alert {
  margin: 8px 0;
}

/* ------------------------------------------------------------ 请求预览 / 响应 */

.od__wire,
.od__res {
  padding: 12px 16px 14px;
  min-width: 0;
}

.od__sec-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.od__sec-head--sub {
  margin-top: 14px;
}

.od__sec-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 620;
}

.od__sec-sub {
  color: var(--nat-text-sub);
  font-size: 12px;
  font-weight: 560;
}

.od__sec-note {
  font-size: 11.5px;
}

.od__sec-act {
  margin-left: auto;
  font-size: 12px;
}

.od__dirty {
  color: #b45309;
  font-size: 11.5px;
}

.od__url {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 7px 10px;
  border: 1px solid var(--nat-hairline);
  border-radius: 8px;
  background: #f3f1ed;
}

.od__url-text {
  min-width: 0;
  font-size: 12px;
  color: #1c1917;
  overflow-wrap: anywhere;
}

.od__json :deep(.el-textarea__inner) {
  font-family: var(--nat-font-mono);
  font-size: 12px;
  line-height: 1.7;
}

.od__nobody {
  margin: 4px 0;
  font-size: 12.5px;
}

/* 代码块：暖纸底 + 发丝线，与 #/open 速览一致 */
.od__pre {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--nat-hairline);
  border-radius: 8px;
  background: #f3f1ed;
  font-family: var(--nat-font-mono);
  font-size: 12px;
  line-height: 1.7;
  color: #1c1917;
  white-space: pre-wrap;
  word-break: break-word;
}

.od__pre--res {
  max-height: 420px;
  overflow: auto;
  background: #fbfaf8;
}

/* 响应状态行 */
.od__res-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  min-width: 0;
}

.od__res-code {
  flex: none;
  padding: 2px 9px;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 12.5px;
  font-weight: 650;
}

.od__res-code.is-ok {
  color: #15803d;
  border-color: rgba(21, 128, 61, 0.3);
  background: rgba(21, 128, 61, 0.07);
}

.od__res-code.is-warn {
  color: #b45309;
  border-color: rgba(180, 83, 9, 0.3);
  background: rgba(180, 83, 9, 0.07);
}

.od__res-code.is-err {
  color: #b91c1c;
  border-color: rgba(185, 28, 28, 0.3);
  background: rgba(185, 28, 28, 0.07);
}

.od__res-meta {
  color: var(--nat-text-weak);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.od__res-url {
  min-width: 0;
  color: var(--nat-text-weak);
  font-size: 11.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 创建成功后的 requestId 突出条 */
.od__rid {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 8px 0;
  padding: 8px 12px;
  border: 1px solid rgba(21, 128, 61, 0.25);
  border-radius: 8px;
  background: rgba(21, 128, 61, 0.05);
  font-size: 12.5px;
}

.od__rid-k {
  flex: none;
  color: var(--nat-text-sub);
  font-size: 12px;
}

.od__rid-link {
  color: #1c1917;
  font-weight: 560;
  text-decoration: none;
}

.od__rid-link:hover {
  text-decoration: underline;
}

.od__stat {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--nat-text-sub);
}

.od__stat b {
  color: #1c1917;
}

/* ------------------------------------------------------------ 附件表单 */

.od__file {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  width: 100%;
}

.od__file-name {
  min-width: 0;
  font-size: 12.5px;
  color: #1c1917;
  overflow-wrap: anywhere;
}

.od__file-size {
  color: var(--nat-text-weak);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.od__file-hidden {
  display: none;
}

.od__upload-brief {
  padding: 8px 10px;
  border: 1px solid var(--nat-hairline);
  border-radius: 8px;
  background: #f3f1ed;
  font-size: 12.5px;
  color: #1c1917;
  overflow-wrap: anywhere;
}

/* ------------------------------------------------------------ 接入手册（速读版） */

.od__hb {
  margin-top: 12px;
  padding: 14px 18px 16px;
}

.od__hb-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 28px;
}

@media (max-width: 1020px) {
  .od__hb-grid {
    grid-template-columns: 1fr;
  }
}

.od__hb-col {
  min-width: 0;
}

.od__hb-t {
  margin: 10px 0 4px;
  font-size: 12.5px;
  font-weight: 640;
  color: #1c1917;
}

.od__hb-list {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.9;
}

.od__hb-p {
  margin: 4px 0 0;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.9;
}

/* public-base 警示：琥珀描边纸底，比正文重一档但不刺眼 */
.od__hb-warn {
  margin: 10px 0 8px;
  padding: 9px 12px;
  border: 1px solid rgba(180, 83, 9, 0.35);
  border-radius: 8px;
  background: rgba(180, 83, 9, 0.06);
  color: #713f12;
  font-size: 12.5px;
  line-height: 1.85;
}

.od__hb-warn b {
  color: #7c2d12;
}

.od__hb-curl {
  margin-top: 8px;
}

.od__hb-foot {
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--nat-hairline);
  color: var(--nat-text-weak);
  font-size: 12px;
  line-height: 1.9;
}

/* ------------------------------------------------------------ 注意事项 */

.od__tips {
  margin-top: 12px;
  padding: 14px 18px 16px;
}

.od__tips-list {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--nat-text-sub);
  font-size: 12.5px;
  line-height: 1.95;
}
</style>
