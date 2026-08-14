package org.ipro.reportstudio.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ReportRunQuota;
import org.ipro.reportstudio.query.QuerySemanticAnalyzer;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration модуля отчётов (reportstudio). Пакеты org.ipro.reportstudio.*
 * не попадают в component-scan приложения (базовый пакет org.ip) — по образцу
 * RlsAutoConfiguration/TelemetryAutoConfiguration все бины регистрируются здесь.
 */
@AutoConfiguration
public class ReportStudioAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QuerySemanticAnalyzer reportQuerySemanticAnalyzer(EntityManagerFactory entityManagerFactory) {
        return new SqmQuerySemanticAnalyzer(entityManagerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportQueryGuard reportQueryGuard(QuerySemanticAnalyzer analyzer,
                                             AccessService accessService,
                                             RlsDimensionRegistry dimensionRegistry,
                                             RlsCurrentUser currentUser,
                                             EntityManagerFactory entityManagerFactory) {
        return new ReportQueryGuard(analyzer, accessService, dimensionRegistry,
            currentUser, entityManagerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportQueryExecutor reportQueryExecutor(EntityManager entityManager,
                                                   RlsFilterActivator rlsFilterActivator) {
        return new ReportQueryExecutor(entityManager, rlsFilterActivator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportRunQuota reportRunQuota(
            @Value("${ipro.report.max-parallel-runs:2}") int maxParallelRuns) {
        return new ReportRunQuota(maxParallelRuns);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportPreviewService reportPreviewService(ReportQueryExecutor executor) {
        return new ReportPreviewService(executor);
    }
}