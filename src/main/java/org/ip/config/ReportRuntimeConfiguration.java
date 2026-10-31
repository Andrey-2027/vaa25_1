package org.ip.config;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.query.ReportContext;
import org.ipro.report.task.ReportTaskExecutor;
import org.ipro.report.ui.print.JrxmlRunLauncher;
import org.ip.views.reports.JrxmlRunDialog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Отчётный runtime приложения: единственные бины, которые остаются прикладными
 * после M2.2.
 *
 * <p>Каталог, печать, параметры и UniDR UI живут в {@code platform-report-vaadin};
 * экспорт {@code JasperPrint} и пул задач — в {@code platform-report} (M2.1). Здесь
 * остаются только швы приложения: JSON для нейтрального query-слоя и открытие
 * прикладного JR-диалога из платформенного каталога.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ReportRuntimeConfiguration {

    /**
     * JSON для нейтрального query-слоя: приложение не предоставляет ObjectMapper,
     * а {@code ReportParamResolver} читает им значения по умолчанию и правила.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.fasterxml.jackson.databind.ObjectMapper reportObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /**
     * JR-диалог остаётся приложению: он знает его task executor и security-снимок.
     * Report-каталог видит только порт запуска, поэтому не тянет org.ip-классы.
     */
    @Bean
    @ConditionalOnMissingBean
    public JrxmlRunLauncher jrxmlRunLauncher(JrxmlTemplateService templates,
                                             JrxmlExecutionService execution,
                                             ReportTaskExecutor tasks) {
        return new JrxmlRunLauncher() {
            @Override public void open(JrxmlTemplate template) {
                new JrxmlRunDialog(template, execution, tasks).open();
            }
            @Override public void open(JrxmlTemplate template, ReportContext context) {
                new JrxmlRunDialog(template, execution, tasks, context).open();
            }
        };
    }

}
