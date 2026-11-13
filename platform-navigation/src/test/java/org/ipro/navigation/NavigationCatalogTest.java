package org.ipro.navigation;
import org.ipro.metadata.SubsystemRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class NavigationCatalogTest {
    static SubsystemRegistry registry(String name) {
        var registry = new SubsystemRegistry(name, new org.ipro.metadata.MetadataResolver());
        registry.afterPropertiesSet();
        return registry;
    }
    static NavigationCatalog catalog() {
        return NavigationCatalog.build(registry("org.ipro.navigation.fixture.original"), NavigationEntityKeys::derive);
    }
    @Test void derivesStableKeys() {
        assertThat(catalog().keys()).contains("subsystem:root", "item:root-item", "item:first");
    }
}
