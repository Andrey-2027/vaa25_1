package org.ipro.form.config;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.LookupService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.grouping.GroupingValuesProviderFactory;
import org.ipro.form.FieldFactory;
import org.ipro.form.ItemFormSaveHandler;
import org.ipro.form.ItemFormSaveHandlerRegistry;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.CrudAction;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormCustomizationRegistrar;
import org.ipro.form.builder.ListFormCustomization;
import org.ipro.form.builder.ListFormCustomizationRegistrar;
import org.ipro.form.builder.SelectionFormCustomization;
import org.ipro.form.builder.SelectionFormCustomizationRegistrar;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.host.FormRouteUrlBridge;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRouteAliasDeclaration;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteCodec;
import org.ipro.form.link.FormRouteOpener;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormRegistryConfiguration;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsUiGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;

import java.util.List;

/**
 * Бины формообразующего слоя платформы (org.ipro.form).
 *
 * <p><b>D3.5.5: явные {@code @Bean} вместо {@code @Import} concrete-классов.</b> Прежний
 * {@code @Import}-хаб был не стилистикой, а дефектом wiring: {@code @Import} регистрирует
 * <i>класс</i> под FQN-именем и не участвует в conditional-обработке. Проверено экспериментом:
 * пользовательский {@code @Bean FieldFactory} в {@code @Configuration} даёт два определения одного
 * типа ({@code fieldFactory} и {@code org.ipro.form.FieldFactory}), то есть «приложение может
 * заменить любой бин» не выполнялось — вместо замены получалась неоднозначная инъекция. Здесь
 * замена обеспечена механизмом контейнера: {@code @ConditionalOnMissingBean} проверяет тип, а
 * пользовательские бины зарегистрированы раньше авто-конфигураций (прецедент — {@code
 * CrudAutoConfiguration}, D3.4).</p>
 *
 * <p><b>Что является точкой расширения, а что инвариантом.</b> {@code @ConditionalOnMissingBean}
 * стоит там, где подмена осмысленна: сборка формы выбора, создание полей, резолв формы, навигация.
 * Без условия — там, где бин выражает контракт wiring или агрегирует чужие вклады: gate доступа,
 * фабрика секций, реестр команд и три регистратора кастомизаций (каждый принимает
 * {@code List<...>} вкладов приложения). Подмена такого бина — не конфигурация, а поломка, поэтому
 * конфликт обязан быть громким (два кандидата одной инъекции), а не молча выключать подсистему.</p>
 *
 * <p><b>Скоупы объявлены на фабричных методах, а не на классах.</b> {@code @UIScope} у координатора
 * и {@code @Scope("prototype")} у представления формы читаются с метода: аннотация класса при
 * {@code @Bean}-регистрации не наследуется. Это не мелочь — потеря {@code @UIScope} возвращает
 * межсессионный дефект P1 (workspace одного UI виден другому), поэтому оба скоупа закреплены
 * заборами {@code FormCoordinatorScopeGuardTest}.</p>
 *
 * <p><b>Почему {@code FormRegistryConfiguration} остался {@code @Import}.</b> Это
 * {@code @Configuration}, а не компонент: его {@code onApplicationEvent} замораживает реестр,
 * вызывая {@code formRegistry()} <i>напрямую</i>, и работает это только через CGLIB-прокси
 * конфигурации. Превратив класс в обычный {@code @Bean}, получаешь вызов метода у одноразового
 * объекта — реестр остаётся незамороженным, и композиция форм продолжает меняться во время работы.
 * Зависимость от прокси-семантики закреплена тестом {@code FormAutoConfigurationTest}.</p>
 *
 * <p><b>D3.5.4 (физический перенос):</b> класс уехал в {@code platform-vaadin} вместе с остальным
 * UI-слоем, владение регистрацией — за imports-файлом модуля, а не приложения: незарегистрированную
 * конфигурацию компилятор не видит, это ловит {@code PlatformAutoConfigurationRegistryTest}
 * (ровно один владелец на артефакт).</p>
 */
@AutoConfiguration
@AutoConfigureAfter(MetadataAutoConfiguration.class)
@Import(FormRegistryConfiguration.class)
public class FormAutoConfiguration {

