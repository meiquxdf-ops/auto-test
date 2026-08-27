<template>
  <div class="task-dashboard">
    <!-- 任务概览卡片 -->
    <div class="overview-section">
      <div class="overview-card">
        <div class="card-header">
          <h2 class="card-title">
            <i class="el-icon-document"></i>
            任务执行概览
          </h2>
          <div class="status-badge" :class="getStatusClass(result.overallStatus)">
            <el-tag :type="getTagType(result.overallStatus)" size="large" effect="dark">
              {{ result.overallStatus === 'success' ? '完成' : getStatusText(result.overallStatus) }}
            </el-tag>
          </div>
        </div>
        
        <div class="stats-grid">
          <div class="stat-item">
            <div class="stat-icon total">
              <i class="el-icon-files"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ taskData.length }}</div>
              <div class="stat-label">总任务数</div>
            </div>
          </div>
          
          <div class="stat-item">
            <div class="stat-icon success">
              <i class="el-icon-check"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ result.statusCounts.success }}</div>
              <div class="stat-label">成功</div>
            </div>
          </div>
          
          <div class="stat-item">
            <div class="stat-icon fail">
              <i class="el-icon-close"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ result.statusCounts.fail }}</div>
              <div class="stat-label">失败</div>
            </div>
          </div>
          
          <div class="stat-item">
            <div class="stat-icon running">
              <i class="el-icon-loading"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ result.runningTasks.length }}</div>
              <div class="stat-label">运行中</div>
            </div>
          </div>
          
          <div class="stat-item">
            <div class="stat-icon pending">
              <i class="el-icon-time"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ result.statusCounts.pending }}</div>
              <div class="stat-label">待执行</div>
            </div>
          </div>
          
          <div class="stat-item">
            <div class="stat-icon exception">
              <i class="el-icon-warning"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ result.statusCounts.exception }}</div>
              <div class="stat-label">异常</div>
            </div>
          </div>
          
          <div class="stat-item">
            <div class="stat-icon cancel">
              <i class="el-icon-close"></i>
            </div>
            <div class="stat-content">
              <div class="stat-number">{{ result.statusCounts.cancel }}</div>
              <div class="stat-label">已取消</div>
            </div>
          </div>
        </div>
        
        <div class="progress-section">
          <div class="progress-header">
            <span class="progress-label">执行进度</span>
            <span class="progress-percentage">{{ result.executePercentage.toFixed(1) }}%</span>
          </div>
          <el-progress 
            :percentage="result.executePercentage" 
            :stroke-width="12"
            :show-text="false"
            class="custom-progress"
          ></el-progress>
        </div>
      </div>
      
      <div class="info-cards">
        <div class="info-card">
          <div class="info-card-header">
            <i class="el-icon-user"></i>
            <span>执行人员</span>
          </div>
          <div class="executors-list">
            <div v-for="item in result.operators" :key="item" class="executor-item">
              <el-avatar 
                :src="'http://10.1.2.8:9000/chaosuser/'+item+'/avatar.png'"
                size="medium"
                class="executor-avatar"
              ></el-avatar>
              <span class="executor-name">{{ item }}</span>
            </div>
          </div>
        </div>
        
        <div class="info-card">
          <div class="info-card-header">
            <i class="el-icon-time"></i>
            <span>执行时间</span>
          </div>
          <div class="time-info">
            <div class="time-item">
              <span class="time-label">开始时间:</span>
              <span class="time-value">{{ formatTime(result.executeStartTime) }}</span>
            </div>
            <div class="time-item">
              <span class="time-label">结束时间:</span>
              <span class="time-value">{{ formatTime(result.executeEndTime) }}</span>
            </div>
            <div class="time-item">
              <span class="time-label">执行时长:</span>
              <span class="time-value">{{ formattedExecutionTime() }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 任务列表操作栏 -->
    <div class="actions-section">
      <div class="search-box">
        <el-input 
          v-model="caseSearch"
          @input="applyTaskFilters"
          placeholder="输入任务名称或编号筛选..."
          class="custom-search"
          size="large"
          clearable
        >
          <template #prefix>
            <i class="el-icon-search"></i>
          </template>
        </el-input>
        <el-select
          v-model="statusSearch"
          class="status-filter"
          placeholder="全部状态"
          size="large"
          clearable
          @change="applyTaskFilters"
        >
          <el-option label="全部状态" value="" />
          <el-option
            v-for="item in taskStatusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
      
      <div class="action-buttons">
        <el-button 
          type="primary" 
          size="large"
          :disabled="selectTask.length === 0"
          @click="confirmBatchRepeatDialogVisable = true"
          class="batch-execute-btn"
        >
          <i class="el-icon-refresh"></i>
          重新执行选中 ({{ selectTask.length }})
        </el-button>
        <el-button
          size="large"
          type="warning"
          plain
          :disabled="selectedCancelableTasks.length === 0"
          :loading="batchCancelLoading"
          @click="batchCancelTasks"
        >
          取消选中任务 ({{ selectedCancelableTasks.length }})
        </el-button>
      </div>
    </div>

    <!-- 任务列表表格 -->
    <div class="table-section">
      <el-table 
        :data="taskDataCopy" 
        height="400" 
        stripe
        class="custom-table"
        row-key="id"
        @selection-change="handleSelectionChange"
        :header-cell-style="{ background: '#f8f9fa', color: '#606266', fontWeight: '600' }"
      >
        <el-table-column type="selection" width="44" align="center"></el-table-column>
        
        <el-table-column label="任务" prop="desc" min-width="360" class-name="task-cell">
          <template #default="scope">
            <div class="case-summary">
              <span class="case-desc">{{ scope.row.desc }}</span>
              <el-link
                :href="getChaosUrl() + '/product/testManage/' + planId + '/test-plan-list?planId=' + scope?.row?.requestId?.split('_')[2] + '&tabKey=1&caseNo=' + scope.row.name"
                target="_blank"
                type="primary"
                class="case-link case-no-link"
              >
                {{ scope.row.name }}
              </el-link>
            </div>
          </template>
        </el-table-column>
        
        <el-table-column label="执行时间" prop="startTime" width="150" align="center">
          <template #default="scope">
            <div class="time-stack">
              <span class="time-cell" :title="fullTimeFormatter(scope.row.startTime)">
                {{ compactTimeFormatter(scope.row.startTime) }}
              </span>
              <span v-if="scope.row.executeStatus !== 'running'" class="time-cell muted" :title="fullTimeFormatter(scope.row.endTime)">
                {{ compactTimeFormatter(scope.row.endTime) }}
              </span>
              <span v-else class="estimated-time">
                {{ formatExpectTime(scope.row.expectEndTime) }}
              </span>
            </div>
          </template>
        </el-table-column>
        
        <el-table-column label="机器" prop="machineIps" width="136" show-overflow-tooltip>
          <template #default="{row}">
            <span class="machine-info" :title="machineDisplay(row)">
              {{ machineDisplay(row) }}
            </span>
          </template>
        </el-table-column>
        
        <el-table-column label="状态" prop="executeStatus" width="86" align="center">
          <template #default="scope">
            <div class="status-cell">
              <div class="status-indicator" :class="getStatusClass(scope.row.executeStatus)">
                <span v-if="scope.row.executeStatus === 'running'" class="static-dot running"></span>
                <span class="status-text">{{ getDisplayStatus(scope.row.executeStatus) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        
        <el-table-column label="操作" width="220" align="right">
          <template #default="scope">
            <div class="action-cell">
              <el-button
                v-if="canOpenLog(scope.row)"
                size="small"
                type="primary"
                text
                @click="openLogDialog(scope.row)"
                class="table-action-link"
              >
                日志
              </el-button>

              <el-button
                v-if="canShowAgentEvents(scope.row)"
                size="small"
                type="primary"
                text
                @click="openAgentEventDialog(scope.row)"
                class="table-action-link"
              >
                节点
              </el-button>

              <el-button
                v-if="scope.row.executeStatus === 'running'"
                size="small"
                type="danger"
                plain
                @click="stopTask(scope.$index, scope.row)"
                class="action-btn"
              >
                中断
              </el-button>

              <el-button
                v-if="scope.row.executeStatus === 'to be scheduled' || scope.row.executeStatus === 'dispatching'"
                size="small"
                type="warning"
                plain
                @click="cancelTask(scope.$index, scope.row)"
                class="action-btn"
              >
                取消
              </el-button>

              <el-button
                v-if="scope.row.executeStatus !== 'to be scheduled'"
                size="small"
                type="primary"
                plain
                @click="handleRepeat(scope.$index, scope.row)"
                class="action-btn"
              >
                重试
              </el-button>

              <el-button
                v-if="scope?.row?.annex"
                size="small"
                type="primary"
                text
                @click="openAnnexDiaLog(scope.row)"
                class="table-action-link"
              >
                附件
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 对话框部分保持不变 -->
    <el-dialog
      v-model="executeLogDialogVisable"
      :title="logDialogTitle"
      class="log-dialog"
      width="80%"
      :fullscreen="false"
      :close-on-click-modal="false"
      :close-on-press-escape="true"
      @closed="handleLogDialogClosed"
    >
      <div class="log-dialog-content">
        <div class="log-dialog-header">
          <div class="log-info">
            <el-tag size="small" :type="getLogConnectionTagType(logConnectionState)">
              {{ getLogConnectionText(logConnectionState) }}
            </el-tag>
            <span class="log-meta" :title="currentExecuteTask.taskName">{{ currentExecuteTask.taskName || '-' }}</span>
            <span class="log-meta mono">executeId: {{ currentExecuteTask.id || '-' }}</span>
            <span class="log-meta mono">{{ currentExecuteTask.ipAddress || '-' }}</span>
            <span class="log-count">共 {{ logLineCount }} 行</span>
            <span class="update-status" v-if="pendingDialogLogs.length > 0">
              待更新 {{ pendingDialogLogs.length }} 行
            </span>
            <span class="log-error" v-if="logErrorMessage">{{ logErrorMessage }}</span>
            <span v-if="currentExecuteTask.executeStatus === 'running'" class="running-status">
              <span class="static-dot running"></span>
              正在运行中...
            </span>
          </div>
          <div class="log-controls">
            <el-switch
              v-model="logDialogAutoScroll"
              active-text="自动滚动"
              inactive-text="手动"
              size="small"
            />
            <el-button size="small" @click="scrollLogDialogToBottom(true)" type="info" plain>
              <i class="el-icon-arrow-down"></i>
              到底部
            </el-button>
            <el-button size="small" @click="clearLogDialog" type="info" plain>
              <i class="el-icon-delete"></i>
              清空日志
            </el-button>
            <el-button size="small" @click="copyLogDialogContent" type="info" plain>
              <i class="el-icon-document-copy"></i>
              复制日志
            </el-button>
          </div>
        </div>
        <div class="log-dialog-body" ref="dialogLogBodyRef" @scroll="handleDialogLogScroll">
          <div v-if="logVisableDialogContent" class="dialog-log-lines">
            <div
              v-for="(line, index) in logVisableDialogContent.split('\n')"
              :key="`${index}-${line}`"
              class="dialog-log-line"
            >
              <span class="dialog-log-no">{{ index + 1 }}</span>
              <span class="dialog-log-text">{{ line }}</span>
            </div>
          </div>
          <div v-else class="log-empty">
            {{ logConnectionState === 'loading' ? '正在加载日志...' : '暂无日志' }}
          </div>
        </div>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="closeLogDialog">
            关闭
          </el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="agentEventDialogVisible"
      :title="agentEventDialogTitle"
      width="860px"
      class="agent-event-dialog"
      :close-on-click-modal="false"
    >
      <div class="agent-event-panel">
        <div class="agent-event-toolbar">
          <div class="agent-event-context">
            <span>machine: {{ currentAgentEventContext.machineTag || '-' }}</span>
            <span>task: {{ currentAgentEventContext.taskId || '-' }}</span>
            <span>execute: {{ currentAgentEventContext.executeId || '-' }}</span>
          </div>
          <el-button size="small" :loading="agentEventsLoading" @click="refreshAgentEvents">
            刷新
          </el-button>
        </div>
        <div class="agent-event-list">
          <div v-for="item in agentEvents" :key="item.eventId" class="agent-event-row">
            <div class="agent-event-head">
              <span class="agent-event-time">{{ formatAgentEventTime(item.time) }}</span>
              <el-tag size="small" :type="agentEventLevelType(item.level)">
                {{ item.eventType }}
              </el-tag>
              <span class="agent-event-machine">{{ item.machineTag || '-' }}</span>
            </div>
            <div class="agent-event-main">
              <span v-if="item.taskId">task {{ item.taskId }}</span>
              <span v-if="item.executeId">execute {{ item.executeId }}</span>
              <span>{{ item.message || item.detail || '-' }}</span>
            </div>
            <pre v-if="item.command" class="agent-event-command">{{ item.command }}</pre>
            <div v-if="item.errorMessage" class="agent-event-error">{{ item.errorMessage }}</div>
          </div>
          <el-empty v-if="!agentEventsLoading && agentEvents.length === 0" description="暂无 Agent 关键节点" />
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="confirmBatchRepeatDialogVisable"
      title="确认重新执行"
      width="600px"
      class="confirm-dialog"
    >
      <div class="confirm-content">
        <i class="el-icon-warning-outline"></i>
        <p>确定要重新执行以下用例吗？</p>
        <div class="selected-cases">
          <div v-for="item in selectTask" :key="item.id" class="case-item">
            <div class="case-header">
              <span class="case-name">{{ item.name }}</span>
              <span class="case-status" :class="getStatusClass(item.executeStatus)">
                {{ getDisplayStatus(item.executeStatus) }}
              </span>
            </div>
            <div class="case-desc">{{ item.desc }}</div>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="confirmBatchRepeatDialogVisable = false">取消</el-button>
          <el-button type="primary" @click="doBatchTask">确定</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="previewVisible"
      title="附件预览"
      class="preview-dialog"
      width="80%"
    >
      <div class="preview-content">
        <pre>{{ previewText }}</pre>
      </div>
    </el-dialog>

    <el-dialog
      v-model="annexDialogVisable"
      title="附件列表"
      class="annex-dialog"
      width="60%"
    >
      <div class="annex-list">
        <div v-for="(item, index) in JSON.parse(currentTaskDetail?.annex || '[]')" :key="`annex-${index}-${item.name || item.path}`" class="annex-item">
          <div class="annex-info">
            <i class="el-icon-document"></i>
            <a :href="item.url" target="_blank" class="annex-link">{{ item.fileName }}</a>
            <span class="annex-size">({{ formatFileSize(item.fileSize) }})</span>
          </div>
          <div class="annex-path">路径: {{ item.filePath }}</div>
          <el-button size="small" type="primary" @click="getAnnexDetail(item.url)">
            <i class="el-icon-view"></i>
            预览
          </el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="repeatDialogVisable"
      title="重复执行任务"
      class="repeat-dialog"
      width="800px"
    >
      <el-form :model="form" label-width="100px" class="repeat-form">
        <el-form-item label="用例编号">
          <el-input v-model="form.name" disabled />
        </el-form-item>
        <el-form-item label="执行指令">
          <el-input v-model="form.command" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>

      <div class="machine-selection">
        <h4>选择执行机器</h4>
        <el-table
          ref="singleTableRef"
          :data="machineTableList"
          highlight-current-row
          max-height="300px"
          @current-change="handleCurrentChange"
          class="machine-table"
        >
          <el-table-column label="状态" width="120">
            <template #default="{row}">
              <div class="machine-status">
                <span class="status-dot" :class="row.executeStatus === 'idle' ? 'idle' : 'busy'"></span>
                {{ row.executeStatus }}
              </div>
            </template>
          </el-table-column>

          <el-table-column label="资源类型" width="120">
            <template #default="{row}">
              <el-tag :type="row.isDocker === 1 ? 'info' : 'success'" size="small">
                {{ row.isDocker === 1 ? 'Docker' : '物理机' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="机器地址">
            <template #default="{row}">
              <div class="machine-address">
                <i class="el-icon-monitor"></i>
                <span v-if="row.isDocker === 1">
                  {{ row.linkIp }}/{{ row.containerName || row.containerId }}
                </span>
                <span v-else>
                  {{ row.ipAddress }}
                </span>
              </div>
            </template>
          </el-table-column>
          
          <el-table-column label="机器状态" width="120">
            <template #default="{row}">
              <div class="machine-health">
                <span class="status-dot" :class="row.status === 'ONLINE' ? 'online' : 'offline'"></span>
                {{ row.status }}
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="repeatDialogVisable = false">取消</el-button>
          <el-button type="primary" @click="executeTask">执行</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import {useHttp} from "@/http";
import {onMounted, onUnmounted, reactive, ref, computed, nextTick, watch} from "vue";
import {useRoute} from "vue-router";
import moment from "moment";
import ElMessage from "element-plus/es/components/message/index";
import axios from "axios";
import useClipboard from 'vue-clipboard3';

const {listenSSE, get, post} = useHttp();

const taskData = ref<any[]>([]);

const taskDataCopy = ref<any[]>([]);

const logVisableDialogContent = ref("");

const executeTaskingLog = ref("");

const executeLogDialogVisable = ref(false);

const annexDialogVisable = ref(false);

const repeatDialogVisable = ref(false);

const previewVisible = ref(false);

const confirmBatchRepeatDialogVisable = ref(false);

const currentExecuteTask = ref<any>({});

const currentTaskDetail = ref<any>({});

const agentEventDialogVisible = ref(false);

const currentAgentEventContext = ref<any>({});

const agentEvents = ref<any[]>([]);

const agentEventsLoading = ref(false);

const batchCancelLoading = ref(false);

const taskDetail = ref<any>({});

const selectTask = ref<any[]>([]);

const caseSearch = ref('');

const statusSearch = ref('');

const machineTableList = ref<any[]>([]);

const allTag = ref<string[]>([]);

const isUserSelect = false;

const singleTableRef = ref();

const previewText = ref("");

const result = ref({
  operators: [] as string[],
  executeStartTime: null as string | null,
  executeEndTime: null as string | null,
  overallStatus: null as string | null,
  runningTasks: [] as any[],
  statusCounts: {
    success: 0,
    fail: 0,
    pending: 0,
    exception: 0,
    cancel: 0,
  },
  executePercentage: 0,
  executionTime: 0,
});

const form = reactive({
  name: "",
  region: "",
  date1: "",
  date2: "",
  delivery: false,
  type: [],
  resource: "",
  desc: "",
  command: "",
});

const taskStatusOptions = [
  { label: '待执行', value: 'to be scheduled' },
  { label: '执行中', value: 'running' },
  { label: '通过', value: 'pass' },
  { label: '失败', value: 'fail' },
  { label: '异常', value: 'exception' },
  { label: '阻塞', value: 'block' },
  { label: '取消', value: 'cancel' },
  { label: '下发中', value: 'dispatching' },
];

let arrLog: string[] = [];
let arrLogDialog = new Set<string>();
let activeLogStreamSeq = 0;

// 对话框日志批量更新相关变量
const pendingDialogLogs = ref<string[]>([]);
const dialogUpdateTimer = ref<any>(null);
const dialogLastUpdateTime = ref(0);
const DIALOG_UPDATE_INTERVAL = 200;
const dialogLogBodyRef = ref<HTMLElement | null>(null);
const logDialogAutoScroll = ref(true);
const logConnectionState = ref<'idle' | 'loading' | 'connected' | 'error' | 'closed'>('idle');
const logErrorMessage = ref('');
const logLineCount = computed(() => logVisableDialogContent.value
    ? logVisableDialogContent.value.split('\n').filter(Boolean).length
    : 0);
const logDialogTitle = computed(() => {
  const taskName = currentExecuteTask.value?.taskName || '执行日志';
  const machine = currentExecuteTask.value?.ipAddress || currentExecuteTask.value?.machineTag || '-';
  return `${taskName} - ${machine}`;
});

const agentEventDialogTitle = computed(() => {
  const name = currentAgentEventContext.value?.taskName || 'Agent 关键节点';
  const machine = currentAgentEventContext.value?.machineTag || '-';
  return `${name} - ${machine}`;
});

const selectedCancelableTasks = computed(() => {
  return selectTask.value.filter((row) => canCancelTask(row));
});

const selectMachine = ref();

const editableTabsValue = ref();

const planId = ref('');

const editableTabs = ref<Array<{name: string, title: string, value: any}>>([]);

let closePreviousSSE: (() => void) | null = null;

let runingTaskingLogSSE: (() => void) | null = null;

const route = useRoute();
let requestId = route.query.requestId || route.query.reuqestId;

//创建一个用来去重的
const alreadyRunningTask: Record<string, any> = {};

const handleCurrentChange = (val) => {
  selectMachine.value = val;
};

function timeFormatter(row, column, cellValue) {
  return moment(cellValue).format("YYYY-MM-DD HH:mm:ss");
}

function fullTimeFormatter(value) {
  if (!value) {
    return '-';
  }
  return moment(value).format("YYYY-MM-DD HH:mm:ss");
}

function compactTimeFormatter(value) {
  if (!value) {
    return '-';
  }
  const time = moment(value);
  if (time.isSame(moment(), 'day')) {
    return time.format("HH:mm:ss");
  }
  if (time.isSame(moment(), 'year')) {
    return time.format("MM-DD HH:mm");
  }
  return time.format("YYYY-MM-DD");
}

function formatExpectTime(value) {
  if (!value) {
    return '预计 -';
  }
  const text = String(value).trim();
  if (text.endsWith('s') || text.includes('±')) {
    return `预计 ${text}`;
  }
  return `预计 ${text}s`;
}

function machineDisplay(row) {
  const machine = row?.machineInfoEntity || {};
  if (machine.isDocker === 1) {
    return `${machine.linkIp || row.machineIps || '-'} / ${machine.containerName || machine.containerId || 'docker'}`;
  }
  return machine.ipAddress || row.machineIps || row.machineTag || '-';
}

function agentMachineTag(row) {
  const execution = row?.machineExecuteStatus?.[0] || {};
  return execution.machineTag
      || row?.machineTag
      || row?.machineInfoEntity?.machineTag
      || row?.machineInfoEntity?.ipAddress
      || row?.machineIps
      || execution.ipAddress
      || '';
}

function rowExecuteId(row) {
  return row?.machineExecuteStatus?.[0]?.id || null;
}

function buildAgentEventContext(row) {
  return {
    taskId: row?.id,
    executeId: rowExecuteId(row),
    requestId: row?.requestId || requestId,
    machineTag: agentMachineTag(row),
    taskName: row?.name,
  };
}

function clampWidth(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function textWidth(text, charWidth = 8) {
  return String(text || '').split('').reduce((total, char) => {
    return total + (/[\u4e00-\u9fa5]/.test(char) ? charWidth * 1.7 : charWidth);
  }, 0);
}

const tableColumnWidths = computed(() => {
  const rows = taskDataCopy.value || [];
  const maxDesc = Math.max(
      textWidth('名称', 13),
      ...rows.map((row) => textWidth(row?.desc, 8))
  );
  const maxName = Math.max(
      textWidth('编号', 13),
      ...rows.map((row) => textWidth(row?.name, 8))
  );
  const maxMachine = Math.max(
      textWidth('机器', 13),
      ...rows.map((row) => textWidth(machineDisplay(row), 8))
  );
  const hasAnnex = rows.some((row) => row?.annex);
  const hasRunning = rows.some((row) => row?.executeStatus === 'running');
  const hasCancelable = rows.some((row) => row?.executeStatus === 'to be scheduled');

  return {
    desc: clampWidth(Math.ceil(maxDesc) + 48, 220, 460),
    name: clampWidth(Math.ceil(maxName) + 42, 150, 260),
    machine: clampWidth(Math.ceil(maxMachine) + 38, 110, 220),
    action: hasRunning || hasCancelable || hasAnnex ? 176 : 132,
  };
});

function handleSelectionChange(val) {
  selectTask.value = val;
}

function formattedExecutionTime() {
  const timeInSeconds = result.value.executionTime / 1000;
  if (timeInSeconds < 60) {
    return `${timeInSeconds.toFixed(1)} 秒`;
  } else if (timeInSeconds < 3600) {
    return `${(timeInSeconds / 60).toFixed(1)} 分钟`;
  } else {
    return `${(timeInSeconds / 3600).toFixed(1)} 小时`;
  }
}

function formatTime(time) {
  if (!time) return '--';
  return new Date(time).toLocaleString([], {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function getStatusClass(status) {
  switch (status) {
    case 'success':
    case 'pass':
      return 'pass';
    case 'fail':
    case 'block':
      return 'fail';
    case 'running':
      return 'running';
    case 'to be scheduled':
    case 'create':
      return 'pending';
    case 'exception':
      return 'exception';
    case 'cancel':
      return 'cancel';
    default:
      return 'pending';
  }
}

function getStatusText(status) {
  switch (status) {
    case 'success':
      return '完成';
    case 'fail':
      return '失败';
    case 'running':
      return '运行中';
    case 'partial_failure':
      return '部分失败';
    case 'to be scheduled':
      return '待执行';
    default:
      return status;
  }
}

function processDataToDetail(data) {
  let operators = [];
  let executeStartTime = null;
  let executeEndTime = null;
  let overallStatus = "success";
  let runningTasks = [];
  let successCount = 0;
  let exceptionCount = 0;
  let failCount = 0;
  let pendingCount = 0;
  let cancelCount = 0;
  let totalTasks = data.length;

  data.forEach((item) => {
    if (item.operator) {
      operators.push(item.operator);
    }
    if (executeStartTime === null || (item.startTime && item.startTime < executeStartTime)) {
      executeStartTime = item.startTime;
    }
    if (executeEndTime === null || (item.endTime && item.endTime > executeEndTime)) {
      executeEndTime = item.endTime;
    }
    if (
        item.executeStatus === "fail" ||
        item.executeStatus === "block"
    ) {
      failCount += 1;
    } else if (item.executeStatus === "running") {
      runningTasks.push(item);
    } else if (item.executeStatus === "pass") {
      successCount += 1;
    } else if (item.executeStatus === "to be scheduled") {
      pendingCount += 1;
    } else if (
        item.executeStatus === "exception") {
      exceptionCount += 1;
    } else if (item.executeStatus === "cancel") {
      cancelCount += 1;
    }
  });
  // Update overall status
  if (failCount === totalTasks) {
    overallStatus = "fail";
  } else if (pendingCount > 0) {
    overallStatus = "running";
  } else if (successCount > 0 && failCount > 0) {
    overallStatus = "partial_failure";
  }
  // Calculate percentage
  let executePercentage = ((successCount + failCount) / totalTasks) * 100;
  // Calculate execution time
  let executionTime = 0;
  if (executeStartTime && executeEndTime) {
    executionTime = new Date(executeEndTime).getTime() - new Date(executeStartTime).getTime();
  }
  
  result.value = {
    operators: [...new Set(operators)],
    executeStartTime,
    executeEndTime,
    overallStatus,
    runningTasks,
    statusCounts: {
      success: successCount,
      fail: failCount,
      pending: pendingCount,
      exception: exceptionCount,
      cancel: cancelCount,
    },
    executePercentage,
    executionTime,
  };
}

function sortData(data) {
  // prettier-ignore
  const priority = {
    "running": 1,
    "to be scheduled": 2,
    "fail": 3,
    "pass": 4
  };

  return data.sort((a, b) => {
    return priority[a["executeStatus"]] - priority[b["executeStatus"]];
  });
}

// 防抖函数
const debounce = (func: Function, wait: number) => {
  let timeout: any;
  return function executedFunction(...args: any[]) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
};

// 日志相关
const logLines = ref<Array<{id: string, content: string, timestamp: string}>>([]);
const logViewport = ref<HTMLElement | null>(null);
const autoScroll = ref(true);
const showTimestamp = ref(true);
const showLineNumber = ref(true);
const isAtBottom = ref(true); // 新增：跟踪是否在底部

// 批量更新相关变量
const pendingLogLines = ref<Array<{id: string, content: string, timestamp: string}>>([]);
const updateTimer = ref<any>(null);
const lastUpdateTime = ref(0);
const UPDATE_INTERVAL = 1000; // 1秒更新一次

// 性能监控
const updateCount = ref(0);
const lastPerformanceTime = ref(0);

// 监听autoScroll变化
watch(autoScroll, (newVal) => {
  if (newVal) {
    // 如果重新启用自动滚动，立即滚动到底部
    nextTick(() => {
      scrollToBottom();
    });
  }
});

// 简化的滚动方法
const handleScroll = debounce(() => {
  const viewport = getCurrentLogViewport();
  if (!viewport) return;
  
  const { scrollTop, scrollHeight, clientHeight } = viewport;
  const atBottom = scrollTop + clientHeight >= scrollHeight - 10;
  
  // 更新是否在底部的状态
  isAtBottom.value = atBottom;
  
  // 只有在用户主动滚动时才改变自动滚动状态
  if (atBottom) {
    // 如果用户滚动到底部，自动重新启用自动滚动
    autoScroll.value = true;
  } else {
    // 如果用户向上滚动，禁用自动滚动
    autoScroll.value = false;
  }
}, 100);

// 获取当前激活的日志容器
const getCurrentLogViewport = () => {
  return document.querySelector('.log-viewport') as HTMLElement;
};

// 简化的滚动到底部方法
const scrollToBottom = () => {
  if (!autoScroll.value) return;
  nextTick(() => {
    const viewport = getCurrentLogViewport();
    if (viewport) {
      viewport.scrollTop = viewport.scrollHeight;
    }
  });
};

const scrollToBottomManually = () => {
  const viewport = getCurrentLogViewport();
  if (viewport) {
    viewport.scrollTop = viewport.scrollHeight;
    // 手动回到底部时，自动启用自动滚动
    autoScroll.value = true;
  }
};

const clearLog = () => {
  logLines.value = [];
  pendingLogLines.value = [];
  arrLog = [];
  executeTaskingLog.value = "";
  
  // 清除定时器
  if (updateTimer.value) {
    clearTimeout(updateTimer.value);
    updateTimer.value = null;
  }
};

const copyLog = () => {
  const { toClipboard } = useClipboard();
  const logText = logLines.value.map((line, index) => {
    let result = '';
    if (showLineNumber.value) {
      result += `${(index + 1).toString().padStart(4, ' ')} `;
    }
    if (showTimestamp.value) {
      result += `[${line.timestamp}] `;
    }
    result += line.content;
    return result;
  }).join('\n');
  try {
    toClipboard(logText);
    ElMessage.success('复制成功!');
  } catch (error) {
    ElMessage.error('复制失败: ' + error);
  }
};

// 批量更新日志的方法
const batchUpdateLogs = () => {
  if (pendingLogLines.value.length > 0) {
    // 将待更新的日志行添加到主日志数组中
    logLines.value.push(...pendingLogLines.value);
    
    // 限制日志行数，避免内存溢出
    if (logLines.value.length > 1000) {
      logLines.value = logLines.value.slice(-1000);
    }
    
    // 清空待更新数组
    pendingLogLines.value = [];
    
    // 使用简化的自动滚动
    if (autoScroll.value) {
      nextTick(() => {
        const viewport = getCurrentLogViewport();
        if (viewport) {
          viewport.scrollTop = viewport.scrollHeight;
        }
      });
    }
    
    updateCount.value++;
  }
  
  // 重置定时器
  updateTimer.value = null;
};

// 优化的日志添加方法 - 使用批量更新
const addLogLine = (content: string) => {
  const timestamp = new Date().toLocaleTimeString();
  const id = `log-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  
  // 将新的日志行添加到待更新数组
  pendingLogLines.value.push({
    id,
    content: content.trimStart(),
    timestamp
  });
  
  const now = Date.now();
  
  // 如果距离上次更新超过1秒，或者还没有定时器，则设置定时器
  if (now - lastUpdateTime.value >= UPDATE_INTERVAL || !updateTimer.value) {
    // 清除现有定时器
    if (updateTimer.value) {
      clearTimeout(updateTimer.value);
    }
    
    // 设置新的定时器
    updateTimer.value = setTimeout(() => {
      batchUpdateLogs();
      lastUpdateTime.value = Date.now();
    }, Math.max(0, UPDATE_INTERVAL - (now - lastUpdateTime.value)));
  }
};

// 对话框日志批量更新方法
const batchUpdateDialogLogs = () => {
  if (pendingDialogLogs.value.length > 0) {
    const nextContent = pendingDialogLogs.value.join('\n');
    logVisableDialogContent.value = logVisableDialogContent.value
        ? `${logVisableDialogContent.value}\n${nextContent}`
        : nextContent;
    
    // 限制日志长度，避免内存问题
    const lines = logVisableDialogContent.value.split('\n');
    if (lines.length > 10000) {
      logVisableDialogContent.value = lines.slice(-5000).join('\n');
    }
    
    // 清空待更新数组
    pendingDialogLogs.value = [];
    
    // 自动滚动到底部
    scrollLogDialogToBottom(false);
  }
  
  // 重置定时器
  dialogUpdateTimer.value = null;
};

// 优化的对话框日志添加方法
const addDialogLogLine = (content: string) => {
  // 将新的日志内容添加到待更新数组
  pendingDialogLogs.value.push(content.trimStart());
  
  const now = Date.now();
  
  // 如果距离上次更新超过1秒，或者还没有定时器，则设置定时器
  if (now - dialogLastUpdateTime.value >= DIALOG_UPDATE_INTERVAL || !dialogUpdateTimer.value) {
    // 清除现有定时器
    if (dialogUpdateTimer.value) {
      clearTimeout(dialogUpdateTimer.value);
    }
    
    // 设置新的定时器
    dialogUpdateTimer.value = setTimeout(() => {
      batchUpdateDialogLogs();
      dialogLastUpdateTime.value = Date.now();
    }, Math.max(0, DIALOG_UPDATE_INTERVAL - (now - dialogLastUpdateTime.value)));
  }
};

onMounted(() => {
  const result = listenSSE(
      "/sseEmitter/task/list?" + (requestId ? "requestId=" + requestId : ""),
      (data) => {
        if (data?.error) {
          ElMessage.warning(data.error);
          return;
        }
        if (typeof data === "string") {
          try {
            data = JSON.parse(data);
          } catch (error) {
            return;
          }
        }
        if (!Array.isArray(data?.data)) {
          return;
        }
        
        // 确保每个任务都有唯一的id
        const processedData = data.data.map((item, index) => {
          if (!item.id) {
            item.id = `task-${Date.now()}-${index}`;
          }
          return item;
        });
        
        taskData.value = processedData;
        applyTaskFilters();
        data?.data?.forEach((item) => {
          if (!item.machineExecuteStatus) {
            return;
          }
          item.machineExecuteStatus.forEach((machine) => {
            if (machine.id === currentExecuteTask.value.id) {
              currentExecuteTask.value = machine;
            }
          });
        });
        processDataToDetail(processedData);
      }
  );
  get("/sseEmitter/getTaskDetail?requestId=" + requestId).then((res) => {
    taskDetail.value = res.data;
  });


  window.addEventListener('message', function (event) {

    if (event.origin !== getChaosUrl()) { // 'http://parent.com' 是父页面的源
      return;
    }
    planId.value = event.data;
  });

});

// 组件卸载时清理定时器
onUnmounted(() => {
  // 清理主日志定时器
  if (updateTimer.value) {
    clearTimeout(updateTimer.value);
    updateTimer.value = null;
  }
  
  // 清理对话框日志定时器
  if (dialogUpdateTimer.value) {
    clearTimeout(dialogUpdateTimer.value);
    dialogUpdateTimer.value = null;
  }
  
  // 清理SSE连接
  if (runingTaskingLogSSE) {
    runingTaskingLogSSE();
    runingTaskingLogSSE = null;
  }
  
  if (closePreviousSSE) {
    closePreviousSSE();
    closePreviousSSE = null;
  }
});

const filterTag = (value, row) => {
  return row.tag === value;
};

function handleRepeat(index, row) {
  Object.assign(form, row);
  //查询机器状态
  post("/machine/getMachineList", {
    machineInfoEntity: {},
    request: {pageSize: 999},
  }).then((res) => {
    machineTableList.value = res.data;
    //拿出所有图tag
    res.data.forEach((item) => {
      if (item.tag) {
        allTag.value.push(item.tag);
      }
    });
  });
  repeatDialogVisable.value = true;
}

function canCancelTask(row) {
  return ['to be scheduled', 'dispatching', 'running', 'create'].includes(row?.executeStatus);
}

function cancelTask(index, row) {
  //查询机器状态
  get("/sseEmitter/cancelTask?id=" + row.id).then((res) => {
    if (res?.success === false) {
      ElMessage.error(res.errorMessage || "取消失败!");
      return;
    }
    ElMessage.success("取消成功!");
  });
}

async function batchCancelTasks() {
  const tasks = selectedCancelableTasks.value;
  if (tasks.length === 0) {
    ElMessage.warning("选中的任务没有可取消项");
    return;
  }
  batchCancelLoading.value = true;
  try {
    const results = await Promise.allSettled(
        tasks.map((row) => get("/sseEmitter/cancelTask?id=" + row.id))
    );
    const successCount = results.filter((item) => {
      return item.status === 'fulfilled' && item.value?.success !== false;
    }).length;
    const failCount = results.length - successCount;
    if (successCount > 0 && failCount === 0) {
      ElMessage.success(`已取消 ${successCount} 个任务`);
    } else if (successCount > 0) {
      ElMessage.warning(`已取消 ${successCount} 个任务，${failCount} 个失败`);
    } else {
      ElMessage.error("批量取消失败");
    }
  } finally {
    batchCancelLoading.value = false;
  }
}

function stopTask(index, row) {
  //查询机器状态
  get("/sseEmitter/stopRunningTask?id=" + row.id).then((res) => {
    if (res?.success === false || res?.data === false) {
      ElMessage.error(res?.errorMessage || "中断失败!");
      return;
    }
    ElMessage.success("中断成功!");
  });
}

function canShowAgentEvents(row) {
  return Boolean(row?.id || rowExecuteId(row) || agentMachineTag(row));
}

function  copyLogDialogContent(){
  //复制logVisableDialogContent 到剪切板
  const { toClipboard } = useClipboard();
    try {
      toClipboard(logVisableDialogContent.value);
      ElMessage.success('复制成功!');
    } catch (error) {
      ElMessage.error('复制失败: ' + error);
    }
}

function clearLogDialog() {
  logVisableDialogContent.value = "";
  pendingDialogLogs.value = [];
  arrLogDialog = new Set<string>();
  
  // 清除对话框日志定时器
  if (dialogUpdateTimer.value) {
    clearTimeout(dialogUpdateTimer.value);
    dialogUpdateTimer.value = null;
  }
  
  ElMessage.success('日志已清空!');
}

function executeTask() {
  //判断是否选中，如果没有选中，提示
  if (!selectMachine.value) {
    ElMessage.error("请选择要执行的机器!");
    return;
  }
  post("/sseEmitter/repeat", {
    machineInfoEntity: selectMachine.value,
    task: form,
  });
  repeatDialogVisable.value = false;
}

function openAnnexDiaLog(row) {
  annexDialogVisable.value = true;
  currentTaskDetail.value = row;
}

function handleTabClick(pane) {
  // 重置logViewport引用，因为切换标签页时DOM会重新渲染
  logViewport.value = null;
  
  // 切换标签页时保持用户的自动滚动设置，不强制重置
  // autoScroll.value 保持用户之前的设置
  
  LinkRunningLog(pane.paneName);
}

function getTagType(status) {
  switch (status) {
    case "success":
      return "success";
    case "fail":
      return "danger";
    case "running":
      return "warning";
    case "partial_failure":
      return "";
    default:
      return "";
  }
}

function LinkRunningLog(id) {
  arrLog = []
  logLines.value = []; // 清空日志行
  logViewport.value = null; // 重置logViewport引用
  
  // 保持用户的自动滚动设置，不强制重置
  // autoScroll.value 保持用户之前的设置
  
  executeTaskingLog.value = "\r\n"; // 关闭之前的 SSE 连接
  if (runingTaskingLogSSE) {
    runingTaskingLogSSE();
    runingTaskingLogSSE = null;
  }
  
  runingTaskingLogSSE = listenSSE("/sseEmitter/connect/" + id, (data) => {
    const dataArr = data.data.split("\r\n");

    dataArr.forEach(e => {
      const dataarr = e.split("-idNo: ");
      //先判断是否在 arrLog周年
      if (arrLog.indexOf(dataarr[1]) == -1) {
        addLogLine(dataarr[0]); // 使用新的日志处理方式
        arrLog.push(dataarr[1])
      }
    })
  });
}

function getAnnexDetail(url) {
  axios.get(url, {
    responseType: 'text'
  }).then(res => {
    previewText.value = res.data
    previewVisible.value = true

  })
}

async function openAgentEventDialog(row) {
  currentAgentEventContext.value = buildAgentEventContext(row);
  agentEventDialogVisible.value = true;
  await refreshAgentEvents();
}

async function refreshAgentEvents() {
  const context = currentAgentEventContext.value || {};
  agentEventsLoading.value = true;
  try {
    const params = new URLSearchParams();
    if (context.machineTag) {
      params.set('machineTag', context.machineTag);
    }
    if (context.requestId) {
      params.set('requestId', String(context.requestId));
    }
    if (context.taskId) {
      params.set('taskId', String(context.taskId));
    }
    if (context.executeId) {
      params.set('executeId', String(context.executeId));
    }
    params.set('limit', '300');
    const res = await get(`/debug/agent/events?${params.toString()}`);
    if (res?.success) {
      agentEvents.value = res.data || [];
      return;
    }
    ElMessage.error(res?.errorMessage || '获取 Agent 关键节点失败');
  } catch (error: any) {
    ElMessage.error(error?.message || '获取 Agent 关键节点失败');
  } finally {
    agentEventsLoading.value = false;
  }
}

function agentEventLevelType(level) {
  if (level === 'ERROR') {
    return 'danger';
  }
  if (level === 'WARN') {
    return 'warning';
  }
  return 'info';
}

function formatAgentEventTime(time) {
  if (!time) {
    return '-';
  }
  return moment(time).format('HH:mm:ss');
}

function canOpenLog(row) {
  const machine = row?.machineExecuteStatus?.[0];
  return Boolean(machine?.id || (row?.id && row?.executeStatus !== 'to be scheduled'));
}

function buildLogContext(row) {
  const machine = row?.machineExecuteStatus?.[0] || {};
  return {
    ...machine,
    id: machine.id || row.id,
    taskId: row.id,
    taskName: row.name,
    taskDesc: row.desc,
    ipAddress: machine.ipAddress || machine.machineTag || machineDisplay(row),
    machineTag: machine.machineTag || row.machineTag,
    normalEnd: machine.id ? machine.normalEnd : 'false',
    executeStatus: row.executeStatus,
  };
}

function resetLogDialogState() {
  activeLogStreamSeq++;
  arrLogDialog = new Set<string>();
  logVisableDialogContent.value = "";
  pendingDialogLogs.value = [];
  logErrorMessage.value = "";
  logConnectionState.value = 'loading';
  logDialogAutoScroll.value = true;

  if (dialogUpdateTimer.value) {
    clearTimeout(dialogUpdateTimer.value);
    dialogUpdateTimer.value = null;
  }
  if (closePreviousSSE) {
    closePreviousSSE();
    closePreviousSSE = null;
  }
}

function appendDialogLogPayload(payload, source = 'live') {
  const rawLines = Array.isArray(payload)
      ? payload
      : String(payload ?? '').split(/\r?\n/);

  rawLines.forEach((rawLine, index) => {
    const line = String(rawLine ?? '');
    if (!line.trim()) {
      return;
    }
    const separator = '-idNo: ';
    const separatorIndex = line.lastIndexOf(separator);
    const content = separatorIndex >= 0 ? line.slice(0, separatorIndex) : line;
    const key = separatorIndex >= 0
        ? line.slice(separatorIndex + separator.length)
        : `${source}:${content}:${index}`;

    if (arrLogDialog.has(key)) {
      return;
    }
    arrLogDialog.add(key);
    addDialogLogLine(content);
  });
}

function scrollLogDialogToBottom(force = false) {
  if (!force && !logDialogAutoScroll.value) {
    return;
  }
  nextTick(() => {
    const viewport = dialogLogBodyRef.value;
    if (viewport) {
      viewport.scrollTop = viewport.scrollHeight;
    }
  });
}

function handleDialogLogScroll() {
  const viewport = dialogLogBodyRef.value;
  if (!viewport) {
    return;
  }
  const atBottom = viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 12;
  logDialogAutoScroll.value = atBottom;
}

function closeLogDialog() {
  executeLogDialogVisable.value = false;
}

function handleLogDialogClosed() {
  activeLogStreamSeq++;
  if (closePreviousSSE) {
    closePreviousSSE();
    closePreviousSSE = null;
  }
  if (dialogUpdateTimer.value) {
    batchUpdateDialogLogs();
  }
  logConnectionState.value = 'closed';
}

async function loadInitialLogLines(context, streamSeq) {
  try {
    const normalEnd = context.normalEnd == null ? '' : String(context.normalEnd);
    const logs = await get(`/sseEmitter/task/${context.id}/logs?normalEnd=${encodeURIComponent(normalEnd)}`);
    if (streamSeq !== activeLogStreamSeq) {
      return;
    }
    appendDialogLogPayload(Array.isArray(logs) ? logs : [], 'history');
    if (dialogUpdateTimer.value) {
      batchUpdateDialogLogs();
    }
  } catch (error) {
    if (streamSeq === activeLogStreamSeq) {
      logErrorMessage.value = '历史日志加载失败';
    }
  }
}

async function openLogDialog(row) {
  const context = buildLogContext(row);
  currentExecuteTask.value = context;
  resetLogDialogState();
  executeLogDialogVisable.value = true;
  const streamSeq = activeLogStreamSeq;

  await loadInitialLogLines(context, streamSeq);
  if (streamSeq !== activeLogStreamSeq) {
    return;
  }

  const normalEnd = context.normalEnd == null ? '' : String(context.normalEnd);
  closePreviousSSE = listenSSE(
      `/sseEmitter/connect/${context.id}?normalEnd=${encodeURIComponent(normalEnd)}&skipInit=true`,
      (data) => {
        if (streamSeq !== activeLogStreamSeq) {
          return;
        }
        if (data?.error) {
          logConnectionState.value = 'error';
          logErrorMessage.value = data.error;
          return;
        }
        appendDialogLogPayload(data?.data ?? data, 'live');
      },
      {
        onOpen: () => {
          if (streamSeq === activeLogStreamSeq) {
            logConnectionState.value = 'connected';
          }
        },
        onError: () => {
          if (streamSeq === activeLogStreamSeq) {
            logConnectionState.value = 'error';
            logErrorMessage.value = '实时日志连接已断开';
          }
        },
      }
  );
}

function formatFileSize(size) {
  if (size < 1024) return size + ' B';
  size /= 1024;
  if (size < 1024) return size.toFixed(2) + ' KB';
  size /= 1024;
  return size.toFixed(2) + ' MB';
}

function getStatusColor(status) {
  switch (status) {
    case 'create':
    case 'to be scheduled':
      return '#E6A23C'; // 黄色
    case 'running':
      return '#409EFF'; // 蓝色
    case 'exception':
      return '#F56C6C'; // 红色
    case 'block':
      return '#909399'; // 灰色
    case 'pass':
      return '#67C23A'; // 绿色
    default:
      return '#000000'; // 黑色或默认颜色
  }
}

function getDisplayStatus(status) {
  switch (status) {
    case 'create':
    case 'to be scheduled':
      return '待执行';
    case 'running':
      return '执行中';
    case 'pass':
    case 'success':
      return '通过';
    case 'fail':
      return '失败';
    case 'block':
      return '阻塞';
    case 'exception':
      return '异常';
    case 'cancel':
      return '取消';
    case 'part_success':
    case 'partial_failure':
      return '部分通过';
    default:
      return status ? String(status).toUpperCase() : '-';
  }
}

function getLogConnectionText(state) {
  switch (state) {
    case 'loading':
      return '加载中';
    case 'connected':
      return '实时连接';
    case 'error':
      return '连接异常';
    case 'closed':
      return '已关闭';
    default:
      return '未连接';
  }
}

function getLogConnectionTagType(state) {
  switch (state) {
    case 'connected':
      return 'success';
    case 'loading':
      return 'warning';
    case 'error':
      return 'danger';
    default:
      return 'info';
  }
}

function  doBatchTask(){

  const ids = selectTask.value.map(item => item.id);
  post("/sseEmitter/batchRepeat", {
    ids: ids,
  })
  ElMessage.success("重复执行成功!");
  confirmBatchRepeatDialogVisable.value = false;
}
function getChaosUrl() {
  const currentUrl = new URL(window.location.href);
  const hostname = currentUrl.hostname;
  const port = currentUrl.port;

  if (hostname === 'localhost' && port === '5173') {
    return 'http://localhost:8000';
  } else if (hostname === 'chaos.hongjunwei.com' && port === '1992') {
    return 'http://chaos.hongjunwei.com:9001';
  } else {
    return currentUrl.href; // 返回原始 URL，如果不符合上述任何一种情况
  }
}

function applyTaskFilters() {
  const keyword = caseSearch.value.trim().toLowerCase();
  const status = statusSearch.value;
  taskDataCopy.value = taskData.value.filter((data) => {
    const matchKeyword = !keyword
        || data?.name?.toLowerCase().includes(keyword)
        || data?.desc?.toLowerCase().includes(keyword);
    const matchStatus = !status || data?.executeStatus === status;
    return matchKeyword && matchStatus;
  });
}
const removeTab = (targetName) => {
  const tabs = editableTabs.value;
  const targetTab = tabs.find(tab => tab?.name === targetName);
  
  // 检查是否是running状态，如果是则不允许关闭
  if (targetTab && targetTab.value.executeStatus === 'running') {
    ElMessage.warning('运行中的任务不能关闭日志标签页');
    return;
  }
  
  let activeName = editableTabsValue.value;
  if (activeName === targetName) {
    tabs.forEach((tab, index) => {
      if (tab?.name === targetName) {
        const nextTab = tabs[index + 1] || tabs[index - 1];
        if (nextTab) {
          activeName = nextTab.name;
        }
      }
    });
  }

  editableTabsValue.value = activeName;
  editableTabs.value = tabs.filter((tab) => tab?.name !== targetName);
};

// 测试自动滚动功能
const testAutoScroll = () => {
    scrollToBottom();
};

// 切换自动滚动状态
const toggleAutoScroll = () => {
  autoScroll.value = !autoScroll.value;
  
  if (autoScroll.value) {
    // 如果启用自动滚动，立即滚动到底部
    nextTick(() => {
      const viewport = getCurrentLogViewport();
      if (viewport) {
        viewport.scrollTop = viewport.scrollHeight;
      }
    });
  }
};

// 简单的滚动条修复
const fixScrollbarMismatch = () => {
  const viewport = getCurrentLogViewport();
  if (!viewport) return;
  
  // 简单的滚动到底部
  viewport.scrollTop = viewport.scrollHeight;
};
</script>

<style scoped>
.task-dashboard {
  padding: 16px;
  background: #f4f6f8;
  min-height: 100%;
  height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
}

/* 概览部分 */
.overview-section {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.overview-card {
  background: white;
  border-radius: 6px;
  padding: 20px;
  border: 1px solid #dde3ea;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.card-title {
  font-size: 24px;
  font-weight: 700;
  color: #2c3e50;
  margin: 0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-title i {
  color: #409eff;
  font-size: 28px;
}

.status-badge {
  display: flex;
  align-items: center;
}

/* 统计网格 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: #f8f9fa;
  border-radius: 6px;
  border: 1px solid #e9ecef;
}

.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: white;
  flex-shrink: 0;
}

.stat-icon.total { background: #64748b; }
.stat-icon.success { background: #16a34a; }
.stat-icon.fail { background: #dc2626; }
.stat-icon.running { background: #2563eb; }
.stat-icon.pending { background: #d97706; }
.stat-icon.exception { background: #b91c1c; }
.stat-icon.cancel { background: #6b7280; }

.stat-content {
  flex: 1;
}

.stat-number {
  font-size: 24px;
  font-weight: 700;
  color: #2c3e50;
  line-height: 1;
}

.stat-label {
  font-size: 14px;
  color: #6c757d;
  margin-top: 4px;
}

/* 进度条部分 */
.progress-section {
  background: #f8f9fa;
  border-radius: 6px;
  padding: 20px;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.progress-label {
  font-size: 16px;
  font-weight: 600;
  color: #2c3e50;
}

.progress-percentage {
  font-size: 18px;
  font-weight: 700;
  color: #409eff;
}

.custom-progress {
  --el-progress-bar-height: 12px;
  --el-progress-border-radius: 6px;
}

/* 信息卡片 */
.info-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-card {
  background: white;
  border-radius: 6px;
  padding: 16px;
  border: 1px solid #dde3ea;
}

.info-card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  font-size: 18px;
  font-weight: 600;
  color: #2c3e50;
}

.info-card-header i {
  color: #409eff;
  font-size: 20px;
}

.executors-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.executor-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: #f8f9fa;
  border-radius: 6px;
}

.executor-avatar {
  border: 2px solid #409eff;
}

.executor-name {
  font-weight: 500;
  color: #2c3e50;
}

.time-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.time-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #e9ecef;
}

.time-item:last-child {
  border-bottom: none;
}

.time-label {
  font-weight: 500;
  color: #6c757d;
}

.time-value {
  font-weight: 600;
  color: #2c3e50;
}

/* 日志部分 */
.logs-section {
  background: white;
  border-radius: 6px;
  padding: 16px;
  margin-bottom: 20px;
  border: 1px solid #dde3ea;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.section-header h3 {
  font-size: 20px;
  font-weight: 700;
  color: #2c3e50;
  margin: 0;
}

.running-count {
  background: #2563eb;
  color: white;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 600;
}

.custom-tabs {
  --el-tabs-header-height: 45px;
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 12px;
}

.tab-title {
  font-weight: 600;
  color: #2c3e50;
}

.tab-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 16px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid;
}

.tab-status.running {
  background: rgba(64, 158, 255, 0.1);
  color: #409eff;
  border-color: rgba(64, 158, 255, 0.3);
}

.tab-status.pass {
  background: rgba(17, 153, 142, 0.1);
  color: #11998e;
  border-color: rgba(17, 153, 142, 0.3);
}

.tab-status.fail {
  background: rgba(252, 70, 107, 0.1);
  color: #fc466b;
  border-color: rgba(252, 70, 107, 0.3);
}

.tab-status.pending {
  background: rgba(79, 172, 254, 0.1);
  color: #4facfe;
  border-color: rgba(79, 172, 254, 0.3);
}

.tab-status.exception {
  background: rgba(245, 108, 108, 0.1);
  color: #f56c6c;
  border-color: rgba(245, 108, 108, 0.3);
}

.static-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
  flex: 0 0 auto;
}

.static-dot.running {
  background: #2563eb;
  animation: running-dot-breathe 1.6s ease-in-out infinite;
}

@keyframes running-dot-breathe {
  0%, 100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.log-container {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 16px;
  height: 300px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.log-controls {
  display: flex;
  align-items: center;
  gap: 16px;
}

.log-settings {
  display: flex;
  align-items: center;
  gap: 16px;
}

.log-count {
  font-size: 14px;
  font-weight: 600;
  color: #f8f9fa;
}

.update-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #409eff;
  background: rgba(64, 158, 255, 0.1);
  padding: 4px 8px;
  border-radius: 12px;
  border: 1px solid rgba(64, 158, 255, 0.3);
}

.update-status i {
  display: none;
}

.log-viewport {
  flex: 1;
  overflow-x: hidden;
  background: #1e1e1e;
  border-radius: 8px;
  border: 1px solid #333;
  min-height: 0;
  height: 100%;
}

.log-content {
  padding: 16px;
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.log-line {
  padding: 4px 0;
  display: flex;
  align-items: center;
  font-family: 'Courier New', monospace;
  font-size: 14px;
  line-height: 1.4;
  border-bottom: 1px solid #333;
  background: #1e1e1e;
  min-height: 20px;
  box-sizing: border-box;
}

.log-line:hover {
  background: #1e1e1e;
}

.log-line:last-child {
  border-bottom: none;
}

.log-line-number {
  font-size: 12px;
  font-weight: 500;
  color: #666;
  margin-right: 12px;
  min-width: 50px;
  flex-shrink: 0;
  text-align: right;
  user-select: none;
}

.log-timestamp {
  font-size: 12px;
  font-weight: 500;
  color: #888;
  margin-right: 12px;
  min-width: 80px;
  flex-shrink: 0;
}

.log-text {
  font-size: 14px;
  font-weight: 500;
  color: #00ff00;
  flex: 1;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 滚动条样式 */
.log-viewport::-webkit-scrollbar {
  width: 8px;
}

.log-viewport::-webkit-scrollbar-track {
  background: #2a2a2a;
  border-radius: 4px;
}

.log-viewport::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.log-viewport::-webkit-scrollbar-thumb:hover {
  background: #777;
}

/* 隐藏其他可能的滚动条 */
.log-container::-webkit-scrollbar,
.log-content::-webkit-scrollbar {
  display: none;
}

.log-container,
.log-content {
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE and Edge */
}

/* 自动滚动指示器 */
.auto-scroll-indicator {
  position: absolute;
  bottom: 10px;
  right: 10px;
  background: rgba(0, 0, 0, 0.7);
  color: #00ff00;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  pointer-events: none;
  opacity: 0;
}

.auto-scroll-indicator.show {
  opacity: 1;
}

/* 操作栏 */
.actions-section {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  border-radius: 6px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid #dde3ea;
}

.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 560px;
}

.custom-search {
  --el-input-border-radius: 12px;
  max-width: 360px;
}

.status-filter {
  width: 150px;
}

.action-buttons {
  display: flex;
  gap: 16px;
}

.batch-execute-btn {
  background: #2563eb;
  border: none;
  border-radius: 6px;
  padding: 12px 24px;
  font-weight: 600;
}

/* 表格部分 */
.table-section {
  background: white;
  border-radius: 6px;
  padding: 16px;
  border: 1px solid #dde3ea;
  flex: 1;
  min-height: 0;
}

.custom-table {
  --el-table-border-color: #e9ecef;
  --el-table-header-background-color: #f8f9fa;
  border-radius: 6px;
  overflow: hidden;
}

.custom-table :deep(.task-cell .cell) {
  display: flex;
  align-items: center;
  min-height: 46px;
}

.case-summary {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
  width: 100%;
  min-height: 42px;
}

.case-desc {
  font-weight: 500;
  color: #2c3e50;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-link {
  font-weight: 600;
  text-decoration: none;
}

.case-no-link {
  max-width: 100%;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.2;
  align-self: flex-start;
}

.case-no-link :deep(.el-link__inner) {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-cell {
  font-family: 'Courier New', monospace;
  font-size: 13px;
  color: #6c757d;
  white-space: nowrap;
}

.time-cell.muted {
  color: #98a2b3;
}

.time-stack {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  line-height: 1.2;
}

.estimated-time {
  display: inline-block;
  color: #f5576c;
  font-weight: 500;
  white-space: nowrap;
  font-size: 12px;
}

.machine-info {
  display: block;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  color: #495057;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-cell {
  display: flex;
  align-items: center;
  justify-content: center;
}

.status-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 58px;
  padding: 5px 9px;
  border-radius: 16px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid transparent;
}

.status-indicator.running {
  background: #eff6ff;
  color: #2563eb;
  border-color: #bfdbfe;
}

.status-indicator.pass {
  background: rgba(103, 194, 58, 0.1);
  color: #67C23A;
}

.status-indicator.fail {
  background: rgba(252, 70, 107, 0.1);
  color: #fc466b;
}

.status-indicator.pending {
  background: rgba(79, 172, 254, 0.1);
  color: #4facfe;
}

.status-indicator.exception {
  background: rgba(245, 108, 108, 0.1);
  color: #f56c6c;
}

.status-indicator.cancel {
  background: rgba(144, 147, 153, 0.1);
  color: #909399;
}

@media (prefers-reduced-motion: reduce) {
  .static-dot.running {
    animation: none;
    opacity: 1;
  }
}

.log-btn, .annex-btn {
  color: #409eff;
  font-weight: 500;
  font-size: 12px;
}

.action-cell {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 4px;
  flex-wrap: nowrap;
}

.action-btn {
  border-radius: 6px;
  font-weight: 500;
  padding: 5px 8px;
  margin-left: 0 !important;
}

.table-action-link {
  padding: 4px 4px;
  margin-left: 0 !important;
  font-size: 12px;
}

.more-action {
  color: #667085;
}

.empty-cell {
  color: #98a2b3;
}

/* 对话框样式 */
.log-dialog, .confirm-dialog, .preview-dialog, .annex-dialog, .repeat-dialog {
  --el-dialog-border-radius: 16px;
}

.log-dialog-content {
  display: flex;
  flex-direction: column;
  height: 70vh;
  max-height: 600px;
}

.log-dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid #e9ecef;
  flex-shrink: 0;
}

.log-info {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  flex-wrap: wrap;
}

.log-count {
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.log-meta {
  color: #344054;
  font-size: 13px;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-meta.mono {
  font-family: 'Courier New', monospace;
  color: #475467;
}

.log-error {
  color: #dc2626;
  font-size: 13px;
}

.log-info .update-status {
  color: #409eff;
  background: rgba(64, 158, 255, 0.1);
  border-color: rgba(64, 158, 255, 0.3);
}

.running-status {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #f5576c;
  font-weight: 500;
}

.log-controls {
  display: flex;
  gap: 8px;
}

.log-dialog-body {
  flex: 1;
  background: #1e1e1e;
  border-radius: 8px;
  padding: 0;
  overflow: auto;
  position: relative;
  margin-top: 16px;
  border: 1px solid #333;
}

.dialog-log-lines {
  padding: 12px 0;
  min-height: 100%;
}

.dialog-log-line {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 12px;
  padding: 2px 14px;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.55;
  border-bottom: 1px solid #2a2a2a;
}

.dialog-log-no {
  color: #858585;
  text-align: right;
  user-select: none;
}

.dialog-log-text {
  color: #d6f5d6;
  white-space: pre-wrap;
  word-break: break-all;
  overflow-wrap: break-word;
}

.log-empty {
  color: #98a2b3;
  padding: 28px;
  font-size: 14px;
}

.log-dialog-body::-webkit-scrollbar {
  width: 8px;
}

.log-dialog-body::-webkit-scrollbar-track {
  background: #2a2a2a;
  border-radius: 4px;
}

.log-dialog-body::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.log-dialog-body::-webkit-scrollbar-thumb:hover {
  background: #777;
}

.agent-event-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 420px;
}

.agent-event-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid #e9ecef;
}

.agent-event-context {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  color: #667085;
  font-size: 13px;
  font-family: 'Courier New', monospace;
}

.agent-event-list {
  flex: 1;
  max-height: 560px;
  overflow-y: auto;
  border: 1px solid #e9ecef;
  border-radius: 6px;
  background: #fff;
}

.agent-event-row {
  padding: 12px 14px;
  border-bottom: 1px solid #eef2f6;
}

.agent-event-row:last-child {
  border-bottom: none;
}

.agent-event-head,
.agent-event-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.agent-event-head {
  margin-bottom: 6px;
}

.agent-event-time,
.agent-event-machine {
  font-family: 'Courier New', monospace;
  color: #667085;
  font-size: 12px;
}

.agent-event-main {
  color: #344054;
  font-size: 13px;
  flex-wrap: wrap;
}

.agent-event-command {
  margin: 8px 0 0;
  padding: 8px;
  background: #f8f9fa;
  border: 1px solid #e9ecef;
  border-radius: 6px;
  color: #475467;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.45;
}

.agent-event-error {
  margin-top: 8px;
  color: #dc2626;
  font-size: 12px;
}

.confirm-content {
  text-align: center;
  padding: 20px 0;
}

.confirm-content i {
  font-size: 48px;
  color: #f5576c;
  margin-bottom: 16px;
}

.confirm-content p {
  font-size: 16px;
  color: #2c3e50;
  margin-bottom: 16px;
}

.selected-cases {
  background: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
  max-height: 300px;
  overflow-y: auto;
}

.case-item {
  background: white;
  border-radius: 6px;
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid #e9ecef;
}

.case-item:last-child {
  margin-bottom: 0;
}

.case-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.case-name {
  font-family: 'Courier New', monospace;
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.case-status {
  padding: 4px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
}

.case-status.pass {
  background: rgba(103, 194, 58, 0.1);
  color: #67C23A;
}

.case-status.fail {
  background: rgba(252, 70, 107, 0.1);
  color: #fc466b;
}

.case-status.running {
  background: rgba(245, 87, 108, 0.1);
  color: #f5576c;
}

.case-status.pending {
  background: rgba(79, 172, 254, 0.1);
  color: #4facfe;
}

.case-status.cancel {
  background: rgba(144, 147, 153, 0.1);
  color: #909399;
}

.case-desc {
  font-size: 13px;
  color: #6c757d;
  line-height: 1.4;
  word-break: break-word;
}

/* 确认对话框滚动条样式 */
.selected-cases::-webkit-scrollbar {
  width: 6px;
}

.selected-cases::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

.selected-cases::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.selected-cases::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

.preview-content {
  background: #f8f9fa;
  border-radius: 8px;
  padding: 20px;
  max-height: 600px;
  overflow-y: auto;
}

.preview-content pre {
  margin: 0;
  font-family: 'Courier New', monospace;
  font-size: 14px;
  line-height: 1.5;
}

.annex-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.annex-item {
  background: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
  border: 1px solid #e9ecef;
}

.annex-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.annex-info i {
  color: #409eff;
  font-size: 18px;
}

.annex-link {
  color: #409eff;
  text-decoration: none;
  font-weight: 500;
}

.annex-link:hover {
  text-decoration: underline;
}

.annex-size {
  color: #6c757d;
  font-size: 14px;
}

.annex-path {
  color: #6c757d;
  font-size: 13px;
  font-family: 'Courier New', monospace;
  margin-bottom: 12px;
}

.repeat-form {
  margin-bottom: 24px;
}

.machine-selection h4 {
  font-size: 16px;
  font-weight: 600;
  color: #2c3e50;
  margin-bottom: 16px;
}

.machine-table {
  border-radius: 8px;
  overflow: hidden;
}

.machine-status, .machine-health {
  display: flex;
  align-items: center;
  gap: 8px;
}

.machine-address {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: 'Courier New', monospace;
  font-size: 13px;
}

.machine-address i {
  color: #409eff;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status-dot.idle, .status-dot.online {
  background: #11998e;
}

.status-dot.busy, .status-dot.offline {
  background: #f5576c;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .overview-section {
    grid-template-columns: 1fr;
  }
  
  .stats-grid {
    grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  }
}

@media (max-width: 768px) {
  .task-dashboard {
    padding: 12px;
  }
  
  .overview-section {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  
  .stats-grid {
    grid-template-columns: repeat(auto-fit, minmax(100px, 1fr));
    gap: 8px;
  }
  
  .stat-item {
    padding: 12px;
    gap: 8px;
  }
  
  .stat-icon {
    width: 32px;
    height: 32px;
    font-size: 16px;
  }
  
  .stat-number {
    font-size: 20px;
  }
  
  .actions-section {
    flex-direction: column;
    gap: 12px;
  }
  
  .search-box {
    max-width: 100%;
  }
  
  .log-container {
    height: 250px;
  }
  
  .log-dialog-content {
    height: 60vh;
  }
  
  .log-dialog-header {
    flex-direction: column;
    gap: 12px;
    align-items: flex-start;
  }
  
  .log-controls {
    width: 100%;
    justify-content: flex-end;
  }
}

@media (max-width: 480px) {
  .task-dashboard {
    padding: 8px;
  }
  
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  
  .card-title {
    font-size: 20px;
  }
  
  .card-title i {
    font-size: 24px;
  }
  
  .section-header h3 {
    font-size: 18px;
  }
}

/* 深度选择器 */
:deep(.el-descriptions__cell) {
  padding-bottom: 25px !important;
}

:deep(.el-descriptions__label) {
  font-weight: 600;
}

:deep(.el-tabs__header) {
  margin-bottom: 0;
}

:deep(.el-tabs__item) {
  border-radius: 0;
  border: none;
}

:deep(.el-tabs__item.is-active) {
  background: #f0f2f5;
  color: #409eff;
  font-weight: 600;
}

:deep(.el-tabs--card > .el-tabs__header .el-tabs__nav) {
  border: none;
  border-radius: 0;
}

:deep(.el-table__header) {
  background: #f8f9fa;
}

:deep(.el-table__row:hover) {
  background: #fff;
}

:deep(.el-button--text) {
  padding: 0;
}

:deep(.el-dialog__header) {
  border-bottom: 1px solid #e9ecef;
  padding-bottom: 16px;
}

:deep(.el-dialog__footer) {
  border-top: 1px solid #e9ecef;
  padding-top: 16px;
}

/* 防止页面溢出 */
.task-dashboard {
  max-width: 100%;
  overflow-x: hidden;
}
</style>

<style>
/* 全局样式 - 解决页面无法滚动问题 */
html, body {
  overflow-y: auto !important;
  height: auto !important;
  margin: 0;
  padding: 0;
}

/* 嵌入容器适配 */
.task-dashboard-container {
  height: 100%;
  width: 100%;
  overflow: hidden;
}
</style>
