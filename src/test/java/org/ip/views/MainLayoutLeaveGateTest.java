package org.ip.views;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeLeaveEvent;
import org.ip.views.workspace.Workspace;
import org.junit.jupiter.api.Test;

import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2.4: где защита несохранённого нужна на переходе, а где она была бы вторым
 * подтверждением на одно действие.
 *
 * <p>Решение отделено от показа диалога намеренно: диалог требует живого UI, а правило —
 * «уничтожается ли оболочка на этом переходе» — обязано быть проверено, а не выведено из чтения
 * кода. Именно это правило отделяет вход по адресу (тот же route target, вкладки целы) от ухода
 * на другой адрес приложения (оболочка и все вкладки уничтожаются).</p>
 */
class MainLayoutLeaveGateTest {

    @Test
    void anAddressChangeInsideTheHostDoesNotAsk() {
        assertThat(MainLayout.leavesTheHost(MainLayout.class))
            .as("`/`, `/records/**` и `/lists/**` — один и тот же route target: экземпляр и рабочая"
                + " область сохраняются (измерено в E2.0), вкладки никуда не деваются, и"
                + " подтверждение здесь было бы ложным")
            .isFalse();
    }

    @Test
    void leavingForAnotherRouteTargetAsks() {
        assertThat(MainLayout.leavesTheHost(OtherView.class))
            .as("другой route target означает уничтожение оболочки вместе со всеми вкладками —"
                + " единственный переход, на котором теряются несохранённые формы")
            .isTrue();
    }

    @Test
    void aFailedSaveDoesNotContinueTheLeave() {
        BeforeLeaveEvent.ContinueNavigationAction action = action();
        Workspace workspace = failingWorkspace();

        MainLayout.saveThenContinue(workspace, action);

        verify(action, never()).proceed();
        verify(workspace, never()).closeAll();
    }

    @Test
    void aSuccessfulSaveContinuesTheLeaveAndClosesTheTabs() {
        BeforeLeaveEvent.ContinueNavigationAction action = action();
        Workspace workspace = succeedingWorkspace();

        MainLayout.saveThenContinue(workspace, action);

        // Вкладки уходят вместе с оболочкой: рабочая область живёт в UI scope и без закрытия
        // пережила бы экран, который их показывал (E2.4).
        InOrder order = inOrder(workspace, action);
        order.verify(workspace).saveUnsavedChanges();
        order.verify(workspace).closeAll();
        order.verify(action).proceed();
    }

    @Test
    void continuingWithoutSavingDiscardsTheTabsBeforeTheLeave() {
        BeforeLeaveEvent.ContinueNavigationAction action = action();
        Workspace workspace = mock(Workspace.class);

        MainLayout.leaveWithoutSaving(workspace, action);

        InOrder order = inOrder(workspace, action);
        order.verify(workspace).closeAll();
        order.verify(action).proceed();
        verify(workspace, never()).saveUnsavedChanges();
    }

    /**
     * Кнопка «Сохранить и продолжить»: переход продолжается только после успешного сохранения.
     *
     * <p>Провал оставляет адрес, вкладку и введённое на месте — иначе неудачное сохранение
     * уводило бы с формы, которую сервер только что отклонил, то есть теряло бы правки без
     * единого вопроса. Диалог для этого теста не нужен: проверяется правило, а не рендер.</p>
     */
    private static Workspace succeedingWorkspace() {
        return workspaceSaving(true);
    }

    private static Workspace failingWorkspace() {
        return workspaceSaving(false);
    }

    private static Workspace workspaceSaving(boolean result) {
        Workspace workspace = mock(Workspace.class);
        when(workspace.saveUnsavedChanges()).thenReturn(result);
        return workspace;
    }

    private static BeforeLeaveEvent.ContinueNavigationAction action() {
        return mock(BeforeLeaveEvent.ContinueNavigationAction.class);
    }

    /** Любой другой route target: важно тождество класса, а не то, какой это экран. */
    private static final class OtherView extends Div {
    }
}
