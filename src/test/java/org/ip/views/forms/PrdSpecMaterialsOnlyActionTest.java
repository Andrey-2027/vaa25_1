package org.ip.views.forms;

import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.coordinator.FormNavigator;
import org.ip.model.PrdSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * E1.6a: предметное действие «Только материалы» объявлено данными, а не предикатом по форме.
 *
 * <p>Проверяется ровно то, что раньше задавалось кодом внутри UI: применимость действия (нужна
 * выбранная строка), его поверхность и то, что оно открывает <b>предметный вариант</b> карточки.
 * Отсюда следует и важное для cutover свойство: объявление живёт в одном месте, поэтому одна и та
 * же подпись не может быть видимой по одному правилу и исполняться по другому.</p>
 */
class PrdSpecMaterialsOnlyActionTest {

    private final PrdSpecMaterialsOnlyAction action = new PrdSpecMaterialsOnlyAction();

    @Test
    void declarationDescribesASelectionOnlyRowActionOfTheSpecificationList() {
        ActionDefinition definition = action.definition();

        assertThat(definition.id()).isEqualTo(PrdSpecMaterialsOnlyAction.ID);
        assertThat(definition.surface()).isEqualTo(ActionSurface.LIST_TOOLBAR);
        assertThat(definition.entityType()).isEqualTo(PrdSpec.class);
        assertThat(definition.title()).isEqualTo("Только материалы");
        assertThat(definition.variant())
            .as("действие доступно во всех вариантах списка: вариант карточки — параметр открытия")
            .isNull();
        assertThat(definition.requirement().requiresSelection()).isTrue();
    }

    @Test
    void executionOpensTheMaterialsOnlyVariantOfTheSelectedRow() {
        PrdSpec selected = row(7L);
        FormNavigator navigator = mock(FormNavigator.class);

        action.execute(invocation(selected, navigator, () -> { }));

        verify(navigator).openItemForm(eq(PrdSpec.class),
            eq(PrdSpecMaterialsOnlyAction.MATERIALS_ONLY_VARIANT), eq(7L), any(), eq(null));
    }

    /** Сохранение в открытой карточке обновляет список через снимок, а не через форму. */
    @Test
    void savedRowRefreshesTheListThroughTheInvocation() {
        PrdSpec selected = row(7L);
        FormNavigator navigator = mock(FormNavigator.class);
        boolean[] refreshed = {false};

        action.execute(invocation(selected, navigator, () -> refreshed[0] = true));

        ArgumentCaptor<Consumer<PrdSpec>> onSaved = ArgumentCaptor.forClass(Consumer.class);
        verify(navigator).openItemForm(any(), any(), any(), onSaved.capture(), any());
        onSaved.getValue().accept(selected);

        assertThat(refreshed[0]).isTrue();
    }

    /**
     * Строка исчезла между решением и кликом — штатный случай, а не исключение: действие не
     * выполняется и никуда не навигирует.
     */
    @Test
    void executionWithoutSelectionDoesNothing() {
        FormNavigator navigator = mock(FormNavigator.class);

        action.execute(invocation(null, navigator, () -> { }));

        verify(navigator, never()).openItemForm(any(), any(), any(), any(), any());
    }

    /** Без навигации (локальное использование) действие не падает, а ничего не делает. */
    @Test
    void executionWithoutNavigatorDoesNotFail() {
        action.execute(invocation(row(7L), null, () -> { }));

        assertThat(action.definition().requirement().requiresSelection()).isTrue();
    }

    // --- фикстуры -------------------------------------------------------------------------------

    private static PrdSpec row(Long id) {
        PrdSpec spec = new PrdSpec();
        spec.setId(id);
        return spec;
    }

    private static ActionInvocation invocation(PrdSpec selected, FormNavigator navigator,
                                               Runnable refresh) {
        return new ActionInvocation(PrdSpec.class, null, selected, navigator, refresh,
            Map.of(), Map.of());
    }
}
