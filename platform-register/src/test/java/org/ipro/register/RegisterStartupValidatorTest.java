package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.cfg.Configuration;
import org.ipro.metadata.ManagedEntityCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
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
            var registers = context.getBean(RegisterRegistry.class);
            assertThat(registers.descriptors()).singleElement().satisfies(descriptor ->
                assertThat(descriptor.entityType()).isEqualTo(ValidRegister.class));
            assertThat(registers.require(ValidRegister.class)).isSameAs(registers.require(ValidRegister.class));
            assertThat(registers.find(OrdinaryEntity.class)).isEmpty();
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> registers.require(MutableKeyRegister.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("managed-регистром");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> registers.descriptors().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void rejectsAMutableKeyWithoutAnyReader() {
        context(MutableKeyRegister.class).run(context -> {
            assertThat(context).hasFailed();
            // D-R S2: этот контекст грузит только read-конфигурацию модуля. Отказ приходит от
            // RegisterStartupValidator напрямую, а не обёрнутым в ошибку создания бина:
            // охранник mapping'а, который звал initialize() первым, уехал в key-write-конфигурацию
            // вместе со своей частью среза. Гарантия не ослабла — контекст не поднимается, — и
            // проверка смотрит на корень цепочки, чтобы остаться верной и для обёрнутого случая
            // (полный прикладной контекст создаёт охранник и получает BeanCreationException).
            assertThat(rootCause(context.getStartupFailure()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MutableKeyRegister.class.getName())
                .hasMessageContaining("KEY_UPDATABLE")
                .hasMessageContaining("поле 'code'");
        });
    }

    /** Корень цепочки, включая сам throwable: без причины корнем является он сам. */
    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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

    @Test
    void duplicateRecorderCodeStopsStartupBeforeAnyReaderIsCreated() {
        context(DuplicateRecorderA.class, DuplicateRecorderB.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("уже объявлен");
        });
    }

    @Test
    void recorderWithNonDocumentKindStopsStartup() {
        context(NonDocumentRecorder.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("DOCUMENT");
        });
    }

    /**
     * D-R S1: реестр объявлений и сама проверка уехали в {@code platform-register}, поэтому контекст
     * собирает их так же, как приложение, — модульной авто-конфигурацией, а не своим {@code @Import}.
     * Разница не косметическая: этот тест остаётся <em>приёмочным для приложения</em> («декларации
     * падают громко в контексте, собранном прикладной конфигурацией»), тогда как тесты движка
     * переезжают вместе со своими срезами. Если бы реестр приходил сюда импортом, тест перестал бы
     * проверять именно то, за что отвечает приложение, — композицию своей конфигурации с модульной.
     */
    private static ApplicationContextRunner context(Class<?>... entities) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                org.ipro.register.config.RegisterReadConfiguration.class))
            .withUserConfiguration(CatalogConfiguration.class)
            .withBean(org.ipro.metadata.MetadataResolver.class, org.ipro.metadata.MetadataResolver::new)
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                () -> org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(jakarta.persistence.EntityManager.class,
                () -> org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class))
            .withBean(jakarta.validation.Validator.class,
                () -> org.mockito.Mockito.mock(jakarta.validation.Validator.class))
            .withBean(org.ipro.rls.RlsPolicyEnforcer.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.RlsPolicyEnforcer.class))
            .withBean(org.ipro.rls.RlsFilterActivator.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.RlsFilterActivator.class))
            .withBean(org.ipro.rls.c5.C5OperationPermissionRegistry.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.c5.C5OperationPermissionRegistry.class))
            .withBean(org.ipro.rls.c5.C5PermissionDecisionService.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.c5.C5PermissionDecisionService.class))
            .withBean(org.ipro.rls.RlsDimensionRegistry.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.RlsDimensionRegistry.class))
            .withBean(org.ipro.rls.RlsCurrentUser.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.RlsCurrentUser.class))
            .withBean(org.ipro.rls.AccessService.class,
                () -> org.mockito.Mockito.mock(org.ipro.rls.AccessService.class))
            .withBean(org.ipro.data.EntityDataAccess.class,
                () -> org.mockito.Mockito.mock(org.ipro.data.EntityDataAccess.class))
            .withBean(org.ipro.lifecycle.EntityLifecycleRegistry.class,
                () -> org.mockito.Mockito.mock(org.ipro.lifecycle.EntityLifecycleRegistry.class))
            .withBean(org.ipro.events.EntityEventPublisher.class,
                () -> org.mockito.Mockito.mock(org.ipro.events.EntityEventPublisher.class))
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

    /**
     * D-R S4: {@code @Import} монолитной {@code RegisterAutoConfiguration} снят вместе с самой
     * конфигурацией (её заменили срезовые S1–S3). Он держался в тесте дольше класса: сборка без
     * {@code clean} видела стейл — {@code target/classes} прошлой сборки, — и ссылка на удалённый
     * тип не мешала зелёному прогону. Read-конфигурация приходит сюда авто-конфигурацией (см.
     * {@link #context}), а этот класс остаётся только вкладом приложения в композицию.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
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
        @Column(name = "period_date", nullable = false, updatable = false) LocalDateTime period;
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

    @Entity(name = "StartupDuplicateRecorderA")
    @Table(name = "startup_duplicate_recorder_a")
    @RecorderType("startup-duplicate-recorder")
    @org.ipro.metadata.annotation.EntityMetadata(kind = org.ipro.metadata.annotation.EntityKind.DOCUMENT)
    static class DuplicateRecorderA extends org.ipro.crud.BaseEntity { }

    @Entity(name = "StartupDuplicateRecorderB")
    @Table(name = "startup_duplicate_recorder_b")
    @RecorderType("startup-duplicate-recorder")
    @org.ipro.metadata.annotation.EntityMetadata(kind = org.ipro.metadata.annotation.EntityKind.DOCUMENT)
    static class DuplicateRecorderB extends org.ipro.crud.BaseEntity { }

    @Entity(name = "StartupNonDocumentRecorder")
    @Table(name = "startup_non_document_recorder")
    @RecorderType("startup-non-document")
    @org.ipro.metadata.annotation.EntityMetadata
    static class NonDocumentRecorder extends org.ipro.crud.BaseEntity { }
}
