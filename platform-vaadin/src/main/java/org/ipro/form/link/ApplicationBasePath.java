package org.ipro.form.link;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;

/**
 * Базовый путь развёртывания (context path) — единственное место, где относительный адрес формы
 * становится адресом приложения (E2, ADR-0009 §3).
 *
 * <p><b>Зачем это нужно.</b> Канонический адрес формы относителен приложению:
 * {@code /records/nomenclature/42}. Окно браузера адресует приложение целиком, и под развёртыванием
 * {@code /app} тот же адрес читается как {@code /app/records/nomenclature/42}. Формула «origin плюс
 * адрес» верна только для развёртывания в корне, поэтому и запись истории, и копирование ссылки
 * спрашивают базовый путь здесь, а не подставляют пустую строку.</p>
 *
 * <p><b>Почему отдельный тип, а не запрос в каждом месте.</b> Источник один и тот же
 * ({@link VaadinRequest#getContextPath()}), и спрашивать его в двух местах значило бы завести две
 * формулы одного факта: разойдясь, они дали бы ссылку, ведущую не туда, куда ведёт адресная строка.
 * Здесь же заперт и случай «запроса нет»: вне запроса базовый путь пуст, и адрес остаётся
 * относительным, а не додумывается.</p>
 */
public final class ApplicationBasePath {

    private ApplicationBasePath() {
    }

    /**
     * Базовый путь текущего запроса: {@code ""} — развёртывание в корне, {@code "/app"} — под
     * контекстом.
     *
     * <p>Пусто вне запроса (тест, фоновая нить): неизвестный базовый путь не должен превращаться
     * в выдуманный.</p>
     */
    public static String current() {
        VaadinRequest request = VaadinService.getCurrentRequest();
        if (request == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        return contextPath == null ? "" : contextPath;
    }
}