    // ---------------------------------------------------------------------
    // Точки расширения: подмена осмысленна, платформенный бин отступает.
    // ---------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public SelectionFormAssembler selectionFormAssembler(MetadataResolver metadataResolver,
                                                         ServiceLocator serviceLocator) {
        return new SelectionFormAssembler(metadataResolver, serviceLocator);
    }

    /**
     * Канонический конструктор — 4-арг, с {@code FormRegistry}.
     *
     * <p>2-арг вариант существует «для тестов и ручной сборки» и создаёт собственную
     * {@code FormRegistry}: выбрав его здесь, платформа молча перестала бы видеть кастомные
     * варианты форм, зарегистрированные приложением. Ошибки компиляции такой выбор не даёт, поэтому
     * выбор зафиксирован комментарием и забором {@code FormAutoConfigurationTest}.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public FieldFactory fieldFactory(LookupService lookupService,
                                     SelectionFormAssembler selectionFormAssembler,
                                     MetadataResolver metadataResolver,
                                     FormRegistry formRegistry) {
        return new FieldFactory(lookupService, selectionFormAssembler, metadataResolver, formRegistry);
    }

    /**
     * Канонический конструктор — 9-арг: 7-арг вариант (совместимость со старыми тестами) оставляет
     * группировку значений и провайдеры навигации пустыми, то есть строки с группировкой и навигация
     * из контекстов, которые строит резолвер, тихо перестали бы работать.
     */
    @Bean
    @ConditionalOnMissingBean
    public FormResolver formResolver(FormRegistry formRegistry,
                                     MetadataResolver metadataResolver,
                                     FieldFactory fieldFactory,
                                     ApplicationContext applicationContext,
                                     TableSectionFactory tableSectionFactory,
                                     SelectionFormAssembler selectionFormAssembler,
                                     ServiceLocator serviceLocator,
                                     GroupingValuesProviderFactory groupingValuesProviderFactory,
                                     ObjectProvider<FormNavigator> formNavigators) {
        return new FormResolver(formRegistry, metadataResolver, fieldFactory, applicationContext,
            tableSectionFactory, selectionFormAssembler, serviceLocator,
            groupingValuesProviderFactory, formNavigators);
    }

    /**
     * {@code @UIScope} — на методе, потому что аннотация класса при {@code @Bean} не наследуется:
     * без неё координатор становится синглтоном с мутабельным {@code workspace}, и UI снова
     * перетирают друг друга (дефект P1 из D3.5.3).
     */
    @Bean
    @UIScope
    @ConditionalOnMissingBean
    public FormCoordinator formCoordinator(MetadataResolver metadataResolver,
                                           FieldFactory fieldFactory,
                                           ApplicationContext applicationContext,
                                           FormResolver formResolver,
                                           ServiceLocator serviceLocator,
                                           FormSettingsStore formSettingsStore,
                                           GridViewStore gridViewStore,
                                           RlsUiGate rlsUiGate,
                                           ItemFormAccessBinder itemFormAccessBinder,
                                           ActionRegistry actionRegistry,
                                           ActionContextProvider actionContextProvider,
                                           org.ipro.form.action.ActionHandlerRegistry actionHandlerRegistry,
                                           FormLinkService formLinkService,
                                           EntityCopyService entityCopyService,
                                           TableSectionFactory tableSectionFactory,
                                           ObjectProvider<WorkspaceGateway> workspaceGateways,
                                           ObjectProvider<FormRouteUrlBridge> routeUrlBridges) {
        return new FormCoordinator(metadataResolver, fieldFactory, applicationContext, formResolver,
            serviceLocator, formSettingsStore, gridViewStore, rlsUiGate, itemFormAccessBinder,
            actionRegistry, actionContextProvider, actionHandlerRegistry, formLinkService,
            entityCopyService, tableSectionFactory,
            workspaceGateways, routeUrlBridges);
    }

    /**
     * Мост адреса и вкладок (E2.3, ADR-0009 §7).
     *
     * <p>{@code @UIScope} по тому же факту, что и у координатора: адрес относится к вкладкам
     * текущего UI, а вкладки — состояние UI. Синглтон смешал бы адрес одного окна с содержимым
     * другого (измерено в E2.0: два UI имеют независимые области).</p>
     *
     * <p>Бин регистрируется безусловно и безвреден: до {@code install(...)} он не подписан ни на
     * что и не меняет адрес. Условность здесь означала бы, что координатор обязан спрашивать,
     * «включён ли route host», прежде чем записать адрес вкладки, — то есть второе место,
     * знающее про режим приложения.</p>
     */
    @Bean
    @UIScope
    @ConditionalOnMissingBean
    public FormRouteUrlBridge formRouteUrlBridge(FormRouteCodec formRouteCodec,
                                                 ObjectProvider<WorkspaceGateway> workspaceGateways) {
        return new FormRouteUrlBridge(formRouteCodec, workspaceGateways);
    }

    /**
     * Кодек адреса глубокой ссылки (E2.1): единственное место, знающее грамматику маршрута.
     * Точка расширения намеренно оставлена: прикладной host может подменить нормализацию,
     * не переписывая каталог.
     */
    @Bean
    @ConditionalOnMissingBean
    public FormRouteCodec formRouteCodec() {
        return new FormRouteCodec();
    }

    /**
     * Каталог публикуемых маршрутов (E2.1, ADR-0009 §4).
     *
     * <p>Строится <b>лениво</b> ({@link FormRouteCatalog#deferred}) намеренно: регистраторы
     * вариантов — такие же бины, и порядок их создания контейнером не определён. Снимок до их
     * работы зафиксировал бы неполный набор вариантов молча; здесь раннее обращение — отказ.
     * Декларации приложения ({@link FormRouteAliasDeclaration}) приходят списком: пустой список
     * означает «все ключи выведены из имён классов» и является нормальным состоянием.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public FormRouteCatalog formRouteCatalog(
            EntityDescriptorCatalog descriptorCatalog,
            FormRegistry formRegistry,
            ObjectProvider<FormRouteAliasDeclaration> declarations,
            @Value("${ipro.form.registry.allow-runtime-registration:false}")
            boolean allowRuntimeRegistration) {
        // ObjectProvider, а не List: у приложения обычно нет ни одной декларации, и отсутствие
        // вкладов — норма, а не отсутствующая зависимость.
        //
        // Требование заморозки реестра снимается вместе с тем же свойством, что и сама заморозка
        // (FormRegistryConfiguration): просить завершённую композицию там, где реестр намеренно
        // открыт для рантайм-регистраций, — это запрет выбранного режима, а не проверка.
        return FormRouteCatalog.deferred(() -> descriptorCatalog, () -> formRegistry,
            declarations.stream().toList(), !allowRuntimeRegistration);
    }

    /** Генерация ссылок: каталог плюс кодек (E2.1, ADR-0009 §5/§7). */
    @Bean
    @ConditionalOnMissingBean
    public FormLinkService formLinkService(FormRouteCatalog formRouteCatalog,
                                           FormRouteCodec formRouteCodec) {
        return new FormLinkService(formRouteCatalog, formRouteCodec);
    }

    /**
     * Route-вход: открытие формы по адресу (E2.2, ADR-0009 §6).
     *
     * <p>{@code @UIScope} — не копия скоупа координатора, а следствие того же факта: открытие
     * по адресу заканчивается вкладкой в рабочей области текущего UI, а она — состояние UI, а не
     * приложения. Синглтон здесь держал бы навигатора первого пользователя: у {@code @UIScope}
     * нет scoped-proxy, поэтому внедрение UI-scoped бина в синглтон забор
     * {@code FormCoordinatorScopeGuardTest} справедливо запрещает.</p>
     */
    @Bean
    @UIScope
    @ConditionalOnMissingBean
    public FormRouteOpener formRouteOpener(FormRouteCatalog formRouteCatalog,
                                           FormRouteCodec formRouteCodec,
                                           FormNavigator formNavigator) {
        return new FormRouteOpener(formRouteCatalog, formRouteCodec, formNavigator);
    }

    /**
     * Startup-проверка композиции адресов: коллизии ключей и декларации для непубликуемых
     * типов обязаны ронять старт, а не открываться первым пользователем как битая ссылка.
     *
     * <p>{@code ApplicationReadyEvent}, а не {@code ContextRefreshedEvent}: регистраторы форм
     * завершаются к моменту инициализации синглтонов, и первое обращение к каталогу обязано
     * увидеть замороженный реестр — иначе ленивое построение откажет по собственному guard'у.</p>
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> formRouteCatalogStartupCheck(
            FormRouteCatalog formRouteCatalog) {
        return event -> formRouteCatalog.validate();
    }

    /**
     * Проверка конфигурации route host (E2.3): объявленный host без рабочей области — ошибка
     * старта, а не тихо неработающий адрес.
     *
     * <p>Host умеет показывать форму по адресу только через Workspace (ADR §3: прямой адрес всегда
     * открывает Workspace). Если приложение объявило host и не предоставило UI-scoped
     * {@code WorkspaceGateway}, каждая скопированная ссылка открывалась бы в состояние «некуда»,
     * причём у пользователя, а не у разработчика. Поэтому отказ выдаётся на старте и с причиной.</p>
     *
     * <p><b>Объявление — свойство, а не догадка.</b> «Есть шаблоны маршрутов» из бинов не видно:
     * {@code @RouteAlias} — аннотация класса, а не бин. Приложение, у которого host'а нет, не должно
     * страдать от чужой проверки, поэтому проверка включается явно
     * ({@code ipro.form.route-host.enabled}) и по умолчанию выключена.</p>
     *
     * <p>Смотрим объявления бинов, а не сам бин: {@code WorkspaceGateway} — UI-scoped, и получить
     * его экземпляр на старте нельзя (активного UI ещё нет). Проверка типом отвечает на нужный
     * вопрос — «предоставило ли приложение область», — не создавая её.</p>
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> formRouteHostStartupCheck(
            ListableBeanFactory beans,
            @Value("${ipro.form.route-host.enabled:false}") boolean routeHostEnabled) {
        return event -> {
            if (!routeHostEnabled) {
                return;
            }
            if (beans.getBeanNamesForType(WorkspaceGateway.class).length == 0) {
                throw new IllegalStateException("Приложение объявило route host"
                    + " (ipro.form.route-host.enabled=true), но не предоставило UI-scoped бин"
                    + " WorkspaceGateway: прямой адрес всегда открывает форму в Workspace"
                    + " (ADR-0009 §3), поэтому каждая ссылка открывалась бы в «открывать некуда»."
                    + " Либо приложение предоставляет рабочую область, либо route host не объявляется.");
            }
        };
    }

    // ---------------------------------------------------------------------
    // Инварианты: без условий. Эти бины либо обязательны, либо агрегируют вклады приложения,
    // поэтому конфликт с пользовательским бином обязан быть громким.
    // ---------------------------------------------------------------------

    /**
     * Обязательный gate доступа: без него форма показывает данные в обход проверки доступа.
     *
     * <p>С E1.5 у binder'а нет своих коллабораторов: он переводит в форму готовое решение по
     * действию, а входы решения (capability типа и права) собирает {@link ActionContextProvider}.
     * Своя формула прав здесь больше не живёт — раньше именно тут проверялся один только RLS, и
     * тип без generic {@code UPDATE} открывался редактируемым.</p>
     */
    @Bean
    public ItemFormAccessBinder itemFormAccessBinder() {
        return new ItemFormAccessBinder();
    }

    /**
     * Реестр действий (E1.3/E1.5): платформенный состав тулбара списка и подвала карточки плюс
     * предметные вклады приложения.
     *
     * <p>Без условий, как и остальные инварианты: реестр — не опция «если приложение захочет».
     * Расширяется он через сами {@link ActionDefinition}-бины, а не через отсутствие реестра;
     * дубликат ключа без явного override роняет старт здесь же, а не молча выбирает одно из двух.</p>
     */
    @Bean
    public ActionRegistry actionRegistry(List<ActionDefinition> overrides,
                                        org.ipro.form.action.ActionHandlerRegistry handlerRegistry) {
        // Объявления прикладных действий — регистрации, а не override'ы: это новые действия типа,
        // и правило «override обязан найти цель» к ним не применяется. Состав действий остаётся
        // одним: и платформенные defaults, и прикладные объявления проходят через один реестр,
        // поэтому решение о них считает та же политика.
        List<ActionDefinition> declared = new java.util.ArrayList<>(CrudAction.platformDefaults());
        declared.addAll(handlerRegistry.definitions());
        return new ActionRegistry(declared, overrides);
    }

    /**
     * Провайдер входов решения (E1.3): capability типа из дескрипторов C4, права из RLS-гейта и
     * адресуемость формы из каталога адресов (E2.1).
     *
     * <p>Обязательный коллаборатор: без него у списка либо нет решения вовсе, либо он вынужден
     * считать права сам — именно то, что устраняет E1.</p>
     *
     * <p>Каталог адресов передаётся всегда, хотя он ленив: подключить его «когда понадобится»
     * значило бы получить второй способ собирать контекст — с адресами и без, — и действие,
     * требующее ссылку, падало бы на одном из них.</p>
     */
    @Bean
    public ActionContextProvider actionContextProvider(EntityDescriptorCatalog descriptorCatalog,
                                                       RlsUiGate rlsUiGate,
                                                       FormRouteCatalog formRouteCatalog) {
        return new ActionContextProvider(descriptorCatalog, rlsUiGate, formRouteCatalog);
    }

    @Bean
    public TableSectionFactory tableSectionFactory(MetadataResolver metadataResolver,
                                                   FieldFactory fieldFactory,
                                                   ApplicationContext applicationContext,
                                                   GenericOwnedSectionService genericSectionService,
                                                   ObjectProvider<FormResolver> formResolverProvider,
                                                   List<TableSectionCustomization<?>> customizations) {
        return new TableSectionFactory(metadataResolver, fieldFactory, applicationContext,
            genericSectionService, formResolverProvider, customizations);
    }

    /**
     * Реестр исполнителей прикладных действий (E1.6a).
     *
     * <p>Бин обязательный, а не условный: он собирает вклады приложения, и его подмена означала бы
     * потерю прикладных действий (то же правило D3.5.5, по которому обязательным был и снятый
     * в E1.7 реестр легаси-команд). Дубликат ключа роняет старт здесь же, а не выбирает одно
     * из двух по порядку бинов.</p>
     */
    @Bean
    public org.ipro.form.action.ActionHandlerRegistry actionHandlerRegistry(
            List<org.ipro.form.action.ActionHandler> handlers) {
        return new org.ipro.form.action.ActionHandlerRegistry(handlers);
    }

    @Bean
    public ListFormCustomizationRegistrar listFormCustomizationRegistrar(
            FormRegistry formRegistry,
            List<ListFormCustomization> customizations) {
        return new ListFormCustomizationRegistrar(formRegistry, customizations);
    }

    @Bean
    public ItemFormCustomizationRegistrar itemFormCustomizationRegistrar(
            FormRegistry formRegistry,
            List<ItemFormCustomization> customizations) {
        return new ItemFormCustomizationRegistrar(formRegistry, customizations);
    }

    @Bean
    public SelectionFormCustomizationRegistrar selectionFormCustomizationRegistrar(
            FormRegistry formRegistry,
            List<SelectionFormCustomization> customizations) {
        return new SelectionFormCustomizationRegistrar(formRegistry, customizations);
    }

    /**
     * Представление формы для workspace-режима: {@code prototype} объявлен на методе по той же
     * причине, что {@code @UIScope} выше. Вкладка хранит разобранную сущность и состояние черновика,
     * поэтому общий инстанс означал бы, что вторая открытая форма получает состояние первой.
     *
     * <p>Потребитель бина ровно один — {@code DialogWorkspaceResolutionIT}, который берёт его дважды
     * через {@code ObjectProvider}. Workspace-ветка координатора создаёт представление через
     * {@code AutowireCapableBeanFactory.createBean}, то есть мимо контейнера, и потеря скоупа там не
     * была бы видна в принципе.</p>
     */
    @Bean
    @Scope("prototype")
    public ItemFormWrapperView itemFormWrapperView(ApplicationContext applicationContext,
                                                   FormResolver formResolver,
                                                   ServiceLocator serviceLocator,
                                                   ItemFormAccessBinder itemFormAccessBinder,
                                                   ActionRegistry actionRegistry,
                                                   ActionContextProvider actionContextProvider,
                                                   org.ipro.form.action.ActionHandlerRegistry actionHandlerRegistry,
                                                   FormLinkService formLinkService) {
        return new ItemFormWrapperView(applicationContext, formResolver, serviceLocator,
            itemFormAccessBinder, actionRegistry, actionContextProvider, actionHandlerRegistry,
            formLinkService);
    }

    /**
     * Collect application handlers as an optional override SPI. The list may be
     * empty: standard entities are intentionally handled by the metadata-driven
     * platform path.
     */
    @Bean
    @ConditionalOnMissingBean
    public ItemFormSaveHandlerRegistry itemFormSaveHandlerRegistry(
            ObjectProvider<ItemFormSaveHandler<?>> handlers) {
        return new ItemFormSaveHandlerRegistry(handlers.orderedStream().toList());
    }

    /**
     * Адаптер сохранения item-формы через metadata-driven путь.
     *
     * <p>Бин принадлежит формовому слою, а не метаданным: он реализует контракт
     * {@code ItemFormSaveHandler} и существует ровно там, где есть формы. Раньше его создавала
     * {@code MetadataAutoConfiguration}, из-за чего ядро метаданных импортировало
     * {@code org.ipro.form}: формально это был один бин, фактически — направление зависимости,
     * из-за которого future {@code platform-core} не собирался без UI-слоя.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public MetadataDrivenItemFormSaveAdapter metadataDrivenItemFormSaveAdapter(
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataDrivenAggregateSaveService aggregateSaveService) {
        return new MetadataDrivenItemFormSaveAdapter(sectionMetadataRegistry, aggregateSaveService);
    }
}
