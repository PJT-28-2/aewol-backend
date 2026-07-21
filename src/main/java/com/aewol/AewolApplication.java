package com.aewol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AewolApplication {

    public static void main(String[] args) {
        SpringApplication.run(AewolApplication.class, args);
    }
}
