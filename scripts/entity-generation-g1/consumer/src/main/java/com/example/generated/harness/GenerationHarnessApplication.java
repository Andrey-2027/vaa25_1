package com.example.generated.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Minimal backend bootstrap for the G1.2 harness. The generated root is deliberately a sibling
 * package: the harness never declares a business repository, service or entity of its own.
 */
@SpringBootApplication
@EntityScan("com.example.generated")
public class GenerationHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(GenerationHarnessApplication.class, args);
    }
}
