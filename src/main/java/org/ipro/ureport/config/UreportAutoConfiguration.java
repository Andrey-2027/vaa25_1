package org.ipro.ureport.config;

import jakarta.validation.Validator;

import org.ipro.ureport.UreportTemplateRepository;
import org.ipro.ureport.catalog.ReportCatalogService;
import org.ipro.ureport.service.UreportTemplateService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration модуля UReport3-каталога. Пакеты org.ipro.ureport.*
 * не попадают в component-scan приложения (базовый пакет org.ip) — бины
 * регистрируются здесь, репозиторий — централизованно в
 * {@code RlsAutoConfiguration#@EnableJpaRepositories} (basePackages
 * дополнен "org.ipro.ureport").
 */
@AutoConfiguration
public class UreportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UreportTemplateService ureportTemplateService(
            UreportTemplateRepository repository,
            Validator validator,
            @Value("${ureport.fileStoreDir}") String fileStoreDir) {
        return new UreportTemplateService(repository, validator, fileStoreDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportCatalogService reportCatalogService(
            ReportTemplateService reportTemplateService,
            UreportTemplateService ureportTemplateService,
            org.springframework.context.ApplicationContext applicationContext,
            RlsReadGate rlsReadGate,
            RlsCurrentUser currentUser) {
        // JR-сервис опционален: модуль jr может отсутствовать (тот же профиль
        // обратной совместимости, что и ureportService в ContextualReportLauncher)
        org.ipro.jr.service.JrxmlTemplateService jrxmlTemplateService;
        try {
            jrxmlTemplateService = applicationContext.getBean(
                    org.ipro.jr.service.JrxmlTemplateService.class);
        } catch (RuntimeException noBean) {
            jrxmlTemplateService = null;
        }
        return new ReportCatalogService(reportTemplateService, ureportTemplateService,
                jrxmlTemplateService, rlsReadGate, currentUser);
    }
}
