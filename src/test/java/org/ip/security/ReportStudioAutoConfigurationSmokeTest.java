package org.ip.security;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ipro.reportstudio.config.ReportSchemaCompatibility;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ReportRunQuota;
import org.ipro.reportstudio.query.QuerySemanticAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полный контекст приложения с новой автоконфигурацией
 * org.ipro.reportstudio.config.ReportStudioAutoConfiguration: бины query-слоя
 * зарегистрированы (пакет за пределами component-scan org.ip), репозиторий
 * ReportTemplate попал в @EnableJpaRepositories (расширен в RlsAutoConfiguration).
 */
@SpringBootTest
class ReportStudioAutoConfigurationSmokeTest {

    /** Тот же известный баг сидинга при свежей БД — вне скоупа (см. RlsAutoConfigurationSmokeTest). */
    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private QuerySemanticAnalyzer analyzer;

    @Autowired
    private ReportQueryGuard guard;

    @Autowired
    private ReportQueryExecutor executor;

    @Autowired
    private ReportRunQuota quota;

    @Autowired
    private ReportPreviewService previewService;

    @Autowired
    private ReportTemplateRepository templateRepository;

    @Autowired
    private ReportSchemaCompatibility schemaCompatibility;

    @Test
    void reportStudioBeansAreRegisteredByAutoConfiguration() {
        assertThat(analyzer).isNotNull();
        assertThat(guard).isNotNull();
        assertThat(executor).isNotNull();
        assertThat(quota).isNotNull();
        assertThat(previewService).isNotNull();
    }

    @Test
    void schemaCompatibilityRunnerIsRegistered() {
        assertThat(schemaCompatibility).isNotNull();
    }

    @Test
    void reportTemplateRepositoryIsScanned() {
        assertThat(templateRepository).isNotNull();
    }
}