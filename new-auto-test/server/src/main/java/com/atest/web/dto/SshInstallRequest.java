package com.atest.web.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 「SSH 代装」请求。
 *
 * <p>刻意不加 toString / 不整体打日志：password、privateKey、passphrase 只在
 * 本次请求的内存里用完即弃，不落 H2、不进日志。
 */
@Getter
@Setter
public class SshInstallRequest {

    /** 目标机地址（主机名或 IPv4） */
    private String host;
    private int port = 22;
    private String user = "root";

    /** password | key */
    private String authType = "password";
    private String password;
    /** PEM / OpenSSH 格式私钥全文 */
    private String privateKey;
    /** 私钥口令，可空 */
    private String passphrase;

    /** true 时跳过主机指纹校验（内网 v1，页面上有醒目警告）；默认 accept-new */
    private boolean skipHostKeyCheck;

    /** 安装参数，与 install.sh 一致 */
    private String tag;
    /** Server 的 Agent TCP 地址 host:9800 */
    private String server;
    private int concurrency = 1;
}
