package org.ipro.navigation;

import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.fixture.original.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationCatalogTest {
    static final NavigationEntityKeys DERIVED = NavigationEntityKeys::derive;

    static SubsystemRegistry registry(String packageName) {
        SubsystemRegistry registry = new SubsystemRegistry(packageName, new org.ipro.metadata.MetadataResolver());
        registry.afterPropertiesSet();
        return registry;
    }

    static SubsystemRegistry original() {
        return registry("org.ipro.navigation.fixture.original");
    }

    static NavigationCatalog catalog() {
        return NavigationCatalog.build(original(), DERIVED);
    }

    @Test
    void duplicateCommandsInTheSameSectionAreRejected() {
        var marker = DuplicateCommands.class;
        var node = new org.ipro.metadata.SubsystemNode(marker,
            marker.getAnnotation(org.ipro.metadata.annotation.Subsystem.class));
        var registry = new SubsystemRegistry("unused", new org.ipro.metadata.MetadataResolver()) {
            @Override
            public java.util.List<org.ipro.metadata.SubsystemNode> getRoots() {
                return java.util.List.of(node);
            }
        };
        assertThatThrownBy(() -> NavigationCatalog.build(registry, DERIVED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command:duplicate-commands/root-item/crud.create")
            .hasMessageContaining("is used by");
    }

    @org.ipro.metadata.annotation.Subsystem(title = "Duplicate", commands = {
        @org.ipro.metadata.annotation.SubsystemCommand(entity = Fixtures.RootItem.class, action = "crud.create", title = "First"),
        @org.ipro.metadata.annotation.SubsystemCommand(entity = Fixtures.RootItem.class, action = "crud.create", title = "Second")
    })
    interface DuplicateCommands {}

    @Test
    void projectsReadyMetadataWithoutMutationAndPreservesOrderIncludingEmptyNodes() {
        SubsystemRegistry registry = original();
        var before = registry.getRoots();
        var root = registry.findByMarker(Fixtures.Root.class).orElseThrow();
        var entities = new ArrayList<>(root.getOwnEntities());
        var children = new ArrayList<>(root.getChildren());
        NavigationCatalog catalog = NavigationCatalog.build(registry, DERIVED);

        assertThat(catalog.roots()).extracting(NavigationNode::id).containsExactly("empty", "root");
        var projected = catalog.findSubsystem("root").orElseThrow();
        assertThat(projected.title()).isEqualTo(root.getTitle());
        assertThat(projected.icon()).isEqualTo(root.getIcon());
        assertThat(projected.order()).isEqualTo(root.getOrder());
        assertThat(projected.items()).extracting(NavigationItem::entityClass)
            .containsExactly(Fixtures.RootItem.class);
        assertThat(catalog.findSubsystem("child").orElseThrow().items())
            .extracting(NavigationItem::id).containsExactly("first", "second");
        assertThat(catalog.findItem("root-item").orElseThrow().title()).isEqualTo("Root list");
        assertThat(registry.getRoots()).isSameAs(before);
        assertThat(root.getOwnEntities()).containsExactlyElementsOf(entities);
        assertThat(root.getChildren()).containsExactlyElementsOf(children);
        assertThat(catalog.keys()).containsExactlyInAnyOrder("subsystem:root", "subsystem:child",
            "subsystem:empty", "item:root-item", "item:first", "item:second");
    }

    @Test
    void everythingIsVisibleByDefaultBecauseSettingsLiveOnlyInProfiles() {
        var catalog = catalog();
        catalog.roots().forEach(node -> assertThat(node.defaultVisible()).isTrue());
        assertThat(catalog.findItem("first").orElseThrow().defaultVisible()).isTrue();
    }

    @Test
    void subsystemIdIsKebabCaseOfMarkerSimpleName() {
        assertThat(NavigationIds.kebabCase("Directories")).isEqualTo("directories");
        assertThat(NavigationIds.kebabCase("ProductionDocuments")).isEqualTo("production-documents");
        assertThat(NavigationIds.kebabCase("XMLParser")).isEqualTo("xml-parser");
        assertThat(NavigationEntityKeys.derive(Fixtures.RootItem.class)).isEqualTo("root-item");
    }

    @Test
    void itemKeyComesFromTheKeySourceAndHoldsNoSubsystem() {
        var catalog = NavigationCatalog.build(original(), type -> "k-" + NavigationEntityKeys.derive(type));
        assertThat(catalog.keys()).contains("item:k-first", "item:k-root-item", "subsystem:child");
        assertThat(catalog.findItem("k-first")).isPresent();
    }

    @Test
    void repeatedKeyIsRejected() {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), type -> "same"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("item:same").hasMessageContaining("is used by");
    }

    @Test
    void keyOutsideGrammarIsRejected() {
        for (String key : new String[] {"", "Upper", "a_b", "a.b", "-a", "a-", "a--b", " a", "имя"}) {
            assertThatThrownBy(() -> NavigationCatalog.build(original(), type ->
                type == Fixtures.RootItem.class ? key : NavigationEntityKeys.derive(type)))
                .as("key '%s'", key)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lower-kebab-case");
        }
    }

    @Test
    void keyLongerThanStorageIsRejected() {
        String longKey = "a".repeat(NavigationIds.MAX_KEY_LENGTH);
        assertThatThrownBy(() -> NavigationCatalog.build(original(), type ->
            type == Fixtures.RootItem.class ? longKey : NavigationEntityKeys.derive(type)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longer than");
    }

    @Test
    void problemsAreReportedTogether() {
        assertThatThrownBy(() -> NavigationCatalog.build(original(), type -> "same"))
            .isInstanceOf(IllegalArgumentException.class)
            .satisfies(e -> assertThat(e.getMessage().split("is used by", -1).length - 1).isEqualTo(2));
    }

    @Test
    void sourceAndResolvedCollectionsAreImmutable() {
        NavigationCatalog catalog = catalog();
        assertThatThrownBy(() -> catalog.roots().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.findSubsystem("root").orElseThrow().items().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.findSubsystem("root").orElseThrow().children().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deferredProviderBuildsOnceOnlyWhenKeysAreReadyAndDoesNotCacheFailure() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] ready = {false};
        var provider = NavigationCatalogProvider.deferred(NavigationCatalogTest::original, type -> {
            calls.incrementAndGet();
            if (!ready[0]) throw new IllegalStateException("form registry is not closed");
            return NavigationEntityKeys.derive(type);
        });
        assertThat(calls).hasValue(0);
        assertThatThrownBy(provider::get).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(provider::validate).isInstanceOf(IllegalStateException.class);
        ready[0] = true;
        var built = provider.get();
        int callsAfterBuild = calls.get();
        assertThat(provider.get()).isSameAs(built);
        provider.validate();
        assertThat(calls).hasValue(callsAfterBuild);
    }

    @Test
    void providerOfBuiltCatalogReturnsIt() {
        var catalog = catalog();
        assertThat(NavigationCatalogProvider.of(catalog).get()).isSameAs(catalog);
    }
}
