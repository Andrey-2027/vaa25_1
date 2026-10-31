package org.ipro.design.runtime;

import org.ipro.design.RuntimeDesignSnapshot.RegisterFacts;
import org.ipro.register.RegisterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Optional register bridge. The class is only loaded when the register artifact is present, and the
 * bean is absent when no validated {@link RegisterRegistry} exists. A missing source therefore stays a
 * missing fact instead of an empty registry; the projection reports it explicitly.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.ipro.register.RegisterRegistry")
public class EntityDesignRegisterAutoConfiguration {
    @Bean
    public RegisterFactsSource registerFactsSource(ObjectProvider<RegisterRegistry> registers) {
        RegisterRegistry registry = registers.getIfAvailable();
        return registry == null ? null : new RegisterRegistryFactsSource(registry);
    }
}
