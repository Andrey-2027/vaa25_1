package org.ipro.form.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E1.1: реестр на ключе {@code (surface, entityType, variant, actionId)}.
 *
 * <p>Проверяется не «список действий», а решение: дубликат без override обязан падать на старте,
 * override обязан найти свою цель, порядок регистрации не влияет на результат, а
 * «обязано отсутствовать» выражается тем же override'ом, что и замена.</p>
 */
class ActionRegistryResolutionTest {

    private static final ActionDefinition GENERIC_CREATE = defaultOf(CrudAction.CREATE, 0);
    private static final ActionDefinition GENERIC_EDIT = defaultOf(CrudAction.EDIT, 1);

    @Test
    void genericDefaultResolvesForAnyTypeAndVariant() {
        ActionRegistry registry = new ActionRegistry(List.of(GENERIC_CREATE), List.of());

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null, GENERIC_CREATE.id()))
            .contains(GENERIC_CREATE);
        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Beta.class, "archived", GENERIC_CREATE.id()))
            .contains(GENERIC_CREATE);
    }

    @Test
    void perTypeOverrideWinsOverGenericDefaultAndLeavesOthersUntouched() {
        ActionDefinition alphaCreate = forType(CrudAction.CREATE, Alpha.class, null, 5);
        ActionRegistry registry = new ActionRegistry(List.of(GENERIC_CREATE), List.of(alphaCreate));

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null, GENERIC_CREATE.id()))
            .contains(alphaCreate);
        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Beta.class, null, GENERIC_CREATE.id()))
            .contains(GENERIC_CREATE);
    }

    @Test
    void mostSpecificKeyWinsAcrossTheChain() {
        ActionDefinition variantScoped = forType(CrudAction.CREATE, null, "archived", 9);
        ActionDefinition typeScoped = forType(CrudAction.CREATE, Alpha.class, null, 7);
        ActionDefinition bothScoped = forType(CrudAction.CREATE, Alpha.class, "archived", 11);

        ActionRegistry registry = new ActionRegistry(List.of(GENERIC_CREATE),
            List.of(variantScoped, typeScoped, bothScoped));

        // (type,variant) — самое конкретное
        assertThat(orderFor(registry, Alpha.class, "archived")).isEqualTo(11);
        // (type,null)
        assertThat(orderFor(registry, Alpha.class, "other")).isEqualTo(7);
        // (null,variant) вместо generic
        assertThat(orderFor(registry, Beta.class, "archived")).isEqualTo(9);
        // (null,null)
        assertThat(orderFor(registry, Beta.class, null)).isEqualTo(0);
    }

    @Test
    void typeScopedOverrideBeatsVariantScopedOne() {
        // Порядок из плана §2.4: (type,null) → (null,variant). Проверяется явно, потому что
        // обратный порядок тоже выглядел бы разумным и молча менял бы поведение.
        ActionDefinition variantScoped = forType(CrudAction.CREATE, null, "archived", 9);
        ActionDefinition typeScoped = forType(CrudAction.CREATE, Alpha.class, null, 7);

        ActionRegistry registry = new ActionRegistry(List.of(GENERIC_CREATE),
            List.of(variantScoped, typeScoped));

        assertThat(orderFor(registry, Alpha.class, "archived")).isEqualTo(7);
    }

    @Test
    void duplicateRegistrationFailsAtStartup() {
        assertThatThrownBy(() -> new ActionRegistry(List.of(GENERIC_CREATE, GENERIC_CREATE), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат")
            .hasMessageContaining("crud.create");
    }

    @Test
    void doubleOverrideOfTheSameKeyFailsAtStartup() {
        ActionDefinition first = forType(CrudAction.CREATE, Alpha.class, null, 1);
        ActionDefinition second = forType(CrudAction.CREATE, Alpha.class, null, 2);

        assertThatThrownBy(() -> new ActionRegistry(List.of(GENERIC_CREATE), List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Двойной override");
    }

    @Test
    void overrideWithoutBroaderTargetFailsAtStartup() {
        ActionDefinition orphan = forType(CrudAction.DELETE, Alpha.class, null, 1);

        assertThatThrownBy(() -> new ActionRegistry(List.of(GENERIC_CREATE), List.of(orphan)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("не находит цель")
            .hasMessageContaining("crud.delete");
    }

    @Test
    void overrideCanReplaceTheDefaultAtTheSameKey() {
        ActionDefinition replaced = GENERIC_CREATE.withOrder(42);
        ActionRegistry registry = new ActionRegistry(List.of(GENERIC_CREATE), List.of(replaced));

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null, GENERIC_CREATE.id()))
            .map(ActionDefinition::order).contains(42);
    }

    @Test
    void suppressionIsAnOverrideWithVisibleFalse() {
        ActionDefinition suppressed = forType(CrudAction.CREATE, Alpha.class, null, 0).withVisible(false);
        ActionRegistry registry = new ActionRegistry(List.of(GENERIC_CREATE), List.of(suppressed));

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null, GENERIC_CREATE.id()))
            .map(ActionDefinition::visible).contains(false);
        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Beta.class, null, GENERIC_CREATE.id()))
            .map(ActionDefinition::visible).contains(true);
    }

    @Test
    void resolutionDoesNotDependOnRegistrationOrder() {
        ActionDefinition alphaCreate = forType(CrudAction.CREATE, Alpha.class, null, 5);

        ActionRegistry first = new ActionRegistry(List.of(GENERIC_CREATE, GENERIC_EDIT),
            List.of(alphaCreate));
        ActionRegistry second = new ActionRegistry(List.of(GENERIC_EDIT, GENERIC_CREATE),
            List.of(alphaCreate));

        assertThat(first.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null))
            .isEqualTo(second.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null));
        assertThat(first.resolve(ActionSurface.LIST_TOOLBAR, Beta.class, "archived"))
            .isEqualTo(second.resolve(ActionSurface.LIST_TOOLBAR, Beta.class, "archived"));
    }

    @Test
    void resolvedListIsSortedByOrderThenId() {
        ActionDefinition late = defaultOf(CrudAction.EDIT, 10);
        ActionDefinition early = defaultOf(CrudAction.CREATE, 1);
        ActionDefinition earlySameOrder = new ActionDefinition(CrudAction.COPY.id(),
            ActionSurface.LIST_TOOLBAR, "Копировать", null, null, null, 1,
            CrudAction.COPY.requirement(), true);

        ActionRegistry registry = new ActionRegistry(List.of(late, earlySameOrder, early), List.of());

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null))
            .extracting(definition -> definition.id().value())
            .containsExactly("crud.copy", "crud.create", "crud.edit");
    }

    @Test
    void sameIdOnDifferentSurfacesIsNotADuplicate() {
        ActionDefinition listCreate = defaultOf(CrudAction.CREATE, 0);
        ActionDefinition footerCreate = new ActionDefinition(CrudAction.CREATE.id(),
            ActionSurface.ITEM_FOOTER, "Создать", null, null, null, 0,
            CrudAction.CREATE.requirement(), true);

        ActionRegistry registry = new ActionRegistry(List.of(listCreate, footerCreate), List.of());

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Alpha.class, null)).hasSize(1);
        assertThat(registry.resolve(ActionSurface.ITEM_FOOTER, Alpha.class, null)).hasSize(1);
    }

    private static ActionDefinition defaultOf(CrudAction action, int order) {
        return ActionDefinition.platformDefault(action, ActionSurface.LIST_TOOLBAR,
            action.name(), null, order);
    }

    private static ActionDefinition forType(CrudAction action, Class<?> type, String variant, int order) {
        return new ActionDefinition(action.id(), ActionSurface.LIST_TOOLBAR, action.name(), null,
            type, variant, order, action.requirement(), true);
    }

    private static int orderFor(ActionRegistry registry, Class<?> type, String variant) {
        return registry.resolve(ActionSurface.LIST_TOOLBAR, type, variant, GENERIC_CREATE.id())
            .orElseThrow()
            .order();
    }

    static class Alpha {
    }

    static class Beta {
    }
}
