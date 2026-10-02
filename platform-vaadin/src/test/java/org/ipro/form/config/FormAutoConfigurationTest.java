package org.ipro.form.config;

import org.ipro.crud.EntityCopyService;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.crud.config.CrudAutoConfiguration;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.grouping.GroupingValuesProviderFactory;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionProvenance;
import org.ipro.form.action.ActionProvenanceCatalog;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsUiGate;
import org.junit.jupiter.api.Test;
import org.ipro.form.spi.WorkspaceGateway;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3.5.5: формовый слой объявляет бины явными {@code @Bean}, а не {@code @Import} concrete-классов,
 * и здесь проверяется то, что этим изменением покупалось.
 *
 * <p><b>Почему это отдельный забор.</b> До шага конфигурация перечисляла {@code @Import} 13 классов,
 * то есть регистрировала их <i>безусловно</i>: обещание «приложение может заменить любой
 * платформенный бин» держалось на словах, а не на механизме контейнера.</p>
 *
 * <p><b>Две ловушки в самом заборе, обе найдены экспериментом.</b> Первая:
 * {@code assertThat(context).hasSingleBean(…)} здесь вакуумен — поиск по типу не находит
 * {@code @Import}-определение, и «ровно один бин типа X» проходит успешно. Вторая, менее
 * очевидная: подмену <b>нельзя</b> объявлять через {@code ApplicationContextRunner.withBean(…)}.
 * Этот способ регистрации почему-то подавляет однотипное {@code @Import}-определение, и забор
 * становится зелёным при полностью неисправном коде. Пользовательский бин объявляется так, как это
 * делает приложение, — {@code @Bean} в {@code @Configuration}, — и тогда видно правду:
 * до шага сосуществуют два определения одного типа ({@code fieldFactory} и
 * {@code org.ipro.form.FieldFactory}) и инъекция по типу становится неоднозначной.</p>
 *
 * <p>Поэтому забор считает <b>определения по классу</b>: имя разрешается вторым шагом через
 * {@code getBeanClassName()}, как в {@code FormCoordinatorScopeGuardTest}.</p>
 *
 * <p><b>Что здесь mocking, а что живое.</b> Поднимаются реальные {@code MetadataAutoConfiguration}
 * и {@code CrudAutoConfiguration}: они дают метаданные, {@code ServiceLocator}, {@code LookupService}
 * и — после D3.5.5 — {@code EntityCopyService}. Замоканы только те коллабораторы, которые в
 * приложении приходят из адаптеров потребителя (сторы, gate, группировка строк) или требуют
 * persistence-контекста. Так проверяется настоящая проводка, а не её имитация.</p>
 */
class FormAutoConfigurationTest {

