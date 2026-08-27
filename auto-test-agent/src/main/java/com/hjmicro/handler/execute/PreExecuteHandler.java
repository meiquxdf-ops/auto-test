package com.hjmicro.handler.execute;

import javax.naming.Context;

public abstract class PreExecuteHandler {
    // 后继节点
    protected PreExecuteHandler successor;

    public abstract void apply(Context context);

    public void setSuccessor(PreExecuteHandler successor) {
        this.successor = successor;
    }

    public PreExecuteHandler getSuccessor() {
        return successor;
    }
}