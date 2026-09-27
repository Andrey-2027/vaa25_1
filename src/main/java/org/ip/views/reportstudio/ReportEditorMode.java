package org.ip.views.reportstudio;

/**
 * Режим представления вкладки «Макет», не являющийся свойством {@code ReportTemplate}.
 *
 * <p>Переезд из {@code org.ip.views.reportstudio.structured} в канонический пакет — часть
 * D3.6.4: режим принадлежит каноническому экрану; variant-стеки удалены в D3.6.7.
 * Значение режима не входит в шаблон, не сохраняется и не делает отчёт изменённым.</p>
 */
enum ReportEditorMode {
    USER,
    ADVANCED
}
