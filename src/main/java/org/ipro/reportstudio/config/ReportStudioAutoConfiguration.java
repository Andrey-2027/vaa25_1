package org.ipro.reportstudio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ipro.reportstudio.param.EntityParamRefresher;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ReportRunQuota;
import org.ipro.reportstudio.query.QuerySemanticAnalyzer;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.reportstudio.render.JasperReportCompiler;
import org.ipro.reportstudio.render.ReportCompiler;
import org.ipro.reportstudio.run.ReportArtifactCache;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
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

    @Bean
    @ConditionalOnMissingBean
    public EntityParamRefresher entityParamRefresher(EntityManager entityManager,
                                                     RlsFilterActivator rlsFilterActivator,
                                                     RlsReadGate rlsReadGate,
                                                     RlsCurrentUser currentUser,
                                                     EntityManagerFactory entityManagerFactory) {
        return new EntityParamRefresher(entityManager, rlsFilterActivator, rlsReadGate,
            currentUser, entityManagerFactory.unwrap(SessionFactoryImplementor.class));
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper reportObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportParamResolver reportParamResolver(EntityParamRefresher refresher,
                                                   AccessService accessService,
                                                   RlsCurrentUser currentUser,
                                                   EntityManagerFactory entityManagerFactory,
                                                   ObjectMapper objectMapper,
                                                   @Value("${ipro.report.rls-org-dimension:BRANCH}") String rlsOrgDimension) {
        return new ReportParamResolver(refresher, accessService, currentUser,
            entityManagerFactory.unwrap(SessionFactoryImplementor.class), objectMapper, rlsOrgDimension);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportCompiler reportCompiler() {
        return new JasperReportCompiler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportArtifactCache reportArtifactCache(
            @Value("${ipro.report.cache-max-artifacts:8}") int maxArtifacts) {
        return new ReportArtifactCache(maxArtifacts);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportExecutionService reportExecutionService(ReportQueryGuard guard,
                                                         ReportQueryExecutor executor,
                                                         ReportParamResolver resolver,
                                                         ReportCompiler compiler,
                                                         ReportArtifactCache cache) {
        return new ReportExecutionService(guard, executor, resolver, compiler, cache);
    }
}