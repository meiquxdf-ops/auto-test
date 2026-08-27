<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getInstallInfo, sshInstall, type InstallInfo, type SshInstallResult } from '@/api/agents'
import { ApiError, errorMessage, getApiBase } from '@/api/http'
import type { Agent } from '@/api/types'
import { copyText, shortId } from '@/utils/format'

/**
 * 「安装 Agent」抽屉，三种方式共用同一份 tag / Server 地址 / 并发：
 *  - 复制命令：把 deploy/ 与 atagent 拷到目标机后 root 执行 install.sh；
 *  - curl 安装：目标机一行命令，从本 Server(:8080) 拉脚本与二进制（仅内网）；
 *  - SSH 代装：Server 主动 SSH 到目标机上传并执行 install.sh，输出回显到本页。
 */

const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 当前机器列表，用于校验 tag 唯一性（重名 Server 会 tag_conflict 拒绝） */
  agents: Agent[]
}>()

const emit = defineEmits<{ (e: 'refresh'): void }>()

/* ------------------------------------------------------------ 共用表单 */

// 与 deploy/install.sh 的参数校验保持一致（比页面「改名」更严：不允许 : 和 @）
const TAG_RE = /^[A-Za-z0-9._-]{1,64}$/
const SERVER_RE = /^[A-Za-z0-9._-]+:\d{1,5}$/
const HOST_RE = /^[A-Za-z0-9._-]{1,255}$/

function defaultServer(): string {
  return `${location.hostname || '127.0.0.1'}:9800`
}

/** curl 一行命令里 Server 的 HTTP 地址：优先取页头配置的接口地址，退回当前域名:8080 */
function defaultHttpHost(): string {
  const base = getApiBase()
  if (base) {
    try {
      return new URL(base).host
    } catch {
      /* 配置的不是合法 URL 就退回默认 */
    }
  }
  return `${location.hostname || '127.0.0.1'}:8080`
}

const form = reactive({
  displayTag: '',
  server: defaultServer(),
  concurrency: 1,
})

const tab = ref<'copy' | 'curl' | 'ssh'>('copy')

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

/* ------------------------------------------------------------ 复制命令 */

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

