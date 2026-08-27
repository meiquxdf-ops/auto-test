ALTER TABLE task_execution
  ADD COLUMN dispatch_token varchar(64) NULL COMMENT '任务下发令牌' AFTER machine_tag,
  ADD COLUMN dispatch_base_agent_session_id varchar(128) NULL COMMENT '下发时agent会话' AFTER dispatch_token,
  ADD COLUMN dispatch_base_state_version bigint NULL DEFAULT 0 COMMENT '下发时agent状态版本' AFTER dispatch_base_agent_session_id,
  ADD COLUMN dispatch_time datetime NULL COMMENT '下发时间' AFTER dispatch_base_state_version,
  ADD KEY idx_dispatch_token (dispatch_token),
  ADD KEY idx_machine_status (machine_tag, execute_status);

ALTER TABLE machine_info
  ADD COLUMN agent_session_id varchar(128) NULL COMMENT 'agent会话' AFTER link_ip,
  ADD COLUMN agent_state_version bigint NULL DEFAULT 0 COMMENT 'agent状态版本' AFTER agent_session_id,
  ADD COLUMN running_execute_id bigint NULL COMMENT 'agent当前执行ID' AFTER agent_state_version,
  ADD COLUMN running_dispatch_token varchar(64) NULL COMMENT 'agent当前执行令牌' AFTER running_execute_id,
  ADD COLUMN active_execute_id bigint NULL COMMENT 'server当前下发执行ID' AFTER running_dispatch_token,
  ADD COLUMN active_dispatch_token varchar(64) NULL COMMENT 'server当前下发令牌' AFTER active_execute_id,
  ADD UNIQUE KEY uk_machine_tag (machine_tag),
  ADD KEY idx_active_execute (active_execute_id),
  ADD KEY idx_machine_task (machine_tag, task_id);
