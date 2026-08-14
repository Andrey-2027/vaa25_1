package org.ipro.reportstudio.run;

import net.sf.jasperreports.engine.JasperPrint;

/**
 * Результат запуска отчёта: JasperPrint (данные внутри, транзакция завершена)
 * + ключ run-кэша (повторный экспорт другого формата — из того же артефакта).
 */
public record ReportRunResult(JasperPrint print, String key) {
}
