package org.ipro.e5browser;

import com.vaadin.flow.spring.annotation.EnableVaadin;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EnableVaadin("org.ipro.e5browser")
@EntityScan("com.example.generated")
@Theme("default")
public class BrowserApplication implements AppShellConfigurator {
    public static void main(String[] args) { SpringApplication.run(BrowserApplication.class,args); }
}
