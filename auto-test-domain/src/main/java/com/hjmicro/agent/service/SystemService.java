package com.hjmicro.agent.service;

import com.hjmicro.ClientService;
import com.hjmicro.ServiceInterface;

@ClientService
public interface SystemService  extends ServiceInterface {

    /**
     * 写入配置文件
     * @param key
     * @param value
     * @return
     */
    Boolean writeProperties(String key, String value);


    /**
     * 写入环境变量
     */
    Boolean writeEnv(String key, String value);




}
