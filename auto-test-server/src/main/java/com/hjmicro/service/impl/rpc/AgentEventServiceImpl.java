package com.hjmicro.service.impl.rpc;

import com.hjmicro.domain.dto.AgentEventDTO;
import com.hjmicro.server.service.AgentEventService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class AgentEventServiceImpl implements AgentEventService {

    private static final int MAX_EVENTS = 5000;
    private final Deque<AgentEventDTO> events = new LinkedList<>();
    private final JdbcTemplate jdbcTemplate;

    public AgentEventServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_event_log (
                  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                  event_id VARCHAR(64) NOT NULL COMMENT '事件ID',
                  event_time DATETIME(3) NOT NULL COMMENT '事件时间',
                  event_time_ms BIGINT NOT NULL COMMENT '事件时间毫秒',
                  event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
                  level VARCHAR(16) NULL COMMENT '事件级别',
                  machine_tag VARCHAR(128) NULL COMMENT '机器标识',
                  agent_session_id VARCHAR(128) NULL COMMENT 'Agent会话ID',
                  state_version BIGINT NULL COMMENT 'Agent状态版本',
                  task_id BIGINT NULL COMMENT '任务ID',
                  execute_id BIGINT NULL COMMENT '执行ID',
                  dispatch_token VARCHAR(64) NULL COMMENT '下发令牌',
                  request_id VARCHAR(128) NULL COMMENT '请求ID',
                  command TEXT NULL COMMENT '完整命令',
                  message VARCHAR(1024) NULL COMMENT '事件消息',
                  detail TEXT NULL COMMENT '事件详情',
                  duration_ms BIGINT NULL COMMENT '耗时毫秒',
                  error_type VARCHAR(256) NULL COMMENT '异常类型',
                  error_message TEXT NULL COMMENT '异常消息',
                  server VARCHAR(128) NULL COMMENT 'Server地址',
                  thread VARCHAR(128) NULL COMMENT 'Agent线程',
                  gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_event_id (event_id),
                  KEY idx_event_time (event_time_ms),
                  KEY idx_machine_time (machine_tag, event_time_ms),
                  KEY idx_request_time (request_id, event_time_ms),
                  KEY idx_task_execute_time (task_id, execute_id, event_time_ms),
                  KEY idx_event_type_time (event_type, event_time_ms)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent关键事件日志'
                """);
    }

    @Override
    public Boolean reportEvent(AgentEventDTO event) {
        if (event == null || StringUtils.isBlank(event.getEventType())) {
            return Boolean.FALSE;
        }
        rememberEvent(event);
        try {
            insertEvent(event);
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("[AgentEvent] 落库失败，已保留内存缓冲: eventType={}, eventId={}, error={}",
                    event.getEventType(), event.getEventId(), e.getMessage());
            return Boolean.FALSE;
        }
    }

    public List<AgentEventDTO> listEvents(String machineTag, String requestId, Long taskId,
                                          Long executeId, int limit) {
        int safeLimit = limit <= 0 ? 200 : Math.min(limit, 1000);
        try {
            return listEventsFromDb(machineTag, requestId, taskId, executeId, safeLimit);
        } catch (Exception e) {
            log.warn("[AgentEvent] 查询数据库失败，退回内存缓冲: error={}", e.getMessage());
            return listEventsFromMemory(machineTag, requestId, taskId, executeId, safeLimit);
        }
    }

    private void rememberEvent(AgentEventDTO event) {
        synchronized (events) {
            events.addLast(event);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
    }

    private void insertEvent(AgentEventDTO event) {
        long eventTimeMs = event.getTime() == null ? System.currentTimeMillis() : event.getTime();
        jdbcTemplate.update("""
                        INSERT IGNORE INTO agent_event_log (
                          event_id, event_time, event_time_ms, event_type, level,
                          machine_tag, agent_session_id, state_version, task_id, execute_id,
                          dispatch_token, request_id, command, message, detail,
                          duration_ms, error_type, error_message, server, thread
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.getEventId(),
                new Timestamp(eventTimeMs),
                eventTimeMs,
                event.getEventType(),
                event.getLevel(),
                event.getMachineTag(),
                event.getAgentSessionId(),
                event.getStateVersion(),
                event.getTaskId(),
                event.getExecuteId(),
                event.getDispatchToken(),
                event.getRequestId(),
                event.getCommand(),
                event.getMessage(),
                event.getDetail(),
                event.getDurationMs(),
                event.getErrorType(),
                event.getErrorMessage(),
                event.getServer(),
                event.getThread()
        );
    }

    private List<AgentEventDTO> listEventsFromDb(String machineTag, String requestId, Long taskId,
                                                 Long executeId, int safeLimit) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, event_time_ms, event_type, level, machine_tag, agent_session_id,
                       state_version, task_id, execute_id, dispatch_token, request_id, command,
                       message, detail, duration_ms, error_type, error_message, server, thread
                FROM agent_event_log
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (StringUtils.isNotBlank(machineTag)) {
            sql.append(" AND machine_tag = ?");
            params.add(machineTag);
        }
        if (StringUtils.isNotBlank(requestId)) {
            sql.append(" AND request_id = ?");
            params.add(requestId);
        }
        if (taskId != null) {
            sql.append(" AND task_id = ?");
            params.add(taskId);
        }
        if (executeId != null) {
            sql.append(" AND execute_id = ?");
            params.add(executeId);
        }
        sql.append(" ORDER BY event_time_ms DESC, event_id DESC LIMIT ?");
        params.add(safeLimit);
        return jdbcTemplate.query(sql.toString(), this::mapEvent, params.toArray());
    }

    private List<AgentEventDTO> listEventsFromMemory(String machineTag, String requestId, Long taskId,
                                                     Long executeId, int safeLimit) {
        List<AgentEventDTO> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        return snapshot.stream()
                .filter(event -> StringUtils.isBlank(machineTag)
                        || Objects.equals(machineTag, event.getMachineTag()))
                .filter(event -> StringUtils.isBlank(requestId)
                        || Objects.equals(requestId, event.getRequestId()))
                .filter(event -> taskId == null || Objects.equals(taskId, event.getTaskId()))
                .filter(event -> executeId == null || Objects.equals(executeId, event.getExecuteId()))
                .sorted(Comparator.comparing(AgentEventDTO::getTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();
    }

    private AgentEventDTO mapEvent(ResultSet rs, int rowNum) throws SQLException {
        AgentEventDTO event = new AgentEventDTO();
        event.setEventId(rs.getString("event_id"));
        event.setTime(nullableLong(rs, "event_time_ms"));
        event.setEventType(rs.getString("event_type"));
        event.setLevel(rs.getString("level"));
        event.setMachineTag(rs.getString("machine_tag"));
        event.setAgentSessionId(rs.getString("agent_session_id"));
        event.setStateVersion(nullableLong(rs, "state_version"));
        event.setTaskId(nullableLong(rs, "task_id"));
        event.setExecuteId(nullableLong(rs, "execute_id"));
        event.setDispatchToken(rs.getString("dispatch_token"));
        event.setRequestId(rs.getString("request_id"));
        event.setCommand(rs.getString("command"));
        event.setMessage(rs.getString("message"));
        event.setDetail(rs.getString("detail"));
        event.setDurationMs(nullableLong(rs, "duration_ms"));
        event.setErrorType(rs.getString("error_type"));
        event.setErrorMessage(rs.getString("error_message"));
        event.setServer(rs.getString("server"));
        event.setThread(rs.getString("thread"));
        return event;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
