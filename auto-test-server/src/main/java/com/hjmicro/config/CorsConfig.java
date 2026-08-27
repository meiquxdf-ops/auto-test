package com.hjmicro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")  // 对所有的路径进行CORS配置
                .allowedOrigins("http://localhost:5173")  // 这里替换成你的前端应用的 URL
                .allowedMethods("*")  // 允许所有的请求方法访问该跨域资源服务器，如：POST、GET、PUT、DELETE等
                .allowedHeaders("*")  // 允许所有的请求header访问，可以自定义设置任何请求头信息
                .allowCredentials(true)  // 允许发送Cookie
                .maxAge(3600);  // 预检请求的有效期，单位为秒。设置maxage，可以避免每次都发出预检请求
    }
}