    @Test
    void optionalStructureNavigationIsInjectedIntoBothFormAssemblyPaths() {
        var navigation = mock(org.ipro.form.link.EntityStructureNavigation.class);
        runner(true)
            .withInitializer(context -> context.getBeanFactory().registerScope("vaadin-ui",
                new org.springframework.context.support.SimpleThreadScope()))
            .withBean(org.ipro.form.link.EntityStructureNavigation.class, () -> navigation)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(FormCoordinator.class), "structureNavigation")).isSameAs(navigation);
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(ItemFormWrapperView.class), "structureNavigation")).isSameAs(navigation);
            });
    }

    @Test
    void formsCanBeCreatedWhenStructureNavigationIsNotProvided() {
        runner(true)
            .withInitializer(context -> context.getBeanFactory().registerScope("vaadin-ui",
                new org.springframework.context.support.SimpleThreadScope()))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(FormCoordinator.class), "structureNavigation")).isNull();
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(ItemFormWrapperView.class), "structureNavigation")).isNull();
            });
    }

    @Test
    void registersFormBeansOfTheLayer() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(definitionsOfType(context, FormRegistry.class)).hasSize(1);
            assertThat(definitionsOfType(context, SelectionFormAssembler.class)).hasSize(1);
            assertThat(definitionsOfType(context, FieldFactory.class)).hasSize(1);
            assertThat(definitionsOfType(context, FormResolver.class)).hasSize(1);
            assertThat(definitionsOfType(context, FormCoordinator.class)).hasSize(1);
            assertThat(definitionsOfType(context, ItemFormWrapperView.class)).hasSize(1);
        });
    }

    /**
     * E3.2.0: каталог происхождения собирается той же площадкой, что и реестр, и называет
     * класс-объявление прикладного определения.
     *
     * <p>Проверяется живая проводка, а не её описание: каталог получает определения-бины
     * <b>вместе с именами</b>, иначе место оставалось бы неназванным, и обязан покрыть состав
     * реестра целиком — расхождение источников роняет старт, а не карточку.</p>
     */
    @Test
    void actionProvenanceComesFromTheSameSiteAsTheRegistry() {
        runner(true).withUserConfiguration(SuppressedActionConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            ActionProvenanceCatalog catalog = context.getBean(ActionProvenanceCatalog.class);
            ActionRegistry registry = context.getBean(ActionRegistry.class);

            ActionRegistry.Registration suppression = registry
                .registration(ActionSurface.LIST_TOOLBAR,
                    SuppressedActionConfiguration.ENTITY, null, CrudAction.CREATE.id())
                .orElseThrow();

            assertThat(suppression.definition().visible()).isFalse();
            assertThat(catalog.declarationOf(suppression.key()))
                .isEqualTo(ActionProvenance.registration(
                    SuppressedActionConfiguration.class.getName(), ""));
            assertThat(catalog.executorOf(suppression.key()).note())
                .isEqualTo("исполнитель не зарегистрирован");
        });
    }

    /** Подавление стандартного действия — определение-бин приложения: так объявляет `ActionPolicyConfig`. */
    @org.springframework.context.annotation.Configuration
    static class SuppressedActionConfiguration {

        static final Class<?> ENTITY = SuppressedActionConfiguration.class;

        @org.springframework.context.annotation.Bean
        ActionDefinition suppressedCreate() {
            return ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR, ENTITY);
        }
    }

    /**
     * Extension point: пользовательский бин выигрывает, платформенный не регистрируется вообще.
     *
     * <p>Проверяется именно {@code FieldFactory} — бин с неоднозначным конструктором: кроме
     * канонического 4-арг есть 2-арг вариант, создающий собственную {@code FormRegistry}. Пока
     * регистрация шла {@code @Import}, конструктор выбирал Spring по {@code @Autowired}; при явном
     * {@code @Bean}-методе выбирает человек, и ошибка здесь не даёт ни ошибки компиляции, ни
     * падения — просто формы перестают видеть кастомные варианты.</p>
     */
    @Test
    void applicationBeanReplacesPlatformFieldFactory() {
        runner(true).withUserConfiguration(CustomFieldFactoryConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(definitionsOfType(context, FieldFactory.class))
                    .as("подмена обязана убирать платформенное определение, а не сосуществовать с ним:"
                        + " два определения одного типа дают неоднозначную инъекцию и делают выбор"
                        + " бина случайным")
                    .hasSize(1);
                assertThat(context.getBean(FieldFactory.class))
                    .isSameAs(CustomFieldFactoryConfiguration.CUSTOM);
                assertThat(context.getBean(FormResolver.class)).isNotNull();
            });
    }

    @Test
    void applicationBeanReplacesPlatformFormResolver() {
        runner(true).withUserConfiguration(CustomFormResolverConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(definitionsOfType(context, FormResolver.class)).hasSize(1);
                assertThat(context.getBean(FormResolver.class))
                    .isSameAs(CustomFormResolverConfiguration.CUSTOM);
            });
    }

    /** Пользовательский бин так, как его объявляет приложение: {@code @Bean} в {@code @Configuration}. */
    @org.springframework.context.annotation.Configuration
    static class CustomFieldFactoryConfiguration {

        private static final FieldFactory CUSTOM = mock(FieldFactory.class);

        @org.springframework.context.annotation.Bean
        FieldFactory fieldFactory() {
            return CUSTOM;
        }
    }

    /** Второй extension point: резолвер формы подменяется независимо от фабрики полей. */
    @org.springframework.context.annotation.Configuration
    static class CustomFormResolverConfiguration {

        private static final FormResolver CUSTOM = mock(FormResolver.class);

        @org.springframework.context.annotation.Bean
        FormResolver formResolver() {
            return CUSTOM;
        }
    }

    /**
     * Fail-fast, а не backoff: {@code RlsUiGate} — обязательный коллаборатор безопасности.
     * Контекст обязан упасть и назвать причину. Молчаливое отсутствие gate дало бы UI, который
     * показывает данные, не прошедшие RLS-проверку, — то есть цена отказа выше цены падения старта.
     */
    /**
     * E1.3: каталог дескрипторов — обязательный вход решения о действиях. Без него провайдер
     * «решил бы» без capability типа, то есть вернул бы CRUD, которого у типа нет.
     */
    @Test
    void missingDescriptorCatalogFailsStartupWithNamedReason() {
        runner(true, false).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("EntityDescriptorCatalog");
        });
    }

    @Test
    void missingRlsUiGateFailsStartupWithNamedReason() {
        runner(false).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("RlsUiGate");
        });
    }

    /**
     * Заморозка реестра после старта — гарантия D3.5.3, и до этого шага её не проверял ни один
     * тест: оба вызова {@code freeze()} в {@code FormRegistryLifecycleTest} ручные.
     *
     * <p>Зачем тест, если код не менялся. {@code FormRegistryConfiguration} — {@code @Configuration},
     * чей {@code onApplicationEvent} зовёт {@code formRegistry()} <i>напрямую</i>. Это законная
     * Spring-идиома, но работает она только через CGLIB-прокси: превратив класс в обычный
     * {@code @Bean}, получаешь вызов метода у одноразового объекта, заморозку которого никто не
     * увидит, — и реестр снова принимает регистрации после старта, без единого падения.</p>
     */
    @Test
    void registryIsFrozenAfterContextRefresh() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FormRegistry.class).isFrozen())
                .as("контейнер обязан заморозить реестр после старта: иначе композиция форм"
                    + " продолжает меняться во время работы приложения")
                .isTrue();
        });
    }

    /**
     * Тот же реестр, но с тестовым выходом из заморозки: {@code DialogWorkspaceResolutionIT}
     * регистрирует вариант после старта осознанно, и свойство обязано продолжать работать.
     */
    @Test
    void runtimeRegistrationKeepsRegistryOpenWhenExplicitlyAllowed() {
        runner(true).withPropertyValues("ipro.form.registry.allow-runtime-registration=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FormRegistry.class).isFrozen()).isFalse();
            });
    }

    /**
     * Route host без рабочей области — ошибка старта, а не тихо неработающий адрес (E2.3).
     *
     * <p>Слушатель вызывается здесь напрямую, а не через раннер: {@code ApplicationReadyEvent}
     * публикует {@code SpringApplication}, а {@code ApplicationContextRunner} только обновляет
     * контекст — то есть через раннер этот отказ не воспроизвести вообще. Проверяется само решение,
     * а проводку держит тот же список бинов конфигурации.</p>
     */
    @Test
    void declaredRouteHostWithoutWorkspaceFailsStartupWithANamedReason() {
        ListableBeanFactory beans = mock(ListableBeanFactory.class);
        when(beans.getBeanNamesForType(WorkspaceGateway.class)).thenReturn(new String[0]);

        ApplicationListener<ApplicationReadyEvent> check =
            new FormAutoConfiguration().formRouteHostStartupCheck(beans, true);

        assertThatThrownBy(() -> check.onApplicationEvent(null))
            .as("ссылка открывается только в Workspace: без области каждая копия открывалась бы"
                + " в «некуда» — это узнал бы пользователь, а не разработчик")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WorkspaceGateway")
            .hasMessageContaining("route host");
    }

    /**
     * Объявленный route host с рабочей областью и приложение без host — оба случая нормальны:
     * проверка отвечает на вопрос «есть ли область», а не требует её наличия вообще.
     */
    @Test
    void routeHostCheckPassesWithAWorkspaceAndIsOffWhenNotDeclared() {
        ListableBeanFactory withWorkspace = mock(ListableBeanFactory.class);
        when(withWorkspace.getBeanNamesForType(WorkspaceGateway.class))
            .thenReturn(new String[] {"workspace"});
        ListableBeanFactory withoutWorkspace = mock(ListableBeanFactory.class);
        when(withoutWorkspace.getBeanNamesForType(WorkspaceGateway.class))
            .thenReturn(new String[0]);

        assertThatCode(() -> new FormAutoConfiguration()
            .formRouteHostStartupCheck(withWorkspace, true).onApplicationEvent(null))
            .doesNotThrowAnyException();
        assertThatCode(() -> new FormAutoConfiguration()
            .formRouteHostStartupCheck(withoutWorkspace, false).onApplicationEvent(null))
            .as("приложение без route host не обязано предоставлять рабочую область: чужая"
                + " проверка не имеет права ронять его старт")
            .doesNotThrowAnyException();
    }

    /**
     * У формового слоя нет собственного владельца {@code EntityCopyService}: бин приходит из
     * backend-модуля ({@code CrudAutoConfiguration}), который здесь реально загружен. Проверка
     * держит обе стороны — сервис ядра остаётся доступен потребителю без UI, а UI-конфигурация
     * перестаёт быть его владельцем (иначе при удалении UI-артефакта сервис исчезает из контракта
     * ядра молча, а {@code FormCoordinator} перестаёт подниматься).
     */
    @Test
    void entityCopyServiceComesFromTheBackendModule() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(definitionsOfType(context, EntityCopyService.class)).hasSize(1);
            assertThat(context.getBean(EntityCopyService.class)).isNotNull();
            assertThat(definitionsOfType(context, LookupService.class)).hasSize(1);
            assertThat(definitionsOfType(context, ServiceLocator.class)).hasSize(1);
        });
    }

    /**
     * Раннер с настоящими metadata- и CRUD-конфигурациями и замоканными адаптерами потребителя.
     *
     * @param withRlsUiGate {@code false} — единственный отсутствующий коллаборатор: так проверяется,
     *                      что отсутствие обязательного gate даёт названную причину, а не уход
     *                      конфигурации в backoff
     */
    private static ApplicationContextRunner runner(boolean withRlsUiGate) {
        return runner(withRlsUiGate, true);
    }

    /**
     * @param withDescriptorCatalog {@code false} — отсутствующий каталог дескрипторов: так
     *                              проверяется, что провайдер входов решения обязан иметь capability
     *                              типа и падает с названной причиной, а не собирается наполовину
     */
    private static ApplicationContextRunner runner(boolean withRlsUiGate, boolean withDescriptorCatalog) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetadataAutoConfiguration.class,
                CrudAutoConfiguration.class, FormAutoConfiguration.class))
            .withPropertyValues("platform.subsystem-scan-package=org.example.app")
            .withBean(jakarta.persistence.EntityManagerFactory.class,
                FormAutoConfigurationTest::entityManagerFactory)
            .withBean(jakarta.validation.Validator.class,
                () -> mock(jakarta.validation.Validator.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(org.ipro.rls.RlsPolicyEnforcer.class,
                () -> mock(org.ipro.rls.RlsPolicyEnforcer.class))
            .withBean(org.ipro.rls.RlsFilterActivator.class,
                () -> mock(org.ipro.rls.RlsFilterActivator.class))
            .withBean(org.ipro.events.EntityEventPublisher.class,
                () -> mock(org.ipro.events.EntityEventPublisher.class))
            .withBean(org.ipro.lifecycle.EntityLifecycleRegistry.class,
                () -> new org.ipro.lifecycle.EntityLifecycleRegistry(java.util.List.of()))
            .withBean(CanonicalReadExecutor.class, () -> mock(CanonicalReadExecutor.class))
            .withBean(GroupingValuesProviderFactory.class,
                () -> mock(GroupingValuesProviderFactory.class))
            .withBean(GenericOwnedSectionService.class,
                () -> mock(GenericOwnedSectionService.class))
            .withBean(FormSettingsStore.class, () -> mock(FormSettingsStore.class))
            .withBean(GridViewStore.class, () -> mock(GridViewStore.class));
        ApplicationContextRunner configured = withDescriptorCatalog
            ? runner.withBean(EntityDescriptorCatalog.class,
                () -> mock(EntityDescriptorCatalog.class))
            : runner;
        return withRlsUiGate
            ? configured.withBean(RlsUiGate.class, () -> mock(RlsUiGate.class))
            : configured;
    }

    /** EMF замокан: {@code @PersistenceContext}-бины получают из него EntityManager, метамодель пуста. */
    private static jakarta.persistence.EntityManagerFactory entityManagerFactory() {
        jakarta.persistence.EntityManagerFactory factory =
            mock(jakarta.persistence.EntityManagerFactory.class);
        jakarta.persistence.metamodel.Metamodel metamodel =
            mock(jakarta.persistence.metamodel.Metamodel.class);
        org.mockito.Mockito.when(factory.getMetamodel()).thenReturn(metamodel);
        org.mockito.Mockito.when(metamodel.getEntities())
            .thenReturn(Set.of());
        org.mockito.Mockito.when(factory.createEntityManager())
            .thenReturn(mock(jakarta.persistence.EntityManager.class));
        return factory;
    }

    /**
     * Имена определений, чей класс равен искомому.
     *
     * <p>Второй шаг разрешения типа (по {@code getBeanClassName()}) обязателен: у бинов,
     * зарегистрированных {@code @Import}, resolvable type пуст, и поиск по типу их не видит —
     * именно поэтому здесь считается определениями, а не {@code hasSingleBean}.</p>
     */
    private static List<String> definitionsOfType(ConfigurableApplicationContext context,
                                                  Class<?> type) {
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        List<String> names = new ArrayList<>();
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            if (type.equals(resolveType(factory, definition))) {
                names.add(name);
            }
        }
        return names;
    }

    private static Class<?> resolveType(ConfigurableListableBeanFactory factory,
                                        BeanDefinition definition) {
        Class<?> resolved = definition.getResolvableType().resolve();
        if (resolved != null) {
            return resolved;
        }
        String className = definition.getBeanClassName();
        if (className == null) {
            return null;
        }
        try {
            return org.springframework.util.ClassUtils.forName(className,
                factory.getBeanClassLoader());
        } catch (ClassNotFoundException | LinkageError notOnClasspath) {
            return null;
        }
    }
}
