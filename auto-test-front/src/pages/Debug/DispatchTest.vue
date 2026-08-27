<template>
  <div class="dispatch-test">
    <header class="topbar">
      <div>
        <h1>任务下发测试</h1>
        <p>直接走 Server 的 doTask 调度链路，适合验证任务排队、下发、取消和 Agent 状态回传。</p>
      </div>
      <div class="topbar-actions">
        <el-button :icon="Refresh" :loading="loadingMachines" @click="refreshMachines">刷新机器</el-button>
        <el-button type="primary" :icon="VideoPlay" :loading="submitting" @click="submitTask">下发任务</el-button>
      </div>
    </header>

    <section class="toolbar">
      <el-input v-model="serverUrl" class="server-input" placeholder="Server 地址">
        <template #prepend>Server</template>
      </el-input>
      <el-input v-model="requestId" class="request-input" placeholder="requestId">
        <template #prepend>RequestId</template>
      </el-input>
      <el-button :icon="Connection" @click="connectTaskList">监听任务</el-button>
      <el-button :icon="DocumentCopy" @click="copyPayload">复制请求</el-button>
    </section>

    <main class="layout">
      <aside class="machine-column">
        <div class="section-title">
          <span>在线机器</span>
          <el-tag size="small">{{ machines.length }}</el-tag>
        </div>
        <el-input v-model="machineKeyword" clearable placeholder="筛选 machineTag / IP" />
        <div class="agent-ops">
          <el-button
            type="warning"
            plain
            :icon="CircleClose"
            :disabled="selectedMachines.length === 0"
            :loading="selectedOperationLoading"
            @click="clearSelectedAgents"
          >
            清空选中任务
          </el-button>
          <el-button
            type="danger"
            plain
            :icon="SwitchButton"
            :disabled="selectedMachines.length === 0"
            :loading="selectedOperationLoading"
            @click="restartSelectedAgents"
          >
            重启选中 Agent
          </el-button>
        </div>
        <div class="machine-list">
          <label
            v-for="machine in filteredMachines"
            :key="machine.machineTag"
            class="machine-row"
            :class="{ selected: selectedMachines.includes(machine.machineTag) }"
          >
            <el-checkbox v-model="selectedMachines" :label="machine.machineTag" />
            <div class="machine-main">
              <div class="machine-name">{{ machine.machineTag }}</div>
              <div class="machine-meta">
                <span>{{ machine.ipAddress || '-' }}</span>
                <span>{{ machine.executeStatus || '-' }}</span>
                <span v-if="machine.taskId">task {{ machine.taskId }}</span>
              </div>
            </div>
            <div class="machine-actions" @click.stop>
              <el-tooltip content="清空当前任务" placement="top">
                <el-button
                  link
                  type="warning"
                  :icon="CircleClose"
                  :loading="isOperating(machine.machineTag)"
                  @click="clearAgent(machine.machineTag)"
                />
              </el-tooltip>
              <el-tooltip content="重启 Agent" placement="top">
                <el-button
                  link
                  type="danger"
                  :icon="SwitchButton"
                  :loading="isOperating(machine.machineTag)"
                  @click="restartAgent(machine.machineTag)"
                />
              </el-tooltip>
            </div>
            <span class="status-dot" :class="machine.executeStatus === 'idle' ? 'idle' : 'busy'" />
          </label>
          <el-empty v-if="filteredMachines.length === 0" description="暂无在线机器" />
        </div>
      </aside>

      <section class="form-column">
        <div class="section-title">任务参数</div>
        <el-form label-position="top">
          <div class="form-grid">
            <el-form-item label="任务名">
              <el-input v-model="form.taskName" />
            </el-form-item>
            <el-form-item label="执行人">
              <el-input v-model="form.operator" />
            </el-form-item>
          </div>
          <el-form-item label="命令">
            <el-input
              v-model="form.executableFilePath"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 8 }"
              placeholder="例如：pwd && date && sleep 5 && echo done"
            />
          </el-form-item>
          <el-form-item label="任务描述">
            <el-input v-model="form.taskDesc" />
          </el-form-item>
          <el-form-item label="成功条件 JSON">
            <el-input
              v-model="form.conditionConfig"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 6 }"
              placeholder='例如：[{"condition":"equals","value":"0","status":"pass"}]'
            />
          </el-form-item>
        </el-form>

        <div class="payload-preview">
          <div class="section-title">请求预览</div>
          <pre>{{ JSON.stringify(payload, null, 2) }}</pre>
        </div>
      </section>

      <section class="result-column">
        <div class="section-title">
          <span>任务状态</span>
          <el-tag size="small" :type="taskRows.length ? 'success' : 'info'">{{ taskRows.length }}</el-tag>
        </div>
        <el-table :data="taskRows" height="360" size="small" border>
          <el-table-column prop="id" label="Task" width="82" />
          <el-table-column prop="name" label="Name" min-width="130" />
          <el-table-column prop="machineTag" label="Machine" min-width="140" />
          <el-table-column prop="executeStatus" label="Status" width="125">
            <template #default="{ row }">
              <el-tag :type="statusType(row.executeStatus)" size="small">{{ row.executeStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Execute" width="96">
            <template #default="{ row }">
              <span>{{ row.machineExecuteStatus?.[0]?.id || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="88">
            <template #default="{ row }">
              <el-button link type="danger" :disabled="!canCancel(row)" @click="cancelTask(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="event-log">
          <div class="section-title">事件</div>
          <div class="event-list">
            <div v-for="item in events" :key="item.id" class="event-row">
              <span class="event-time">{{ item.time }}</span>
              <span>{{ item.text }}</span>
            </div>
            <el-empty v-if="events.length === 0" description="暂无事件" />
          </div>
        </div>

        <div class="agent-event-log">
          <div class="section-title">
            <span>Agent 关键节点</span>
            <el-button size="small" :icon="Refresh" :loading="loadingAgentEvents" @click="refreshAgentEvents">
              刷新
            </el-button>
          </div>
          <div class="agent-event-toolbar">
            <el-checkbox v-model="agentEventUseRequestId">当前 RequestId</el-checkbox>
            <span>{{ displayedAgentEvents.length }} / {{ agentEvents.length }}</span>
          </div>
          <div class="agent-event-list">
            <div v-for="item in displayedAgentEvents" :key="item.eventId" class="agent-event-row">
              <div class="agent-event-head">
                <span class="event-time">{{ formatEventTime(item.time) }}</span>
                <el-tag size="small" :type="eventLevelType(item.level)">{{ item.eventType }}</el-tag>
                <span class="agent-event-machine">{{ item.machineTag || '-' }}</span>
              </div>
              <div class="agent-event-main">
                <span v-if="item.taskId">task {{ item.taskId }}</span>
                <span v-if="item.executeId">execute {{ item.executeId }}</span>
                <span>{{ item.message || item.detail || '-' }}</span>
              </div>
              <pre v-if="item.command" class="agent-event-command">{{ item.command }}</pre>
              <div v-if="item.detail || item.errorMessage" class="agent-event-detail">
                {{ item.detail || item.errorMessage }}
              </div>
            </div>
            <el-empty v-if="displayedAgentEvents.length === 0" description="暂无关键节点" />
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script lang="ts" setup>
import axios from 'axios';
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import ElMessage from 'element-plus/es/components/message/index';
import ElMessageBox from 'element-plus/es/components/message-box/index';
import { CircleClose, Connection, DocumentCopy, Refresh, SwitchButton, VideoPlay } from '@element-plus/icons-vue';
import useClipboard from 'vue-clipboard3';

const STORAGE_SERVER_URL = 'auto-test-dispatch-server-url';
const DEFAULT_SERVER_URL = localStorage.getItem(STORAGE_SERVER_URL) || 'http://127.0.0.1:1992';

const serverUrl = ref(DEFAULT_SERVER_URL);
const requestId = ref(`DISPATCH_TEST_${Date.now()}`);
const machines = ref<any[]>([]);
const selectedMachines = ref<string[]>([]);
const machineKeyword = ref('');
const taskRows = ref<any[]>([]);
const events = ref<Array<{ id: number; time: string; text: string }>>([]);
const agentEvents = ref<any[]>([]);
const agentEventUseRequestId = ref(false);
const loadingMachines = ref(false);
const submitting = ref(false);
const operatingMachines = ref<Set<string>>(new Set());
const loadingAgentEvents = ref(false);

const form = reactive({
  taskName: `dispatch-test-${new Date().toLocaleTimeString()}`,
  taskDesc: 'dispatch test from auto-test-front',
  operator: 'dispatch-test',
  executableFilePath: 'pwd && date && sleep 5 && echo dispatch-test-finished',
  conditionConfig: '',
});

let taskListSse: EventSource | null = null;
let taskListSseOpened = false;
let taskListSseErrorLogged = false;
let eventSeq = 0;

const api = computed(() => axios.create({ baseURL: serverUrl.value.replace(/\/$/, '') }));
const selectedOperationLoading = computed(() => selectedMachines.value.some((machineTag) => isOperating(machineTag)));

const payload = computed(() => ({
  requestId: requestId.value,
  taskName: form.taskName,
  taskDesc: form.taskDesc,
  operator: form.operator,
  executableFilePath: form.executableFilePath,
  conditionConfig: form.conditionConfig,
  targetIps: selectedMachines.value,
}));

const filteredMachines = computed(() => {
  const keyword = machineKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return machines.value;
  }
  return machines.value.filter((machine) => {
    return [machine.machineTag, machine.ipAddress, machine.tag]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword));
  });
});

const displayedAgentEvents = computed(() => {
  const selected = new Set(selectedMachines.value);
  return agentEvents.value.filter((event) => {
    if (selected.size > 0 && !selected.has(event.machineTag)) {
      return false;
    }
    return true;
  });
});

watch(serverUrl, (value) => {
  localStorage.setItem(STORAGE_SERVER_URL, value);
});

function addEvent(text: string) {
  events.value.unshift({
    id: ++eventSeq,
    time: new Date().toLocaleTimeString(),
    text,
  });
  if (events.value.length > 80) {
    events.value.pop();
  }
}

async function refreshMachines() {
  loadingMachines.value = true;
  try {
    const res = await api.value.get('/debug/machines');
    if (res.data?.success) {
      machines.value = res.data.data || [];
      addEvent(`刷新机器成功：${machines.value.length} 台在线`);
    } else {
      throw new Error(res.data?.errorMessage || '获取机器失败');
    }
  } catch (error: any) {
    ElMessage.error(error.message || '获取机器失败');
    addEvent(`刷新机器失败：${error.message || error}`);
  } finally {
    loadingMachines.value = false;
  }
}

async function refreshAgentEvents() {
  loadingAgentEvents.value = true;
  try {
    const res = await api.value.get('/debug/agent/events', {
      params: {
        requestId: agentEventUseRequestId.value ? requestId.value : undefined,
        limit: 500,
      },
    });
    if (res.data?.success) {
      agentEvents.value = res.data.data || [];
    } else {
      throw new Error(res.data?.errorMessage || '获取 Agent 关键节点失败');
    }
  } catch (error: any) {
    ElMessage.error(error.message || '获取 Agent 关键节点失败');
    addEvent(`获取 Agent 关键节点失败：${error.message || error}`);
  } finally {
    loadingAgentEvents.value = false;
  }
}

function isOperating(machineTag: string) {
  return operatingMachines.value.has(machineTag);
}

function setOperating(machineTag: string, operating: boolean) {
  const next = new Set(operatingMachines.value);
  if (operating) {
    next.add(machineTag);
  } else {
    next.delete(machineTag);
  }
  operatingMachines.value = next;
}

async function clearAgent(machineTag: string) {
  await operateAgent(machineTag, 'clear');
}

async function restartAgent(machineTag: string) {
  const confirmed = await confirmRestart([machineTag]);
  if (!confirmed) {
    return;
  }
  await operateAgent(machineTag, 'restart');
}

async function clearSelectedAgents() {
  if (selectedMachines.value.length === 0) {
    ElMessage.warning('请选择机器');
    return;
  }
  const machineTags = [...selectedMachines.value];
  await Promise.all(machineTags.map((machineTag) => operateAgent(machineTag, 'clear')));
}

async function restartSelectedAgents() {
  if (selectedMachines.value.length === 0) {
    ElMessage.warning('请选择机器');
    return;
  }
  const machineTags = [...selectedMachines.value];
  const confirmed = await confirmRestart(machineTags);
  if (!confirmed) {
    return;
  }
  await Promise.all(machineTags.map((machineTag) => operateAgent(machineTag, 'restart')));
}

async function confirmRestart(machineTags: string[]) {
  try {
    const targetText = machineTags.length === 1
      ? machineTags[0]
      : `${machineTags.length} 台 Agent`;
    await ElMessageBox.confirm(
      `确认重启 ${targetText}？Agent 进程退出后需要外部守护进程拉起。`,
      '重启 Agent',
      { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' },
    );
    return true;
  } catch {
    return false;
  }
}

async function operateAgent(machineTag: string, action: 'clear' | 'restart') {
  setOperating(machineTag, true);
  const actionText = action === 'restart' ? '重启 Agent' : '清空任务';
  const url = action === 'restart' ? '/debug/agent/restart' : '/debug/stop';
  try {
    const res = await api.value.post(url, { machineTag });
    if (res.data?.success && res.data.data === true) {
      ElMessage.success(`${actionText}成功`);
      addEvent(`${actionText}成功：${machineTag}`);
      setTimeout(refreshAgentEvents, 300);
    } else if (res.data?.success) {
      ElMessage.warning(`${actionText}已执行，Agent RPC 未确认`);
      addEvent(`${actionText}未确认，Server 保留当前运行状态：${machineTag}`);
    } else {
      throw new Error(res.data?.errorMessage || `${actionText}失败`);
    }
    setTimeout(refreshMachines, action === 'restart' ? 1200 : 500);
  } catch (error: any) {
    ElMessage.error(error.message || `${actionText}失败`);
    addEvent(`${actionText}失败：${machineTag}，${error.message || error}`);
  } finally {
    setOperating(machineTag, false);
  }
}

async function submitTask() {
  if (!serverUrl.value.trim()) {
    ElMessage.warning('请填写 Server 地址');
    return;
  }
  if (selectedMachines.value.length === 0) {
    ElMessage.warning('请选择至少一台机器');
    return;
  }
  if (!form.executableFilePath.trim()) {
    ElMessage.warning('请填写命令');
    return;
  }

  submitting.value = true;
  try {
    const res = await api.value.post('/sseEmitter/doTask', payload.value);
    if (res.data?.success) {
      ElMessage.success(`已下发，首个 taskId: ${res.data.data}`);
      addEvent(`下发成功，机器数 ${selectedMachines.value.length}，首个 taskId ${res.data.data}`);
      connectTaskList();
      setTimeout(refreshMachines, 800);
      setTimeout(refreshAgentEvents, 1000);
    } else {
      throw new Error(res.data?.errorMessage || '下发失败');
    }
  } catch (error: any) {
    ElMessage.error(error.message || '下发失败');
    addEvent(`下发失败：${error.message || error}`);
  } finally {
    submitting.value = false;
  }
}

function connectTaskList() {
  closeTaskList();
  taskListSseOpened = false;
  taskListSseErrorLogged = false;
  const url = `${serverUrl.value.replace(/\/$/, '')}/sseEmitter/task/list?requestId=${encodeURIComponent(requestId.value)}`;
  taskListSse = new EventSource(url);
  taskListSse.onopen = () => {
    addEvent(taskListSseOpened ? '任务状态 SSE 已恢复' : `开始监听 requestId=${requestId.value}`);
    taskListSseOpened = true;
    taskListSseErrorLogged = false;
  };
  taskListSse.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      taskRows.value = Array.isArray(data.data) ? data.data : [];
      addEvent(`任务状态更新：${taskRows.value.map((item) => `${item.machineTag}:${item.executeStatus}`).join(', ') || '空'}`);
    } catch (error: any) {
      addEvent(`任务状态解析失败：${error.message || error}`);
    }
  };
  taskListSse.onerror = () => {
    if (!taskListSseErrorLogged) {
      addEvent('任务状态 SSE 连接异常，浏览器会自动重连');
      taskListSseErrorLogged = true;
    }
  };
}

