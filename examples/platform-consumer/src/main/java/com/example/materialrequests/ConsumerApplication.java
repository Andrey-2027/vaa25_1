package com.example.materialrequests;

import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EntityScan("com.example.materialrequests.model")
@EnableJpaRepositories("com.example.materialrequests.repository")
@EnableTransactionManagement(proxyTargetClass = true, order = 0)
public class ConsumerApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
