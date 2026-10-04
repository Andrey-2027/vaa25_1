package org.ip.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.cfg.Configuration;
import org.ipro.metadata.ManagedEntityCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Настоящий JPA metamodel; каждая fixture имеет корректный Hibernate mapping и @Id. */
class RegisterStartupValidatorTest {

    @Test
    void acceptsAValidRegisterWithoutAReaderAndIgnoresOrdinaryEntities() {
        context(ValidRegister.class, OrdinaryEntity.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RegisterStartupValidator.class);
            assertThat(context.getBean(ManagedEntityCatalog.class).managedEntityClasses())
                .containsExactlyInAnyOrder(ValidRegister.class, OrdinaryEntity.class);
            assertThat(context).doesNotHaveBean(RegisterReader.class);
        });
    }

    @Test
    void rejectsAMutableKeyWithoutAnyReader() {
        context(MutableKeyRegister.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MutableKeyRegister.class.getName())
                .hasMessageContaining("KEY_UPDATABLE")
                .hasMessageContaining("поле 'code'");
        });
    }

    @Test
    void reportsAllBrokenRegistersInDeterministicOrder() {
        context(MutableKeyRegister.class, MissingResourceRegister.class).run(context -> {
            assertThat(context).hasFailed();
            String message = context.getStartupFailure().getMessage();
            assertThat(message).contains("FIELD_MISSING", "поле 'missing'", "KEY_UPDATABLE");
            assertThat(message.indexOf(MissingResourceRegister.class.getName()))
                .isLessThan(message.indexOf(MutableKeyRegister.class.getName()));
        });
    }

    @Test
    void acceptsAPersistenceUnitWithoutRegisters() {
        context(OrdinaryEntity.class).run(context -> assertThat(context).hasNotFailed());
    }

    private static ApplicationContextRunner context(Class<?>... entities) {
        return new ApplicationContextRunner().withUserConfiguration(CatalogConfiguration.class)
            .withBean(EntityManagerFactory.class, () -> factory(entities),
                definition -> definition.setDestroyMethodName("close"));
    }

    private static EntityManagerFactory factory(Class<?>... entities) {
        Configuration configuration = new Configuration()
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:register-startup-" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        for (Class<?> entity : entities) {
            configuration.addAnnotatedClass(entity);
        }
        return configuration.buildSessionFactory();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import(RegisterStartupValidator.class)
    static class CatalogConfiguration {
        @Bean
        ManagedEntityCatalog managedEntityCatalog(EntityManagerFactory factory) {
            return new ManagedEntityCatalog(factory);
        }
    }

    @Entity(name = "StartupValidRegister")
    @Table(name = "startup_valid", uniqueConstraints = @UniqueConstraint(columnNames = {"code", "period_date"}))
    @RegisterMetadata(dimensions = "code", period = "period", resources = "value")
    static class ValidRegister {
        @Id Long id;
        @Column(name = "code", nullable = false, updatable = false) String code;
        @Column(name = "period_date", nullable = false, updatable = false) LocalDate period;
        @Column(name = "resource_value") int value;
    }

    @Entity(name = "StartupMutableKeyRegister")
    @Table(name = "startup_mutable", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
    @RegisterMetadata(dimensions = "code", resources = "value")
    static class MutableKeyRegister {
        @Id Long id;
        @Column(name = "code", nullable = false) String code;
        @Column(name = "resource_value") int value;
    }

    @Entity(name = "StartupMissingResourceRegister")
    @Table(name = "startup_missing", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
    @RegisterMetadata(dimensions = "code", resources = "missing")
    static class MissingResourceRegister {
        @Id Long id;
        @Column(name = "code", nullable = false, updatable = false) String code;
        @Column(name = "resource_value") int value;
    }

    @Entity(name = "StartupOrdinaryEntity")
    @Table(name = "startup_ordinary")
    static class OrdinaryEntity {
        @Id Long id;
        String description;
    }
}