function closeTaskList() {
  if (taskListSse) {
    taskListSse.close();
    taskListSse = null;
  }
  taskListSseOpened = false;
  taskListSseErrorLogged = false;
}

function canCancel(row: any) {
  return ['to be scheduled', 'dispatching', 'running'].includes(row.executeStatus);
}

async function cancelTask(row: any) {
  try {
    const res = await api.value.get(`/sseEmitter/cancelTask?id=${row.id}`);
    if (res.data?.success) {
      ElMessage.success('取消成功');
      addEvent(`取消 taskId=${row.id}`);
    } else {
      throw new Error(res.data?.errorMessage || '取消失败');
    }
  } catch (error: any) {
    ElMessage.error(error.message || '取消失败');
    addEvent(`取消失败：${error.message || error}`);
  }
}

function statusType(status: string) {
  if (status === 'pass' || status === 'success') return 'success';
  if (status === 'fail' || status === 'exception' || status === 'block') return 'danger';
  if (status === 'running' || status === 'dispatching') return 'warning';
  if (status === 'cancel') return 'info';
  return '';
}

function eventLevelType(level: string) {
  if (level === 'ERROR') return 'danger';
  if (level === 'WARN') return 'warning';
  return 'info';
}

function formatEventTime(time: number) {
  if (!time) {
    return '-';
  }
  return new Date(time).toLocaleTimeString();
}

