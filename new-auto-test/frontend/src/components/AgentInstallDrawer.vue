<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Agent } from '@/api/types'
import { copyText, shortId } from '@/utils/format'

/**
 * 「安装 Agent」抽屉：只生成 deploy/install.sh 的安装命令，不做远程安装。
 * Server 触达不了一台还没有 Agent 进程的机器（内网、无 SSH 通道），
 * 安装必须由操作者在目标 Linux 机上以 root 执行。
 */

const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 当前机器列表，用于校验 tag 唯一性（重名 Server 会 tag_conflict 拒绝） */
  agents: Agent[]
}>()

const emit = defineEmits<{ (e: 'refresh'): void }>()

/* ---------------------------------------------------------------- 表单 */

// 与 deploy/install.sh 的参数校验保持一致（比页面「改名」更严：不允许 : 和 @）
const TAG_RE = /^[A-Za-z0-9._-]{1,64}$/
const SERVER_RE = /^[A-Za-z0-9._-]+:\d{1,5}$/

function defaultServer(): string {
  return `${location.hostname || '127.0.0.1'}:9800`
}

const form = reactive({
  displayTag: '',
  server: defaultServer(),
  concurrency: 1,
})

watch(visible, (open) => {
  if (!open) return
  form.displayTag = ''
  form.server = defaultServer()
  form.concurrency = 1
})

const tagError = computed(() => {
  const tag = form.displayTag.trim()
  if (!tag) return '机器名不能为空'
  if (!TAG_RE.test(tag)) {
    return '只允许字母、数字、点、下划线、中划线，长度 1 - 64（与 install.sh --tag 校验一致）'
  }
  const dup = props.agents.find((a) => a.displayTag === tag)
  if (dup) return `已被 ${shortId(dup.agentId)} 占用，Server 会以 tag_conflict 拒绝注册，请换一个`
  return ''
})

const serverError = computed(() => {
  const s = form.server.trim()
  if (!s) return 'Server 地址不能为空'
  if (!SERVER_RE.test(s)) return '格式应为 host:port，例如 10.0.0.5:9800'
  const port = Number(s.split(':').pop())
  if (port < 1 || port > 65535) return `端口非法: ${port}`
  return ''
})

const serverWarn = computed(() => {
  if (serverError.value) return ''
  return form.server.trim().endsWith(':8080')
    ? '8080 是 Server 的 HTTP 端口；Agent 走 TCP :9800，填 8080 会一直注册不上'
    : ''
})

const formValid = computed(() => !tagError.value && !serverError.value)

/* ------------------------------------------------------------ 生成命令 */

const tagValue = computed(() => (tagError.value ? '<机器名>' : form.displayTag.trim()))
const serverValue = computed(() => (serverError.value ? '<server-host>:9800' : form.server.trim()))
const concurrencyFlag = computed(() => (form.concurrency !== 1 ? ` --concurrency ${form.concurrency}` : ''))

const installCmd = computed(
  () => `sudo ./install.sh --server ${serverValue.value} --tag ${tagValue.value} --bin ./atagent${concurrencyFlag.value}`,
)

interface CmdBlock {
  key: string
  title: string
  hint: string
  code: string
  needForm: boolean
}

const blocks = computed<CmdBlock[]>(() => [
  {
    key: 'prep',
    title: '第 1 步 · 构建产物并拷到目标机',
    hint: '在能访问源码的机器上执行；scp 只是示例，rsync / 内网文件服务同样可以',
    needForm: false,
    code: [
      '# 编译 linux/amd64 静态二进制（见 agent/README.md）',
      'cd new-auto-test/agent',
      'CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o atagent ./cmd/atagent',
      '# 把 deploy/（install.sh + atagent.service）连同 atagent 一起拷过去',
      'cp atagent ../deploy/ && scp -r ../deploy root@<目标机IP>:/tmp/atagent-install',
    ].join('\n'),
  },
  {
    key: 'install',
    title: '第 2 步 · 在目标机上以 root 安装',
    hint: '脚本会自己等 Agent 注册成功（最多 20s），正常退出即代表机器已上线',
    needForm: true,
    code: [`cd /tmp/atagent-install`, installCmd.value].join('\n'),
  },
  {
    key: 'oneliner',
    title: '一行版',
    hint: 'deploy/（install.sh + atagent.service）和 atagent 已在目标机当前目录时，直接执行',
    needForm: true,
    code: installCmd.value,
  },
])

async function copy(text: string) {
  const ok = await copyText(text)
  ElMessage[ok ? 'success' : 'error']({ message: ok ? '已复制' : '复制失败', duration: 1500 })
}
</script>

