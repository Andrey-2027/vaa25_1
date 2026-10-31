package org.ipro.report.render;

import net.sf.jasperreports.engine.JasperPrint;

/**
 * Экспорт готового {@link JasperPrint} в форматы файлов (R1).
 *
 * <p>Порт отделён от компиляции макета: потребителю нужен только артефакт
 * одного запуска, а не модель шаблона, базы и компилятор UDR bands.
 * Повторный экспорт другого формата выполняется из того же артефакта без
 * нового чтения данных.</p>
 */
public interface JasperExporter {

    byte[] export(JasperPrint print, ReportExportFormat format);
}
