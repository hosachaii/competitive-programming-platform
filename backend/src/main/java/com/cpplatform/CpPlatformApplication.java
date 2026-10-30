package com.cpplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CpPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(CpPlatformApplication.class, args);
    }
}
