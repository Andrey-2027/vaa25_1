package org.ipro.form.action;

import org.ipro.data.EntityCapabilities;
import org.ipro.fetch.plan.FetchScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E1.4: подавление платформенного действия для конкретного типа (suppress-override, §2.4/§2.3).
 *
 * <p>Подавление — не третий механизм регистрации, а override с {@code visible = false}: оно обязано
 * находить свою цель (иначе опечатка создала бы молча новое действие), не задевать другие типы и не
 * подменять собой проверку прав. Требование у подавленной записи не вычисляется — проверяется и это:
 * подавление не должно «случайно разрешать» через пустое требование.</p>
 */
class ActionSuppressionTest {

    /** Тип с полным generic CRUD: на нём видно, что подавление не задевает соседей. */
    private static final class Catalog {
    }

    /** Тип, у которого generic создание скрыто по предметной причине. */
    private static final class Interned {
    }

    private static EntityCapabilities fullCrud() {
        return new EntityCapabilities(Set.of(FetchScenario.LIST, FetchScenario.DETAIL),
            Set.of(org.ipro.data.DataOperation.CREATE, org.ipro.data.DataOperation.UPDATE,
                org.ipro.data.DataOperation.DELETE),
            "test fixture");
    }

    private static ActionContext context() {
        return new ActionContext(Catalog.class, null, fullCrud(), ActionPermission.allowed(),
            false, true, ActionContext.RowState.NEW, null);
    }

    private static ActionContext contextFor(Class<?> type) {
        return new ActionContext(type, null, fullCrud(), ActionPermission.allowed(),
            false, true, ActionContext.RowState.NEW, null);
    }

    @Test
    void suppressedActionIsNotOfferedForThatTypeOnly() {
        ActionRegistry registry = new ActionRegistry(CrudAction.platformDefaults(),
            List.of(ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR,
                Interned.class)));
        ActionDecision suppressed = ActionPolicy.decide(
            definition(registry, Interned.class, CrudAction.CREATE), contextFor(Interned.class));
        ActionDecision untouched = ActionPolicy.decide(
            definition(registry, Catalog.class, CrudAction.CREATE), context());

        assertThat(suppressed.visible()).isFalse();
        assertThat(suppressed.reason()).isEqualTo(ActionDecision.Reason.NOT_APPLICABLE);
        assertThat(untouched.actionable())
            .as("подавление адресовано типу, а не действию вообще")
            .isTrue();
        assertThat(definition(registry, Interned.class, CrudAction.CREATE).visible()).isFalse();
    }

    @Test
    void suppressionHidesOnlyTheNamedAction() {
        ActionRegistry registry = new ActionRegistry(CrudAction.platformDefaults(),
            List.of(ActionDefinition.suppress(CrudAction.COPY, ActionSurface.LIST_TOOLBAR,
                Interned.class)));

        assertThat(definition(registry, Interned.class, CrudAction.COPY).visible()).isFalse();
        assertThat(definition(registry, Interned.class, CrudAction.CREATE).visible())
            .as("соседнее действие того же типа не затронуто")
            .isTrue();
        assertThat(definition(registry, Interned.class, CrudAction.REFRESH).visible()).isTrue();
    }

    @Test
    void suppressedDefinitionDoesNotBecomeAPermissiveRequirement() {
        ActionRegistry registry = new ActionRegistry(CrudAction.platformDefaults(),
            List.of(ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR,
                Interned.class)));

        ActionDefinition suppressed = definition(registry, Interned.class, CrudAction.CREATE);

        assertThat(suppressed.requirement().rowStateSelectsOperation()).isFalse();
        assertThat(suppressed.requirement().requiresRequiredContext()).isFalse();
        assertThat(suppressed.visible()).isFalse();
    }

    @Test
    void suppressionWithoutTargetFailsAtStartup() {
        assertThatThrownBy(() -> new ActionRegistry(CrudAction.platformDefaults(),
            List.of(ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.ITEM_FOOTER,
                Interned.class))))
            .as("crud.create не объявлен на подвале карточки: опечатка обязана падать,"
                + " а не создавать новое действие")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("не находит цель");
    }

    @Test
    void suppressionDoesNotChangeCapabilitiesOfTheType() {
        ActionRegistry registry = new ActionRegistry(CrudAction.platformDefaults(),
            List.of(ActionDefinition.suppress(CrudAction.DELETE, ActionSurface.LIST_TOOLBAR,
                Interned.class)));

        ActionContext context = new ActionContext(Interned.class, null, fullCrud(),
            ActionPermission.allowed(), true, true, ActionContext.RowState.EXISTING, null);

        assertThat(ActionPolicy.decide(definition(registry, Interned.class, CrudAction.DELETE),
            context).visible())
            .as("подавление — решение UI, а не изменение прав или возможностей типа")
            .isFalse();
        assertThat(ActionPolicy.decide(definition(registry, Interned.class, CrudAction.EDIT), context)
            .actionable())
            .isTrue();
    }

    private static ActionDefinition definition(ActionRegistry registry, Class<?> entityType,
                                               CrudAction action) {
        return registry.resolve(ActionSurface.LIST_TOOLBAR, entityType, null, action.id())
            .orElseThrow(() -> new AssertionError("нет регистрации " + action.id()));
    }
}
