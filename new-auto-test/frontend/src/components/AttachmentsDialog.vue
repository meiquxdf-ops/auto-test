<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchFileText, fileUrl, listTaskFiles, uploadTaskFile } from '@/api/attachments'
import { errorMessage, toastError, toastOk } from '@/api/http'
import type { TaskFile } from '@/api/types'
import { formatBytes, formatTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps<{
  taskId: string
  /** 标题里给人看的任务说明（命令截断即可） */
  taskLabel?: string
  /** 运维台可上传；开放查询页只读 */
  allowUpload?: boolean
}>()

const emit = defineEmits<{
  closed: []
  /** 上传成功后通知父级刷新列表里的附件计数 */
  changed: []
}>()

const visible = ref(true)
const files = ref<TaskFile[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try {
    files.value = await listTaskFiles(props.taskId)
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e, '加载附件列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ------------------------------------------------------------ 上传 */

const MAX_BYTES = 32 * 1024 * 1024
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

function pickFile() {
  fileInput.value?.click()
}

async function onFilePicked(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > MAX_BYTES) {
    toastError(null, `文件 ${formatBytes(file.size)} 超过单附件上限 32MB`)
    return
  }
  uploading.value = true
  try {
    await uploadTaskFile(props.taskId, file)
    toastOk(`已上传 ${file.name}`)
    emit('changed')
    await load()
  } catch (e) {
    toastError(e, '上传失败')
  } finally {
    uploading.value = false
  }
}

/* ------------------------------------------------------------ 预览 */

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i
const TEXT_EXT = /\.(txt|log|md|json|xml|ya?ml|csv|ini|conf|properties|sh|py|go|java|js|ts|sql|diff|patch)$/i
const TEXT_PREVIEW_MAX = 512 * 1024

function isImage(f: TaskFile): boolean {
  return (f.contentType ?? '').startsWith('image/') || IMAGE_EXT.test(f.name)
}

function isText(f: TaskFile): boolean {
  const ct = f.contentType ?? ''
  if (ct.startsWith('text/')) return true
  if (/^application\/(json|xml|x-yaml|yaml)/.test(ct)) return true
  return TEXT_EXT.test(f.name)
}

function canPreview(f: TaskFile): boolean {
  return isImage(f) || (isText(f) && f.size <= TEXT_PREVIEW_MAX)
}

interface Preview {
  file: TaskFile
  kind: 'image' | 'text'
  text?: string
  loading: boolean
  error?: string
}

const preview = ref<Preview | null>(null)

async function openPreview(f: TaskFile) {
  if (preview.value?.file.id === f.id) {
    preview.value = null
    return
  }
  if (isImage(f)) {
    preview.value = { file: f, kind: 'image', loading: false }
    return
  }
  preview.value = { file: f, kind: 'text', loading: true }
  try {
    const text = await fetchFileText(f.id)
    if (preview.value?.file.id === f.id) {
      preview.value = { file: f, kind: 'text', text, loading: false }
    }
  } catch (e) {
    if (preview.value?.file.id === f.id) {
      preview.value = { file: f, kind: 'text', loading: false, error: errorMessage(e, '预览失败') }
    }
  }
}

function download(f: TaskFile) {
  const a = document.createElement('a')
  a.href = fileUrl(f.id)
  a.download = f.name
  a.click()
}

const title = computed(() => `附件 · 任务 ${props.taskId}`)

const imageSrc = computed(() =>
  preview.value?.kind === 'image' ? fileUrl(preview.value.file.id, true) : '',
)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="640px"
    class="att-dialog"
    :close-on-click-modal="true"
    @closed="emit('closed')"
  >
    <p v-if="taskLabel" class="att__task" :title="taskLabel">
      <code class="code-inline">{{ taskLabel }}</code>
    </p>

    <div class="att__bar">
      <span class="att__count">{{ files.length }} 个附件 · 单文件上限 32MB</span>
      <div class="att__bar-end">
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <template v-if="allowUpload">
          <input ref="fileInput" type="file" class="att__input" @change="onFilePicked" />
          <el-button size="small" type="primary" :loading="uploading" @click="pickFile">上传附件</el-button>
        </template>
      </div>
    </div>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="att__alert" />

    <el-table v-if="files.length" :data="files" size="small" class="att__table" max-height="360">
      <el-table-column label="文件名" min-width="200">
        <template #default="{ row }">
          <div class="att__name" :title="row.name">{{ row.name }}</div>
          <div v-if="row.executeId" class="att__meta">来自执行 {{ row.executeId.slice(0, 10) }}…</div>
          <div v-else class="att__meta">运维台上传</div>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="90">
        <template #default="{ row }">
          <span class="mono">{{ formatBytes(row.size) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="122">
        <template #default="{ row }">
          <span class="mono att__time">{{ formatTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="118" align="right">
        <template #default="{ row }">
          <el-button
            v-if="canPreview(row)"
            size="small"
            text
            type="primary"
            @click="openPreview(row)"
          >
            {{ preview?.file.id === row.id ? '收起' : '预览' }}
          </el-button>
          <el-button size="small" text type="primary" @click="download(row)">下载</el-button>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState
      v-else-if="!loading && !error"
      size="small"
      title="还没有附件"
      :desc="allowUpload
        ? '脚本可用 $ATEST_HTTP_BASE 与 $ATEST_EXECUTE_ID 回传产物，或点右上角「上传附件」'
        : '脚本可用 $ATEST_HTTP_BASE 与 $ATEST_EXECUTE_ID 回传产物'"
    />

    <div v-if="preview" class="att__preview">
      <div class="att__preview-head">
        <span class="att__preview-name" :title="preview.file.name">{{ preview.file.name }}</span>
        <button class="link-btn" @click="preview = null">关闭预览</button>
      </div>
      <div v-if="preview.kind === 'image'" class="att__preview-body att__preview-body--img">
        <img :src="imageSrc" :alt="preview.file.name" class="att__img" />
      </div>
      <div v-else class="att__preview-body">
        <el-skeleton v-if="preview.loading" :rows="3" animated />
        <el-alert v-else-if="preview.error" type="error" :closable="false" :title="preview.error" />
        <pre v-else class="att__pre">{{ preview.text }}</pre>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.att__task {
  margin: 0 0 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.att__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.att__count {
  color: var(--nat-text-weak);
  font-size: 12px;
}

.att__bar-end {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.att__bar-end :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 触发用的原生 file input 藏起来，交互走按钮 */
.att__input {
  display: none;
}

.att__alert {
  margin-bottom: 10px;
}

.att__table {
  width: 100%;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
}

.att__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}

.att__meta {
  color: var(--nat-text-weak);
  font-size: 11px;
}

.att__time {
  font-size: 12px;
  color: var(--nat-text-sub);
}

/* ---------------------------------------------------------- 预览 */

.att__preview {
  margin-top: 12px;
  border: 1px solid var(--nat-border);
  border-radius: 8px;
  overflow: hidden;
}

.att__preview-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--nat-border);
  background: #fafafa;
}

.att__preview-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--nat-text-sub);
}

.att__preview-head .link-btn {
  margin-left: auto;
  flex: none;
  font-size: 12px;
}

.att__preview-body {
  max-height: 320px;
  overflow: auto;
  padding: 10px 12px;
}

.att__preview-body--img {
  display: flex;
  justify-content: center;
  background: #fafafa;
}

.att__img {
  max-width: 100%;
  max-height: 300px;
  object-fit: contain;
}

.att__pre {
  margin: 0;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
