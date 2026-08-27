<template>
  <div class="debug-console">
    <!-- 页面标题 -->
    <div class="page-header">
      <h1>Agent 调试控制台</h1>
      <div class="header-actions">
        <el-button type="primary" @click="refreshMachines" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新机器列表
        </el-button>
      </div>
    </div>

    <div class="main-content">
      <!-- 左侧：机器列表 -->
      <div class="machine-panel">
        <div class="panel-header">
          <h3>在线 Agent ({{ onlineMachines.length }})</h3>
        </div>
        <div class="machine-list">
          <div
            v-for="machine in onlineMachines"
            :key="machine.machineTag"
            class="machine-item"
            :class="{ active: selectedMachine?.machineTag === machine.machineTag }"
            @click="selectMachine(machine)"
          >
            <div class="machine-info">
              <div class="machine-main">
                <span class="status-dot" :class="machine.executeStatus === 'idle' ? 'idle' : 'busy'"></span>
                <span class="machine-tag">{{ machine.machineTag }}</span>
              </div>
              <div class="machine-meta">
                <span v-if="machine.isDocker === 1" class="docker-badge">Docker</span>
                <span class="machine-status">{{ machine.executeStatus }}</span>
              </div>
            </div>
            <div class="machine-details">
              <div class="detail-item">
                <span class="label">IP:</span>
                <span class="value">{{ machine.ipAddress }}</span>
              </div>
              <div class="detail-item">
                <span class="label">CPU:</span>
                <span class="value">{{ machine.cpuUsage?.toFixed(1) }}%</span>
              </div>
              <div class="detail-item">
                <span class="label">内存:</span>
                <span class="value">{{ formatMemory(machine.availableMemory) }}/{{ formatMemory(machine.totalMemory) }}</span>
              </div>
              <div v-if="machine.containerName" class="detail-item">
                <span class="label">容器:</span>
                <span class="value">{{ machine.containerName }}</span>
              </div>
            </div>
            <div class="machine-actions">
              <el-button
                v-if="machine.executeStatus !== 'idle'"
                type="danger"
                size="small"
                :loading="stoppingMachine === machine.machineTag"
                @click.stop="stopMachineTask(machine)"
              >
                中止任务
              </el-button>
            </div>
          </div>
          <div v-if="onlineMachines.length === 0" class="no-machines">
            <el-empty description="暂无在线 Agent" />
          </div>
        </div>
      </div>

      <!-- 右侧：命令输入和日志 -->
      <div class="console-panel">
        <div class="panel-header">
          <h3>
            <span v-if="selectedMachine">{{ selectedMachine.machineTag }}</span>
            <span v-else>请选择 Agent</span>
          </h3>
        </div>

        <!-- 命令输入区 -->
        <div class="command-section">
          <div class="command-input-wrapper">
            <el-input
              v-model="command"
              placeholder="输入要执行的命令，如: ls -la, pwd, echo hello"
              @keyup.enter="executeCommand"
              :disabled="!selectedMachine"
              size="large"
            >
              <template #prepend>
                <span class="prompt">$</span>
              </template>
              <template #append>
                <el-button
                  type="primary"
                  @click="executeCommand"
                  :loading="executing"
                  :disabled="!selectedMachine || !command.trim()"
                >
                  执行
                </el-button>
              </template>
            </el-input>
          </div>
          <div class="command-history" v-if="commandHistory.length > 0">
            <span class="history-label">历史命令:</span>
            <el-tag
              v-for="(cmd, index) in commandHistory.slice(-5)"
              :key="index"
              size="small"
              @click="command = cmd"
              class="history-tag"
            >
              {{ cmd }}
            </el-tag>
          </div>
        </div>

        <!-- 日志输出区 -->
        <div class="log-section">
          <div class="log-header">
            <span class="log-title">执行日志</span>
            <div class="log-actions">
              <el-button size="small" @click="clearLogs" type="info" plain>
                清空日志
              </el-button>
              <el-button size="small" @click="copyLogs" type="info" plain>
                复制日志
              </el-button>
            </div>
          </div>
          <div class="log-viewport" ref="logViewport">
            <div class="log-content">
              <div v-for="(entry, index) in logEntries" :key="index" class="log-entry">
                <div class="log-command-line">
                  <span class="log-time">{{ entry.time }}</span>
                  <span class="log-machine">{{ entry.machine }}</span>
                  <span class="log-prompt">$</span>
                  <span class="log-cmd">{{ entry.command }}</span>
                </div>
                <div class="log-output">
                  <div v-if="entry.truncated" class="log-truncated">
                    ... 已省略 {{ entry.truncatedCount }} 行旧日志（仅显示最近 {{ MAX_LOG_LINES }} 行）
                  </div>
                  <div v-for="(line, lineIndex) in entry.output" :key="lineIndex" class="log-line">
                    {{ line }}
                  </div>
                  <div v-if="entry.status === 'running'" class="log-loading">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    执行中... ({{ entry.output.length }} 行)
                  </div>
                  <div v-if="entry.status === 'error'" class="log-error">
                    {{ entry.error }}
                  </div>
                </div>
              </div>
              <div v-if="logEntries.length === 0" class="no-logs">
                <span>选择 Agent 并输入命令开始调试</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { useHttp } from '@/http';
