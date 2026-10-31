package org.ip.security;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.NomenclaturePrice;
import org.ipro.register.RegisterRegistry;
import org.ipro.query.register.RegisterReportReadPolicy;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryExecutor;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.ReportRunQuota;
import org.ipro.query.QuerySemanticAnalyzer;
import org.ipro.query.QuerySnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полный контекст приложения с {@code org.ip.config.ReportRuntimeConfiguration}:
 * бины query-слоя зарегистрированы, репозиторий ReportTemplate попал в
 * {@code @EnableJpaRepositories} (расширен в RlsAutoConfiguration).
 */
@SpringBootTest
class ReportRuntimeConfigurationSmokeTest {

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
    private QuerySnapshotService snapshotService;

    @Autowired
    private RegisterRegistry registers;

    @Autowired
    private RegisterReportReadPolicy registerReportReadPolicy;

    @Autowired
    private org.ipro.query.register.RegisterSliceCteGenerator sliceGenerator;

    @Test
    void registerInfrastructureAndReportPolicyAreRegisteredWithoutApplicationScan() {
        assertThat(registerReportReadPolicy).isNotNull();
        assertThat(sliceGenerator).isNotNull();
        assertThat(registers.require(NomenclaturePrice.class).dimensionNames()).containsExactly("nomenclature");
        assertThat(registers.require(NomenclaturePrice.class).period().orElseThrow().name()).isEqualTo("period");
    }

    @Test
    void reportStudioBeansAreRegisteredByAutoConfiguration() {
        assertThat(analyzer).isNotNull();
        assertThat(guard).isNotNull();
        assertThat(executor).isNotNull();
        assertThat(quota).isNotNull();
        assertThat(previewService).isNotNull();
        assertThat(snapshotService).isNotNull();
    }

}
