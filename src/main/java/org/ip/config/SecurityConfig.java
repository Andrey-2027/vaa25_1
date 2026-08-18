package org.ip.config;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.ip.views.login.LoginView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/h2-console/**").permitAll()
            )
            // Превью отчётов (iframe с PDF из StreamResource) same-origin:
            // X-Frame-Options по умолчанию DENY блокировал бы встроенный фрейм.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            .with(VaadinSecurityConfigurer.vaadin(), configurer -> {
                configurer.loginView(LoginView.class);
            });
        return http.build();
    }
}
