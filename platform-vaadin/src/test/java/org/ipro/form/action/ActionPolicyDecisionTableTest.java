package org.ipro.form.action;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.action.ActionDecision.Reason;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1.1: таблица решений вместо UI-теста. Каждая строка — сочетание входа и ожидаемого
 * типизированного исхода. Тест не поднимает Vaadin и не требует Spring: решение — чистая
 * функция.
 */
class ActionPolicyDecisionTableTest {

    private static final EntityCapabilities STANDARD_ROOT = capabilities(
        Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
        Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
        "standard root");

    /** Тип без generic записи: как SklNomOpa. */
    private static final EntityCapabilities NO_WRITES = capabilities(
        Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
        Set.of(), "набор immutable");

    /** Тип только на создание: как AttributeValue — DETAIL без UPDATE. */
    private static final EntityCapabilities CREATE_ONLY = capabilities(
        Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
        Set.of(DataOperation.CREATE), "значение атрибута бессмертно");

    /** Строка агрегата: ни LIST/DETAIL, ни записей. */
    private static final EntityCapabilities OWNED_ROW = capabilities(
        Set.of(FetchScenario.ROW), Set.of(), "owned row");

    @Test
    void createWaitsForRequiredContextAndThenBecomesAvailable() {
        ActionDefinition create = definition(CrudAction.CREATE, 0);

        assertThat(ActionPolicy.decide(create, context(STANDARD_ROOT, ActionPermission.allowed(), false, false)))
            .isEqualTo(ActionDecision.blocked(Reason.CONTEXT_INCOMPLETE, "Сначала заполните обязательный контекст"));

        assertThat(ActionPolicy.decide(create, context(STANDARD_ROOT, ActionPermission.allowed(), false, true)))
            .isEqualTo(ActionDecision.allowed());
    }

    @Test
    void createIsHiddenWhenTypeHasNoCreate() {
        ActionDefinition create = definition(CrudAction.CREATE, 0);

        ActionDecision decision = ActionPolicy.decide(create,
            context(NO_WRITES, ActionPermission.allowed(), false, true));

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(decision.message()).contains("CREATE");
    }

