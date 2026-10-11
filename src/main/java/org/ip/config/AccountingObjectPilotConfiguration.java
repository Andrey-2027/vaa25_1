package org.ip.config;

import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadableIdsResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The pilot fixture runs before a web session exists; use the supported uncached resolver seam. */
@Configuration(proxyBeanMethods = false)
@Profile("accounting-pilot & !prod")
public class AccountingObjectPilotConfiguration {
    @Bean
    RlsReadableIdsResolver accountingPilotReadableIdsResolver(AccessService access) {
        return access::getReadableIds;
    }
}
