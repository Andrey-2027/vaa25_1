package org.ipro.form.action;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.shared.Tooltip;
import org.ipro.form.link.FormLinkResult;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.NotLinkableReason;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2.1: affordance «скопировать ссылку» — состояние приходит решением, адрес берётся в момент клика.
 *
 * <p>Проверяется то, что здесь является контрактом, а не разметкой: из какого решения складываются
 * видимость и доступность, что видит пользователь при отказе и что происходит, когда решение
 * сказало «ссылка построима», а каталог адресов её не построил. Записи в буфер обмена в тестах нет:
 * это операция браузера, и клик проверяется ровно на тех путях, где UI не нужен.</p>
 */
class CopyLinkButtonTest {

    /** Тип-ярлык: решению и генерации ссылки важен класс как ключ. */
    static class Published {
    }

    private static final FormRoute LIST_ROUTE =
        new FormRoute(FormRouteKind.LIST, "published", null, null);

    @Test
    void hiddenDecisionHidesTheButtonEntirely() {
        CopyLinkButton button = button(linkableLinks(),
            () -> ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, "нет адреса"),
            decision -> {
            });

        assertThat(button.isVisible())
            .as("неприменимое действие не показывается: «нет ссылки» — не состояние экрана,"
                + " которое пользователь может изменить")
            .isFalse();
    }

    @Test
    void allowedDecisionMakesTheButtonActionable() {
        CopyLinkButton button = button(linkableLinks(), ActionDecision::allowed, decision -> {
        });

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isTrue();
        assertThat(tooltipOf(button))
            .as("доступной кнопке нечего объяснять")
            .isNull();
    }

    @Test
    void blockedDecisionStaysVisibleWithItsReason() {
        CopyLinkButton button = button(linkableLinks(),
            () -> ActionDecision.blocked(ActionDecision.Reason.NO_SELECTION,
                "Сначала выберите строку"),
            decision -> {
            });

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isFalse();
        assertThat(tooltipOf(button))
            .as("текст отказа приходит из решения, а не сочиняется кнопкой")
            .isEqualTo("Сначала выберите строку");
    }

    @Test
    void refreshPicksUpTheNewDecision() {
        AtomicReference<ActionDecision> current = new AtomicReference<>(ActionDecision.hidden(
            ActionDecision.Reason.NOT_LINKABLE, "У этого типа нет публичной ссылки"));
        CopyLinkButton button = button(linkableLinks(), current::get, decision -> {
        });
        assertThat(button.isVisible()).isFalse();

        current.set(ActionDecision.allowed());
        button.refresh();

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isTrue();
    }

    @Test
    void staleButtonRechecksTheDecisionOnClick() {
        AtomicReference<ActionDecision> current = new AtomicReference<>(ActionDecision.allowed());
        AtomicReference<ActionDecision> reported = new AtomicReference<>();
        CopyLinkButton button = button(linkableLinks(), current::get, reported::set);

        // Кнопка нарисована как доступная, но с тех пор решение изменилось: права могли отозвать,
        // запись — исчезнуть. Клик обязан спросить решение заново, а не поверить кнопке.
        current.set(ActionDecision.blocked(ActionDecision.Reason.ACCESS_DENIED, "Доступ отозван"));
        button.click();

        assertThat(reported.get()).isNotNull();
        assertThat(reported.get().reason()).isEqualTo(ActionDecision.Reason.ACCESS_DENIED);
        assertThat(reported.get().message()).isEqualTo("Доступ отозван");
    }

    @Test
    void addressMissingDespiteAnAllowedDecisionIsNamedNotSwallowed() {
        AtomicReference<ActionDecision> reported = new AtomicReference<>();
        // Решение говорит «доступно», каталог адресов адреса не построил: два источника разошлись,
        // и пользователь обязан получить причину, а не тишину после нажатия.
        CopyLinkButton button = button(
            linksReturning(FormLinkResult.notLinkable(NotLinkableReason.NOT_PUBLISHED)),
            ActionDecision::allowed, reported::set);

        button.click();

        assertThat(reported.get()).isNotNull();
        assertThat(reported.get().reason()).isEqualTo(ActionDecision.Reason.NOT_LINKABLE);
        assertThat(reported.get().message()).isNotBlank();
    }

    @Test
    void recordButtonAsksTheAddressOfTheRecord() {
        FormLinkService links = mock(FormLinkService.class);
        when(links.linkToRecord(Published.class, 42L, "archived"))
            .thenReturn(FormLinkResult.linkable(
                new FormRoute(FormRouteKind.ITEM, "published", 42L, "archived"),
                "/records/published/42?variant=archived"));

        CopyLinkButton button = CopyLinkButton.forRecord("Скопировать ссылку", links,
            Published.class, "archived", () -> 42L, ActionDecision::allowed, decision -> {
        });
        button.activate();

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isTrue();
    }

    @Test
    void recordButtonSurvivesAnUnsavedRecordWithoutPretending() {
        FormLinkService links = mock(FormLinkService.class);
        // Новая запись: id ещё нет, и FormLinkService отвечает причиной, а не пустой ссылкой.
        when(links.linkToRecord(Published.class, null, null)).thenReturn(
            FormLinkResult.notLinkable(NotLinkableReason.MISSING_ID));

        CopyLinkButton button = CopyLinkButton.forRecord("Скопировать ссылку", links,
            Published.class, null, () -> null, ActionDecision::allowed, decision -> {
        });
        button.activate();

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isTrue();
    }

    /** Живая кнопка: присоединена к UI (в тесте — тем же методом, что и слушатель присоединения). */
    private static CopyLinkButton button(FormLinkService links, Supplier<ActionDecision> decision,
                                         Consumer<ActionDecision> onBlocked) {
        CopyLinkButton button = CopyLinkButton.forList("Скопировать ссылку", links,
            Published.class, null, decision, onBlocked);
        button.activate();
        return button;
    }

    @Test
    void buttonAsksNothingUntilItIsLive() {
        AtomicReference<ActionDecision> asked = new AtomicReference<>();
        CopyLinkButton button = CopyLinkButton.forList("Скопировать ссылку", linkableLinks(),
            Published.class, null, () -> {
                asked.set(ActionDecision.allowed());
                return ActionDecision.allowed();
            }, decision -> {
            });

        button.refresh();

        assertThat(asked.get())
            .as("форма, которую только собирают, не спрашивает каталог адресов: он строится один"
                + " раз после готовности композиции форм")
            .isNull();
        assertThat(button.isVisible()).isFalse();
        assertThat(button.isEnabled()).isFalse();
    }

    private static FormLinkService linkableLinks() {
        return linksReturning(FormLinkResult.linkable(LIST_ROUTE, "/lists/published"));
    }

    private static FormLinkService linksReturning(FormLinkResult result) {
        FormLinkService links = mock(FormLinkService.class);
        when(links.linkToList(Published.class, null)).thenReturn(result);
        return links;
    }

    /** В Vaadin 25 {@code getTooltipText} нет: текст читается через {@code Tooltip}. */
    private static String tooltipOf(Button button) {
        Tooltip tooltip = button.getTooltip();
        return tooltip == null ? null : tooltip.getText();
    }
}
