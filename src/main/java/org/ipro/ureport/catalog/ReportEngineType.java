package org.ipro.ureport.catalog;

/**
 * Движок отчёта в едином каталоге. Часть ключа записи: id сущностей разных
 * движков — разные namespace, диспетчеризация действий только по паре
 * (type, id) — см. UnionReport1.md, Р9.
 */
public enum ReportEngineType {
    /** UserDynamicReport — конструктор reportstudio (ReportTemplate). */
    UDR,
    /** UReport3 — веб-дизайнер (UreportTemplate + XML в файловом хранилище). */
    UREPORT3
}
