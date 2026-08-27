package com.hjmicro.domain.dto;

import java.io.Serializable;

public class HeartbeatMessage implements Serializable {
    private String operatingSystem;

    private String processor;

    private String memory;

    private double cpuUsage;

    private long totalMemory;

    private long availableMemory;

    private String ipAddress;

    private  String diskUsage;

    private String tag;

    private String executeStatus;

    private Long  submitTime;

    //用来判断是否是docker
    private Boolean isDockerContainer;

    private String dockerContainerId;

    private String dockerContainerName;

    //链接ip
    private String linkIp;

    //链接端口
    private Integer linkPort;

    private String machineTag;

    private String agentSessionId;

    private Long stateVersion;

    private Long runningExecuteId;

    private String dispatchToken;

    public String getMachineTag() {
        return machineTag;
    }

    public void setMachineTag(String machineTag) {
        this.machineTag = machineTag;
    }

    public String getLinkIp() {
        return linkIp;
    }

    public void setLinkIp(String linkIp) {
        this.linkIp = linkIp;
    }

    public Integer getLinkPort() {
        return linkPort;
    }

    public void setLinkPort(Integer linkPort) {
        this.linkPort = linkPort;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getExecuteStatus() {
        return executeStatus;
    }

    public void setExecuteStatus(String executeStatus) {
        this.executeStatus = executeStatus;
    }

    public String getDiskUsage() {
        return diskUsage;
    }

    public void setDiskUsage(String diskUsage) {
        this.diskUsage = diskUsage;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getProcessor() {
        return processor;
    }

    public void setProcessor(String processor) {
        this.processor = processor;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public long getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(long totalMemory) {
        this.totalMemory = totalMemory;
    }

    public long getAvailableMemory() {
        return availableMemory;
    }

    public void setAvailableMemory(long availableMemory) {
        this.availableMemory = availableMemory;
    }

    public Long getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(Long submitTime) {
        this.submitTime = submitTime;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Boolean getDockerContainer() {
        return isDockerContainer;
    }

    public void setDockerContainer(Boolean dockerContainer) {
        isDockerContainer = dockerContainer;
    }

    public String getDockerContainerId() {
        return dockerContainerId;
    }

    public void setDockerContainerId(String dockerContainerId) {
        this.dockerContainerId = dockerContainerId;
    }

    public String getDockerContainerName() {
        return dockerContainerName;
    }

    public void setDockerContainerName(String dockerContainerName) {
        this.dockerContainerName = dockerContainerName;
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

    public Long getRunningExecuteId() {
        return runningExecuteId;
    }

    public void setRunningExecuteId(Long runningExecuteId) {
        this.runningExecuteId = runningExecuteId;
    }

    public String getDispatchToken() {
        return dispatchToken;
    }

    public void setDispatchToken(String dispatchToken) {
        this.dispatchToken = dispatchToken;
    }

    @Override
    public String toString() {
        return "HeartbeatMessage{" +
                "operatingSystem='" + operatingSystem + '\'' +
                ", processor='" + processor + '\'' +
                ", memory='" + memory + '\'' +
                ", cpuUsage=" + cpuUsage +
                ", totalMemory=" + totalMemory +
                ", availableMemory=" + availableMemory +
                ", ipAddress='" + ipAddress + '\'' +
                '}';
    }
}