import { ElMessage } from 'element-plus';
import { Refresh, Loading } from '@element-plus/icons-vue';
import useClipboard from 'vue-clipboard3';

const { get, post, listenSSE } = useHttp();

// 状态
const loading = ref(false);
const executing = ref(false);
const onlineMachines = ref<any[]>([]);
const selectedMachine = ref<any>(null);
const command = ref('');
const commandHistory = ref<string[]>([]);
const logEntries = ref<any[]>([]);
const logViewport = ref<HTMLElement | null>(null);
const stoppingMachine = ref<string | null>(null);

// SSE 连接
let currentSSE: (() => void) | null = null;

// 日志行数限制
const MAX_LOG_LINES = 1000;
// 历史命令条目数量限制
const MAX_LOG_ENTRIES = 50;

// 添加日志行（带行数限制）
const addLogLine = (entry: any, line: string, seenLines: Set<string>) => {
  entry.output.push(line);

  // 超过限制时，删除最旧的日志
  if (entry.output.length > MAX_LOG_LINES) {
    const removed = entry.output.shift();
    // 同时从去重 Set 中删除，防止内存泄漏
    if (removed) {
      seenLines.delete(removed);
    }

    // 如果还没有添加截断提示，添加一个
    if (!entry.truncated) {
      entry.truncated = true;
      entry.truncatedCount = 0;
    }
    entry.truncatedCount++;
  }
};

// 清理旧的日志条目
const cleanupOldEntries = () => {
  while (logEntries.value.length > MAX_LOG_ENTRIES) {
    logEntries.value.shift();
  }
};

// 获取机器列表
const refreshMachines = async () => {
  loading.value = true;
  try {
    const res = await get('/debug/machines');
    if (res.success) {
      onlineMachines.value = res.data || [];
    } else {
      ElMessage.error(res.errorMessage || '获取机器列表失败');
    }
  } catch (e: any) {
    ElMessage.error('获取机器列表失败: ' + e.message);
  } finally {
    loading.value = false;
  }
};

// 选择机器
const selectMachine = (machine: any) => {
  selectedMachine.value = machine;
};

// 停止机器上的任务
const stopMachineTask = async (machine: any) => {
  stoppingMachine.value = machine.machineTag;
  try {
    const res = await post('/debug/stop', {
      machineTag: machine.machineTag
    });
    if (res.success) {
      ElMessage.success('任务已中止');
      // 刷新机器列表
      refreshMachines();
    } else {
      ElMessage.error(res.errorMessage || '中止任务失败');
    }
  } catch (e: any) {
    ElMessage.error('中止任务失败: ' + e.message);
  } finally {
    stoppingMachine.value = null;
  }
};

