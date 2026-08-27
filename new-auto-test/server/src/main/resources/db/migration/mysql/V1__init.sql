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
    connected_at      datetime(3),
    disconnected_at   datetime(3),
    last_heartbeat_at datetime(3),
    created_at        datetime(3)   not null,
    updated_at        datetime(3)   not null,
    primary key (agent_id),
    unique key ux_agent_display_tag (display_tag),
    key ix_agent_status (status)
) engine = innodb default charset = utf8mb4;

create table task (
    id               bigint       not null auto_increment,
    name             varchar(255),
    command          mediumtext   not null,
    cwd              varchar(1024),
    env              mediumtext,
    condition_config mediumtext,
    targets          mediumtext,
    operator         varchar(64),
    timeout_sec      int          not null default 0,
    priority         int          not null default 0,
    queue_order      bigint       not null default 0,
    status           varchar(16)  not null,
    total_count      int          not null default 0,
    rerun_of         bigint,
    created_at       datetime(3)  not null,
    updated_at       datetime(3)  not null,
    primary key (id),
    key ix_task_status (status),
    key ix_task_queue (queue_order, id)
) engine = innodb default charset = utf8mb4;

create table task_execution (
    id                bigint      not null auto_increment,
    execute_id        varchar(64) not null,
    task_id           bigint      not null,
    agent_id          varchar(64) not null,
    agent_tag         varchar(128),
    target_raw        varchar(128),
    status            varchar(16) not null,
    sub_status        varchar(24),
    dispatch_token    varchar(64),
    lease_expire_at   datetime(3),
    acked             tinyint(1)  not null default 0,
    cancel_requested  tinyint(1)  not null default 0,
    timeout_requested tinyint(1)  not null default 0,
    exit_code         int,
    last_line         mediumtext,
    reason            varchar(512),
    matched_rule      varchar(512),
    log_seq           int         not null default 0,
    log_min_seq       int         not null default 0,
    log_bytes         bigint      not null default 0,
    truncated         tinyint(1)  not null default 0,
    attempt           int         not null default 1,
    disconnected_at   datetime(3),
    dispatched_at     datetime(3),
    started_at        datetime(3),
    finished_at       datetime(3),
    created_at        datetime(3) not null,
    updated_at        datetime(3) not null,
    primary key (id),
    unique key ux_execution_execute_id (execute_id),
    key ix_execution_task (task_id),
    key ix_execution_agent_status (agent_id, status),
    key ix_execution_status (status)
) engine = innodb default charset = utf8mb4;

create table execution_log (
    id         bigint      not null auto_increment,
    execute_id varchar(64) not null,
    seq        int         not null,
    line       mediumtext  not null,
    bytes      int         not null default 0,
    ts         datetime(3) not null,
    primary key (id),
    unique key ux_execution_log_seq (execute_id, seq)
) engine = innodb default charset = utf8mb4;

create table agent_event (
    id         bigint      not null auto_increment,
    agent_id   varchar(64) not null,
    evt_id     varchar(64) not null,
    type       varchar(64) not null,
    execute_id varchar(64),
    message    mediumtext,
    event_time datetime(3),
    created_at datetime(3) not null,
    primary key (id),
    unique key ux_agent_event_id (agent_id, evt_id),
    key ix_agent_event_exec (execute_id, id),
    key ix_agent_event_agent (agent_id, id)
) engine = innodb default charset = utf8mb4;

create table dispatch_event (
    id         bigint      not null auto_increment,
    execute_id varchar(64),
    task_id    bigint,
    agent_id   varchar(64),
    type       varchar(64) not null,
    detail     mediumtext,
    created_at datetime(3) not null,
    primary key (id),
    key ix_dispatch_event_exec (execute_id, id),
    key ix_dispatch_event_agent (agent_id, id),
    key ix_dispatch_event_task (task_id, id)
) engine = innodb default charset = utf8mb4;
