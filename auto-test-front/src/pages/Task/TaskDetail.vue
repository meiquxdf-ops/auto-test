<template>
  <div id="app">
    <el-table
      :data="tableData"
      style="width: 100%"
      @row-click="handleRowClick"
      :row-class-name="tableRowClassName"
    >
      <el-table-column prop="date" label="Date" width="180"></el-table-column>
      <el-table-column prop="name" label="Name" width="180"></el-table-column>
      <el-table-column prop="status" label="Status" width="180">
        <template slot-scope="scope">
          <el-tag :type="getStatusTagType(scope.row.status)">{{
            scope.row.status
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="progress" label="Progress">
        <template slot-scope="scope">
          <el-progress :percentage="scope.row.progress"></el-progress>
        </template>
      </el-table-column>
      <el-table-column label="Operations">
        <template slot-scope="scope">
          <el-button-group>
            <el-button
              type="primary"
              icon="el-icon-play"
              @click="startTask(scope.row)"
              >Start</el-button
            >
            <el-button
              type="warning"
              icon="el-icon-pause"
              @click="pauseTask(scope.row)"
              >Pause</el-button
            >
            <el-button
              type="danger"
              icon="el-icon-refresh-right"
              @click="restartTask(scope.row)"
              >Restart</el-button
            >
          </el-button-group>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :visible.sync="dialogVisible"
      title="Task Details"
      :width="'60%'"
    >
      <h2>{{ currentTask.name }}</h2>
      <p>Status: {{ currentTask.status }}</p>
      <p>Date: {{ currentTask.date }}</p>
      <h3>Real-time Log</h3>
      <el-input
        type="textarea"
        :rows="10"
        :value="currentTask.log"
        disabled
      ></el-input>
      <h3>Test Results</h3>
      <el-input
        type="textarea"
        :rows="10"
        :value="currentTask.result"
        disabled
      ></el-input>
      <span slot="footer" class="dialog-footer">
        <el-button @click="dialogVisible = false">Close</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script lang="ts">
export default {
  name: "App",
  data() {
    return {
      dialogVisible: false,
      currentTask: {},
      tableData: [
        {
          date: "2023-6-30",
          name: "Task 1",
          status: "Processing",
          progress: 50,
          log: "Processing Task 1...",
          result: "Passed: 5, Failed: 2",
        },
        {
          date: "2023-6-30",
          name: "Task 2",
          status: "Completed",
          progress: 100,
          log: "Completed Task 2...",
          result: "Passed: 10, Failed: 0",
        },
        {
          date: "2023-6-30",
          name: "Task 3",
          status: "Pending",
          progress: 0,
          log: "Pending Task 3...",
          result: "Passed: 0, Failed: 0",
        },
      ],
    };
  },
  methods: {
    handleRowClick(row) {
      this.currentTask = row;
      this.dialogVisible = true;
    },
    getStatusTagType(status) {
      switch (status) {
        case "Processing":
          return "success";
        case "Completed":
          return "info";
        case "Paused":
          return "warning";
        case "Failed":
          return "danger";
        default:
          return "default";
      }
    },
    startTask(task) {
      // Simulating start of task
      this.$set(task, "status", "Processing");
      this.$set(task, "log", task.log + "\nStarting task...");
      this.simulateProgress(task);
    },
    pauseTask(task) {
      // Simulating pause of task
      this.$set(task, "status", "Paused");
      this.$set(task, "log", task.log + "\nPausing task...");
    },
    restartTask(task) {
      // Simulating restart of task
      this.$set(task, "status", "Processing");
      this.$set(task, "log", task.log + "\nRestarting task...");
      this.$set(task, "progress", 0);
      this.simulateProgress(task);
    },
    simulateProgress(task) {
      let intervalId = setInterval(() => {
        if (task.progress >= 100 || task.status !== "Processing") {
          clearInterval(intervalId);
          if (task.progress >= 100) {
            this.$set(task, "status", "Completed");
            this.$set(task, "log", task.log + "\nTask completed!");
            this.$set(task, "result", "Passed: 10, Failed: 0");
          }
        } else {
          this.$set(task, "progress", task.progress + 10);
          this.$set(task, "log", task.log + `\nProgress: ${task.progress}%`);
        }
      }, 1000);
    },
  },
};
</script>
