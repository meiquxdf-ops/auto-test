package com.hjmicro.domain.dto;

import java.io.Serializable;
import java.util.HashMap;

public class RpcResult implements Serializable, RpcDTO {

    public String requestId;

    public Boolean success;

    public String message;
    public Object result;
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
