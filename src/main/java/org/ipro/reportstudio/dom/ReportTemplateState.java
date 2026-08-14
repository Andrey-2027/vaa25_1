package org.ipro.reportstudio.dom;

/**
 * Состояние шаблона отчёта (Фаза 1).
 * Правки идут в одном шаблоне; при публикации (DRAFT -> PUBLISHED) делается
 * снапшот декларации в каталог (Фаза 6), версии не накапливаются.
 */
public enum ReportTemplateState {

    DRAFT,
    PUBLISHED
}