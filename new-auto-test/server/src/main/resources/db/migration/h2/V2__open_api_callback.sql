-- 开放 API：调用方自带 requestId（全局唯一，批量下多条任务共用一个），
-- 任务终态（finished / canceled）后向 callback_url POST 一次结果。
alter table task add column request_id         varchar(64);
alter table task add column callback_url       varchar(1024);
alter table task add column callback_status    varchar(16) not null default 'NONE';
alter table task add column callback_attempts  int         not null default 0;
alter table task add column callback_last_error varchar(512);
alter table task add column callback_last_at   timestamp;

-- 非唯一：一个 requestId 下可以挂多条任务（batch），唯一性在创建入口校验
create index ix_task_request_id on task (request_id);
create index ix_task_callback_status on task (callback_status);
