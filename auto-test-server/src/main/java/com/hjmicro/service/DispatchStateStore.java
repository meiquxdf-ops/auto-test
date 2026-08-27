package com.hjmicro.service;

import com.hjmicro.domain.CommonConstant;
import com.hjmicro.domain.dto.HeartbeatMessage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
public class DispatchStateStore {

    private static final List<String> ACTIVE_EXECUTION_STATUS =
            Arrays.asList(CommonConstant.DISPATCHING, CommonConstant.RUNNING);

    private final JdbcTemplate jdbcTemplate;

    public DispatchStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DispatchStateRegistry.AgentState recordHeartbeat(String machineTag, HeartbeatMessage message) {
        DispatchStateRegistry.AgentState state = DispatchStateRegistry.recordHeartbeat(machineTag, message);
        jdbcTemplate.update(
                "INSERT INTO machine_info (machine_tag, ip_address, status, execute_status, last_updated, " +
                        "agent_session_id, agent_state_version, running_execute_id, running_dispatch_token) " +
                        "VALUES (?, ?, 'ONLINE', ?, NOW(), ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE ip_address = VALUES(ip_address), status = 'ONLINE', " +
                        "execute_status = IF(task_id IS NULL, VALUES(execute_status), execute_status), " +
                        "last_updated = NOW(), agent_session_id = VALUES(agent_session_id), " +
                        "agent_state_version = VALUES(agent_state_version), " +
                        "running_execute_id = VALUES(running_execute_id), " +
                        "running_dispatch_token = VALUES(running_dispatch_token)",
                machineTag,
                message.getIpAddress(),
                message.getExecuteStatus(),
                state.getAgentSessionId(),
                state.getStateVersion(),
                state.getRunningExecuteId(),
                state.getDispatchToken()
        );
        return state;
    }

