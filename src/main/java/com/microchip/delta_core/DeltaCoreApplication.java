package com.microchip.delta_core;

import com.microchip.delta_core.config.DeltaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(DeltaProperties.class)
public class DeltaCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeltaCoreApplication.class, args);
    }
}
