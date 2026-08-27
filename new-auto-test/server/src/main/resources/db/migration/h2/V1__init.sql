create table agent (
    agent_id          varchar(64)   not null,
    display_tag       varchar(128)  not null,
    status            varchar(16)   not null,
    session_id        varchar(64),
    boot_id           varchar(64),
    version           varchar(64),
    remote_addr       varchar(128),
    aliases           varchar(2048),
    concurrency       int           not null default 1,
    running_count     int           not null default 0,
    connected_at      timestamp,
    disconnected_at   timestamp,
    last_heartbeat_at timestamp,
    created_at        timestamp     not null,
    updated_at        timestamp     not null,
    primary key (agent_id)
);
create unique index ux_agent_display_tag on agent (display_tag);
create index ix_agent_status on agent (status);

create table task (
    id               bigint auto_increment primary key,
    name             varchar(255),
    command          varchar(65535) not null,
    cwd              varchar(1024),
    env              varchar(65535),
    condition_config varchar(65535),
    targets          varchar(65535),
    operator         varchar(64),
    timeout_sec      int            not null default 0,
    priority         int            not null default 0,
    queue_order      bigint         not null default 0,
    status           varchar(16)    not null,
    total_count      int            not null default 0,
    rerun_of         bigint,
    created_at       timestamp      not null,
    updated_at       timestamp      not null
);
create index ix_task_status on task (status);
create index ix_task_queue on task (queue_order, id);

create table task_execution (
    id                bigint auto_increment primary key,
    execute_id        varchar(64)   not null,
    task_id           bigint        not null,
    agent_id          varchar(64)   not null,
    agent_tag         varchar(128),
    target_raw        varchar(128),
    status            varchar(16)   not null,
    sub_status        varchar(24),
    dispatch_token    varchar(64),
    lease_expire_at   timestamp,
    acked             boolean       not null default false,
    cancel_requested  boolean       not null default false,
    timeout_requested boolean       not null default false,
    exit_code         int,
    last_line         varchar(65535),
    reason            varchar(512),
    matched_rule      varchar(512),
    log_seq           int           not null default 0,
    log_min_seq       int           not null default 0,
    log_bytes         bigint        not null default 0,
    truncated         boolean       not null default false,
    attempt           int           not null default 1,
    disconnected_at   timestamp,
    dispatched_at     timestamp,
    started_at        timestamp,
    finished_at       timestamp,
    created_at        timestamp     not null,
    updated_at        timestamp     not null
);
create unique index ux_execution_execute_id on task_execution (execute_id);
create index ix_execution_task on task_execution (task_id);
create index ix_execution_agent_status on task_execution (agent_id, status);
create index ix_execution_status on task_execution (status);

create table execution_log (
    id         bigint auto_increment primary key,
    execute_id varchar(64)    not null,
    seq        int            not null,
    line       varchar(65535) not null,
    bytes      int            not null default 0,
    ts         timestamp      not null
);
create unique index ux_execution_log_seq on execution_log (execute_id, seq);

create table agent_event (
    id         bigint auto_increment primary key,
    agent_id   varchar(64)    not null,
    evt_id     varchar(64)    not null,
    type       varchar(64)    not null,
    execute_id varchar(64),
    message    varchar(65535),
    event_time timestamp,
    created_at timestamp      not null
);
create unique index ux_agent_event_id on agent_event (agent_id, evt_id);
create index ix_agent_event_exec on agent_event (execute_id, id);
create index ix_agent_event_agent on agent_event (agent_id, id);

create table dispatch_event (
    id         bigint auto_increment primary key,
    execute_id varchar(64),
    task_id    bigint,
    agent_id   varchar(64),
    type       varchar(64)    not null,
    detail     varchar(65535),
    created_at timestamp      not null
);
create index ix_dispatch_event_exec on dispatch_event (execute_id, id);
create index ix_dispatch_event_agent on dispatch_event (agent_id, id);
create index ix_dispatch_event_task on dispatch_event (task_id, id);
