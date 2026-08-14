package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Результат семантического анализа JPQL (Фаза 2). Иммутабельный:
 * valid() — запрос можно выполнять (SELECT, разобрался без ошибок);
 * failures — диагностика (не-SELECT, синтаксис);
 * entities — все сущности, к которым запрос обращается (для RLS-гейта);
 * selectFields — колонки верхнего SELECT в порядке следования (schema отчёта);
 * parameters — имена параметров (:name), объявленных в запросе.
 */
public record Analysis(
        List<String> failures,
        List<EntityUsage> entities,
        List<QueryField> selectFields,
        Set<String> parameters) {

    public Analysis {
        failures = Collections.unmodifiableList(new ArrayList<>(failures));
        entities = Collections.unmodifiableList(new ArrayList<>(entities));
        selectFields = Collections.unmodifiableList(new ArrayList<>(selectFields));
        parameters = Collections.unmodifiableSet(new TreeSet<>(parameters));
    }

    public static Analysis failed(String message) {
        return new Analysis(List.of(message), List.of(), List.of(), Set.of());
    }

    public boolean valid() {
        return failures.isEmpty();
    }

    public boolean isSelect() {
        return failures.isEmpty() || !failures.get(0).startsWith("Разрешён только");
    }

    @Override
    public String toString() {
        return "Analysis{valid=" + valid() + ", entities=" + entities
            + ", fields=" + selectFields + ", params=" + parameters + "}";
    }
}