package org.ipro.reportstudio.dom;

/**
 * Расположение заголовка группы относительно значения (Фаза 4, рендер).
 * Своя копия net.sf.dynamicreports.report.constant.GroupHeaderLayout — три
 * константы, 1:1 с DR (сверено с апидоком core 6.12.0: EMPTY, VALUE,
 * TITLE_AND_VALUE — других значений в этом enum у DR нет), чтобы доменный
 * слой не зависел от типов DynamicReports. Применимо только к GROUP_HEADER.
 */
public enum ReportGroupHeaderLayout {

    /** Только значение поля группировки, без подписи. */
    VALUE,

    /** Подпись (заголовок колонки, по которой группируем) и значение рядом. */
    TITLE_AND_VALUE,

    /** Заголовок группы не печатается вовсе. */
    EMPTY
}
