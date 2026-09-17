package org.ipro.fetch.config;

import org.ipro.fetch.ManagedEntityTypes;
import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.fetch.instance.InstanceNameBridgeInstaller;
import org.ipro.fetch.instance.InstanceNameProvider;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsDimensionValueLabelResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Отдельная конфигурационная граница C3 (FetchPlan + InstanceName), см. ADR-0006.
 *
 * <p>Компоненты C3 намеренно НЕ добавляются в {@link MetadataAutoConfiguration}:
 * metadata core обязан подниматься и работать без них. Конфигурация не импортирует и не
 * сканирует {@code org.ip} — набор сущностей приходит из {@link ManagedEntityCatalog},
 * единственного источника управляемых entity-классов.</p>
 *
 * <p>Здесь же подключается {@link FetchPlanRegistry}: построение registry валидирует
 * объявленные {@code @Lookup.fetch} пути и падает при ошибке конфигурации, поэтому
 * неверная декларация не может дожить до runtime.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(MetadataAutoConfiguration.class)
public class FetchPlanInstanceNameAutoConfiguration {

    @Bean
    @ConditionalOnBean(ManagedEntityCatalog.class)
    @ConditionalOnMissingBean
    public ManagedEntityTypes managedEntityTypes(ManagedEntityCatalog managedEntityCatalog) {
        return new ManagedEntityTypes(managedEntityCatalog);
    }

    /**
     * Платформенный источник отображаемого имени и анализатор состава имени. Создаётся
     * при старте: построение резолвера валидирует объявленные {@code @InstanceName} paths
     * и падает при ошибке конфигурации.
     *
     * <p>Бин всегда присутствует (без {@code @ConditionalOnMissingBean}): анализатор
     * состава имени нужен fetch-планам независимо от того, кто рендерит имя. Если
     * приложение объявляет свой {@link InstanceNameProvider}, он выигрывает у этого бина
     * как провайдер рендеринга — этим управляет {@link InstanceNameBridgeInstaller}, а не
     * отказ платформенного бина: иначе пользовательский провайдер унёс бы с собой и
     * анализ, и fetch-планы вернулись бы к null-границе.</p>
     *
     * <p>Условие — {@code ManagedEntityTypes}, а не только {@code MetadataResolver}:
     * ManagedEntityTypes создаётся лишь при наличии {@code ManagedEntityCatalog}.
     * Частичный контекст с metadata, но без каталога сущностей должен получить корректный
     * backoff, а не {@code UnsatisfiedDependencyException} при инъекции параметра.</p>
     */
    @Bean
    @ConditionalOnBean({ManagedEntityTypes.class, MetadataResolver.class})
    public InstanceNameResolver instanceNameResolver(ManagedEntityTypes managedEntityTypes,
                                                     MetadataResolver metadataResolver) {
        return new InstanceNameResolver(managedEntityTypes.all(), metadataResolver);
    }

    /**
     * Устанавливает статический {@link InstanceNameBridge} на время жизни контекста:
     * при старте регистрируется провайдер (пользовательский или платформенный) и
     * анализатор, при закрытии снимается ровно эта регистрация. Раньше установка жила в
     * @Bean-методе резолвера и не снималась никогда.
     */
    @Bean
    @ConditionalOnBean(InstanceNameResolver.class)
    public InstanceNameBridgeInstaller instanceNameBridgeInstaller(
            InstanceNameResolver instanceNameResolver,
            ObjectProvider<InstanceNameProvider> providers) {
        return new InstanceNameBridgeInstaller(instanceNameResolver, providers);
    }

    /**
     * Единый источник fetch-планов. Создаётся после {@link InstanceNameResolver}: к плану
     * сценария {@code LOOKUP}/{@code DETAIL} добавляются зависимости имени сущности.
     */
    @Bean
    @ConditionalOnBean({ManagedEntityTypes.class, InstanceNameResolver.class})
    @ConditionalOnMissingBean
    public FetchPlanRegistry fetchPlanRegistry(ManagedEntityTypes managedEntityTypes,
                                               MetadataResolver metadataResolver,
                                               InstanceNameResolver instanceNameResolver) {
        return new FetchPlanRegistry(managedEntityTypes, metadataResolver, instanceNameResolver);
    }

    /**
     * Адаптер «fetch+metadata → RLS» для нейтрального шва
     * {@code RlsDimensionValueLabelResolver} (шаг 8б): каталог значений RLS спрашивает
     * только этот контракт, а реализация читает select-колонки метаданных и единое
     * display-имя fetch-плана. Живёт здесь, а не в {@code MetadataAutoConfiguration}:
     * вызов {@code InstanceNameBridge} из metadata-конфигурации нарушил бы запрет
     * {@code metadata → fetch} ({@code PlatformArchitectureTest}), направление
     * {@code fetch → metadata} разрешено. Backoff как у соседних бинов: без
     * {@code MetadataResolver} бин не создаётся, metadata-only срезы не падают.
     */
    @Bean
    @ConditionalOnBean(MetadataResolver.class)
    @ConditionalOnMissingBean
    public RlsDimensionValueLabelResolver rlsDimensionValueLabelResolver(
            MetadataResolver metadataResolver) {
        return (entityType, value) -> {
            java.util.List<ColumnPath> columns =
                metadataResolver.resolve(entityType).getSelectColumnPaths();
            String code = columns.isEmpty() ? null : text(columns.getFirst().getValue(value));
            String name = columns.size() < 2 ? InstanceNameBridge.displayName(value)
                : text(columns.get(1).getValue(value));
            return new RlsDimensionValueLabelResolver.Labels(code, name);
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
