package com.pawwork.opencode.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Demo application: exposes the OpenCode Spring Boot starter as an OpenAPI-documented REST API.
 *
 * <p>Swagger UI: {@code http://localhost:8080/swagger-ui.html} —
 * raw spec: {@code http://localhost:8080/v3/api-docs}.</p>
 */
@SpringBootApplication
@EnableAsync
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