    @Test
    void createIsBlockedByRightsWhileStayingVisible() {
        ActionDefinition create = definition(CrudAction.CREATE, 0);

        ActionDecision decision = ActionPolicy.decide(create, context(STANDARD_ROOT,
            ActionPermission.of(false, "Нет прав на создание (измерение ENTITY:PrdSpec)",
                true, "", true, ""),
            false, true));

        assertThat(decision.visible()).isTrue();
        assertThat(decision.enabled()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(decision.message()).contains("ENTITY:PrdSpec");
    }

    @Test
    void editWaitsForSelection() {
        ActionDefinition edit = definition(CrudAction.EDIT, 1);

        assertThat(ActionPolicy.decide(edit, context(STANDARD_ROOT, ActionPermission.allowed(), false, false))
            .reason()).isEqualTo(Reason.NO_SELECTION);

        assertThat(ActionPolicy.decide(edit, context(STANDARD_ROOT, ActionPermission.allowed(), true, false)))
            .isEqualTo(ActionDecision.allowed());
    }

    @Test
    void editIsHiddenOnTypeWithoutUpdate() {
        ActionDecision decision = ActionPolicy.decide(definition(CrudAction.EDIT, 1),
            context(CREATE_ONLY, ActionPermission.allowed(), true, true));

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(decision.message()).contains("UPDATE");
    }

    /**
     * {@code crud.open} — единственное действие с требованием {@code UNAVAILABLE}: оно применимо
     * там, где изменение недоступно <b>по любой причине</b> — и по типу, и по правам на строку
     * (E1.2-pilot: вторая причина раньше не покрывалась, и строка с отказом в правах оставалась
     * без пути чтения).
     */
    @Test
    void openIsVisibleExactlyWhenUpdateIsUnavailable() {
        ActionDefinition open = definition(CrudAction.OPEN, 2);
        ActionPermission updateDenied = ActionPermission.of(true, "",
            false, "Нет прав на изменение (измерение JOURNAL)", true, "");

        // Тип без generic UPDATE — прежний носитель просмотра.
        assertThat(ActionPolicy.decide(open, context(CREATE_ONLY, ActionPermission.allowed(), true, true)))
            .isEqualTo(ActionDecision.allowed());

        // Права отказаны на выбранной строке — носитель, найденный gate'ом E1.2-pilot.
        assertThat(ActionPolicy.decide(open, context(STANDARD_ROOT, updateDenied, true, true)))
            .isEqualTo(ActionDecision.allowed());

        // Изменение доступно — просмотр не подменяет изменение.
        ActionDecision available = ActionPolicy.decide(open,
            context(STANDARD_ROOT, ActionPermission.allowed(), true, true));
        assertThat(available.visible()).isFalse();
        assertThat(available.reason()).isEqualTo(Reason.NOT_APPLICABLE);
        assertThat(available.message()).contains("UPDATE");
    }

    /**
     * Без выделенной строки строчные права не проверены, поэтому «изменение недоступно»
     * не утверждается, и просмотр остаётся скрытым: состав панели не должен зависеть от того,
     * выбрана ли строка (иначе «Просмотр» мелькал бы и исчезал при выборе изменяемой строки).
     */
    @Test
    void openDoesNotAppearBeforeRowRightsAreEvaluated() {
        ActionDefinition open = definition(CrudAction.OPEN, 2);

        ActionDecision withoutSelection = ActionPolicy.decide(open, context(STANDARD_ROOT,
            ActionPermission.forClass(true, ""), false, true));

        assertThat(withoutSelection.visible()).isFalse();
        assertThat(withoutSelection.reason()).isEqualTo(Reason.NOT_APPLICABLE);
    }

    @Test
    void openIsHiddenWhenTypeHasNoDetail() {
        ActionDecision decision = ActionPolicy.decide(definition(CrudAction.OPEN, 2),
            context(OWNED_ROW, ActionPermission.allowed(), true, true));

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(decision.message()).contains("DETAIL");
    }

    /**
     * E1.2a: без выделения строковые права не проверены, поэтому причина — {@code NO_SELECTION},
     * а не отказ в правах, которого никто не оценивал. Паритет с
     * {@code ListForm.configureGridSelection}: там RLS-проверка строки тоже выполняется только при
     * непустом выделении.
     */
    @Test
    void selectionIsReportedBeforeRowRights() {
        ActionDefinition edit = definition(CrudAction.EDIT, 1);

        ActionDecision decision = ActionPolicy.decide(edit, context(STANDARD_ROOT,
            ActionPermission.forClass(true, ""), false, false));

        assertThat(decision.visible()).isTrue();
        assertThat(decision.reason()).isEqualTo(Reason.NO_SELECTION);
    }

    /**
     * Права строки важнее незаполненного контекста — паритет с {@code ListForm.updateCreateButtonState},
     * где RLS-причина приоритетнее причины обязательного контекста.
     */
    @Test
    void rightsAreDecidedBeforeRequiredContext() {
        ActionDefinition edit = definition(CrudAction.EDIT, 1);

        ActionDecision decision = ActionPolicy.decide(edit, context(STANDARD_ROOT,
            ActionPermission.of(true, "", false, "", true, ""), true, false));

        assertThat(decision.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(decision.message()).isEqualTo(ActionPermission.DEFAULT_DENY_REASON);
    }

    @Test
    void structuralAbsenceOutweighsRights() {
        ActionDefinition create = definition(CrudAction.CREATE, 0);

        ActionDecision decision = ActionPolicy.decide(create, context(NO_WRITES,
            ActionPermission.of(false, true, true), false, false));

        assertThat(decision.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(decision.visible()).isFalse();
    }

    @Test
    void copyNeedsCreateAndDetailButNotRequiredContext() {
        ActionDefinition copy = definition(CrudAction.COPY, 3);

        ActionDecision withoutContext = ActionPolicy.decide(copy,
            context(STANDARD_ROOT, ActionPermission.allowed(), true, false));
        assertThat(withoutContext).isEqualTo(ActionDecision.allowed());

        ActionDecision withoutCreate = ActionPolicy.decide(copy,
            context(NO_WRITES, ActionPermission.allowed(), true, true));
        assertThat(withoutCreate.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);

        ActionDecision deniedCreate = ActionPolicy.decide(copy, context(STANDARD_ROOT,
            ActionPermission.of(false, true, true), true, true));
        assertThat(deniedCreate.reason()).isEqualTo(Reason.ACCESS_DENIED);

        ActionDecision withoutSelection = ActionPolicy.decide(copy,
            context(STANDARD_ROOT, ActionPermission.allowed(), false, true));
        assertThat(withoutSelection.reason()).isEqualTo(Reason.NO_SELECTION);
    }

    @Test
    void deleteIsHiddenOnTypeWithoutDelete() {
        ActionDecision decision = ActionPolicy.decide(definition(CrudAction.DELETE, 4),
            context(CREATE_ONLY, ActionPermission.allowed(), true, true));

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(decision.message()).contains("DELETE");
    }

    @Test
    void refreshIgnoresSelectionContextAndRights() {
        ActionDefinition refresh = definition(CrudAction.REFRESH, 5);

        ActionDecision decision = ActionPolicy.decide(refresh, context(NO_WRITES,
            ActionPermission.of(false, "нет чтения", false, "нет изменения", false, "нет удаления"),
            false, false));

        assertThat(decision).isEqualTo(ActionDecision.allowed());
    }

    @Test
    void suppressedDefinitionIsHiddenWithTypedReason() {
        ActionDefinition suppressed = definition(CrudAction.CREATE, 0).withVisible(false);

        ActionDecision decision = ActionPolicy.decide(suppressed,
            context(STANDARD_ROOT, ActionPermission.allowed(), false, true));

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.NOT_APPLICABLE);
    }

    @Test
    void actionableRequiresBothVisibleAndEnabled() {
        ActionDecision blocked = ActionDecision.blocked(Reason.NO_SELECTION, "нет строки");
        ActionDecision hidden = ActionDecision.hidden(Reason.TYPE_NOT_SUPPORTED, "нет операции");

        assertThat(blocked.actionable()).isFalse();
        assertThat(hidden.actionable()).isFalse();
        assertThat(ActionDecision.allowed().actionable()).isTrue();
    }

    private static ActionDefinition definition(CrudAction action, int order) {
        return ActionDefinition.platformDefault(action, ActionSurface.LIST_TOOLBAR,
            action.name(), null, order);
    }

    private static ActionContext context(EntityCapabilities capabilities, ActionPermission permission,
                                         boolean selection, boolean requiredContext) {
        return new ActionContext(TestEntity.class, null, capabilities, permission, selection,
            requiredContext, ActionContext.RowState.EXISTING, null);
    }

    private static EntityCapabilities capabilities(Set<FetchScenario> reads, Set<DataOperation> writes,
                                                   String reason) {
        return new EntityCapabilities(reads, writes, reason);
    }

    /** Тип-ярлык: решению важен класс как ключ, а не его состав. */
    static class TestEntity {
    }
}