async function copyPayload() {
  const { toClipboard } = useClipboard();
  try {
    await toClipboard(JSON.stringify(payload.value, null, 2));
    ElMessage.success('请求已复制');
  } catch (error: any) {
    ElMessage.error(error.message || '复制失败');
  }
}

onMounted(() => {
  refreshMachines();
  refreshAgentEvents();
});

onUnmounted(() => {
  closeTaskList();
});
</script>

<style scoped>
.dispatch-test {
  min-height: 100vh;
  padding: 20px;
  box-sizing: border-box;
  background: #f4f6f8;
  color: #1f2937;
}

.topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 16px;
}

.topbar h1 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 650;
}

.topbar p {
  margin: 0;
  color: #667085;
  font-size: 13px;
}

.topbar-actions,
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar {
  padding: 12px 0 18px;
  border-top: 1px solid #dde3ea;
}

.server-input {
  max-width: 360px;
}

.request-input {
  max-width: 340px;
}

.layout {
  display: grid;
  grid-template-columns: minmax(280px, 340px) minmax(360px, 1fr) minmax(420px, 1.1fr);
  gap: 16px;
  align-items: start;
}

.machine-column,
.form-column,
.result-column {
  min-width: 0;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 32px;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 650;
}

.machine-list {
  margin-top: 10px;
  max-height: calc(100vh - 185px);
  overflow: auto;
  border: 1px solid #dde3ea;
  background: #fff;
}

