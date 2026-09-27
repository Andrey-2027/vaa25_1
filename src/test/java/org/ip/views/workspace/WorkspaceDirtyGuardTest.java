package org.ip.views.workspace;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.internal.ExecutionContext;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2.4: единый dirty-контракт рабочей области.
 *
 * <p>Тест держит то, что до E2.4 решалось отдельно на каждом пути: «есть ли что терять» — один
 * вопрос, и закрытие вкладки, уход по маршруту и нативная защита перезагрузки обязаны получать
 * один ответ. Второй предикат где-нибудь из трёх дал бы ровно то, от чего эта веха уходит:
 * путь, который тихо теряет данные, потому что смотрел не туда.</p>
 *
 * <p>Нативная защита проверяется решением, а не JS: {@code applyUnloadGuard} — шов, через который
 * видно, поставился бы обработчик выгрузки или снялся. Сам JS требует браузера, и его поведение
 * проверяется наблюдением.</p>
 */
class WorkspaceDirtyGuardTest {

    private final RecordingWorkspace workspace = new RecordingWorkspace();

    @Test
    void cleanTabsHaveNothingToLose() {
        workspace.openComponent(new PlainTab(false), "clean", "Чистая");

        assertThat(workspace.hasUnsavedChanges()).isFalse();
        assertThat(workspace.unsavedEntryIds()).isEmpty();
    }

    @Test
    void unsavedChangesFollowTheTabStateAndNameTheirTabs() {
        PlainTab first = new PlainTab(false);
        PlainTab second = new PlainTab(false);
        workspace.openComponent(first, "first", "Первая");
        workspace.openComponent(second, "second", "Вторая");

        first.dirty = true;
        second.dirty = true;
        assertThat(workspace.hasUnsavedChanges()).isTrue();
        assertThat(workspace.unsavedEntryIds())
            .as("порядок вкладок — порядок открытия: подтверждение не должно зависеть от того,"
                + " какая из них активна")
            .containsExactly("first", "second");

        second.dirty = false;
        assertThat(workspace.unsavedEntryIds()).containsExactly("first");
    }

    @Test
    void theMessageComesFromTheFirstUnsavedTab() {
        PlainTab clean = new PlainTab(false);
        PlainTab dirty = new PlainTab(false);
        dirty.message = "В карточке есть несохранённые изменения. Сохранить их перед закрытием?";
        workspace.openComponent(clean, "clean", "Чистая");
        workspace.openComponent(dirty, "dirty", "Грязная");
        dirty.dirty = true;

        assertThat(workspace.unsavedChangesMessage()).isEqualTo(dirty.message);
    }

    @Test
    void aReadOnlyTabCannotBeSavedAndSoIsNotOfferedForSaving() {
        ReadOnlyTab tab = new ReadOnlyTab();
        workspace.openComponent(tab, "view-only", "Просмотр");
        tab.dirty = true;

        assertThat(workspace.hasUnsavedChanges()).isTrue();
        assertThat(workspace.canSaveUnsavedChanges())
            .as("кнопка «Сохранить и продолжить» на карточке просмотра пообещала бы запись,"
                + " которую сервер отклонит (E1.5)")
            .isFalse();
    }

    @Test
    void aDirtyTabThatCannotBeSavedStopsTheSaveBeforeTheFirstWrite() {
        SaveableTab saveable = new SaveableTab();
        ReadOnlyTab viewOnly = new ReadOnlyTab();
        workspace.openComponent(saveable, "saveable", "Сохраняемая");
        workspace.openComponent(viewOnly, "view-only", "Просмотр");
        saveable.dirty = true;
        viewOnly.dirty = true;

        assertThat(workspace.canSaveUnsavedChanges())
            .as("«Сохранить и продолжить» не обещает то, чего не может: вкладка просмотра не станет"
                + " чище ни от одного сохранения")
            .isFalse();
        assertThat(workspace.saveUnsavedChanges()).isFalse();
        assertThat(saveable.saved)
            .as("частичное сохранение перед отказом — это уже запись, которая осталась бы без"
                + " продолжения: отказ обязан быть до первой записи")
            .isFalse();
    }