// 执行命令
const executeCommand = async () => {
  if (!selectedMachine.value || !command.value.trim()) return;

  const cmd = command.value.trim();
  executing.value = true;

  // 添加到历史
  if (!commandHistory.value.includes(cmd)) {
    commandHistory.value.push(cmd);
  }

  // 清理旧的日志条目（防止内存堆积）
  cleanupOldEntries();

  // 创建日志条目
  const entryData = {
    time: new Date().toLocaleTimeString(),
    machine: selectedMachine.value.machineTag,
    command: cmd,
    output: [] as string[],
    status: 'running',
    error: '',
    truncated: false,
    truncatedCount: 0
  };
  logEntries.value.push(entryData);
  // 获取响应式引用（Vue 3 会自动将 push 的对象转换为响应式代理）
  const entry = logEntries.value[logEntries.value.length - 1];
  scrollToBottom();

  try {
    const res = await post('/debug/execute', {
      machineTag: selectedMachine.value.machineTag,
      command: cmd
    });

    if (res.success) {
      const executeId = res.data.executeId;
      command.value = '';

      // 执行命令后刷新机器列表（延迟1秒让状态更新）
      setTimeout(() => refreshMachines(), 1000);

      // 连接 SSE 获取日志
      connectSSE(executeId, entry);
    } else {
      entry.status = 'error';
      entry.error = res.errorMessage || '执行失败';
      executing.value = false;
    }
  } catch (e: any) {
    entry.status = 'error';
    entry.error = e.message || '执行失败';
    executing.value = false;
  }
};

// 连接 SSE 获取日志
const connectSSE = (executeId: number, entry: any) => {
  // 关闭之前的连接
  if (currentSSE) {
    currentSSE();
    currentSSE = null;
  }

  // 用于去重的 Set
  const seenLines = new Set<string>();

  // 关闭 SSE 的辅助函数
  const closeSSE = () => {
    if (currentSSE) {
      currentSSE();
      currentSSE = null;
    }
  };

  currentSSE = listenSSE(`/sseEmitter/connect/${executeId}`, (data: any) => {
    // 处理 SSE 错误
    if (data.error) {
      entry.status = 'error';
      entry.error = data.error;
      executing.value = false;
      closeSSE();
      return;
    }

    if (data.data) {
      const lines = data.data.split('\r\n');
      lines.forEach((line: string) => {
        if (line) {
          // 去除 -idNo: xxx 标记
          const cleanLine = line.replace(/-idNo:\s*\S+$/, '').trim();

          // 检查是否是完成标记
          if (cleanLine.includes('[finished]')) {
            const result = cleanLine.replace('[finished]', '').trim();
            if (result && !seenLines.has(result)) {
              seenLines.add(result);
              addLogLine(entry, result, seenLines);
            }
            entry.status = 'completed';
            executing.value = false;
            closeSSE();
            scrollToBottom();
            // 任务完成后刷新机器列表
            refreshMachines();
            return;
          }

          // 使用 Set 去重
          if (cleanLine && !seenLines.has(cleanLine)) {
            seenLines.add(cleanLine);
            addLogLine(entry, cleanLine, seenLines);
            scrollToBottom();
          }
        }
      });
    }
  });

  // 2小时超时保护（针对长时间任务）
  setTimeout(() => {
    if (entry.status === 'running') {
      entry.status = 'completed';
      entry.output.push('[超时] 执行超过2小时，已断开SSE连接（任务可能仍在执行）');
      executing.value = false;
      closeSSE();
      scrollToBottom();
    }
  }, 2 * 60 * 60 * 1000); // 2小时
};

// 滚动到底部（节流，避免频繁滚动）
let scrollTimer: ReturnType<typeof setTimeout> | null = null;
const scrollToBottom = () => {
  if (scrollTimer) return;
  scrollTimer = setTimeout(() => {
    scrollTimer = null;
    nextTick(() => {
      if (logViewport.value) {
        logViewport.value.scrollTop = logViewport.value.scrollHeight;
      }
    });
  }, 100); // 100ms 节流
};

// 清空日志
const clearLogs = () => {
  logEntries.value = [];
  ElMessage.success('日志已清空');
};

// 复制日志
const copyLogs = () => {
  const { toClipboard } = useClipboard();
  const text = logEntries.value.map(entry => {
    let result = `[${entry.time}] ${entry.machine}$ ${entry.command}\n`;
    result += entry.output.join('\n');
    if (entry.error) {
      result += `\nError: ${entry.error}`;
    }
    return result;
  }).join('\n\n');

  try {
    toClipboard(text);
    ElMessage.success('复制成功');
  } catch (e) {
    ElMessage.error('复制失败');
  }
};

// 格式化内存
const formatMemory = (bytes: number) => {
  if (!bytes) return '0';
  const mb = bytes / 1024 / 1024;
  if (mb < 1024) {
    return mb.toFixed(0) + 'MB';
  }
  return (mb / 1024).toFixed(1) + 'GB';
};

