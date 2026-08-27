package com.hjmicro.service;

import com.hjmicro.agent.service.SystemService;

import java.nio.file.Files;
import java.nio.file.Paths;

public class SystemServiceImpl implements SystemService {

    @Override
    public Boolean writeProperties(String key, String value) {
        // 写入配置文件 /etc/hjmicro/config.properties
        try {
            Files.write(Paths.get("/etc/hjmicro/config.properties"), (key + "=" + value).getBytes());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public Boolean writeEnv(String key, String value) {
        // 写入系统环境变量
        return null;
    }

}
