package org.ipro.reportstudio.dom;

/**
 * Назначение поля бэнда.
 *
 * <p><b>COLUMN</b> — колонка отчёта (DETAIL) или агрегат по существующей
 * колонке (GROUP_FOOTER/REPORT_FOOTER): связана с queryField, поддерживает
 * width/format/alignment и (в footer) aggregation.</p>
 *
 * <p><b>TEXT</b> — статический текстовый блок (заголовок/подпись) в
 * REPORT_HEADER/NO_DATA/GROUP_FOOTER/REPORT_FOOTER: есть только text, остальные
 * атрибуты колонки не применимы.</p>
 *
 * <p><b>ROW_NUMBER</b> — колонка «№ п/п» (DETAIL): без queryField; номер
 * строки печатает рендер (row number по всему отчёту). Допустимы
 * caption/width/alignment.</p>
 *
 * <p><b>EXPRESSION</b> — вычисляемая строка (DETAIL): text = шаблон
 * {@code "Продано: {item}, {qty} шт."}, в котором {@code {alias}} — имена
 * колонок DETAIL. Без queryField; aggregation = NONE.</p>
 *
 * <p><b>FORMULA</b> — вычисляемая арифметика (DETAIL): text = формула
 * {@code "({qty} * {price}) + 6", разбирается {@code FormulaEvaluator}.
 * Без queryField; aggregation = NONE.</p>
 */
public enum ReportFieldKind {

    COLUMN,
    TEXT,
    ROW_NUMBER,
    EXPRESSION,
    FORMULA
}
