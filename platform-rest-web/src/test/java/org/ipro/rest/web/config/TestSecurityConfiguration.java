package org.ipro.rest.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/** Minimal managed Security stack (HttpSecurity/WebSecurityConfiguration) for context tests. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class TestSecurityConfiguration {
}
