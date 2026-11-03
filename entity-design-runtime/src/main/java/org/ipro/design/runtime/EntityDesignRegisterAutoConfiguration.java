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
    public RegisterFactsSource registerFactsSource(ObjectProvider<RegisterRegistry> registers,
            ObjectProvider<org.ipro.register.RegisterKeyWrites> writes,
            ObjectProvider<org.ipro.register.RegisterReaderFactory> readers,
            ObjectProvider<org.ipro.register.RegisterNamedOperations> operations,
            ObjectProvider<org.ipro.register.RegisterKeyWriteGuardInstaller> guards,
            ObjectProvider<org.ipro.register.RecorderOrderClockBootstrap> clock) {
        RegisterRegistry registry = registers.getIfAvailable();
        return registry == null ? null : new RegisterRegistryFactsSource(registry, () -> writes.getIfAvailable() != null
            && readers.getIfAvailable() != null && operations.getIfAvailable() != null
            && guards.getIfAvailable() != null && clock.getIfAvailable() != null);
    }
}
