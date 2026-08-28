-- 附件：脚本在测试机上产出的文件通过 HTTP 传回 Server 本地磁盘，元数据单独一张表，
-- 不塞进 task 行。文件内容不进 DB，磁盘路径由 stored_name 拼出（atest.attachments.dir）。
create table task_attachment (
    id           bigint auto_increment primary key,
    task_id      bigint        not null,
    execute_id   varchar(64),
    file_name    varchar(255)  not null,
    stored_name  varchar(320)  not null,
    content_type varchar(128),
    size_bytes   bigint        not null default 0,
    created_at   datetime(3)   not null
) engine = innodb default charset = utf8mb4;
create index ix_task_attachment_task on task_attachment (task_id, id);
create index ix_task_attachment_exec on task_attachment (execute_id, id);
