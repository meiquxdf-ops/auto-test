package com.hjmicro.domain.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class TaskExecuteDTO implements Serializable {

    public String  executableFilePath;

    public Map<String,String> systemProperties;

    public Long taskId;

    public Long executeId;

    public String ipAddress;

    public String dispatchToken;

    public String requestId;

    public String getExecutableFilePath() {
        return executableFilePath;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setExecutableFilePath(String executableFilePath) {
        this.executableFilePath = executableFilePath;
    }

    public Map<String, String> getSystemProperties() {
        return systemProperties;
    }

    public void setSystemProperties(Map<String, String> systemProperties) {
        this.systemProperties = systemProperties;
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
}