.agent-ops {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}

.agent-ops :deep(.el-button) {
  margin-left: 0;
}

.machine-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 58px;
  padding: 8px 10px;
  border-bottom: 1px solid #eef2f6;
  cursor: pointer;
}

.machine-row:last-child {
  border-bottom: 0;
}

.machine-row.selected {
  background: #eef6ff;
}

.machine-main {
  flex: 1;
  min-width: 0;
}

.machine-name {
  font-family: Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.machine-meta {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  color: #667085;
  font-size: 12px;
}

.machine-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #f59e0b;
}

.status-dot.idle {
  background: #22c55e;
}

.status-dot.busy {
  background: #f59e0b;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.payload-preview {
  margin-top: 4px;
}

pre {
  max-height: 260px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid #dde3ea;
  background: #111827;
  color: #d1fae5;
  font-size: 12px;
  line-height: 1.45;
}

.event-log {
  margin-top: 14px;
}

.agent-event-log {
  margin-top: 14px;
}

.event-list {
  height: calc(100vh - 570px);
  min-height: 180px;
  overflow: auto;
  border: 1px solid #dde3ea;
  background: #fff;
}

.event-row {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid #eef2f6;
  font-size: 12px;
  line-height: 1.4;
}

.event-time {
  flex: 0 0 74px;
  color: #667085;
  font-family: Menlo, Monaco, Consolas, monospace;
}

.agent-event-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: #667085;
  font-size: 12px;
}

.agent-event-list {
  max-height: 360px;
  overflow: auto;
  border: 1px solid #dde3ea;
  background: #fff;
}

.agent-event-row {
  padding: 9px 10px;
  border-bottom: 1px solid #eef2f6;
  font-size: 12px;
}

.agent-event-row:last-child {
  border-bottom: 0;
}

.agent-event-head,
.agent-event-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.agent-event-main {
  margin-top: 6px;
  color: #344054;
  flex-wrap: wrap;
}

.agent-event-machine {
  color: #667085;
  font-family: Menlo, Monaco, Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-event-command {
  max-height: 96px;
  margin-top: 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

.agent-event-detail {
  margin-top: 6px;
  color: #667085;
  line-height: 1.45;
  word-break: break-word;
}

@media (max-width: 1200px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .machine-list,
  .event-list {
    max-height: 360px;
  }

  .toolbar,
  .topbar {
    flex-direction: column;
    align-items: stretch;
  }

  .server-input,
  .request-input {
    max-width: none;
  }
}
</style>
