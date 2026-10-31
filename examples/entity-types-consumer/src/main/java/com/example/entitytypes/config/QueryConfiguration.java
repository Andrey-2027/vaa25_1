package com.example.entitytypes.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jackson 2 bridge required by platform-query; Boot 4 configures Jackson 3. */
@Configuration(proxyBeanMethods = false)
public class QueryConfiguration {
    @Bean
    public ObjectMapper reportObjectMapper() { return new ObjectMapper(); }
}
