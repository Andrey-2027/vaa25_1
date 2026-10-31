package org.ipro.query.config;

import org.ipro.data.EntityDescriptorCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.QuerySemanticAnalyzer;
import org.ipro.query.QuerySnapshotService;
import org.ipro.query.param.EntityParamRefresher;
import org.ipro.query.param.ReportParamResolver;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.ReportQueryExecutor;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.ReportQueryLanguage;
import org.ipro.query.ReportQueryRunService;
import org.ipro.query.ReportReadAuthorizer;
import org.ipro.query.ReportReadPolicy;
import org.ipro.query.ReportRunQuota;
import org.ipro.query.register.RegisterReportReadPolicy;
import org.ipro.query.register.RegisterSliceCteGenerator;
import org.ipro.query.register.RegisterSliceReportTemplates;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterRegistry;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.data.presentation.EntityPresentationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * M1: query backend registration. Пакеты {@code org.ipro.query.*} не попадают в
 * component-scan приложения (базовый пакет {@code org.ip}), поэтому все бины
 * объявляются здесь и подключаются модульным
 * {@code META-INF/spring/...AutoConfiguration.imports}.
 *
 * <p>Модуль остаётся без Vaadin и без UDR DOM: единственные входы — платформенные
 * бины (метаданные, RLS, register, presentation) и JPA. Приложение больше не
 * обязано собирать query-механизм вручную; оно только добавляет артефакт.</p>
 */
@AutoConfiguration
public class QueryAutoConfiguration {

    @Bean
    public RegisterSliceCteGenerator registerSliceCteGenerator() {
        return new RegisterSliceCteGenerator();
    }

    @Bean
    public ReportQueryLanguage reportQueryLanguage(QueryBuilderMetadataCatalog catalog,
                                                   RegisterSliceReportTemplates recipes) {
        return new ReportQueryLanguage(catalog, recipes);
    }

    @Bean
    public RegisterSliceReportTemplates registerSliceReportTemplates(
            EntityManagerFactory factory, RegisterRegistry registers,
            QueryBuilderMetadataCatalog catalog,
            RegisterSliceCteGenerator generator,
            C5PermissionEvaluator permissions, RlsCurrentUser currentUser) {
        return new RegisterSliceReportTemplates(factory, registers, catalog, generator,
            permissions, currentUser);
    }

    @Bean
    public RegisterReportReadPolicy registerReportReadPolicy(
            EntityManagerFactory factory, RlsDimensionRegistry dimensions,
            RegisterRegistry registers) {
        return new RegisterReportReadPolicy(factory, dimensions, registers);
    }

    @Bean
    public ReportReadAuthorizer reportReadAuthorizer(
            EntityManagerFactory factory, C5PermissionEvaluator permissions,
            RlsCurrentUser currentUser, java.util.List<ReportReadPolicy> policies,
            AccessService accessService, RlsDimensionRegistry dimensionRegistry) {
        return new ReportReadAuthorizer(factory, permissions, currentUser, policies,
            accessService, dimensionRegistry);
    }

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
                                             EntityManagerFactory entityManagerFactory,
                                             ReportReadAuthorizer authorizer) {
        return new ReportQueryGuard(analyzer, accessService, dimensionRegistry,
            currentUser, entityManagerFactory, authorizer);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportQueryExecutor reportQueryExecutor(EntityManager entityManager,
                                                   RlsFilterActivator rlsFilterActivator,
                                                   RlsCurrentUser currentUser, ReportQueryGuard guard,
                                                   ReportReadAuthorizer authorizer,
                                                   EntityPresentationService presentations,
                                                   RecorderTypeRegistry recorderTypes) {
        var presenter = new org.ipro.query.QueryResultLabelPresenter(presentations, recorderTypes);
        return new ReportQueryExecutor(entityManager, rlsFilterActivator, currentUser, guard,
            authorizer, presenter);
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
    public QuerySnapshotService querySnapshotService(ReportPreviewService previewService) {
        return new QuerySnapshotService(previewService);
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
    public QueryBuilderMetadataCatalog queryBuilderMetadataCatalog(
            ManagedEntityTypes managedEntityTypes,
            MetadataResolver metadataResolver,
            RlsReadGate rlsReadGate,
            RlsCurrentUser currentUser, EntityDescriptorCatalog descriptors) {
        return new QueryBuilderMetadataCatalog(managedEntityTypes, metadataResolver, rlsReadGate, currentUser, descriptors);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportQueryAssemblyService reportQueryAssemblyService(ReportQueryGuard guard,
                                                                 ReportParamResolver resolver,
                                                                 EntityParamRefresher refresher,
                                                                 QueryBuilderMetadataCatalog visualCatalog,
                                                                 ReportQueryLanguage language) {
        var service = new ReportQueryAssemblyService(guard, resolver, refresher, visualCatalog);
        service.useQueryLanguage(language);
        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportQueryRunService reportQueryRunService(
            ReportQueryAssemblyService assembly, ReportQueryExecutor executor) {
        return new ReportQueryRunService(assembly, executor);
    }
}
