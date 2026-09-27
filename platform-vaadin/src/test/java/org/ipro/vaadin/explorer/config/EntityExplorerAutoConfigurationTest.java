package org.ipro.vaadin.explorer.config;

import org.ipro.autoconfigure.PlatformProperties;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * D3.5.5: подсистема Entity Explorer отступает ({@code backoff}), когда её коллабораторов в
 * контексте нет, вместо падения старта с {@code UnsatisfiedDependency}.
 *
 * <p><b>Почему это забор, а не украшение.</b> Класс объявляет три бина, каждый из которых
 * требует чужих бинов (метаданные, реестр форм, нумерация, RLS). До шага условия проверяли только
 * «пользователь не объявил свой бин», поэтому отсутствие, например, {@code MetadataResolver}
 * приводило к падению контекста — при том что сам UI-модуль к метаданным необязателен. Ошибка
 * выглядит как «платформа сломалась», хотя на самом деле не выполнено условие применимости
 * подсистемы.</p>
 *
 * <p><b>Условие — на классе, а не на методе, и это осознанно.</b> {@code @ConditionalOnBean}
 * внутри одной конфигурации оценивается в момент регистрации методов, а не инициализации бинов:
 * условие вида «нужен {@code EntitySummaryAssembler}», объявленный <i>в этом же классе</i>,
 * зависело бы от порядка объявления методов. Такой порядок невидим в diff'е и меняется при
 * перестановке строк — то есть это ровно та хрупкость, ради устранения которой шаг и делается.
 * Класс-уровневое условие перечисляет только чужие типы и потому от порядка не зависит.</p>
 */
class EntityExplorerAutoConfigurationTest {

    /** Чужие типы, от которых зависит вся подсистема: ни одного из них конфигурация не создаёт. */
    private static ApplicationContextRunner withCollaborators() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EntityExplorerAutoConfiguration.class))
            .withBean(MetadataResolver.class, () -> mock(MetadataResolver.class))
            .withBean(FormRegistry.class, FormRegistry::new)
            .withBean(EntityDescriptorCatalog.class, () -> mock(EntityDescriptorCatalog.class))
            .withBean(FormRouteCatalog.class, () -> mock(FormRouteCatalog.class))
            .withBean(EntityLifecycleRegistry.class,
                () -> new EntityLifecycleRegistry(java.util.List.of()))
            .withBean(SectionMetadataRegistry.class, () -> mock(SectionMetadataRegistry.class))
            .withBean(ReferenceIndex.class, () -> mock(ReferenceIndex.class))
            .withBean(NumberingMetadataRegistry.class,
                () -> mock(NumberingMetadataRegistry.class))
            .withBean(SubsystemRegistry.class, () -> mock(SubsystemRegistry.class))
            .withBean(RlsDimensionRegistry.class, () -> mock(RlsDimensionRegistry.class))
            .withBean(PlatformProperties.class, () -> mock(PlatformProperties.class));
    }

    /**
     * Нет метаданных и реестра форм — подсистема обязана просто не подняться. Ни падения, ни
     * частичной проводки: конфигурация либо даёт все три бина, либо ни одного.
     */
    @Test
    void backsOffWhenCollaboratorsAreMissing() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EntityExplorerAutoConfiguration.class))
            .run(context -> {
                assertThat(context)
                    .as("отсутствие метаданных — не ошибка wiring: подсистема чтения метаданных"
                        + " неприменима, и это должно быть видно как отсутствие бинов, а не как"
                        + " UnsatisfiedDependency на старте")
                    .hasNotFailed();
                assertThat(context).doesNotHaveBean(EntitySummaryAssembler.class);
                assertThat(context).doesNotHaveBean(SubsystemSummaryAssembler.class);
                assertThat(context).doesNotHaveBean(FacetResolver.class);
            });
    }

    /**
     * Обратная сторона: забор не должен быть вакуумным — при наличии коллабораторов бины обязаны
     * появиться. Иначе «backoff работает» было бы верно и для конфигурации, которая не работает
     * вообще.
     */
    @Test
    void registersSubsystemWhenCollaboratorsArePresent() {
        withCollaborators().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FacetResolver.class);
            assertThat(context).hasSingleBean(EntitySummaryAssembler.class);
            assertThat(context).hasSingleBean(SubsystemSummaryAssembler.class);
        });
    }

    @Test
    void missingRouteCatalogDisablesExplorerButMissingStartupCheckDoesNot() {
        withCollaborators()
            .run(context -> {
                assertThat(context).hasNotFailed();
                EntitySummaryAssembler assembler = context.getBean(EntitySummaryAssembler.class);
                assertThat(assembler.unassignedDiagnostics())
                    .singleElement()
                    .satisfies(row -> assertThat(row.value().value())
                        .isEqualTo("Стартовая проверка не подключена"));
            });

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EntityExplorerAutoConfiguration.class))
            .withBean(MetadataResolver.class, () -> mock(MetadataResolver.class))
            .withBean(FormRegistry.class, FormRegistry::new)
            .withBean(EntityDescriptorCatalog.class, () -> mock(EntityDescriptorCatalog.class))
            .withBean(EntityLifecycleRegistry.class,
                () -> new EntityLifecycleRegistry(java.util.List.of()))
            .withBean(SectionMetadataRegistry.class, () -> mock(SectionMetadataRegistry.class))
            .withBean(ReferenceIndex.class, () -> mock(ReferenceIndex.class))
            .withBean(NumberingMetadataRegistry.class, () -> mock(NumberingMetadataRegistry.class))
            .withBean(SubsystemRegistry.class, () -> mock(SubsystemRegistry.class))
            .withBean(RlsDimensionRegistry.class, () -> mock(RlsDimensionRegistry.class))
            .withBean(PlatformProperties.class, () -> mock(PlatformProperties.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(EntitySummaryAssembler.class);
            });
    }

    /** {@code FacetResolver} остаётся точкой расширения и при полном наборе коллабораторов. */
    @Test
    void applicationFacetResolverReplacesTheNoOpDefault() {
        FacetResolver custom = mock(FacetResolver.class);

        withCollaborators()
            .withBean(FacetResolver.class, () -> custom)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(FacetResolver.class);
                assertThat(context.getBean(FacetResolver.class)).isSameAs(custom);
            });
    }
}