const copyBlocks = computed<CmdBlock[]>(() => [
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

/* ------------------------------------------------------------ curl 安装 */

const httpHost = ref(defaultHttpHost())
const info = ref<InstallInfo | null>(null)
const infoError = ref('')
const infoLoading = ref(false)

async function loadInfo() {
  infoLoading.value = true
  try {
    info.value = await getInstallInfo()
    infoError.value = ''
  } catch (e) {
    info.value = null
    infoError.value = errorMessage(e, '读取安装分发状态失败')
  } finally {
    infoLoading.value = false
  }
}

const httpHostError = computed(() => {
  const h = httpHost.value.trim()
  if (!h) return 'HTTP 地址不能为空'
  if (!SERVER_RE.test(h)) return '格式应为 host:port，例如 10.0.0.5:8080'
  return ''
})

const curlCmd = computed(
  () =>
    `curl -fsSL http://${httpHostError.value ? '<http-host>:8080' : httpHost.value.trim()}/api/agent/install.sh | ` +
    `sudo bash -s -- --tag ${tagValue.value} --server ${serverValue.value}${concurrencyFlag.value}`,
)

const binaryReady = computed(() => !!info.value?.binaryAvailable)

function fmtSize(n?: number): string {
  if (!n) return '-'
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/* ------------------------------------------------------------ SSH 代装 */

const ssh = reactive({
  host: '',
  port: 22,
  user: 'root',
  authType: 'password' as 'password' | 'key',
  password: '',
  privateKey: '',
  passphrase: '',
  skipHostKeyCheck: false,
})

const sshRunning = ref(false)
const sshResult = ref<SshInstallResult | null>(null)

const sshHostError = computed(() => {
  const h = ssh.host.trim()
  if (!h) return ''
  return HOST_RE.test(h) ? '' : '只允许主机名或 IPv4（字母数字和 . _ -）'
})

const sshAuthMissing = computed(() =>
  ssh.authType === 'password' ? !ssh.password : !ssh.privateKey.trim(),
)

/** 需求：tag 或目标机为空时禁用按钮；其余校验错误一并拦下 */
const sshDisabled = computed(
  () =>
    sshRunning.value ||
    !form.displayTag.trim() ||
    !ssh.host.trim() ||
    !!tagError.value ||
    !!serverError.value ||
    !!sshHostError.value ||
    sshAuthMissing.value,
)

/** 底部提示：SSH 页把「开始安装」放在页脚常驻，文案跟着状态走 */
const footHint = computed(() => {
  if (sshRunning.value) return `正在 SSH 到 ${ssh.host} 执行 install.sh，请勿关闭`
  if (tab.value === 'ssh') return 'Server 代装，最长约 3 分钟'
  return '装完机器会自动出现在列表里'
})

const SSH_ERROR_LABELS: Record<string, string> = {
  tag_conflict: '机器名重名（tag_conflict）',
  connect_failed: '连不上目标机',
  auth_failed: 'SSH 认证失败',
  bad_private_key: '私钥不可用',
  host_key_changed: '主机指纹变化',
  upload_failed: '上传失败',
  timeout: '安装超时',
  install_failed: '安装失败',
  binary_missing: 'Server 缺少 atagent 二进制',
  bad_request: '参数有误',
  internal_error: '内部错误',
}

async function runSshInstall() {
  if (sshDisabled.value) return
  sshRunning.value = true
  sshResult.value = null
  try {
    const res = await sshInstall({
      host: ssh.host.trim(),
      port: ssh.port,
      user: ssh.user.trim() || 'root',
      authType: ssh.authType,
      password: ssh.authType === 'password' ? ssh.password : undefined,
      privateKey: ssh.authType === 'key' ? ssh.privateKey : undefined,
      passphrase: ssh.authType === 'key' && ssh.passphrase ? ssh.passphrase : undefined,
      skipHostKeyCheck: ssh.skipHostKeyCheck,
      tag: form.displayTag.trim(),
      server: form.server.trim(),
      concurrency: form.concurrency,
    })
    sshResult.value = res
    if (res.ok) {
      ElMessage({ type: 'success', message: `已在 ${ssh.host.trim()} 上装好并注册为 ${form.displayTag.trim()}`, duration: 3000 })
      emit('refresh')
    }
  } catch (e) {
    sshResult.value = {
      ok: false,
      error: errorMessage(e, 'SSH 安装失败'),
      errorCode: e instanceof ApiError ? e.code : undefined,
      output: '',
    }
  } finally {
    sshRunning.value = false
  }
}

/* ---------------------------------------------------------------- 生命周期 */

watch(visible, (open) => {
  if (!open) return
  form.displayTag = ''
  form.server = defaultServer()
  form.concurrency = 1
  httpHost.value = defaultHttpHost()
  sshResult.value = null
  // 口令 / 私钥绝不留在内存里过夜：每次打开抽屉清空
  ssh.password = ''
  ssh.privateKey = ''
  ssh.passphrase = ''
  void loadInfo()
})
</script>

<template>
  <el-drawer v-model="visible" :size="'min(680px, 100vw)'" :with-header="false">
    <div class="aid">
      <div class="aid__head">
        <div class="aid__ident">
          <div class="aid__title">安装 Agent</div>
          <div class="aid__sub">
            三种方式都走 <code class="code-inline">deploy/install.sh</code>，装完自动注册
          </div>
        </div>
        <el-button text :icon="'Close'" @click="visible = false" />
      </div>

      <div class="aid__body">
        <el-form label-position="top" class="aid__form" @submit.prevent>
          <div class="aid__grid">
            <el-form-item :error="(form.displayTag ? tagError : '') || undefined">
              <template #label>
                <span class="lbl">
                  <span class="lbl__text">机器名 tag <b class="req">*</b></span>
                  <span class="lbl__hint">全局唯一，重名会被拒绝</span>
                </span>
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
                  <span class="lbl__text">Server 地址 <b class="req">*</b></span>
                  <span class="lbl__hint">Agent 的 TCP :9800，不是网页的 :8080</span>
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
                <span class="lbl__text">最大并发</span>
                <span class="lbl__hint">1 - 4，装完也能在列表里改</span>
              </span>
            </template>
            <el-input-number v-model="form.concurrency" :min="1" :max="4" controls-position="right" />
          </el-form-item>
        </el-form>

        <el-tabs v-model="tab" class="aid__tabs">
          <!-- ==================================================== 复制命令 -->
          <el-tab-pane label="复制命令" name="copy">
            <div class="muted tab-desc">把安装物料拷到目标 Linux 机（需 root + systemd），在目标机上执行。</div>
            <section v-for="b in copyBlocks" :key="b.key" class="cmd">
              <div class="cmd__head">
                <div class="cmd__meta">
                  <span class="cmd__title">{{ b.title }}</span>
                  <span class="cmd__hint">{{ b.hint }}</span>
                </div>
                <el-tooltip
                  :disabled="!b.needForm || formValid"
                  content="先把上面的机器名 / Server 地址填对"
                  placement="top"
                >
                  <span>
                    <el-button
                      size="small"
                      type="primary"
                      plain
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
          </el-tab-pane>

          <!-- ==================================================== curl 安装 -->
          <el-tab-pane label="curl 安装" name="curl">
            <div class="muted tab-desc">
              目标机能访问 Server 的 HTTP :8080 时，一行命令装完：脚本、unit 模板、二进制都从本 Server 下载（<b>仅内网</b>，带
              sha256 校验）。
            </div>

            <el-alert
              v-if="infoError"
              type="error"
              :closable="false"
              show-icon
              :title="infoError"
              class="aid__warn"
            />
            <el-alert
              v-else-if="info && !binaryReady"
              type="warning"
              :closable="false"
              show-icon
              class="aid__warn"
            >
              <template #title>Server 上还没有可分发的 atagent 二进制</template>
              {{ info.hint }}
            </el-alert>
            <div v-else-if="info" class="dist-ok">
              <el-tag size="small" type="success" effect="plain">二进制已就绪</el-tag>
              <span class="mono sub">{{ fmtSize(info.binarySize) }}</span>
              <span class="mono sub" :title="info.binarySha256">sha256 {{ info.binarySha256?.slice(0, 16) }}…</span>
              <el-tag v-if="info.binaryElf === false" size="small" type="danger" effect="plain">
                不像 ELF 文件，请检查
              </el-tag>
            </div>

            <el-form label-position="top" @submit.prevent>
              <el-form-item :error="httpHostError || undefined">
                <template #label>
                  <span class="lbl">
                    <span class="lbl__text">Server HTTP 地址</span>
                    <span class="lbl__hint">目标机 curl 访问的地址（:8080）</span>
                  </span>
                </template>
                <el-input v-model="httpHost" spellcheck="false" class="mono" style="max-width: 320px" />
              </el-form-item>
            </el-form>

            <section class="cmd">
              <div class="cmd__head">
                <div class="cmd__meta">
                  <span class="cmd__title">在目标机上以 root 执行</span>
                  <span class="cmd__hint">装完自动等注册，最多 20s</span>
                </div>
                <el-tooltip
                  :disabled="formValid && !httpHostError && binaryReady"
                  :content="binaryReady ? '先把机器名 / Server 地址 / HTTP 地址填对' : 'Server 上还没有二进制'"
                  placement="top"
                >
                  <span>
                    <el-button
                      size="small"
                      type="primary"
                      plain
                      :icon="'DocumentCopy'"
                      :disabled="!formValid || !!httpHostError || !binaryReady"
                      @click="copy(curlCmd)"
                    >
                      复制
                    </el-button>
                  </span>
                </el-tooltip>
              </div>
              <pre class="cmd__code mono">{{ curlCmd }}</pre>
            </section>

            <div class="muted aid__tip">
              引导脚本先取 <code class="code-inline">/api/agent/files/install.sh</code>，再按
              <code class="code-inline">--url /api/agent/binary --sha256 …</code> 下载校验二进制，参数原样透传。
            </div>
          </el-tab-pane>

          <!-- ==================================================== SSH 代装 -->
          <el-tab-pane label="SSH 代装" name="ssh">
            <div class="muted tab-desc">
              由 Server SSH 到目标机上传并以 root 执行，输出回显在下方。口令 / 私钥<b>仅本次请求内存中使用</b>，不保存、不写日志。
            </div>

            <el-alert
              v-if="info && !binaryReady"
              type="warning"
              :closable="false"
              show-icon
              class="aid__warn"
            >
              <template #title>Server 上没有 atagent 二进制，代装会失败</template>
              {{ info.hint }}
            </el-alert>

            <el-form label-position="top" @submit.prevent>
              <div class="aid__grid3">
                <el-form-item :error="sshHostError || undefined">
                  <template #label><span class="lbl__text">目标机地址 <b class="req">*</b></span></template>
                  <el-input v-model="ssh.host" placeholder="例如 10.0.0.21" spellcheck="false" class="mono" />
                </el-form-item>
                <el-form-item label="SSH 端口">
                  <el-input-number v-model="ssh.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
                </el-form-item>
                <el-form-item label="SSH 用户">
                  <el-input v-model="ssh.user" spellcheck="false" class="mono" />
                </el-form-item>
              </div>

              <el-form-item label="登录凭据">
                <div class="cred">
                  <el-radio-group v-model="ssh.authType" size="small">
                    <el-radio-button value="password">口令</el-radio-button>
                    <el-radio-button value="key">私钥</el-radio-button>
                  </el-radio-group>
                  <el-input
                    v-if="ssh.authType === 'password'"
                    v-model="ssh.password"
                    type="password"
                    show-password
                    placeholder="SSH 登录口令"
                    autocomplete="new-password"
                    class="cred__input"
                  />
                </div>
                <template v-if="ssh.authType === 'key'">
                  <el-input
                    v-model="ssh.privateKey"
                    type="textarea"
                    :rows="4"
                    placeholder="粘贴 OpenSSH / PEM 私钥全文"
                    spellcheck="false"
                    class="mono cred__key"
                  />
                  <el-input
                    v-model="ssh.passphrase"
                    type="password"
                    show-password
                    placeholder="私钥口令（没有就留空）"
                    autocomplete="new-password"
                    class="cred__key"
                  />
                </template>
              </el-form-item>

              <el-form-item>
                <div class="hostkey">
                  <el-checkbox v-model="ssh.skipHostKeyCheck">跳过主机指纹校验</el-checkbox>
                  <div v-if="ssh.skipHostKeyCheck" class="hostkey__warn">
                    连接可能被中间人劫持，口令 / 私钥会送给冒充的机器，仅限受控内网使用。
                  </div>
                  <div v-else class="hostkey__hint">默认 accept-new：首次连接记录指纹，之后指纹变了会拒绝。</div>
                </div>
              </el-form-item>
            </el-form>

            <template v-if="sshResult">
              <el-alert
                :type="sshResult.ok ? 'success' : 'error'"
                :closable="false"
                show-icon
                class="ssh-result-alert"
              >
                <template #title>
                  <template v-if="sshResult.ok">安装完成，机器已注册</template>
                  <template v-else>
                    {{ SSH_ERROR_LABELS[sshResult.errorCode ?? ''] ?? '安装失败' }}
                  </template>
                </template>
                {{ sshResult.ok ? sshResult.message : sshResult.error }}
              </el-alert>
              <section v-if="sshResult.output" class="cmd">
                <div class="cmd__head">
                  <div class="cmd__meta">
                    <span class="cmd__title">install.sh 输出（尾部）</span>
                    <span v-if="sshResult.exitCode != null" class="cmd__hint">退出码 {{ sshResult.exitCode }}</span>
                    <span v-if="sshResult.durationMs != null" class="cmd__hint">{{ Math.round(sshResult.durationMs / 1000) }}s</span>
                  </div>
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    :icon="'DocumentCopy'"
                    @click="copy(sshResult.output!)"
                  >
                    复制
                  </el-button>
                </div>
                <pre class="cmd__code cmd__code--out mono">{{ sshResult.output }}</pre>
              </section>
            </template>
          </el-tab-pane>
        </el-tabs>

        <div class="muted aid__tip">
          失败排查：<code class="code-inline">tag_conflict</code> 换名重跑；连不上先
          <code class="code-inline">nc -vz &lt;server-host&gt; 9800</code>。重跑不会换机器身份。
        </div>
      </div>

      <div class="aid__foot">
        <span class="muted aid__foot-hint">{{ footHint }}</span>
        <span class="spacer" />
        <el-button :icon="'Refresh'" @click="emit('refresh')">刷新列表</el-button>
        <el-tooltip
          v-if="tab === 'ssh'"
          :disabled="!sshDisabled || sshRunning"
          content="机器名、目标机地址、认证信息都填好才能开始"
          placement="top"
        >
          <span>
            <el-button type="primary" :loading="sshRunning" :disabled="sshDisabled" @click="runSshInstall">
              {{ sshRunning ? '安装中…' : '开始安装' }}
            </el-button>
          </span>
        </el-tooltip>
        <el-button v-else type="primary" @click="visible = false">完成</el-button>
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

.aid__ident {
  min-width: 0;
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

/* 抽屉里只有这一层滚动，代码块 / 输出都不再自带滚动条 */
.aid__body {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.aid__warn {
  margin: 0 0 12px;
}

.aid__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

.aid__grid3 {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 0 14px;
}

@media (max-width: 640px) {
  .aid__grid,
  .aid__grid3 {
    grid-template-columns: 1fr;
  }
}

.aid__tabs {
  margin-top: 2px;
}

/* 切页签不用滚回顶部 */
.aid__tabs :deep(.el-tabs__header) {
  position: sticky;
  top: 0;
  z-index: 3;
  margin-bottom: 14px;
  background: var(--nat-panel);
}

.tab-desc {
  font-size: 12.5px;
  line-height: 1.8;
  margin-bottom: 12px;
}

.tab-desc b {
  font-weight: 640;
}

/* 说明另起一行，窄栏里标题不会被挤成「地 / 址」 */
.aid :deep(.el-form-item__label) {
  height: auto;
  line-height: 1.5;
  padding-bottom: 6px;
}

.lbl {
  display: block;
}

.lbl__text,
.lbl__hint {
  display: block;
  white-space: nowrap;
}

.lbl__hint {
  font-size: 12px;
  color: var(--nat-text-weak);
  font-weight: 400;
  white-space: normal;
}

.req {
  color: #dc2626;
}

.dist-ok {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.dist-ok .sub {
  font-size: 12px;
  color: var(--nat-text-sub);
}

.cmd {
  margin-bottom: 14px;
}

.cmd__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.cmd__meta {
  min-width: 0;
}

.cmd__title {
  font-size: 13px;
  font-weight: 600;
}

.cmd__hint {
  font-size: 12px;
  color: var(--nat-text-weak);
  line-height: 1.6;
  margin-left: 8px;
}

/* 命令整条可见：换行而不是横向裁掉后半段（--server 必须看得到） */
.cmd__code {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--nat-border);
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.cmd__code--out {
  overflow-wrap: anywhere;
}

.cred {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.cred__input {
  flex: 1;
  min-width: 0;
}

.cred__key {
  width: 100%;
  margin-top: 8px;
}

.hostkey {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.hostkey__warn {
  color: #dc2626;
  font-size: 12.5px;
  line-height: 1.7;
  font-weight: 600;
}

.hostkey__hint {
  color: var(--nat-text-weak);
  font-size: 12px;
  line-height: 1.6;
}

.ssh-result-alert {
  margin-bottom: 12px;
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

.aid__foot-hint {
  font-size: 12px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spacer {
  flex: 1;
}
</style>
