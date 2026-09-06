package org.ipro.jr.config;

import jakarta.validation.Validator;

import org.ipro.crud.ReferenceCheckService;
import org.ipro.jr.JrxmlTemplateRepository;
import org.ipro.jr.run.JpqlDatasetRunner;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration движка JR (.jrxml). Пакеты org.ipro.jr.* не попадают
 * в component-scan приложения (базовый пакет org.ip) — бины регистрируются
 * здесь, репозиторий — централизованно в RlsAutoConfiguration (basePackages).
 *
 * <p>Хранилище файлов — отдельное свойство {@code jrxml.fileStoreDir}
 * (не переиспользуется ureport.fileStoreDir — жизненные циклы движков
 * независимы, Р7 плана ReportJR-Jpql-Plan.md).</p>
 */
@AutoConfiguration
public class JrAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JrxmlTemplateService jrxmlTemplateService(
            JrxmlTemplateRepository repository,
            Validator validator,
            ReferenceCheckService referenceCheckService,
            @Value("${jrxml.fileStoreDir}") String fileStoreDir) {
        return new JrxmlTemplateService(repository, validator, referenceCheckService,
                fileStoreDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public JpqlDatasetRunner jrJpqlDatasetRunner(JpqlDatasetRunner datasetRunner) {
        return datasetRunner;
    }

    @Bean
    @ConditionalOnMissingBean
    public JrxmlExecutionService jrxmlExecutionService(
            JrxmlTemplateService templateService,
            JpqlDatasetRunner datasetRunner,
            @Value("${jrxml.max-columns:50}") int maxColumns) {
        return new JrxmlExecutionService(templateService, datasetRunner::run, maxColumns);
    }
}
