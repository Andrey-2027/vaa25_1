package org.ipro.report.ui.config;

import org.ipro.report.catalog.ReportEngineCapabilities;
import org.ipro.report.catalog.ReportEngineType;

import java.util.Arrays;
import java.util.List;

/**
 * Какие типы отчётов включены конфигурацией развёртывания.
 *
 * <p>Настройка отвечает на вопрос «есть ли этот тип отчётов в приложении», а не «есть ли
 * бин»: до неё окно выбора типа отчёта ориентировалось на наличие портов. Из-за этого
 * выключить JR было нельзя вовсе (его сервисы безусловны), а выключение UniDR означало
 * отсутствие бинов — то есть видимость типа зависела от сборки поставки, а не от
 * настройки развёртывания. Теперь у каждого типа свой переключатель
 * ({@code app.jrxml.enabled}, {@code app.unidr.enabled}), а отсутствие порта остаётся
 * вторым, независимым условием: поставка без UniDR не показывает тип даже при включённом
 * свойстве.</p>
 *
 * <p>Это не продолжение {@link ReportEngineCapabilities}: та таблица отвечает, <em>что
 * движок умеет</em> (одинаково для всех развёртываний), эта — что включено в конкретном.</p>
 */
public record ReportTypeAvailability(boolean jrxmlEnabled, boolean unidrEnabled) {

    /**
     * Включён ли тип. Таблица полная: новый {@link ReportEngineType} ломает компиляцию,
     * а не получает молчаливое «нет данных» и не исчезает из окна выбора.
     */
    public boolean enabled(ReportEngineType type) {
        return switch (type) {
            case JR -> jrxmlEnabled;
            case UNIDR -> unidrEnabled;
        };
    }

    /** Включённые типы в порядке объявления типов: окно выбора строится из этого списка. */
    public List<ReportEngineType> enabledTypes() {
        return Arrays.stream(ReportEngineType.values()).filter(this::enabled).toList();
    }
}
