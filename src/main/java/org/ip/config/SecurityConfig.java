package org.ip.config;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.ip.views.login.LoginView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    /**
     * Отдельная stateless-цепочка для REST-эндпоинта JPQL-превью (фаза 3 плана
     * ReportJR-Jpql-Plan.md): HTTP Basic, без сессий, CSRF отключён — безопасно
     * ИМЕННО потому, что транспорт basic (каждый запрос несёт учётные данные),
     * а не cookie-сессия. Доступ всё равно требует права REPORTS:JPQL_PREVIEW
     * (проверяется в контроллере, 403 без гранта).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain jpqlPreviewApiChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/report-jpql/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/h2-console/**").permitAll()
                // Страница ошибок Boot: без этого любая ошибка сервера маскируется 403
                .requestMatchers("/error").permitAll()
                // Веб-дизайнер UReport: требует аутентификации (VaadinSecurityConfigurer
                // по умолчанию не покрывает не-Vaadin URL)
                .requestMatchers("/ureport/**").authenticated()
            )
            // Превью отчётов (iframe с PDF из StreamResource) same-origin:
            // X-Frame-Options по умолчанию DENY блокировал бы встроенный фрейм.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            // Дизайнер UReport шлёт все операции (loadReport/saveReportFile и т.д.)
            // POST-запросами без CSRF-токена — исключаем его URL из CSRF-проверки.
            // Аутентификация на /ureport/** при этом сохраняется.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/ureport/**"))
            .with(VaadinSecurityConfigurer.vaadin(), configurer -> {
                configurer.loginView(LoginView.class);
            });
        return http.build();
    }
}
