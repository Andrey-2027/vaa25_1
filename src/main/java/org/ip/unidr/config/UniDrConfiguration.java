package org.ip.unidr.config;

import org.ip.unidr.model.*;
import org.ip.unidr.query.*;
import org.ip.unidr.run.*;
import org.ip.unidr.render.UniDrReportCompiler;
import org.ipro.reportstudio.render.ReportCompiler;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.run.ReportTaskExecutor;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Disabled by default; JSON file storage without new database mappings. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.unidr", name = "enabled", havingValue = "true")
public class UniDrConfiguration {
    @Bean UniDrQuerySourceService uniDrQuerySources(ReportQueryAssemblyService assembly) {return new UniDrQuerySourceService(assembly);}
    @Bean UniDrSourceCatalog uniDrSources(UniDrQuerySourceService queries) { return new UniDrSourceCatalog(java.util.List.of(org.ip.unidr.source.UniDrReceivingItemsSource.definition()),queries::describe); }
    @Bean UniDrDefinitionValidator uniDrValidator(UniDrSourceCatalog sources) { return new UniDrDefinitionValidator(sources); }
    @Bean UniDrDefinitionJsonCodec uniDrCodec(UniDrDefinitionValidator validator) { return new UniDrDefinitionJsonCodec(validator); }
    @Bean org.ip.unidr.store.UniDrFileStore uniDrFiles(UniDrDefinitionJsonCodec codec, UniDrAccess access,
        @Value("${app.unidr.file-store-dir:reports/unidr}") String directory) {
        return new org.ip.unidr.store.UniDrFileStore(java.nio.file.Path.of(directory), codec, access);
    }
    @Bean org.ip.unidr.store.UniDrCatalogContributor uniDrCatalog(org.ip.unidr.store.UniDrFileStore store) {
        return new org.ip.unidr.store.UniDrCatalogContributor(store);
    }
    @Bean UniDrAccess uniDrAccess(Environment environment, RlsCurrentUser current) {
        return new UniDrAccess(() -> environment.getProperty("app.unidr.enabled", Boolean.class, false), current);
    }
    @Bean UniDrRunParameterResolver uniDrParameters(UniDrDefinitionValidator validator) { return new UniDrRunParameterResolver(validator); }
    @Bean UniDrReferenceService uniDrReferences(EntityLookup lookup, UniDrAccess access, PlatformTransactionManager transactions) {
        return new UniDrReferenceService(lookup, access, transactions);
    }
    @Bean UniDrQueryAdapter uniDrAdapter(UniDrSourceCatalog sources, UniDrDefinitionValidator validator, ReportQueryAssemblyService assembly) {
        return new UniDrQueryAdapter(sources, validator, assembly);
    }
    @Bean UniDrQueryService uniDrQueries(UniDrAccess access, UniDrRunParameterResolver parameters, UniDrReferenceService references,
        UniDrQueryAdapter adapter, ReportQueryExecutor executor, PlatformTransactionManager transactions, UniDrQuerySourceService querySources,
        @Value("${app.unidr.max-rows:5000}") int rows, @Value("${app.unidr.timeout-ms:30000}") long timeout) {
        var service=new UniDrQueryService(access, parameters, references, adapter, executor, transactions, rows, timeout);service.useQuerySources(querySources);return service;
    }
    @Bean Clock uniDrClock() { return Clock.systemUTC(); }
    @Bean UniDrReportCompiler uniDrCompiler(UniDrSourceCatalog sources, UniDrDefinitionValidator validator) {
        return new UniDrReportCompiler(sources, validator);
    }
    @Bean UniDrRunService uniDrRuns(UniDrAccess access, UniDrQueryService queries, ReportTaskExecutor worker, ReportRunQuota quota,
        @Qualifier("uniDrClock") Clock clock, UniDrReportCompiler compiler, ReportCompiler exporter) {
        return new UniDrRunService(access, queries, worker, quota, clock, compiler, exporter);
    }
}
