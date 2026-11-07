package org.ipro.form;

import org.ipro.crud.ServiceLocator;
import org.ipro.form.builder.FormTypeCustomization;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SelectionFormAssemblerLazyFamilyTest {
    @Configuration(proxyBeanMethods = false) static class Config {
        @Bean SelectionFormAssembler selectionFormAssembler() {
            return new SelectionFormAssembler(new MetadataResolver(), mock(ServiceLocator.class));
        }
        @Bean FormTypeCustomization familyDependingOnSelection(SelectionFormAssembler assembler) {
            return type -> false;
        }
    }
    @Test void familyMayDependOnSelectionAssemblerWithoutAnInitializationCycle() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SelectionFormAssembler.class).hasSingleBean(FormTypeCustomization.class);
        });
    }
}
