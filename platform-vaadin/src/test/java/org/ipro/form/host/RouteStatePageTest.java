package org.ipro.form.host;

import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.html.H2;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.FormRouteKind;
import org.ipro.form.link.NotLinkableReason;
import org.ipro.form.link.OpenResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2.3: страница состояния адреса. Тест фиксирует то, ради чего исходы вообще разделены: у каждого
 * отказа свой заголовок, текст берётся у исхода, а идентификатор записи на страницу не попадает.
 */
class RouteStatePageTest {

    private static final FormRoute ROUTE = new FormRoute(FormRouteKind.ITEM, "nomenclature", 42L, null);

    @Test
    void everyFailureOutcomeGetsItsOwnTitleAndTheOutcomeMessage() {
        assertThat(show(OpenResult.invalidRoute("id вне грамматики: '042'")).title())
            .isEqualTo("Адрес не найден");
        assertThat(show(OpenResult.notFound(ROUTE)).title())
            .isEqualTo("Запись не найдена");
        assertThat(show(OpenResult.forbidden(ROUTE, "Чтение Nomenclature запрещено")).title())
            .isEqualTo("Доступ запрещён");
        assertThat(show(OpenResult.notLinkable(ROUTE, NotLinkableReason.REQUIRED_CONTEXT)).title())
            .isEqualTo("У формы нет публичного адреса");
        assertThat(show(OpenResult.unavailable(ROUTE, "Список не открылся")).title())
            .isEqualTo("Форма недоступна");

        assertThat(show(OpenResult.notFound(ROUTE)).texts())
            .as("текст страницы — текст исхода, а не свой: иначе различение hidden/missing"
                + " пришлось бы держать в двух местах")
            .containsExactly("Запись не найдена", "Запись не найдена или недоступна");
    }

    @Test
    void hiddenAndMissingRowProduceIdenticalPages() {
        assertThat(show(OpenResult.notFound(ROUTE)).texts())
            .as("скрытая RLS-строка и физически отсутствующая неразличимы: различать их значило бы"
                + " сделать адрес инструментом проверки существования чужих записей")
            .isEqualTo(show(OpenResult.notFound(
                new FormRoute(FormRouteKind.ITEM, "nomenclature", 7L, null))).texts());
    }

    @Test
    void recordIdIsNeverRendered() {
        assertThat(show(OpenResult.notFound(ROUTE)).texts())
            .allSatisfy(text -> assertThat(text).doesNotContain("42"));
        assertThat(show(OpenResult.forbidden(ROUTE, "Чтение Nomenclature запрещено")).texts())
            .allSatisfy(text -> assertThat(text).doesNotContain("42"));
    }

    @Test
    void openedIsNotAStateAndIsRejectedLoudly() {
        assertThatThrownBy(() -> new RouteStatePage(OpenResult.opened(ROUTE)))
            .as("открытая форма — это не состояние: молча показать по ней страницу значило бы"
                + " скрыть дефект вызывающего")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Opened");
    }

    @Test
    void outcomeLabelIsTheAuditLabel() {
        assertThat(show(OpenResult.notFound(ROUTE)).outcome()).isEqualTo("not-found");
        assertThat(show(OpenResult.invalidRoute("пусто")).outcome()).isEqualTo("invalid-route");
    }

    /** Что именно видно на странице: заголовок, тексты и метка исхода. */
    private record Shown(String title, List<String> texts, String outcome) {
    }

    private static Shown show(OpenResult result) {
        RouteStatePage page = new RouteStatePage(result);
        List<String> texts = page.getChildren()
            .filter(HasText.class::isInstance)
            .map(HasText.class::cast)
            .map(HasText::getText)
            .toList();
        String title = page.getChildren()
            .filter(H2.class::isInstance)
            .map(H2.class::cast)
            .map(H2::getText)
            .findFirst()
            .orElseThrow();
        return new Shown(title, texts, page.outcome());
    }
}
