package org.ip.unidr.config;

import org.ipro.unidr.run.*;
import org.ipro.crud.EntityLookup;
import org.ipro.query.*;
import org.ipro.report.task.ReportTaskExecutor;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(UniDrConfiguration.class);
    @Test void defaultOffNeedsNoReportInfrastructure() { context.run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(UniDrRunService.class)); }
    @Test void explicitOffNeedsNoReportInfrastructure() { context.withPropertyValues("app.unidr.enabled=false").run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(UniDrRunService.class)); }
    private ApplicationContextRunner enabled() {
        return context.withPropertyValues("app.unidr.enabled=true")
            .withBean(RlsCurrentUser.class, () -> () -> "alice")
            .withBean(EntityLookup.class, () -> mock(EntityLookup.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ReportQueryAssemblyService.class, () -> mock(ReportQueryAssemblyService.class))
            .withBean(ReportQueryExecutor.class, () -> mock(ReportQueryExecutor.class))
            .withBean(org.ipro.report.render.JasperExporter.class, () -> mock(org.ipro.report.render.JasperExporter.class))
            .withBean(ReportTaskExecutor.class, () -> new ReportTaskExecutor(Runnable::run))
            .withBean(ReportRunQuota.class, () -> new ReportRunQuota(2));
    }
    @Test void enabledUsesExistingInfrastructureAndDefaultLimits() { enabled().run(c -> assertThat(c).hasNotFailed().hasSingleBean(UniDrRunService.class)); }
    @Test void enabledRegistersFileStorageAndCatalogContributor() {
        enabled().run(c->assertThat(c).hasNotFailed().hasSingleBean(org.ipro.unidr.store.UniDrFileStore.class).hasSingleBean(org.ipro.report.catalog.ReportCatalogContributor.class));
    }
    @Test void unsafeLimitsFailStartup() { enabled().withPropertyValues("app.unidr.max-rows=5001").run(c -> assertThat(c).hasFailed()); }
}
