<template>
  <div class="terminal" v-html="logs"></div>
</template>

<script lang="ts">
export default {
  data() {
    return {
      logs: "",
      source: null,
    };
  },
  created() {
    let username = "123"; // 你的用户名
    this.source = new EventSource(
      `http://chaos.hongjunwei.com:1992/sseEmitter/connect/${username}`
    );
    this.source.onopen = (event) => {
      this.addLog("Connection established", "system");
    };
    this.source.onerror = (event) => {
      this.addLog("Connection error", "system");
    };
    this.source.addEventListener("push", (event) => {
      this.addLog(event.data, "output");
    });
  },
  beforeDestroy() {
    if (this.source) {
      this.source.close();
    }
  },
  methods: {
    addLog(message, type) {
      let msgClass = type === "system" ? "system-msg" : "output-msg";
      this.logs += `<div class="${msgClass}">${message}</div>`;
    },
  },
};
</script>

<style scoped>
.terminal {
  background-color: #000;
  color: #fff;
  padding: 1em;
  font-family: "Courier New", monospace;
  height: 400px;
  overflow-y: auto;
  width: 100%;
  display: inline-block;
}
.system-msg {
  color: #ff3d00;
}
.output-msg {
  color: #76ff03;
}
</style>
