package org.ipro.form.host;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.form.link.OpenResult;

import java.util.Objects;

/**
 * Страница состояния адреса (E2.3, ADR-0009 §6): то, что видно, когда форма по адресу не
 * открылась.
 *
 * <p><b>Почему отдельный компонент, а не сообщение об ошибке.</b> Отказов у адреса пять, и они
 * различаются не оттенком текста, а тем, что пользователю делать дальше: «такого адреса нет» —
 * проверить ссылку, «доступ закрыт» — не искать обход через адрес, «форма недоступна» —
 * это дефект, а не отсутствие данных. Свести их в один текст значит потерять это различие ровно
 * там, где оно единственное и осталось.</p>
 *
 * <p><b>Что здесь нельзя показать.</b> Ничего, что различало бы скрытую RLS-строку и физически
 * отсутствующую: у обоих исходов один текст (он берётся из {@link OpenResult}, а не сочиняется
 * страницей). Идентификатор записи не выводится вообще — страница не знает про него ничего
 * сверх текста, который ей дал исход.</p>
 *
 * <p>Успешный исход сюда не попадает: {@link OpenResult.Opened} — это открытая форма, а не
 * состояние, и передавать его сюда означает дефект у вызывающего.</p>
 */
public class RouteStatePage extends VerticalLayout {

    private final OpenResult result;

    public RouteStatePage(OpenResult result) {
        this.result = Objects.requireNonNull(result, "result must not be null");
        if (result instanceof OpenResult.Opened) {
            throw new IllegalArgumentException("Opened — это открытая форма, а не страница"
                + " состояния: показывать нечего, значит вызывающий перепутал исход");
        }

        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);
        getStyle().set("text-align", "center");
        addClassName("route-state-page");

        H2 title = new H2(title());
        title.addClassName("route-state-title");

        Paragraph message = new Paragraph(result.message().isEmpty()
            ? "Адрес открыть не удалось."
            : result.message());
        message.addClassName("route-state-message");

        add(title, message);
    }

    /** Исход, который показывает страница: он же метка route audit. */
    public OpenResult result() {
        return result;
    }

    /** Стабильная метка исхода — та же строка, что уходит в аудит, а не текст для пользователя. */
    public String outcome() {
        return result.outcome();
    }

    /**
     * Заголовок по исходу. Исчерпывающий switch по sealed-иерархии: новый исход — ошибка
     * компиляции здесь, а не безымянная страница у пользователя.
     */
    private String title() {
        return switch (result) {
            case OpenResult.InvalidRoute ignored -> "Адрес не найден";
            case OpenResult.NotFound ignored -> "Запись не найдена";
            case OpenResult.Forbidden ignored -> "Доступ запрещён";
            case OpenResult.NotLinkable ignored -> "У формы нет публичного адреса";
            case OpenResult.Unavailable ignored -> "Форма недоступна";
            case OpenResult.Opened ignored -> throw new IllegalStateException("недостижимо");
        };
    }
}
