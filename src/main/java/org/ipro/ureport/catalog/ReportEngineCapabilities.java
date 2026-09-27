package org.ipro.ureport.catalog;

import java.util.Map;
import java.util.Objects;

/**
 * Матрица возможностей движка отчёта (D3.6): единственное место, где решается,
 * <em>что движок умеет</em> в каталоге и куда ведёт открытие его записи.
 *
 * <p>Раньше эти решения были размазаны по трём каталогам: каждый
 * {@code switch} по {@link ReportEngineType} в {@code ReportCatalogView} и в
 * variant-стеках повторял один и тот же набор правил («копия — только UDR»,
 * «удаление UDR в каталоге не поддержано», «JR открывает сведения»). Теперь
 * решения собраны здесь, а эффекты (какой диалог открыть, какой сервис позвать)
 * остаются во вьюхе: один и тот же {@link OpenTarget} в разных точках входа
 * приводит к разным эффектам — в каталоге {@code EDITOR} грузит шаблон во
 * встроенный редактор, в contextual launcher — уводит на маршрут редактора.</p>
 *
 * <p>Решения про <em>запуск</em> здесь нет намеренно: запуск поддерживают все три
 * движка, различаются только диалоги параметров — это эффект, а не доступность.
 * Флаг, который всегда {@code true}, был бы мёртвой веткой и прятал бы реальные
 * различия вместо того, чтобы их показывать.</p>
 *
 * <p>Расширение D3.7 (границы report-модулей, capability-aware actions из E1/F)
 * добавляет поля в {@link Capabilities}. Таблица должна оставаться данными, пока
 * эти границы не определены — SPI, придуманный заранее, заморозил бы текущие
 * границы модулей как API (тот же риск, что в D3.5).</p>
 */
public final class ReportEngineCapabilities {

    /** Куда ведёт открытие записи каталога. */
    public enum OpenTarget {
        /** Встроенный редактор отчёта (UDR). */
        EDITOR,
        /** Внешний веб-дизайнер в новой вкладке (UReport3). */
        DESIGNER,
        /** Только сведения: макет правится вне приложения (JR). */
        INFO
    }

    /**
     * Возможности одного движка.
     *
     * @param label           подпись типа в каталоге
     * @param openTarget      цель открытия записи
     * @param copy            доступно «Создать копию»
     * @param exportJson      доступен «Экспорт JSON»
     * @param deleteInCatalog удаление доступно прямо в каталоге (через подтверждение)
     */
    public record Capabilities(
            String label,
            OpenTarget openTarget,
            boolean copy,
            boolean exportJson,
            boolean deleteInCatalog) {

        public Capabilities {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(openTarget, "openTarget");
        }
    }

    /**
     * Полная таблица: у каждого движка запись обязана быть, иначе падает
     * {@link #of(ReportEngineType)} — новый движок нельзя добавить молча.
     */
    private static final Map<ReportEngineType, Capabilities> BY_TYPE = Map.of(
            ReportEngineType.UDR, new Capabilities(
                    "UDR", OpenTarget.EDITOR, true, true, false),
            ReportEngineType.UREPORT3, new Capabilities(
                    "UReport3", OpenTarget.DESIGNER, false, false, true),
            ReportEngineType.JR, new Capabilities(
                    "JR", OpenTarget.INFO, false, false, true));

    /** Возможности движка. Движок без записи в таблице — ошибка, а не «нет данных». */
    public static Capabilities of(ReportEngineType type) {
        Objects.requireNonNull(type, "type");
        Capabilities capabilities = BY_TYPE.get(type);
        if (capabilities == null) {
            throw new IllegalArgumentException(
                    "Нет возможностей для движка отчёта: " + type);
        }
        return capabilities;
    }

    private ReportEngineCapabilities() {
    }
}
