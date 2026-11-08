package com.example.generated.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Backend bootstrap for register C. Scans the generated register and the consumer-owned Item
 * reference target; platform infrastructure is supplied exclusively by installed JARs.
 */
@SpringBootApplication(scanBasePackages = "com.example.generated")
@EntityScan("com.example")
public class GenerationHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(GenerationHarnessApplication.class, args);
    }
}
