package org.ipro.form.action;

import org.ipro.metadata.FactOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3.2.0 шаг 1.2: каталог происхождения знает <b>место</b> объявления и исполнителя, а не
 * «похожее на источник» имя.
 *
 * <p>Каталог собирается так же, как в {@code FormAutoConfiguration}: из платформенных defaults, из
 * прикладных {@link ActionHandler}-бинов и из определений-бинов приложения, взятых вместе с их
 * именами. Тесты проверяют каждую из трёх ветвей по отдельности, потому что «источник» — это
 * утверждение о коде, и одна ветка не подтверждает соседнюю.</p>
 */
class ActionProvenanceCatalogTest {

    static class ProbeEntity {
    }

    /** Подавление — определение-бин приложения: так объявляет приложение (`ActionPolicyConfig`). */
    @Configuration(proxyBeanMethods = false)
    static class SuppressionConfig {

        @Bean
        ActionDefinition probeSuppression() {
            return ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR,
                ProbeEntity.class);
        }
    }

    /** Прикладное действие объявляется исполнителем: у него нет ни одного другого места. */
    @Configuration(proxyBeanMethods = false)
    static class HandlerConfig {

        @Bean
        ActionHandler probeHandler() {
            return new ProbeHandler();
        }
    }

    @Test
    void platformDefaultsNameCrudActionAndSayTheyApplyToAnyType() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SuppressionConfig.class, HandlerConfig.class)) {
            Fixture fixture = fixtureOf(context);

            for (ActionRegistry.Registration registration : fixture.registry().registrationsWithKeys()) {
                if (registration.key().entityType() != null) {
                    continue;
                }
                ActionProvenance provenance = fixture.catalog().declarationOf(registration.key());
                assertThat(provenance.origin())
                    .as("платформенный default: %s", registration.key())
                    .isEqualTo(FactOrigin.PLATFORM_DEFAULT);
                assertThat(provenance.symbol()).isEqualTo(CrudAction.class.getName());
                assertThat(provenance.note()).isEqualTo("платформенное действие, применимо к любому типу");
            }
        }
    }

    @Test
    void suppressionBeanNamesItsDeclaringConfiguration() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SuppressionConfig.class, HandlerConfig.class)) {
            Fixture fixture = fixtureOf(context);

            ActionRegistry.Registration suppression = fixture.registry()
                .registration(ActionSurface.LIST_TOOLBAR, ProbeEntity.class, null, CrudAction.CREATE.id())
                .orElseThrow();

            assertThat(suppression.definition().visible()).isFalse();
            assertThat(fixture.catalog().declarationOf(suppression.key()))
                .isEqualTo(ActionProvenance.registration(SuppressionConfig.class.getName(), ""));
        }
    }

    @Test
    void handlerDeclaresAndExecutesItsOwnAction() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SuppressionConfig.class, HandlerConfig.class)) {
            Fixture fixture = fixtureOf(context);

            ActionRegistry.Registration declared = fixture.registry().registrationsWithKeys().stream()
                .filter(registration -> "probe.do".equals(registration.key().id().value()))
                .findFirst()
                .orElseThrow();

            ActionProvenance expected = ActionProvenance.registration(ProbeHandler.class.getName(), "");
            assertThat(fixture.catalog().declarationOf(declared.key())).isEqualTo(expected);
            assertThat(fixture.catalog().executorOf(declared.key())).isEqualTo(expected);
        }
    }

    @Test
    void actionWithoutExecutorSaysSoInsteadOfShowingAFakeSource() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SuppressionConfig.class, HandlerConfig.class)) {
            Fixture fixture = fixtureOf(context);

            ActionRegistry.Key refresh = fixture.registry()
                .registration(ActionSurface.LIST_TOOLBAR, ProbeEntity.class, null, CrudAction.REFRESH.id())
                .orElseThrow()
                .key();

            assertThat(fixture.catalog().executorOf(refresh))
                .isEqualTo(ActionProvenance.platformDefault("", "исполнитель не зарегистрирован"));
        }
    }

    @Test
    void jdkProxyHandlerIsNotPresentedAsADeclaringClass() {
        ActionHandler proxy = (ActionHandler) Proxy.newProxyInstance(
            ActionHandler.class.getClassLoader(), new Class<?>[]{ActionHandler.class},
            new InvocationHandler() {
                private final ActionHandler delegate = new ProbeHandler();

                @Override
                public Object invoke(Object self, Method method, Object[] args) {
                    if ("definition".equals(method.getName())) {
                        return delegate.definition();
                    }
                    throw new UnsupportedOperationException("заглушка измерения");
                }
            });
        ActionHandlerRegistry handlers = new ActionHandlerRegistry(List.of(proxy));
        ActionRegistry registry = new ActionRegistry(declaredWith(handlers), List.of());
        ActionProvenanceCatalog catalog = ActionProvenanceCatalog.ofBeans(
            registry, Map.of(), handlers, new DefaultListableBeanFactory());

        ActionRegistry.Key key = registry.registrationsWithKeys().stream()
            .map(ActionRegistry.Registration::key)
            .filter(candidate -> "probe.do".equals(candidate.id().value()))
            .findFirst()
            .orElseThrow();
        ActionProvenance expected = ActionProvenance.registration("",
            "место объявления не подтверждено");
        assertThat(catalog.declarationOf(key)).isEqualTo(expected);
        assertThat(catalog.executorOf(key)).isEqualTo(expected);
    }

    @Test
    void unknownKeyIsNotAttributedToAnything() {
        ActionProvenanceCatalog catalog = new ActionProvenanceCatalog(Map.of(), Map.of());

        ActionProvenance provenance = catalog.declarationOf(
            new ActionRegistry.Key(ActionSurface.ITEM_MENU, ProbeEntity.class, null,
                ActionId.of("probe.unknown")));

        assertThat(provenance.origin()).isEqualTo(FactOrigin.UNKNOWN);
        assertThat(provenance.symbol()).isEmpty();
        assertThat(provenance.note()).isEqualTo("место объявления не сообщается");
    }

    @Test
    void compositionFailsWhenProvenanceDoesNotCoverTheRegistry() {
        ActionHandlerRegistry empty = ActionHandlerRegistry.empty();
        List<ActionDefinition> declared = declaredWith(empty);
        declared.add(ActionDefinition.forEntity(ActionId.of("probe.hidden"),
            ActionSurface.ITEM_MENU, ProbeEntity.class, "Скрытое", null, 50, ActionRequirement.none()));
        ActionRegistry registry = new ActionRegistry(declared, List.of());

        assertThatThrownBy(() -> ActionProvenanceCatalog.ofBeans(registry, Map.of(), empty,
            new DefaultListableBeanFactory()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("не покрывает реестр");
    }

    @Test
    void compositionFailsWhenARegistrationHasNoProvenance() {
        ActionHandlerRegistry empty = ActionHandlerRegistry.empty();
        List<ActionDefinition> declared = declaredWith(empty);
        declared.add(ActionDefinition.forEntity(ActionId.of("probe.hidden"),
            ActionSurface.ITEM_MENU, ProbeEntity.class, "Скрытое", null, 50, ActionRequirement.none()));
        ActionRegistry registry = new ActionRegistry(declared, List.of());
        Map<String, ActionDefinition> beans = new LinkedHashMap<>();
        beans.put("probeSuppression", ActionDefinition.suppress(CrudAction.CREATE,
            ActionSurface.LIST_TOOLBAR, ProbeEntity.class));

        assertThatThrownBy(() -> ActionProvenanceCatalog.ofBeans(registry, beans, empty,
            new DefaultListableBeanFactory()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Регистрация без происхождения");
    }

    /** Сборка «как в FormAutoConfiguration»: реестр из трёх источников, затем каталог. */
    private static Fixture fixtureOf(AnnotationConfigApplicationContext context) {
        ActionHandlerRegistry handlers = new ActionHandlerRegistry(
            List.copyOf(context.getBeansOfType(ActionHandler.class).values()));
        List<ActionDefinition> declared = declaredWith(handlers);
        Map<String, ActionDefinition> beans =
            new LinkedHashMap<>(context.getBeansOfType(ActionDefinition.class));
        ActionRegistry registry = new ActionRegistry(declared, List.copyOf(beans.values()));
        ActionProvenanceCatalog catalog = ActionProvenanceCatalog.ofBeans(
            registry, beans, handlers, context.getBeanFactory());
        return new Fixture(registry, catalog);
    }

    private static List<ActionDefinition> declaredWith(ActionHandlerRegistry handlers) {
        List<ActionDefinition> declared = new ArrayList<>(CrudAction.platformDefaults());
        declared.addAll(handlers.definitions());
        return declared;
    }

    private record Fixture(ActionRegistry registry, ActionProvenanceCatalog catalog) {
    }

    /** Исполнитель, объявляющий собственное действие: и место объявления, и класс — он сам. */
    static class ProbeHandler implements ActionHandler {

        @Override
        public ActionDefinition definition() {
            return ActionDefinition.forEntity(ActionId.of("probe.do"), ActionSurface.ITEM_MENU,
                ProbeEntity.class, "Обработать", null, 20, ActionRequirement.none());
        }

        @Override
        public void execute(ActionInvocation invocation) {
            throw new UnsupportedOperationException("заглушка измерения");
        }
    }
}
