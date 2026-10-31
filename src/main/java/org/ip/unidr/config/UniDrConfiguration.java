package org.ip.unidr.config;

import org.ipro.unidr.render.UniDrReportCompiler;
import org.ipro.report.render.JasperExporter;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.query.UniDrQueryAdapter;
import org.ip.unidr.query.UniDrReferenceService;
import org.ipro.crud.EntityLookup;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.ReportQueryExecutor;
import org.ipro.query.ReportRunQuota;
import org.ipro.report.task.ReportTaskExecutor;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import org.ipro.unidr.model.UniDrDefinitionJsonCodec;
import org.ipro.unidr.model.UniDrDefinitionValidator;
import org.ipro.unidr.query.UniDrQueryService;
import org.ipro.unidr.query.UniDrQuerySourceService;
import org.ipro.unidr.query.UniDrTableSources;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.unidr.run.UniDrOrgContext;
import org.ipro.unidr.run.UniDrRunParameterResolver;
import org.ipro.unidr.run.UniDrRunService;
import org.ipro.unidr.store.UniDrCatalogContributor;
import org.ipro.unidr.store.UniDrFileStore;

import java.time.Clock;

/** Disabled by default; JSON file storage without new database mappings. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.unidr", name = "enabled", havingValue = "true")
public class UniDrConfiguration {
    @Bean UniDrQuerySourceService uniDrQuerySources(ReportQueryAssemblyService assembly,org.springframework.beans.factory.ObjectProvider<jakarta.persistence.EntityManagerFactory> entities,
            org.springframework.beans.factory.ObjectProvider<QueryBuilderMetadataCatalog> catalog) {
        var service=new UniDrQuerySourceService(assembly,entities.getIfAvailable());var metadata=catalog.getIfAvailable();if(metadata!=null)service.useTables(new UniDrTableSources(metadata));return service;
    }
    @Bean UniDrSourceCatalog uniDrSources(UniDrQuerySourceService queries, UniDrReferenceService references) {
        var catalog=new UniDrSourceCatalog(java.util.List.of(org.ip.unidr.source.UniDrReceivingItemsSource.definition()),queries::describe,queries::describeParameters);
        catalog.usePlans(queries::describeSource);
        catalog.useReferences(references);
        catalog.useSourcePlans(source -> UniDrQueryAdapter.plan(source,references));
        return catalog;
    }
    @Bean UniDrDefinitionValidator uniDrValidator(UniDrSourceCatalog sources) { return new UniDrDefinitionValidator(sources); }
    @Bean UniDrDefinitionJsonCodec uniDrCodec(UniDrDefinitionValidator validator) { return new UniDrDefinitionJsonCodec(validator); }
    @Bean UniDrFileStore uniDrFiles(UniDrDefinitionJsonCodec codec, UniDrAccess access,
        @Value("${app.unidr.file-store-dir:reports/unidr}") String directory) {
        return new UniDrFileStore(java.nio.file.Path.of(directory), codec, access);
    }
    @Bean UniDrCatalogContributor uniDrCatalog(UniDrFileStore store) {
        return new UniDrCatalogContributor(store);
    }
    @Bean UniDrAccess uniDrAccess(Environment environment, RlsCurrentUser current) {
        return new UniDrAccess(() -> environment.getProperty("app.unidr.enabled", Boolean.class, false), current);
    }
    @Bean UniDrRunParameterResolver uniDrParameters(UniDrDefinitionValidator validator) { return new UniDrRunParameterResolver(validator); }
    @Bean UniDrReferenceService uniDrReferences(EntityLookup lookup, UniDrAccess access, PlatformTransactionManager transactions,
                                               org.springframework.beans.factory.ObjectProvider<jakarta.persistence.EntityManagerFactory> entities) {
        var service=new UniDrReferenceService(lookup, access, transactions);var factory=entities.getIfAvailable();
        if(factory!=null)service.useEntityTypes(factory.getMetamodel().getEntities().stream().map(jakarta.persistence.metamodel.EntityType::getJavaType).toList());return service;
    }
    @Bean UniDrQueryAdapter uniDrAdapter(UniDrSourceCatalog sources, UniDrDefinitionValidator validator, ReportQueryAssemblyService assembly,
            UniDrReferenceService references) {
        return new UniDrQueryAdapter(sources, validator, assembly, references);
    }
    @Bean UniDrQueryService uniDrQueries(UniDrAccess access, UniDrRunParameterResolver parameters, UniDrReferenceService references,
        UniDrQueryAdapter adapter, ReportQueryExecutor executor, PlatformTransactionManager transactions, UniDrQuerySourceService querySources,
        @Value("${app.unidr.max-rows:5000}") int rows, @Value("${app.unidr.timeout-ms:30000}") long timeout,
        org.springframework.beans.factory.ObjectProvider<AccessService> grants,
        org.springframework.beans.factory.ObjectProvider<RlsDimensionRegistry> dimensions,
        @Value("${ipro.report.rls-org-dimension:BRANCH}") String dimension) {
        var service=new UniDrQueryService(access, parameters, references, adapter, executor, transactions, rows, timeout);service.useQuerySources(querySources);
        if(grants.getIfAvailable()!=null && dimensions.getIfAvailable()!=null)service.useOrgContext(new UniDrOrgContext(grants.getObject(),dimensions.getObject(),dimension));return service;
    }
    @Bean Clock uniDrClock() { return Clock.systemUTC(); }
    @Bean UniDrReportCompiler uniDrCompiler(UniDrSourceCatalog sources, UniDrDefinitionValidator validator) {
        return new UniDrReportCompiler(sources, validator);
    }
    @Bean UniDrRunService uniDrRuns(UniDrAccess access, UniDrQueryService queries, ReportTaskExecutor worker, ReportRunQuota quota,
        @Qualifier("uniDrClock") Clock clock, UniDrReportCompiler compiler, JasperExporter exporter) {
        return new UniDrRunService(access, queries, worker, quota, clock, compiler, exporter);
    }
}