    @Test
    void aSaveThatLeavesTheTabDirtyDoesNotOpenTheWay() {
        StaysDirtyTab tab = new StaysDirtyTab();
        workspace.openComponent(tab, "tab", "Вкладка");
        tab.dirty = true;

        assertThat(workspace.saveUnsavedChanges())
            .as("уход продолжается только тогда, когда грязных вкладок не осталось: ответ"
                + " doSave() — не то же самое, что чистое состояние")
            .isFalse();
    }

    @Test
    void aFailedSaveStopsTheLeaveBeforeTouchingTheNextTab() {
        SaveableTab first = new SaveableTab();
        SaveableTab second = new SaveableTab();
        first.saveSucceeds = false;
        workspace.openComponent(first, "first", "Первая");
        workspace.openComponent(second, "second", "Вторая");
        first.dirty = true;
        second.dirty = true;

        assertThat(workspace.saveUnsavedChanges()).isFalse();
        assertThat(second.saved)
            .as("сохранять остальные после неудачи нельзя: провал мог быть про невалидное поле,"
                + " и дописывать данные в других формах значило бы продолжать уже неудавшееся"
                + " действие")
            .isFalse();
    }

    @Test
    void everySaveMustSucceedBeforeTheWayIsClear() {
        SaveableTab first = new SaveableTab();
        SaveableTab second = new SaveableTab();
        workspace.openComponent(first, "first", "Первая");
        workspace.openComponent(second, "second", "Вторая");
        first.dirty = true;
        second.dirty = true;

        assertThat(workspace.saveUnsavedChanges()).isTrue();
        assertThat(first.saved).isTrue();
        assertThat(second.saved).isTrue();
        assertThat(first.dirty).isFalse();
        assertThat(second.dirty).isFalse();
        assertThat(workspace.hasUnsavedChanges()).isFalse();
    }

    @Test
    void leavingTheHostClosesEveryTabAndRemovesTheGuard() {
        PlainTab clean = new PlainTab(false);
        SaveableTab dirty = new SaveableTab();
        Div state = new Div();
        workspace.openComponent(clean, "clean", "Чистая");
        workspace.openComponent(dirty, "dirty", "Грязная");
        dirty.dirty = true;
        workspace.refreshUnloadGuard();
        workspace.showTransientContent(state);
        workspace.guardCalls.clear();

        workspace.closeAll();

        assertThat(workspace.unsavedEntryIds()).isEmpty();
        assertThat(workspace.hasUnsavedChanges())
            .as("вкладки живут, пока живёт экран: иначе после ухода оставался бы стоять"
                + " beforeunload, а при следующем входе вернулись бы вкладки, правки которых"
                + " пользователь отказался сохранять")
            .isFalse();
        assertThat(workspace.activeEntryId()).isNull();
        assertThat(state.getParent()).isEmpty();
        assertThat(workspace.guardCalls)
            .as("обработчик выгрузки — часть того же состояния: уходит вместе с вкладками")
            .containsExactly(false);
    }

    @Test
    void theUnloadGuardIsInstalledOnlyWhileSomethingCanBeLost() {
        PlainTab tab = new PlainTab(false);
        workspace.openComponent(tab, "tab", "Вкладка");
        assertThat(workspace.guardCalls).isEmpty();

        tab.dirty = true;
        workspace.refreshUnloadGuard();
        assertThat(workspace.guardCalls)
            .as("предупреждение при выгрузке обязано стоять ровно тогда, когда есть что терять:"
                + " иначе предупреждение на чистых вкладках обесценивает его на грязных")
            .containsExactly(true);

        workspace.refreshUnloadGuard();
        assertThat(workspace.guardCalls).as("неизменное решение не переставляет обработчик").containsExactly(true);

        tab.dirty = false;
        workspace.refreshUnloadGuard();
        assertThat(workspace.guardCalls).containsExactly(true, false);
    }

    @Test
    void savingRemovesTheUnloadGuardAsASideEffect() {
        SaveableTab tab = new SaveableTab();
        workspace.openComponent(tab, "tab", "Вкладка");
        tab.dirty = true;
        workspace.refreshUnloadGuard();

        assertThat(workspace.saveUnsavedChanges()).isTrue();

        assertThat(workspace.guardCalls).containsExactly(true, false);
    }

