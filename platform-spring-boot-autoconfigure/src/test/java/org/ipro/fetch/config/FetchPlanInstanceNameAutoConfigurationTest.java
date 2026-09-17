package org.ipro.fetch.config;

import org.ipro.fetch.ManagedEntityTypes;
import org.ipro.fetch.instance.InstanceNameBridgeInstaller;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Граница C3 (ADR-0006): компоненты FetchPlan/InstanceName подключаются отдельной
 * автоконфигурацией, зависят только от {@link ManagedEntityCatalog} и не выполняют
 * собственный classpath scan.
 */
class FetchPlanInstanceNameAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FetchPlanInstanceNameAutoConfiguration.class));

    @Test
    void registersManagedEntityTypesFromCatalog() {
        ManagedEntityCatalog catalog = mock(ManagedEntityCatalog.class);
        when(catalog.managedEntityClasses()).thenReturn(Set.of(String.class));

        runner.withBean(ManagedEntityCatalog.class, () -> catalog)
            .run(context -> {
                assertThat(context).hasSingleBean(ManagedEntityTypes.class);
                assertThat(context.getBean(ManagedEntityTypes.class).all())
                    .containsExactly(String.class);
            });
    }

    @Test
    void backsOffWhenManagedEntityCatalogIsAbsent() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ManagedEntityTypes.class));
    }

    /**
     * Резолвер имён строится в той же границе C3. Каталог намеренно пуст: тест проверяет
     * wiring бина, а не глобальное состояние статического моста (его наполняет реальный
     * контекст приложения).
     */
    @Test
    void registersInstanceNameResolverWhenMetadataIsPresent() {
        ManagedEntityCatalog catalog = mock(ManagedEntityCatalog.class);
        when(catalog.managedEntityClasses()).thenReturn(Set.of());

        runner.withBean(ManagedEntityCatalog.class, () -> catalog)
            .withBean(MetadataResolver.class, MetadataResolver::new)
            .run(context -> assertThat(context).hasSingleBean(InstanceNameResolver.class));
    }

    /**
     * Metadata есть, каталога сущностей нет: раньше условие бина резолвера проверяло только
     * {@code MetadataResolver}, поэтому инъекция параметра {@code ManagedEntityTypes}
     * валила ОБЩИЙ контекст {@code UnsatisfiedDependencyException} вместо backoff.
     */
    @Test
    void backsOffWhenCatalogIsAbsentEvenIfMetadataIsPresent() {
        runner.withBean(MetadataResolver.class, MetadataResolver::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ManagedEntityTypes.class);
                assertThat(context).doesNotHaveBean(InstanceNameResolver.class);
                assertThat(context).doesNotHaveBean(InstanceNameBridgeInstaller.class);
            });
    }

    @Test
    void backsOffWhenMetadataResolverIsAbsent() {
        ManagedEntityCatalog catalog = mock(ManagedEntityCatalog.class);
        when(catalog.managedEntityClasses()).thenReturn(Set.of());

        runner.withBean(ManagedEntityCatalog.class, () -> catalog)
            .run(context -> assertThat(context).doesNotHaveBean(InstanceNameResolver.class));
    }

    @Test
    void configurationDoesNotComponentScanApplicationPackage() {
        assertThat(FetchPlanInstanceNameAutoConfiguration.class
            .isAnnotationPresent(ComponentScan.class)).isFalse();
    }
}
