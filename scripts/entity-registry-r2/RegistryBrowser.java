package org.ipro.registryfixture;

import com.vaadin.flow.spring.annotation.EnableVaadin;
import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Database-free browser stand. Deliberately outside the application package. */
@SpringBootApplication(excludeName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@EnableVaadin("org.ipro.registryfixture")
public class RegistryBrowser implements AppShellConfigurator {
    public static void main(String[] args) { SpringApplication.run(RegistryBrowser.class, args); }
}
