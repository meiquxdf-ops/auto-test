package com.hjmicro.domain.dto;

import java.io.Serializable;

public class TaskExecuteInfo implements Serializable {

    /**
     * 执行id
     */
    Long executeId;

    /**
     * 任务编号
     */
    Long taskId;

    /**
     * 输出信息
     */
    String outLine;

    /**
     * 机器ip
     */

    String ipAddress;

    /**
     * 执行状态
     */
    Boolean isFinished;

    /**
     * 执行结果
     */
    Boolean isSuccess;


    //是否是第一条数据
    Boolean isFirst;

    Boolean isCanceled;

    String result;

    String dispatchToken;

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Long getExecuteId() {
        return executeId;
    }

    public void setExecuteId(Long executeId) {
        this.executeId = executeId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getOutLine() {
        return outLine;
    }

    public void setOutLine(String outLine) {
        this.outLine = outLine;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Boolean getFinished() {
        return isFinished;
    }

    public void setFinished(Boolean finished) {
        isFinished = finished;
    }

    public Boolean getSuccess() {
        return isSuccess;
    }

    public void setSuccess(Boolean success) {
        isSuccess = success;
    }

    public Boolean getFirst() {
        return isFirst;
    }

    public void setFirst(Boolean first) {
        isFirst = first;
    }

    public Boolean getCanceled() {
        return isCanceled;
    }

    public void setCanceled(Boolean canceled) {
        isCanceled = canceled;
    }

    public String getDispatchToken() {
        return dispatchToken;
    }

    public void setDispatchToken(String dispatchToken) {
        this.dispatchToken = dispatchToken;
    }
}
