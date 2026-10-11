package org.ip.unidr.config;

import org.ip.unidr.run.*;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.run.ReportTaskExecutor;
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
            .withBean(org.ipro.reportstudio.render.ReportCompiler.class, () -> mock(org.ipro.reportstudio.render.ReportCompiler.class))
            .withBean(ReportTaskExecutor.class, () -> new ReportTaskExecutor(Runnable::run))
            .withBean(ReportRunQuota.class, () -> new ReportRunQuota(2));
    }
    @Test void enabledUsesExistingInfrastructureAndDefaultLimits() { enabled().run(c -> assertThat(c).hasNotFailed().hasSingleBean(UniDrRunService.class)); }
    @Test void enabledRegistersFileStorageAndCatalogContributor() {
        enabled().run(c->assertThat(c).hasNotFailed().hasSingleBean(org.ip.unidr.store.UniDrFileStore.class).hasSingleBean(org.ipro.ureport.catalog.ReportCatalogContributor.class));
    }
    @Test void unsafeLimitsFailStartup() { enabled().withPropertyValues("app.unidr.max-rows=5001").run(c -> assertThat(c).hasFailed()); }
}
