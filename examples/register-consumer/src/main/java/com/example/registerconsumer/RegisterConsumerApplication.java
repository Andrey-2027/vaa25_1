package com.example.registerconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * A2.1 external consumer: a backend-only application that declares its own catalog and two
 * independent registers. The register engine (registry, classification, readers, key commands,
 * guards, clock and C5 wiring) arrives from {@code platform-backend-starter} through
 * auto-configuration; this application supplies only principal/roles/auditor adapters.
 */
@SpringBootApplication
@EntityScan("com.example.registerconsumer.model")
@EnableTransactionManagement(proxyTargetClass = true, order = 0)
public class RegisterConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegisterConsumerApplication.class, args);
    }
}
