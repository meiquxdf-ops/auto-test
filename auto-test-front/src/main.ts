import "./assets/main.css";
import {createApp} from "vue";
import App from "./App.vue";
import {createRouter, createWebHashHistory} from "vue-router";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import * as ElementPlusIconsVue from "@element-plus/icons-vue";
import taskDetail from "@/pages/Task/TaskDetail.vue";
import taskList from "@/pages/Task/TaskList.vue";
import debugConsole from "@/pages/Debug/DebugConsole.vue";
import dispatchTest from "@/pages/Debug/DispatchTest.vue";

const Home = import("./components/Home.vue");
const About = import("./components/About.vue");
const log = import("./pages/Task/ExecuteLog.vue");
const TaskDetail = import("./pages/Task/TaskDetail.vue");

const routes = [
  { path: "/", component: Home },
  { path: "/about", component: About },
  { path: "/task/log", component: log },
  { path: "/task/list", component: taskList },
  { path: "/task/taskDetail", component: taskDetail },
  { path: "/debug", component: debugConsole },
  { path: "/dispatch-test", component: dispatchTest },
];

const router = createRouter({
  // 4. 内部提供了 history 模式的实现。为了简单起见，我们在这里使用 hash 模式。
  history: createWebHashHistory(),
  routes, // `routes: routes` 的缩写
});

const app = createApp(App).use(router).use(ElementPlus);

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

app.mount("#app");
