package org.ipro.reportstudio.render;

import net.sf.jasperreports.engine.JasperPrint;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.dom.ReportTemplate;

/**
 * Компиляция бэнд-модели отчёта в JasperPrint + экспорт в форматы файлов.
 * Точка подмены стека рендера: единственное место, где используется
 * DynamicReports/JasperReports (DR-типы наружу не торчат).
 */
public interface ReportCompiler {

    JasperPrint compile(ReportTemplate template, ReportDataset dataset);

    byte[] export(JasperPrint print, ReportExportFormat format);
}
