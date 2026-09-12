package org.ipro.form;

import java.util.List;
import java.util.Objects;

/**
 * Снимок состояния одной табличной части для application save-контракта.
 *
 * <p>Пустой список при {@link SectionPresence#ATTACHED} — осознанное состояние
 * «секция подключена и очищена». Для {@link SectionPresence#ABSENT} список всегда
 * пуст, поэтому скрытая секция не может быть случайно передана как команда
 * очистки.</p>
 *
 * @param <R> тип строки табличной части
 */
public record SectionPayload<R>(SectionPresence presence, List<R> rows) {

    public SectionPayload {
        Objects.requireNonNull(presence, "presence must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        rows = List.copyOf(rows);
        if (presence == SectionPresence.ABSENT && !rows.isEmpty()) {
            throw new IllegalArgumentException("ABSENT section cannot contain rows");
        }
    }

    /** Создать снимок фактически подключённой секции, включая пустой список строк. */
    public static <R> SectionPayload<R> attached(List<R> rows) {
        return new SectionPayload<>(SectionPresence.ATTACHED, rows);
    }

    /** Создать снимок секции, отсутствующей в текущем варианте формы. */
    public static <R> SectionPayload<R> absent() {
        return new SectionPayload<>(SectionPresence.ABSENT, List.of());
    }

    public boolean isAttached() {
        return presence == SectionPresence.ATTACHED;
    }

    public boolean isAbsent() {
        return presence == SectionPresence.ABSENT;
    }
}
