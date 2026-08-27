-- 运维台 / playground 不填 requestId 时由 Server 自动生成（UUID），因此每条任务都有
-- requestId：老数据补一个再收紧为 NOT NULL。
update task set request_id = cast(random_uuid() as varchar(64)) where request_id is null;
alter table task alter column request_id set not null;

-- requestId 的全局唯一（重复创建 -> 409）由本表主键在 DB 层兜底：
-- 批量下一个 requestId 挂多条任务，task.request_id 上放不了唯一索引，唯一索引放在这里。
-- 行只在创建事务内插入（认领），创建失败整体回滚，key 不会被白白占用。
create table open_request (
    request_id varchar(64) not null,
    source     varchar(16) not null,
    created_at timestamp   not null,
    primary key (request_id)
);

insert into open_request (request_id, source, created_at)
select request_id, 'backfill', min(created_at) from task group by request_id;
