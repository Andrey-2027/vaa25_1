package org.ipro.metadata.facet;

import java.util.Optional;

/**
 * SPI переопределений надписей/подписей метаданных (роль 3, П5).
 *
 * <p>Платформа даёт default-бин (no-op → {@link Optional#empty()}); слой хранилища
 * переопределений администратора позже поставит свой бин (например, через
 * {@code @Primary} или {@code @ConditionalOnMissingBean} в обратную сторону) — резолюция
 * «код-дефолт ← переопределение» встроится в тот же метод без изменения контракта.</p>
 *
 * <p>Правило: метод вызывается ТОЛЬКО для граней с {@link FacetKind#overridable()} — код
 * вызывает резолвер по {@code FacetKey}, у которого kind помечен overridable. Код-дефолты
 * резолвер не знает; их знает вызывающий (EntitySummaryAssembler).</p>
 */
public interface FacetResolver {

    /**
     * Найти переопределение значения грани. {@code Optional.empty()} — переопределения нет,
     * действует кодовый дефолт.
     */
    Optional<String> findOverride(FacetKey key);

    /** No-op реализация: переопределений нет никогда. */
    static FacetResolver none() {
        return key -> Optional.empty();
    }
}
