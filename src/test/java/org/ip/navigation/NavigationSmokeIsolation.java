package org.ip.navigation;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import java.io.IOException;

/** Other test harness applications must not be component-scanned into the production application. */
public class NavigationSmokeIsolation implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton("navigationProductionOnlyScan", new TypeExcludeFilter() {
            @Override public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
                return reader.getResource().getURL().toString().contains("/test-classes/");
            }
        });
    }
}
