package com.hjmicro.domain.dto;

import java.io.Serializable;

public class PingMessage implements Serializable, RpcDTO {

    private String requestId;
    private long timestamp;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}

