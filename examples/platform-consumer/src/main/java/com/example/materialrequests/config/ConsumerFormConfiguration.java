package com.example.materialrequests.config;

import org.ipro.form.FormSaveHandler;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.identity.IdentifiableEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConsumerFormConfiguration {
    /** Required application SPI. All consumer forms use the platform aggregate adapter. */
    @Bean
    FormSaveHandler<IdentifiableEntity<Long>> formSaveHandler(MetadataDrivenItemFormSaveAdapter adapter) {
        return adapter::save;
    }
}
