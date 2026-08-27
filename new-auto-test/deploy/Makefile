# new-auto-test 开发入口
#
#   make dev      Docker 一键起 Server + 前端（Agent 用 `make agent` 在宿主机连入）
#   make local    不用 Docker 时打印三个终端各自要跑什么
#
# 在 deploy/ 目录下执行。

SHELL        := bash
ROOT         := $(abspath ..)
SERVER_DIR   := $(ROOT)/server
AGENT_DIR    := $(ROOT)/agent
FRONT_DIR    := $(ROOT)/frontend

# docker compose v2 优先，退回 docker-compose v1
COMPOSE := $(shell command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1 && echo "docker compose" || echo "docker-compose")

# 开发用 Agent 参数（可 make agent TAG=xxx SERVER_ADDR=host:port 覆盖）
SERVER_ADDR ?= 127.0.0.1:9800
TAG         ?= dev-$(shell hostname -s 2>/dev/null || echo node)
CONCURRENCY ?= 1

.PHONY: help dev down logs restart ps \
        server agent agent-build front \
        build build-server build-agent build-front test local clean

help: ## 显示本帮助
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

## ---- Docker 一键开发 ----

dev: build-server ## 一键起 Server(:8080/:9800) + 前端(:5173) + docker-agent-01
	$(COMPOSE) up --build

down: ## 停止并移除容器
	$(COMPOSE) down

logs: ## 跟随查看容器日志
	$(COMPOSE) logs -f --tail=200

restart: ## 重启容器
	$(COMPOSE) restart

ps: ## 容器状态
	$(COMPOSE) ps

## ---- 本地进程开发（不用 Docker）----

server: ## 本地起 Server（HTTP :8080 + Agent TCP :9800）
	cd $(SERVER_DIR) && mvn -q spring-boot:run

agent-build: ## 编译 Agent 二进制到 agent/atagent
	cd $(AGENT_DIR) && go build -o atagent ./cmd/atagent

agent: agent-build ## 编译并起一个开发 Agent 连到 $(SERVER_ADDR)
	mkdir -p $(AGENT_DIR)/data
	cd $(AGENT_DIR) && ./atagent run \
		-server $(SERVER_ADDR) \
		-tag $(TAG) \
		-data-dir ./data \
		-concurrency $(CONCURRENCY) \
		-log-level debug

front: ## 本地起前端 dev server（:5173，/api 反代到 :8080）
	cd $(FRONT_DIR) && npm install && npm run dev

local: ## 打印本地三终端启动指引
	@echo "终端 1（Server）  : cd deploy && make server"
	@echo "终端 2（Agent）   : cd deploy && make agent   # 或 make agent TAG=qa-node-01"
	@echo "终端 3（前端）    : cd deploy && make front"
	@echo "打开 http://127.0.0.1:5173"

## ---- 构建与测试 ----

build: build-server build-agent build-front ## 三端全量构建

build-server: ## 打包 Server jar（server/target/）
	cd $(SERVER_DIR) && mvn -q -DskipTests package

build-agent: agent-build ## 编译 Agent（同 agent-build）

build-front: ## 构建前端产物（frontend/dist/）
	cd $(FRONT_DIR) && npm install && npm run build

test: ## 跑 Server 与 Agent 测试
	cd $(SERVER_DIR) && mvn -q test
	cd $(AGENT_DIR) && go test ./...

clean: ## 清理构建产物
	cd $(SERVER_DIR) && mvn -q clean
	rm -f $(AGENT_DIR)/atagent
	rm -rf $(FRONT_DIR)/dist
