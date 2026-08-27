package com.hjmicro.domain;

public enum SseTagEnum {
    EXECUTE_INFO("outExecuteInfo", "输出执行信息"),
    TASK_LIST("taskList", "任务列表");

     String code;
     String desc;

    SseTagEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
