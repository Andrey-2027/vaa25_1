package org.ip.views.workspace;

import com.vaadin.flow.component.html.Div;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * E2.3: поведение рабочей области, на которое опирается host адреса.
 *
 * <p>Это не тест вкладок вообще, а тест трёх вещей, ради которых WorkspaceGateway расширен:
 * смена активной вкладки обязана быть событием, активация — существовать отдельно от открытия,
 * и состояние адреса обязано показываться <b>вместо</b> содержимого, не становясь вкладкой.
 * Без них адрес не удержать в соответствии с видимым содержимым.</p>
 *
 * <p>Компоненты создаются напрямую ({@code openComponent}), поэтому {@code WorkspaceManager} не
 * участвует: он отвечает за создание View по классу, а здесь проверяются вкладки и события.</p>
 */
class WorkspaceRouteStateTest {

    private final Workspace workspace = new Workspace(mock(WorkspaceManager.class));
    private final List<String> activeChanges = new ArrayList<>();

    @Test
    void openingAndSwitchingTabsReportTheActiveEntryOnce() {
        workspace.addActiveEntryListener(activeChanges::add);

        workspace.openComponent(new Div(), "first", "Первая");
        workspace.openComponent(new Div(), "second", "Вторая");

        assertThat(activeChanges)
            .as("одно открытие — одно уведомление: повтор рассылки заставлял бы мост дважды"
                + " решать, какой адрес писать")
            .containsExactly("first", "second");
        assertThat(workspace.activeEntryId()).isEqualTo("second");
    }

    @Test
    void closingTheLastTabReportsThatNothingIsActive() {
        workspace.addActiveEntryListener(activeChanges::add);
        workspace.openComponent(new Div(), "only", "Единственная");

        workspace.close("only");

        assertThat(activeChanges)
            .as("активная вкладка исчезла — адрес обязан перестать указывать на закрытую форму,"
                + " а не остаться ей")
            .containsExactly("only", null);
        assertThat(workspace.activeEntryId()).isNull();
    }

    @Test
    void closingTheActiveTabReportsTheCloseBeforeTheNewActiveEntry() {
        List<String> events = new ArrayList<>();
        workspace.addEntryClosedListener(entryId -> events.add("closed:" + entryId));
        workspace.addActiveEntryListener(entryId -> events.add("active:" + entryId));
        workspace.openComponent(new Div(), "first", "Первая");
        workspace.openComponent(new Div(), "second", "Вторая");
        events.clear();

        workspace.close("second");

        assertThat(events)
            .as("порядок — часть контракта: мост адреса решает «заменить запись или добавить"
                + " шаг» по факту закрытия, и услышать его после смены активности значило бы"
                + " уже опоздать")
            .containsExactly("closed:second", "active:first");
    }

    @Test
    void activationMovesToAnExistingTabAndIgnoresAnUnknownOne() {
        workspace.openComponent(new Div(), "first", "Первая");
        workspace.openComponent(new Div(), "second", "Вторая");

        workspace.activate("first");
        assertThat(workspace.activeEntryId()).isEqualTo("first");

        workspace.activate("missing");
        assertThat(workspace.activeEntryId())
            .as("активация приходит из перехода браузера: по ключу, которого нет, открывать"
                + " форму нельзя — иначе состояние адреса создавало бы содержимое")
            .isEqualTo("first");
    }

    @Test
    void routeStateIsShownInsteadOfTheActiveViewAndDisappearsOnTheNextActivation() {
        Div first = new Div();
        Div second = new Div();
        Div state = new Div();
        workspace.openComponent(first, "first", "Первая");
        workspace.openComponent(second, "second", "Вторая");

        workspace.showTransientContent(state);

        assertThat(state.getParent()).isPresent();
        assertThat(second.isVisible()).isFalse();
        assertThat(workspace.activeEntryId())
            .as("показ состояния не меняет активную вкладку: адрес остаётся тем, по которому"
                + " пришли")
            .isEqualTo("second");

        workspace.activate("first");

        assertThat(state.getParent()).isEmpty();
        assertThat(first.isVisible()).isTrue();
    }

