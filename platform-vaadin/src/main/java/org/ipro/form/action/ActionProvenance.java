package org.ipro.form.action;

import org.ipro.metadata.FactOrigin;

import java.util.Objects;

/**
 * Происхождение действия (E3.2.0): откуда взято объявление и кто его исполняет.
 *
 * <p>Тот же разбор, что у {@link org.ipro.metadata.facet.ResolvedValue} для граней метаданных, и
 * та же норма, что у регистраций E3.1: <b>происхождение либо приходит от владельца факта, либо
 * честно пустое</b>. Оно никогда не выводится из имени типа сущности, из названия конфигурации
 * или из пути к {@code .java}-файлу: «похоже на источник» — не источник.</p>
 *
 * <p>Оси две, и они не смешиваются: {@code origin} отвечает на вопрос «какого рода запись»
 * (платформенный fallback, регистрация приложения, неизвестно), а {@code symbol} — «где именно
 * она сделана» (FQN класса-объявления или класса-исполнителя). Пустой {@code symbol} при
 * непустом {@code note} означает ровно «место не сообщается», а не «места нет».</p>
 *
 * @param origin род записи
 * @param symbol FQN класса, где факт записан; пусто — место не сообщается
 * @param note   пояснение к происхождению; пусто, когда пояснять нечего
 */
public record ActionProvenance(FactOrigin origin, String symbol, String note) {

    public ActionProvenance {
        Objects.requireNonNull(origin, "origin must not be null");
        symbol = symbol == null ? "" : symbol;
        note = note == null ? "" : note;
    }

    /** Платформенный состав: действие объявлено самой платформой и применимо к любому типу. */
    public static ActionProvenance platformDefault(String symbol, String note) {
        return new ActionProvenance(FactOrigin.PLATFORM_DEFAULT, symbol, note);
    }

    /** Объявление или исполнитель приложения: запись сделана в названном классе. */
    public static ActionProvenance registration(String symbol, String note) {
        return new ActionProvenance(FactOrigin.REGISTRATION, symbol, note);
    }

    /**
     * Место объявления не сообщается: символ пуст, род записи неизвестен.
     *
     * <p>Единственный случай {@link FactOrigin#UNKNOWN} в этом аспекте — и он явный, а не
     * запасной: неизвестность называется неизвестностью, чтобы её нельзя было принять за
     * «платформенное по умолчанию».</p>
     */
    public static ActionProvenance unattributed(String note) {
        return new ActionProvenance(FactOrigin.UNKNOWN, "", note);
    }

    /** Есть ли названное место: пустой символ означает «не сообщается». */
    public boolean hasSymbol() {
        return !symbol.isBlank();
    }
}