// 定时器引用
let refreshInterval: ReturnType<typeof setInterval> | null = null;

onMounted(() => {
  refreshMachines();
  // 每10秒刷新一次机器列表
  refreshInterval = setInterval(refreshMachines, 10000);
});

onUnmounted(() => {
  // 清理定时器
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
  // 关闭 SSE 连接
  if (currentSSE) {
    currentSSE();
    currentSSE = null;
  }
});
</script>

<style scoped>
.debug-console {
  padding: 20px;
  height: 100vh;
  box-sizing: border-box;
  background: #f5f7fa;
  display: flex;
  flex-direction: column;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: #2c3e50;
}

.main-content {
  flex: 1;
  display: flex;
  gap: 20px;
  min-height: 0;
}

/* 机器列表面板 */
.machine-panel {
  width: 350px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
}

.panel-header {
  padding: 16px 20px;
  border-bottom: 1px solid #e9ecef;
}

.panel-header h3 {
  margin: 0;
  font-size: 16px;
  color: #2c3e50;
}

.machine-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.machine-item {
  padding: 12px;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 2px solid transparent;
  background: #f8f9fa;
}

.machine-item:hover {
  background: #e9ecef;
}

.machine-item.active {
  background: #e6f7ff;
  border-color: #1890ff;
}

.machine-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.machine-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.idle {
  background: #52c41a;
}

.status-dot.busy {
  background: #faad14;
}

.machine-tag {
  font-weight: 600;
  color: #2c3e50;
  font-family: 'Courier New', monospace;
  font-size: 13px;
}

.machine-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}

.docker-badge {
  background: #1890ff;
  color: white;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
}

.machine-status {
  font-size: 12px;
  color: #6c757d;
}

.machine-details {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  font-size: 12px;
}

.machine-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
}

.detail-item {
  display: flex;
  gap: 4px;
}

.detail-item .label {
  color: #6c757d;
}

.detail-item .value {
  color: #2c3e50;
  font-family: 'Courier New', monospace;
}

.no-machines {
  padding: 40px 20px;
  text-align: center;
}

/* 控制台面板 */
.console-panel {
  flex: 1;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.command-section {
  padding: 16px 20px;
  border-bottom: 1px solid #e9ecef;
}

.command-input-wrapper {
  margin-bottom: 8px;
}

.prompt {
  font-family: 'Courier New', monospace;
  font-weight: bold;
  color: #52c41a;
}

.command-history {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.history-label {
  font-size: 12px;
  color: #6c757d;
}

.history-tag {
  cursor: pointer;
  transition: all 0.2s;
}

.history-tag:hover {
  background: #1890ff;
  color: white;
}

/* 日志区域 */
.log-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-bottom: 1px solid #e9ecef;
}

.log-title {
  font-weight: 600;
  color: #2c3e50;
}

.log-actions {
  display: flex;
  gap: 8px;
}

.log-viewport {
  flex: 1;
  overflow-y: auto;
  background: #1e1e1e;
  padding: 16px;
}

.log-content {
  font-family: 'Courier New', monospace;
  font-size: 13px;
}

.log-entry {
  margin-bottom: 16px;
}

.log-command-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.log-time {
  color: #6c757d;
  font-size: 11px;
}

.log-machine {
  color: #1890ff;
}

.log-prompt {
  color: #52c41a;
  font-weight: bold;
}

.log-cmd {
  color: #ffffff;
}

.log-output {
  padding-left: 20px;
}

.log-line {
  color: #52c41a;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.log-loading {
  color: #faad14;
  display: flex;
  align-items: center;
  gap: 8px;
}

.log-error {
  color: #ff4d4f;
}

.log-truncated {
  color: #faad14;
  font-style: italic;
  padding: 4px 0;
  border-bottom: 1px dashed #555;
  margin-bottom: 8px;
}

.no-logs {
  color: #6c757d;
  text-align: center;
  padding: 40px;
}

/* 滚动条 */
.log-viewport::-webkit-scrollbar {
  width: 8px;
}

.log-viewport::-webkit-scrollbar-track {
  background: #2a2a2a;
}

.log-viewport::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.log-viewport::-webkit-scrollbar-thumb:hover {
  background: #777;
}

.machine-list::-webkit-scrollbar {
  width: 6px;
}

.machine-list::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.machine-list::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}
</style>
