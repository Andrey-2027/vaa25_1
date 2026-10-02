package org.ipro.vaadin.explorer.config;

import org.ipro.autoconfigure.PlatformProperties;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionProvenanceCatalog;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.config.FormAutoConfiguration;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.registry.FormRegistry;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.FetchPlanInspection;
import org.ipro.data.config.DataAccessAutoConfiguration;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.ipro.events.config.EventsAutoConfiguration;

/**
 * Auto-Configuration поверхности чтения метаданных — Entity Explorer
 * (пакет {@code org.ipro.metadata.explorer}) и шов переопределений надписей
 * ({@code org.ipro.metadata.facet}).
 *
 * <p>Бины не попадают в component-scan приложения (базовый пакет {@code org.ip}) —
 * регистрируются здесь, как остальные подсистемы платформы (см.
 * {@code org.ipro.metadata.config.MetadataAutoConfiguration}).</p>
 *
 * <p>{@link FacetResolver} — default no-op (роль 3, store-слой переопределений пока не
 * существует). Store-бин позже подставит своё переопределение.</p>
 *
 * <p><b>D3.5.5: backoff вместо {@code UnsatisfiedDependency} и явный порядок.</b> До шага условия
 * проверяли только «пользователь не объявил свой бин», поэтому отсутствие любого коллаборатора
 * (метаданные, реестр форм, нумерация, RLS) роняло старт контекста — при том что сам UI-модуль к
 * метаданным необязателен. Проверено тестом: без коллабораторов падало с
 * {@code No qualifying bean of type 'org.ipro.autoconfigure.PlatformProperties'}. Теперь подсистема
 * просто не поднимается.</p>
 *
 * <p><b>Почему условие на классе, а не на каждом методе.</b> {@code @ConditionalOnBean}
 * внутри одной конфигурации оценивается в момент регистрации методов: условие вида «нужен
 * {@code EntitySummaryAssembler}», объявленный <i>в этом же классе</i>, зависело бы от порядка
 * объявления методов — невидимой в diff'е характеристики, которая меняется при перестановке строк.
 * Класс-уровневое условие перечисляет только чужие типы и потому от порядка не зависит, зато даёт
 * согласованность: подсистема либо даёт все три бина, либо ни одного.</p>
 *
 * <p><b>Почему порядок объявлен явно.</b> Условие требует, чтобы определения коллабораторов уже
 * существовали. До шага порядок держался на алфавитном совпадении FQN
 * ({@code org.ipro.form…} раньше {@code org.ipro.vaadin…}) — случайный для требования признак,
 * который в D3.4 устраняли в {@code CrudAutoConfiguration}. Без явного {@code @AutoConfigureAfter}
 * добавленные условия молча выключили бы всю подсистему: модуль на месте, функция отсутствует.</p>
 */
@AutoConfiguration
@AutoConfigureAfter({MetadataAutoConfiguration.class, FormAutoConfiguration.class,
    DataAccessAutoConfiguration.class, EventsAutoConfiguration.class})
@ConditionalOnBean({
    PlatformProperties.class,
    MetadataResolver.class,
    FormRegistry.class,
    EntityDescriptorCatalog.class,
    FormRouteCatalog.class,
    EntityLifecycleRegistry.class,
    SectionMetadataRegistry.class,
    ReferenceIndex.class,
    NumberingMetadataRegistry.class,
    SubsystemRegistry.class,
    RlsDimensionRegistry.class,
    // Аспект действий (E3.2.0): без состава, исполнителей и происхождения карточка типа
    // молчала бы о том, что на типе действительно есть. Все три бина объявлены
    // FormAutoConfiguration — единственным местом, где видны три источника сборки.
    ActionRegistry.class,
    ActionHandlerRegistry.class,
    ActionProvenanceCatalog.class,
    // Аспект сценариев чтения (E3.2.0 шаг 2): план и его пути — факт модуля-владельца
    // (platform-core). Без инспекции карточка показывала бы тип без единого сценария —
    // то есть утверждала бы, что читать его нельзя, а не что факт не подключён.
    FetchPlanInspection.class
})
public class EntityExplorerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FacetResolver facetResolver() {
        return FacetResolver.none();
    }

    @Bean
    @ConditionalOnMissingBean
    public SubsystemSummaryAssembler subsystemSummaryAssembler(
            EntitySummaryAssembler entitySummaryAssembler,
            SubsystemRegistry subsystemRegistry,
            RlsDimensionRegistry rlsDimensionRegistry) {
        return new SubsystemSummaryAssembler(
            entitySummaryAssembler, subsystemRegistry, rlsDimensionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public EntitySummaryAssembler entitySummaryAssembler(
            PlatformProperties platformProperties,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver,
            EntityDescriptorCatalog descriptorCatalog,
            FormRouteCatalog formRouteCatalog,
            EntityLifecycleRegistry lifecycleRegistry,
            SectionMetadataRegistry sectionMetadataRegistry,
            ObjectProvider<MetadataConsistencyStartupCheck> startupCheck,
            ActionRegistry actionRegistry,
            ActionHandlerRegistry actionHandlerRegistry,
            ActionProvenanceCatalog actionProvenanceCatalog,
            FetchPlanInspection fetchPlanInspection,
            RlsDimensionRegistry rlsDimensionRegistry) {
        return new EntitySummaryAssembler(
            platformProperties.requiredSubsystemScanPackage(), metadataResolver, formRegistry,
            referenceIndex, numberingMetadataRegistry, subsystemRegistry, facetResolver,
            descriptorCatalog, formRouteCatalog, lifecycleRegistry, sectionMetadataRegistry,
            startupCheck.getIfAvailable(), actionRegistry, actionHandlerRegistry,
            actionProvenanceCatalog, fetchPlanInspection, rlsDimensionRegistry);
    }
}