<template>
  <el-drawer v-model="visible" size="640px" :with-header="false">
    <div class="aid">
      <div class="aid__head">
        <div>
          <div class="aid__title">安装 Agent</div>
          <div class="aid__sub">
            按 <code class="code-inline">deploy/install.sh</code> 生成安装命令，装好后机器自动注册上线
          </div>
        </div>
        <el-button text :icon="'Close'" @click="visible = false" />
      </div>

      <div class="aid__body">
        <el-alert type="warning" :closable="false" show-icon class="aid__notice">
          <template #title>本页只生成命令，不能远程安装</template>
          Server 无法触达一台还没有 Agent 进程的机器（内网环境、无 SSH 通道），「重启 Agent」也只对在线会话有效。
          下面的命令<b>必须在目标 Linux 机（需 systemd）上以 root 执行</b>；装完回到本页刷新列表，新机器应显示「在线」。
        </el-alert>

        <el-form label-position="top" class="aid__form" @submit.prevent>
          <div class="aid__grid">
            <el-form-item :error="(form.displayTag ? tagError : '') || undefined">
              <template #label>
                <span class="lbl">机器名 tag <b class="req">*</b></span>
              </template>
              <el-input
                v-model="form.displayTag"
                placeholder="例如 qa-node-01，全局唯一"
                spellcheck="false"
                clearable
              />
            </el-form-item>
            <el-form-item :error="serverError || undefined">
              <template #label>
                <span class="lbl">
                  Server 地址
                  <span class="lbl__hint">Agent TCP 端口（:9800），不是网页的 HTTP :8080</span>
                </span>
              </template>
              <el-input v-model="form.server" spellcheck="false" class="mono" />
            </el-form-item>
          </div>
          <el-alert
            v-if="serverWarn"
            type="error"
            :closable="false"
            show-icon
            :title="serverWarn"
            class="aid__warn"
          />
          <el-form-item>
            <template #label>
              <span class="lbl">
                最大并发
                <span class="lbl__hint">1 - 4，默认 1；装完也可在机器列表里改</span>
              </span>
            </template>
            <el-input-number v-model="form.concurrency" :min="1" :max="4" controls-position="right" />
          </el-form-item>
        </el-form>

        <section v-for="b in blocks" :key="b.key" class="cmd">
          <div class="cmd__head">
            <div>
              <span class="cmd__title">{{ b.title }}</span>
              <span class="cmd__hint">{{ b.hint }}</span>
            </div>
            <el-tooltip :disabled="!b.needForm || formValid" content="先把上面的机器名 / Server 地址填对" placement="top">
              <span>
                <el-button
                  size="small"
                  text
                  type="primary"
                  :icon="'DocumentCopy'"
                  :disabled="b.needForm && !formValid"
                  @click="copy(b.code)"
                >
                  复制
                </el-button>
              </span>
            </el-tooltip>
          </div>
          <pre class="cmd__code mono">{{ b.code }}</pre>
        </section>

        <div class="muted aid__tip">
          安装失败时按脚本输出排查：<code class="code-inline">tag_conflict</code> 换名重跑；连不上先
          <code class="code-inline">nc -vz &lt;server-host&gt; 9800</code>。重跑 install.sh 不会更换机器身份
          （<code class="code-inline">/var/lib/atagent/agent-id</code> 保留）。
        </div>
      </div>

      <div class="aid__foot">
        <span class="muted">装完没出现？状态会实时推送，也可手动刷新</span>
        <span class="spacer" />
        <el-button :icon="'Refresh'" @click="emit('refresh')">刷新机器列表</el-button>
        <el-button type="primary" @click="visible = false">完成</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.aid {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.aid__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--nat-border);
}

.aid__title {
  font-size: 16px;
  font-weight: 640;
}

.aid__sub {
  color: var(--nat-text-weak);
  font-size: 12px;
  margin-top: 3px;
}

.aid__body {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.aid__notice {
  margin-bottom: 14px;
}

.aid__notice b {
  font-weight: 640;
}

.aid__warn {
  margin: -6px 0 12px;
}

.aid__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

.lbl {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}

.lbl__hint {
  font-size: 12px;
  color: var(--nat-text-weak);
  font-weight: 400;
}

.req {
  color: #dc2626;
}

.cmd {
  margin-bottom: 14px;
}

.cmd__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.cmd__title {
  font-size: 13px;
  font-weight: 600;
}

.cmd__hint {
  font-size: 12px;
  color: var(--nat-text-weak);
  margin-left: 8px;
}

.cmd__code {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--nat-border);
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12.5px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre;
}

.aid__tip {
  font-size: 12px;
  line-height: 1.8;
  padding-top: 2px;
}

.aid__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--nat-border);
}

.aid__foot .muted {
  font-size: 12px;
}

.spacer {
  flex: 1;
}
</style>
