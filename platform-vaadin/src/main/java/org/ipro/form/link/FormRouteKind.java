package org.ipro.form.link;

import java.util.Optional;

/**
 * Вид формы в публичном адресе глубокой ссылки (E2, ADR-0009 §3).
 *
 * <p>Вид — часть адреса, а не query-параметр: {@code /records/...} и {@code /lists/...}
 * различаются семантикой, и host обязан понимать, что открывать, до разбора тела адреса.
 * {@link FormType#SELECTION} сюда не входит: форма выбора — не самостоятельное содержимое
 * вкладки, а диалог внутри другой формы.</p>
 */
public enum FormRouteKind {

    /** Карточка существующей записи: {@code /records/{entityKey}/{id}}. */
    ITEM("records"),

    /** Список: {@code /lists/{entityKey}}. */
    LIST("lists");

    private final String segment;

    FormRouteKind(String segment) {
        this.segment = segment;
    }

    /** Первый сегмент адреса этого вида. */
    public String segment() {
        return segment;
    }

    /** Вид по первому сегменту адреса. Неизвестный сегмент — пусто, а не default. */
    public static Optional<FormRouteKind> ofSegment(String segment) {
        for (FormRouteKind kind : values()) {
            if (kind.segment.equals(segment)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
