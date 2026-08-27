package com.atest.tcp;

import com.atest.common.Json;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire envelope, see docs/protocol.md.
 *
 * <pre>
 * {"v":1,"t":"req","id":42,"m":"hello","a":{}}
 * {"v":1,"t":"rsp","id":42,"ok":true,"r":{}}
 * {"v":1,"t":"rsp","id":42,"ok":false,"e":{"c":"busy","msg":"..."}}
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Envelope {

    public static final String T_REQ = "req";
    public static final String T_RSP = "rsp";

    public int v = 1;
    public String t;
    public Long id;
    public String m;
    public JsonNode a;
    public Boolean ok;
    public JsonNode r;
    public Err e;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Err {
        public String c;
        public String msg;

        public Err() {
        }

        public Err(String c, String msg) {
            this.c = c;
            this.msg = msg;
        }
    }

    public static Envelope req(long id, String method, Object args) {
        Envelope env = new Envelope();
        env.t = T_REQ;
        env.id = id;
        env.m = method;
        env.a = args == null ? Json.obj() : Json.toNode(args);
        return env;
    }

    public static Envelope ok(Long id, Object result) {
        Envelope env = new Envelope();
        env.t = T_RSP;
        env.id = id;
        env.ok = Boolean.TRUE;
        env.r = result == null ? Json.obj() : Json.toNode(result);
        return env;
    }

    public static Envelope error(Long id, String code, String message) {
        Envelope env = new Envelope();
        env.t = T_RSP;
        env.id = id;
        env.ok = Boolean.FALSE;
        env.e = new Err(code, message);
        return env;
    }

    public boolean isRequest() {
        return T_REQ.equals(t);
    }

    public boolean isResponse() {
        return T_RSP.equals(t);
    }

    public boolean isOk() {
        return Boolean.TRUE.equals(ok);
    }

    public String errorCode() {
        return e == null ? null : e.c;
    }

    public String errorMessage() {
        return e == null ? null : e.msg;
    }

    public JsonNode args() {
        return a == null ? Json.obj() : a;
    }

    public JsonNode result() {
        return r == null ? Json.obj() : r;
    }
}