    @Test
    void reopeningTheActiveTabReturnsItsContentAfterTheRouteState() {
        Div home = new Div();
        Div state = new Div();
        workspace.addActiveEntryListener(activeChanges::add);
        workspace.openComponent(home, "home", "Главная");
        workspace.showTransientContent(state);

        workspace.openComponent(new Div(), "home", "Главная");

        assertThat(state.getParent())
            .as("страница состояния скрывает активную вкладку, но не снимает выбор с её Tab:"
                + " без явного показа содержимого «Главная» после ошибочной ссылки возвращала бы"
                + " пустоту — выбор той же вкладки не порождает события")
            .isEmpty();
        assertThat(home.isVisible()).isTrue();
        assertThat(workspace.activeEntryId()).isEqualTo("home");
        assertThat(activeChanges)
            .as("адрес обязан вернуться к вкладке вместе с содержимым: пока был показан отказ,"
                + " адрес описывал неудавшуюся ссылку, и промолчать здесь значило бы оставить в окне"
                + " адрес, которого не видно")
            .containsExactly("home", "home");
    }

    @Test
    void openingAnotherTabAfterTheRouteStateDoesNotRepeatTheNotification() {
        Div first = new Div();
        Div second = new Div();
        workspace.addActiveEntryListener(activeChanges::add);
        workspace.openComponent(first, "first", "Первая");
        workspace.openComponent(second, "second", "Вторая");
        workspace.showTransientContent(new Div());

        workspace.openComponent(new Div(), "first", "Первая");
        workspace.openComponent(new Div(), "first", "Первая");

        assertThat(activeChanges)
            .as("повторный показ уже активной вкладки без страницы состояния ничего не решает"
                + " заново: лишнее уведомление — лишняя запись адреса")
            .containsExactly("first", "second", "first");
    }

    @Test
    void activatingTheAlreadyActiveTabReturnsItsContentAfterTheRouteState() {
        Div home = new Div();
        Div state = new Div();
        workspace.openComponent(home, "home", "Главная");
        workspace.showTransientContent(state);

        workspace.activate("home");

        assertThat(state.getParent())
            .as("возврат браузером на адрес без формы активирует уже активную вкладку — и обязан"
                + " показать её содержимое, а не оставить страницу состояния")
            .isEmpty();
        assertThat(home.isVisible()).isTrue();
    }

    /**
     * E3.2.2 §4.4: после закрытия вкладки Explorer меню открывает её заново — активация обязана
     * прозвучать снова, иначе host не восстановит адрес, а мост не узнает адреса новой вкладки.
     */
    @Test
    void reopeningAClosedTabReportsItsActivationAgain() {
        workspace.addActiveEntryListener(activeChanges::add);
        workspace.openComponent(new Div(), "entity-explorer", "Explorer");
        workspace.close("entity-explorer");
        activeChanges.clear();

        workspace.openComponent(new Div(), "entity-explorer", "Explorer");

        assertThat(activeChanges)
            .as("вкладка, открытая заново после закрытия, — новая вкладка: активация обязана"
                + " прозвучать, иначе адрес сохранённого выбора не восстановится")
            .containsExactly("entity-explorer");
    }

    @Test
    void routeStateIsNotATab() {
        workspace.openComponent(new Div(), "first", "Первая");

        workspace.showTransientContent(new Div());

        assertThat(workspace.activeEntryId()).isEqualTo("first");
        workspace.close("first");
        assertThat(workspace.activeEntryId())
            .as("у состояния адреса вкладки нет — иначе «закрыть» предлагало бы закрыть 404,"
                + " а в баре висел бы пункт без формы")
            .isNull();
    }
}
