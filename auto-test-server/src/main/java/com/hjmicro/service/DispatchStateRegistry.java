package com.hjmicro.service;

import com.hjmicro.domain.dto.HeartbeatMessage;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class DispatchStateRegistry {

    private static final ConcurrentHashMap<String, AgentState> latestAgentStateByMachine =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, DispatchState> dispatchStateByMachine =
            new ConcurrentHashMap<>();

    public static AgentState recordHeartbeat(String machineTag, HeartbeatMessage message) {
        AgentState state = new AgentState(
                message.getAgentSessionId(),
                message.getStateVersion() == null ? 0L : message.getStateVersion(),
                message.getRunningExecuteId(),
                message.getDispatchToken(),
                message.getExecuteStatus()
        );
        latestAgentStateByMachine.put(machineTag, state);
        return state;
    }

    public static AgentState getAgentState(String machineTag) {
        return latestAgentStateByMachine.get(machineTag);
    }

    public static DispatchState startDispatch(String machineTag, Long taskId, Long executeId,
                                              String dispatchToken) {
        AgentState agentState = latestAgentStateByMachine.get(machineTag);
        DispatchState state = new DispatchState(
                machineTag,
                taskId,
                executeId,
                dispatchToken,
                agentState == null ? null : agentState.getAgentSessionId(),
                agentState == null ? 0L : agentState.getStateVersion(),
                System.currentTimeMillis()
        );
        dispatchStateByMachine.put(machineTag, state);
        return state;
    }

    public static DispatchState getDispatch(String machineTag) {
        return dispatchStateByMachine.get(machineTag);
    }

    public static DispatchState getDispatchByExecuteId(Long executeId) {
        if (executeId == null) {
            return null;
        }
        return dispatchStateByMachine.values().stream()
                .filter(state -> Objects.equals(state.getExecuteId(), executeId))
                .findFirst()
                .orElse(null);
    }

    public static DispatchState getDispatchByTaskId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return dispatchStateByMachine.values().stream()
                .filter(state -> Objects.equals(state.getTaskId(), taskId))
                .findFirst()
                .orElse(null);
    }

    public static boolean isCurrent(Long executeId, String dispatchToken) {
        DispatchState state = getDispatchByExecuteId(executeId);
        return state != null && Objects.equals(state.getDispatchToken(), dispatchToken);
    }

    public static void clearIfCurrent(String machineTag, Long executeId, String dispatchToken) {
        DispatchState state = dispatchStateByMachine.get(machineTag);
        if (state != null
                && Objects.equals(state.getExecuteId(), executeId)
                && Objects.equals(state.getDispatchToken(), dispatchToken)) {
            dispatchStateByMachine.remove(machineTag, state);
        }
    }

    public static void clearMachine(String machineTag) {
        dispatchStateByMachine.remove(machineTag);
        latestAgentStateByMachine.remove(machineTag);
    }

    public static class AgentState {
        private final String agentSessionId;
        private final long stateVersion;
        private final Long runningExecuteId;
        private final String dispatchToken;
        private final String executeStatus;

        AgentState(String agentSessionId, long stateVersion, Long runningExecuteId,
                   String dispatchToken, String executeStatus) {
            this.agentSessionId = agentSessionId;
            this.stateVersion = stateVersion;
            this.runningExecuteId = runningExecuteId;
            this.dispatchToken = dispatchToken;
            this.executeStatus = executeStatus;
        }

        public String getAgentSessionId() {
            return agentSessionId;
        }

        public long getStateVersion() {
            return stateVersion;
        }

        public Long getRunningExecuteId() {
            return runningExecuteId;
        }

        public String getDispatchToken() {
            return dispatchToken;
        }

        public String getExecuteStatus() {
            return executeStatus;
        }
    }

    public static class DispatchState {
        private final String machineTag;
        private final Long taskId;
        private final Long executeId;
        private final String dispatchToken;
        private final String baseAgentSessionId;
        private final long baseStateVersion;
        private final long dispatchTime;

        DispatchState(String machineTag, Long taskId, Long executeId, String dispatchToken,
                      String baseAgentSessionId, long baseStateVersion, long dispatchTime) {
            this.machineTag = machineTag;
            this.taskId = taskId;
            this.executeId = executeId;
            this.dispatchToken = dispatchToken;
            this.baseAgentSessionId = baseAgentSessionId;
            this.baseStateVersion = baseStateVersion;
            this.dispatchTime = dispatchTime;
        }

        public String getMachineTag() {
            return machineTag;
        }

        public Long getTaskId() {
            return taskId;
        }

        public Long getExecuteId() {
            return executeId;
        }

        public String getDispatchToken() {
            return dispatchToken;
        }

        public String getBaseAgentSessionId() {
            return baseAgentSessionId;
        }

        public long getBaseStateVersion() {
            return baseStateVersion;
        }

        public long getDispatchTime() {
            return dispatchTime;
        }
    }
}
