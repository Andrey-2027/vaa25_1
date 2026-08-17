package org.ipro.reportstudio.dom;

/**
 * Тип бэнда отчёта (Фаза 1, layout).
 * Группировка — «Группировка отчёта» (не SQL GROUP BY): режет плоский
 * отсортированный набор; GROUP_HEADER/GROUP_FOOTER образуют пары по
 * (parent, groupField), GROUP_HEADER может иметь родителя-GROUP_HEADER
 * для вложенных групп (N уровней).
 */
public enum ReportBandKind {

    REPORT_HEADER,
    GROUP_HEADER,
    DETAIL,
    GROUP_FOOTER,
    REPORT_FOOTER,
    /** Блок «нет данных»: печатается, когда выборка пуста; только TEXT-поля. */
    NO_DATA;

    /** Групповой бэнд (требует groupField и парный бэнд). */
    public boolean isGroupBand() {
        return this == GROUP_HEADER || this == GROUP_FOOTER;
    }

    /** Footer-бэнд: единственное место, где допустимы агрегаты. */
    public boolean isFooterBand() {
        return this == GROUP_FOOTER || this == REPORT_FOOTER;
    }

    /** Бэнд, поля которого — только текстовые блоки (без колонок). */
    public boolean isTextOnlyBand() {
        return this == REPORT_HEADER || this == NO_DATA;
    }
}