    @Test
    void anInstallationThatCouldNotHappenIsNotRemembered() {
        PlainTab tab = new PlainTab(false);
        workspace.openComponent(tab, "tab", "Вкладка");
        tab.dirty = true;
        workspace.canApply = false;

        workspace.refreshUnloadGuard();
        workspace.canApply = true;
        workspace.refreshUnloadGuard();

        assertThat(workspace.guardCalls)
            .as("неудавшаяся установка не должна считаться принятым решением: иначе обработчик"
                + " вкладки ещё не в UI не появился бы уже никогда, а форма при этом грязная")
            .containsExactly(true, true);
    }

    @Test
    void theDecisionIsReconsideredAfterTheInputOfTheSameRequest() {
        PlainTab tab = new PlainTab(false);
        workspace.openComponent(tab, "tab", "Вкладка");
        workspace.guardCalls.clear();

        List<SerializableConsumer<ExecutionContext>> queued = new ArrayList<>();
        UI ui = mock(UI.class);
        when(ui.beforeClientResponse(any(), any())).thenAnswer(invocation -> {
            queued.add(invocation.getArgument(1));
            return null;
        });

        workspace.queueUnloadGuardRefresh(ui);
        workspace.queueUnloadGuardRefresh(ui);
        assertThat(queued)
            .as("один заказ на запрос: несколько одинаковых заданий в очереди — это одно и то же решение,"
                + " посчитанное несколько раз")
            .hasSize(1);
        assertThat(workspace.guardCalls)
            .as("заказ сам по себе ещё ничего не решает: считать надо, когда ввод уже применён")
            .isEmpty();

        tab.dirty = true;
        queued.get(0).accept(null);

        assertThat(workspace.guardCalls)
            .as("решение перечитывается в конце запроса, принёсшего правку, — иначе оно отставало бы"
                + " на целый запрос, а следующего запроса может и не быть")
            .containsExactly(true);

        workspace.queueUnloadGuardRefresh(ui);
        assertThat(queued).as("следующий запрос заказывает пересчёт снова").hasSize(2);
    }

    /** Рабочая область с записанными решениями по нативной защите. */
    private static final class RecordingWorkspace extends Workspace {

        private final List<Boolean> guardCalls = new ArrayList<>();

        /** Может ли решение быть исполнено: {@code false} — «некому передать». */
        boolean canApply = true;

        RecordingWorkspace() {
            super(mock(WorkspaceManager.class));
        }

        @Override
        boolean applyUnloadGuard(boolean installed) {
            guardCalls.add(installed);
            return canApply;
        }
    }

    /** Вкладка без сохранения: только dirty-состояние. */
    private static class PlainTab extends Div implements Dirtyable {

        boolean dirty;
        String message = "Есть несохранённые изменения.";

        PlainTab(boolean dirty) {
            this.dirty = dirty;
        }

        @Override
        public boolean isDirty() {
            return dirty;
        }

        @Override
        public String getCloseConfirmMessage() {
            return message;
        }
    }

    /** Вкладка, которая умеет сохраняться: с исходом, который заказывает тест. */
    private static class SaveableTab extends PlainTab implements Savable {

        boolean saveSucceeds = true;
        boolean saved;

        SaveableTab() {
            super(false);
        }

        @Override
        public boolean doSave() {
            saved = true;
            if (saveSucceeds) {
                dirty = false;
            }
            return saveSucceeds;
        }
    }

    /** Режим просмотра (E1.5): грязная, но сохранять нечего. */
    private static final class ReadOnlyTab extends SaveableTab {

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public boolean doSave() {
            throw new IllegalStateException("режим просмотра не сохраняет: вызов означает дефект"
                + " ветки диалога");
        }
    }

    /** Сохранение отчиталось успехом, но вкладка осталась грязной: уход обязан отказаться. */
    private static final class StaysDirtyTab extends SaveableTab {

        @Override
        public boolean doSave() {
            saved = true;
            return true;
        }
    }
}
