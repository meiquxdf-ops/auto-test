package com.atest;

import com.atest.config.AtestProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AtestProperties.class)
public class AtestApplication {

    public static void main(String[] args) {
        SpringApplication.run(AtestApplication.class, args);
    }
}
