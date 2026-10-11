package org.ip.navigation;

import org.ip.model.UnitOfMeasurement;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationNavigationConfigurationTest {
    @Test
    void actualApplicationMetadataHasCompleteStableIdentitiesAndUsesThePublishedContributorBean() {
        new ApplicationContextRunner().withUserConfiguration(ApplicationNavigationConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var registry = new SubsystemRegistry("org.ip");
                registry.afterPropertiesSet();
                var contributor = context.getBean(org.ipro.navigation.NavigationDefaultsContributor.class);
                var catalog = NavigationCatalog.build(registry, List.of(contributor));
                try (var input = getClass().getResourceAsStream("/navigation/pilot-navigation-ids.txt")) {
                    assertThat(input).isNotNull();
                    var expected = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
                    assertThat(catalog.keys().stream().sorted().toList()).containsExactlyElementsOf(expected);
                }
                assertThat(catalog.findItem("units-list").orElseThrow().entityClass()).isEqualTo(UnitOfMeasurement.class);
                var resolved = new NavigationResolver().resolve(catalog,
                    Map.of("item:units-list", NavigationVisibility.HIDE), type -> true);
                assertThat(resolved.findSubsystem("directories").orElseThrow().items())
                    .noneMatch(item -> item.entityClass() == UnitOfMeasurement.class);
                assertThat(catalog.findItem("units-list")).isPresent();
                assertThat(resolved.findSubsystem("documents")).isEmpty();
                assertThat(resolved.staleKeys()).isEmpty();
            });
    }
}
