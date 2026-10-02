package org.ipro.lifecycle;

import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (runtime slice) + E3.1 шаг 4: инспекция lifecycle handler'а доказывает своё поведение
 * в модуле-владельце реестра.
 *
 * <p>Ключевое различие, которое здесь проверяется: «хук не переопределён» и «переопределение
 * не подтверждено» — разные факты. Второй случай даёт {@link EntityLifecycleInspection#of}
 * пустой ответ, а не шесть строк «default».</p>
 */
class EntityLifecycleInspectionTest {

    @Test
    void classifiesOverriddenHookAndLeavesTheRestAsContractDefaults() {
        EntityLifecycleInspection.Assessment assessment =
            EntityLifecycleInspection.of(new RecordingHandler()).orElseThrow();

        assertThat(assessment.handlerType()).isEqualTo(RecordingHandler.class);
        assertThat(assessment.hooks()).hasSize(6);
        assertThat(assessment.hooks()).filteredOn(EntityLifecycleInspection.Hook::declared)
            .extracting(EntityLifecycleInspection.Hook::name)
            .containsExactly("beforeAggregateSave");
        assertThat(assessment.hooks()).filteredOn(hook -> !hook.declared())
            .extracting(EntityLifecycleInspection.Hook::name)
            .containsExactly("beforeSave", "beforeUpdate", "beforeDelete", "onSave", "afterCommit");
    }

    @Test
    void unrollsJdkProxyToItsTargetClass() {
        ProxyFactory factory = new ProxyFactory(new RecordingHandler());
        EntityLifecycle<?> proxy = (EntityLifecycle<?>) factory.getProxy();

        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        EntityLifecycleInspection.Assessment assessment =
            EntityLifecycleInspection.of(proxy).orElseThrow();

        assertThat(assessment.handlerType()).isEqualTo(RecordingHandler.class);
        assertThat(assessment.hooks()).filteredOn(EntityLifecycleInspection.Hook::declared)
            .extracting(EntityLifecycleInspection.Hook::name)
            .containsExactly("beforeAggregateSave");
    }

    @Test
    void unrollsCglibProxyToItsTargetClass() {
        ProxyFactory factory = new ProxyFactory(new RecordingHandler());
        factory.setProxyTargetClass(true);
        EntityLifecycle<?> proxy = (EntityLifecycle<?>) factory.getProxy();

        assertThat(Proxy.isProxyClass(proxy.getClass())).isFalse();
        assertThat(proxy.getClass()).isNotEqualTo(RecordingHandler.class);
        EntityLifecycleInspection.Assessment assessment =
            EntityLifecycleInspection.of(proxy).orElseThrow();

        assertThat(assessment.handlerType()).isEqualTo(RecordingHandler.class);
        assertThat(assessment.hooks()).filteredOn(EntityLifecycleInspection.Hook::declared)
            .extracting(EntityLifecycleInspection.Hook::name)
            .containsExactly("beforeAggregateSave");
    }

    @Test
    void refusesToGuessForJavaProxyCreatedOutsideSpring() {
        EntityLifecycle<?> proxy = (EntityLifecycle<?>) Proxy.newProxyInstance(
            EntityLifecycle.class.getClassLoader(),
            new Class<?>[] {EntityLifecycle.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "entityType" -> InspectionEntity.class;
                case "toString" -> "hand-built proxy";
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                default -> null;
            });

        assertThat(EntityLifecycleInspection.of(proxy)).isEmpty();
    }

    @Test
    void subclassOfHandlerIsClassifiedByItsActualOverride() {
        EntityLifecycleInspection.Assessment assessment =
            EntityLifecycleInspection.of(new DerivedHandler()).orElseThrow();

        assertThat(assessment.handlerType()).isEqualTo(DerivedHandler.class);
        assertThat(assessment.hooks()).filteredOn(EntityLifecycleInspection.Hook::declared)
            .extracting(EntityLifecycleInspection.Hook::name)
            .containsExactly("beforeDelete");
    }

    /** Guard: новый хук в контракте обязан появиться в ответе, а не пропасть молча. */
    @Test
    void hookNamesAreExactlyTheContractMethodsWithoutEntityType() {
        Set<String> reported = EntityLifecycleInspection.of(new RecordingHandler()).orElseThrow()
            .hooks().stream()
            .map(EntityLifecycleInspection.Hook::name)
            .collect(Collectors.toSet());
        Set<String> contract = Arrays.stream(EntityLifecycle.class.getMethods())
            .filter(method -> method.getDeclaringClass() == EntityLifecycle.class)
            .map(Method::getName)
            .filter(name -> !"entityType".equals(name))
            .collect(Collectors.toSet());

        assertThat(reported).isEqualTo(contract);
    }

    @Test
    void orderAndValuesAreDeterministic() {
        EntityLifecycle<?> handler = new RecordingHandler();

        List<EntityLifecycleInspection.Hook> first =
            EntityLifecycleInspection.of(handler).orElseThrow().hooks();
        List<EntityLifecycleInspection.Hook> second =
            EntityLifecycleInspection.of(handler).orElseThrow().hooks();

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(EntityLifecycleInspection.Hook::name)
            .containsExactly("beforeSave", "beforeUpdate", "beforeAggregateSave",
                "beforeDelete", "onSave", "afterCommit");
    }

    static class InspectionEntity implements IdentifiableEntity {
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    static class RecordingHandler implements EntityLifecycle<InspectionEntity> {

        @Override
        public Class<InspectionEntity> entityType() {
            return InspectionEntity.class;
        }

        @Override
        public void beforeAggregateSave(AggregateSaveContext<InspectionEntity> context) {
        }
    }

    static class BaseHandler implements EntityLifecycle<InspectionEntity> {

        @Override
        public Class<InspectionEntity> entityType() {
            return InspectionEntity.class;
        }

        @Override
        public void beforeDelete(EntityDeleteContext<InspectionEntity> context) {
        }
    }

    static final class DerivedHandler extends BaseHandler {
    }
}
