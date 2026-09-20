package org.ipro.form.config;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.LookupService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.grouping.GroupingValuesProviderFactory;
import org.ipro.form.FieldFactory;
import org.ipro.form.ItemFormSaveHandler;
import org.ipro.form.ItemFormSaveHandlerRegistry;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionCustomization;
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
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormRegistryConfiguration;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.ListCommand;
import org.ipro.form.registry.ListCommandRegistry;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.ListFormToolbarContributor;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsUiGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
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
                                           EntityCopyService entityCopyService,
                                           TableSectionFactory tableSectionFactory,
                                           List<ListFormToolbarContributor> toolbarContributors,
                                           ObjectProvider<WorkspaceGateway> workspaceGateways) {
        return new FormCoordinator(metadataResolver, fieldFactory, applicationContext, formResolver,
            serviceLocator, formSettingsStore, gridViewStore, rlsUiGate, itemFormAccessBinder,
            entityCopyService, tableSectionFactory, toolbarContributors, workspaceGateways);
    }

    // ---------------------------------------------------------------------
    // Инварианты: без условий. Эти бины либо обязательны, либо агрегируют вклады приложения,
    // поэтому конфликт с пользовательским бином обязан быть громким.
    // ---------------------------------------------------------------------

    /** Обязательный gate доступа: без него форма показывает данные в обход RLS-проверки. */
    @Bean
    public ItemFormAccessBinder itemFormAccessBinder(RlsUiGate rlsUiGate) {
        return new ItemFormAccessBinder(rlsUiGate);
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

    @Bean
    public ListCommandRegistry listCommandRegistry(List<ListCommand<?>> commands) {
        return new ListCommandRegistry(commands);
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
                                                   ItemFormAccessBinder itemFormAccessBinder) {
        return new ItemFormWrapperView(applicationContext, formResolver, serviceLocator,
            itemFormAccessBinder);
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
