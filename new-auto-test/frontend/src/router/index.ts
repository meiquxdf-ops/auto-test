import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '总览', nav: 'dashboard' },
  },
  {
    path: '/agents',
    name: 'agents',
    component: () => import('@/views/AgentsView.vue'),
    meta: { title: '机器', nav: 'agents' },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('@/views/TasksView.vue'),
    meta: { title: '任务队列', nav: 'tasks' },
  },
  {
    path: '/executions/:executeId',
    name: 'execution',
    component: () => import('@/views/ExecutionDetailView.vue'),
    props: true,
    meta: { title: '执行详情', nav: 'tasks' },
  },
  {
    path: '/timeline',
    name: 'timeline',
    component: () => import('@/views/TimelineView.vue'),
    meta: { title: '时间线', nav: 'timeline' },
  },
  {
    path: '/playground',
    name: 'playground',
    component: () => import('@/views/PlaygroundView.vue'),
    meta: { title: '测试下发', nav: 'playground' },
  },
  {
    path: '/open',
    name: 'open',
    component: () => import('@/views/OpenConsoleView.vue'),
    meta: { title: '开放查询', nav: 'open' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 测试执行平台` : '测试执行平台'
})

export default router