    public DispatchStateRegistry.AgentState getAgentState(String machineTag) {
        DispatchStateRegistry.AgentState cachedState = DispatchStateRegistry.getAgentState(machineTag);
        if (cachedState != null) {
            return cachedState;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT agent_session_id, agent_state_version, running_execute_id, " +
                            "running_dispatch_token, execute_status FROM machine_info WHERE machine_tag = ?",
                    (rs, rowNum) -> new DispatchStateRegistry.AgentState(
                            rs.getString("agent_session_id"),
                            rs.getLong("agent_state_version"),
                            nullableLong(rs, "running_execute_id"),
                            rs.getString("running_dispatch_token"),
                            rs.getString("execute_status")
                    ),
                    machineTag
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public boolean reserveMachine(String machineTag, Long taskId) {
        int updated = jdbcTemplate.update(
                "UPDATE machine_info SET execute_status = ?, task_id = ?, active_execute_id = NULL, " +
                        "active_dispatch_token = NULL WHERE machine_tag = ? AND task_id IS NULL " +
                        "AND (execute_status IS NULL OR execute_status = ?)",
                CommonConstant.DISPATCHING,
                taskId,
                machineTag,
                CommonConstant.IDLE
        );
        return updated > 0;
    }

    public void startDispatch(String machineTag, Long taskId, Long executeId, String dispatchToken,
                              DispatchStateRegistry.AgentState agentState) {
        long baseStateVersion = agentState == null ? 0L : agentState.getStateVersion();
        String baseSessionId = agentState == null ? null : agentState.getAgentSessionId();
        jdbcTemplate.update(
                "UPDATE task_execution SET dispatch_token = ?, dispatch_base_agent_session_id = ?, " +
                        "dispatch_base_state_version = ?, dispatch_time = ? WHERE id = ?",
                dispatchToken,
                baseSessionId,
                baseStateVersion,
                new Timestamp(System.currentTimeMillis()),
                executeId
        );
        jdbcTemplate.update(
                "UPDATE machine_info SET execute_status = ?, task_id = ?, active_execute_id = ?, " +
                        "active_dispatch_token = ? WHERE machine_tag = ? AND task_id = ?",
                CommonConstant.DISPATCHING,
                taskId,
                executeId,
                dispatchToken,
                machineTag,
                taskId
        );
        DispatchStateRegistry.startDispatch(machineTag, taskId, executeId, dispatchToken);
    }

    public DispatchStateRegistry.DispatchState getDispatch(String machineTag) {
        DispatchStateRegistry.DispatchState state = getDispatchFromMachine(machineTag);
        if (state != null) {
            return state;
        }
        return getLatestActiveExecutionByMachine(machineTag);
    }

    public DispatchStateRegistry.DispatchState getDispatchByExecuteId(Long executeId) {
        if (executeId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT e.machine_tag, e.task_id, e.id AS execute_id, e.dispatch_token, " +
                            "e.dispatch_base_agent_session_id, e.dispatch_base_state_version, e.dispatch_time " +
                            "FROM task_execution e WHERE e.id = ? AND e.is_deleted = 0 " +
                            "AND (e.execute_status IN (?, ?) OR EXISTS (" +
                            "SELECT 1 FROM machine_info m WHERE m.active_execute_id = e.id " +
                            "AND m.active_dispatch_token = e.dispatch_token))",
                    this::mapDispatchState,
                    executeId,
                    CommonConstant.DISPATCHING,
                    CommonConstant.RUNNING
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public DispatchStateRegistry.DispatchState getDispatchByTaskId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT machine_tag, task_id, id AS execute_id, dispatch_token, " +
                            "dispatch_base_agent_session_id, dispatch_base_state_version, dispatch_time " +
                            "FROM task_execution WHERE task_id = ? AND is_deleted = 0 " +
                            "AND execute_status IN (?, ?) ORDER BY id DESC LIMIT 1",
                    this::mapDispatchState,
                    taskId,
                    CommonConstant.DISPATCHING,
                    CommonConstant.RUNNING
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public boolean isCurrent(Long executeId, String dispatchToken) {
        DispatchStateRegistry.DispatchState state = getDispatchByExecuteId(executeId);
        return state != null && Objects.equals(state.getDispatchToken(), dispatchToken);
    }

    public void clearIfCurrent(String machineTag, Long taskId, Long executeId, String dispatchToken) {
        jdbcTemplate.update(
                "UPDATE machine_info SET execute_status = ?, task_id = NULL, active_execute_id = NULL, " +
                        "active_dispatch_token = NULL WHERE machine_tag = ? AND task_id = ? " +
                        "AND active_execute_id = ? AND active_dispatch_token = ?",
                CommonConstant.IDLE,
                machineTag,
                taskId,
                executeId,
                dispatchToken
        );
        DispatchStateRegistry.clearIfCurrent(machineTag, executeId, dispatchToken);
    }

    public void releaseMachine(String machineTag, Long taskId) {
        jdbcTemplate.update(
                "UPDATE machine_info SET execute_status = ?, task_id = NULL, active_execute_id = NULL, " +
                        "active_dispatch_token = NULL WHERE machine_tag = ? AND task_id = ?",
                CommonConstant.IDLE,
                machineTag,
                taskId
        );
    }

    public void clearMachine(String machineTag) {
        jdbcTemplate.update(
                "UPDATE machine_info SET execute_status = ?, task_id = NULL, active_execute_id = NULL, " +
                        "active_dispatch_token = NULL WHERE machine_tag = ?",
                CommonConstant.IDLE,
                machineTag
        );
    }

    public void markMachineOffline(String machineTag) {
        jdbcTemplate.update(
                "UPDATE machine_info SET status = 'OFFLINE', execute_status = ?, task_id = NULL, " +
                        "active_execute_id = NULL, active_dispatch_token = NULL WHERE machine_tag = ?",
                CommonConstant.IDLE,
                machineTag
        );
        DispatchStateRegistry.clearMachine(machineTag);
    }

    public void markStaleIdleMachinesOffline(long staleBeforeMs) {
        jdbcTemplate.update(
                "UPDATE machine_info SET status = 'OFFLINE', execute_status = ?, task_id = NULL, " +
                        "active_execute_id = NULL, active_dispatch_token = NULL " +
                        "WHERE last_updated < ? AND (task_id IS NULL OR execute_status = ?)",
                CommonConstant.IDLE,
                new Timestamp(staleBeforeMs),
                CommonConstant.IDLE
        );
    }

    private DispatchStateRegistry.DispatchState getDispatchFromMachine(String machineTag) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT e.machine_tag, e.task_id, e.id AS execute_id, e.dispatch_token, " +
                            "e.dispatch_base_agent_session_id, e.dispatch_base_state_version, e.dispatch_time " +
                            "FROM machine_info m JOIN task_execution e ON e.id = m.active_execute_id " +
                            "WHERE m.machine_tag = ? AND e.is_deleted = 0 AND e.execute_status IN (?, ?)",
                    this::mapDispatchState,
                    machineTag,
                    ACTIVE_EXECUTION_STATUS.get(0),
                    ACTIVE_EXECUTION_STATUS.get(1)
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private DispatchStateRegistry.DispatchState getLatestActiveExecutionByMachine(String machineTag) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT machine_tag, task_id, id AS execute_id, dispatch_token, " +
                            "dispatch_base_agent_session_id, dispatch_base_state_version, dispatch_time " +
                            "FROM task_execution WHERE machine_tag = ? AND is_deleted = 0 " +
                            "AND execute_status IN (?, ?) ORDER BY id DESC LIMIT 1",
                    this::mapDispatchState,
                    machineTag,
                    ACTIVE_EXECUTION_STATUS.get(0),
                    ACTIVE_EXECUTION_STATUS.get(1)
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private DispatchStateRegistry.DispatchState mapDispatchState(ResultSet rs, int rowNum) throws SQLException {
        Timestamp dispatchTime = rs.getTimestamp("dispatch_time");
        return new DispatchStateRegistry.DispatchState(
                rs.getString("machine_tag"),
                nullableLong(rs, "task_id"),
                nullableLong(rs, "execute_id"),
                rs.getString("dispatch_token"),
                rs.getString("dispatch_base_agent_session_id"),
                rs.getLong("dispatch_base_state_version"),
                dispatchTime == null ? new Date().getTime() : dispatchTime.getTime()
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
