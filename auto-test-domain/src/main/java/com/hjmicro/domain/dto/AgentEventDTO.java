package com.hjmicro.domain.dto;

import java.io.Serializable;

public class AgentEventDTO implements Serializable {
    private String eventId;
    private Long time;
    private String eventType;
    private String level;
    private String machineTag;
    private String agentSessionId;
    private Long stateVersion;
    private Long taskId;
    private Long executeId;
    private String dispatchToken;
    private String requestId;
    private String command;
    private String message;
    private String detail;
    private Long durationMs;
    private String errorType;
    private String errorMessage;
    private String server;
    private String thread;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getMachineTag() {
        return machineTag;
    }

    public void setMachineTag(String machineTag) {
        this.machineTag = machineTag;
    }

    public String getAgentSessionId() {
        return agentSessionId;
    }

    public void setAgentSessionId(String agentSessionId) {
        this.agentSessionId = agentSessionId;
    }

    public Long getStateVersion() {
        return stateVersion;
    }

    public void setStateVersion(Long stateVersion) {
        this.stateVersion = stateVersion;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getExecuteId() {
        return executeId;
    }

    public void setExecuteId(Long executeId) {
        this.executeId = executeId;
    }

    public String getDispatchToken() {
        return dispatchToken;
    }

    public void setDispatchToken(String dispatchToken) {
        this.dispatchToken = dispatchToken;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getThread() {
        return thread;
    }

    public void setThread(String thread) {
        this.thread = thread;
    }
}
