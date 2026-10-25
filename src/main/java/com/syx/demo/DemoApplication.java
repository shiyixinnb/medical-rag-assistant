package com.syx.demo;

import com.syx.demo.config.RedisPreflightChecker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DemoApplication.class);
        // 启动前先探测 Redis，避免 Jedis 那句误导性的 "Failed to connect to any host resolved for DNS name."
        app.addListeners(new RedisPreflightChecker());
        app.run(args);
    }

}
