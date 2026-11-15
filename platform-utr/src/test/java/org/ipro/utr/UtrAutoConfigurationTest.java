package org.ipro.utr;

import org.ipro.query.QueryProjectionCompiler;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UtrAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UtrAutoConfiguration.class));

    @Test
    void enabledFlagControlsStoreAndCatalogSourceRegistration() {
        runner.run(context -> assertThat(context).doesNotHaveBean(UtrFileStore.class));
        runner.withPropertyValues("app.utr.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UtrFileStore.class));
        runner.withPropertyValues("app.utr.enabled=true", "app.utr.file-store-dir=target/test-utr")
                .withBean(RlsCurrentUser.class, () -> () -> "author")
                .withBean(QueryProjectionCompiler.class, () -> mock(QueryProjectionCompiler.class))
                .run(context -> assertThat(context).hasSingleBean(UtrFileStore.class));
    }
}
