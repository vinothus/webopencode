package com.pawwork.opencode.demo;

import com.pawwork.opencode.config.TrustStoreSupport;
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
        // Before anything else: a VPN that re-signs HTTPS breaks every outbound call, and the
        // truststore has to be set before the first HTTP client is built.
        String trustStore = TrustStoreSupport.configure();
        if (trustStore != null) {
            System.out.println("[opencode] using truststore " + trustStore);
        }
        SpringApplication.run(DemoApplication.class, args);
    }
}
