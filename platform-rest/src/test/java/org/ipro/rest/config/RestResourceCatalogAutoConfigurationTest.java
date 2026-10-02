package org.ipro.rest.config;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceCatalogException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestResources.path;
import static org.ipro.rest.api.RestResources.publish;
import static org.mockito.Mockito.mock;

class RestResourceCatalogAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestResourceCatalogAutoConfiguration.class));

    @Test
    void installsEmptyCatalogWithoutRequiringJpaWhenNoDeclarationsExist() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RestResourceCatalog.class).resources()).isEmpty();
        });
    }

    @Test
    void failsNamedAtStartupWhenDeclarationsHaveNoBackend() {
        contextRunner.withUserConfiguration(DeclarationConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(RestResourceCatalogException.class)
                .hasStackTraceContaining("BACKEND_CONTEXT_REQUIRED")
                .hasStackTraceContaining("restDefinition");
        });
    }

    @Test
    void rejectsNonSingletonDeclarationsByBeanNameBeforeBuildingTheCatalog() {
        contextRunner.withUserConfiguration(PrototypeDeclarationConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(RestResourceCatalogException.class)
                .hasStackTraceContaining("NON_SINGLETON_RESOURCE")
                .hasStackTraceContaining("prototypeRestDefinition");
        });
    }

    @Test
    void discoversFactoryBeanDeclarationsWhoseProductTypeRequiresInitialization() {
        contextRunner.withUserConfiguration(OpaqueFactoryDeclarationConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(RestResourceCatalogException.class)
                .hasStackTraceContaining("BACKEND_CONTEXT_REQUIRED")
                .hasStackTraceContaining("opaqueRestDefinition");
        });
    }

    @Test
    void rejectsMultiplePersistenceUnitsEvenWhenOneIsPrimary() {
        contextRunner.withUserConfiguration(DeclarationConfiguration.class, TwoFactoryConfiguration.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(RestResourceCatalogException.class)
                    .hasStackTraceContaining("AMBIGUOUS_PERSISTENCE_UNIT")
                    .hasStackTraceContaining("@Primary does not select");
            });
    }

    @Test
    void treatsFactoryAliasesAsOnePersistenceUnit() {
        contextRunner.withUserConfiguration(DeclarationConfiguration.class, AliasedFactoryConfiguration.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(RestResourceCatalogException.class)
                    .hasStackTraceContaining("EntityDescriptorCatalog");
                assertThat(context.getStartupFailure().getMessage())
                    .doesNotContain("AMBIGUOUS_PERSISTENCE_UNIT");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclarationConfiguration {
        @Bean
        RestResourceDefinition<Fixture> restDefinition() {
            return publish("fixture", 1, Fixture.class)
                .field("id", RestFieldType.LONG, path("id"), NOT_NULL)
                .field("code", RestFieldType.STRING, path("code"), NOT_NULL)
                .listFields("id", "code").listDefaultFields("id")
                .detailFields("id", "code").detailDefaultFields("id")
                .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrototypeDeclarationConfiguration {
        @Bean
        @Scope("prototype")
        RestResourceDefinition<Fixture> prototypeRestDefinition() {
            return publish("fixture", 1, Fixture.class)
                .field("id", RestFieldType.LONG, path("id"), NOT_NULL)
                .field("code", RestFieldType.STRING, path("code"), NOT_NULL)
                .listFields("id", "code").listDefaultFields("id")
                .detailFields("id", "code").detailDefaultFields("id")
                .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OpaqueFactoryDeclarationConfiguration {
        @Bean
        FactoryBean<Object> opaqueRestDefinition() {
            return new OpaqueRestDefinitionFactoryBean();
        }
    }

    static class OpaqueRestDefinitionFactoryBean implements FactoryBean<Object>, InitializingBean {
        private Class<?> objectType;

        @Override
        public void afterPropertiesSet() {
            objectType = RestResourceDefinition.class;
        }

        @Override
        public Object getObject() {
            return publish("fixture", 1, Fixture.class)
                .field("id", RestFieldType.LONG, path("id"), NOT_NULL)
                .field("code", RestFieldType.STRING, path("code"), NOT_NULL)
                .listFields("id", "code").listDefaultFields("id")
                .detailFields("id", "code").detailDefaultFields("id")
                .build();
        }

        @Override
        public Class<?> getObjectType() {
            return objectType;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoFactoryConfiguration {
        @Bean
        @Primary
        EntityManagerFactory primaryEntityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }

        @Bean
        EntityManagerFactory secondaryEntityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }

        @Bean
        EntityDescriptorCatalog entityDescriptorCatalog() {
            return mock(EntityDescriptorCatalog.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AliasedFactoryConfiguration {
        @Bean(name = {"entityManagerFactory", "entityManagerFactoryAlias"})
        EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }
    }

    static class Fixture { }
}
