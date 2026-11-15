package org.ipro.navigation;

import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.config.NavigationAutoConfiguration;
import org.ipro.navigation.internal.NavigationStructureBootstrap;
import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationKnownFunctionRepository;
import org.ipro.navigation.store.NavigationMenuEntity;
import org.ipro.navigation.store.NavigationMenuRepository;
import org.ipro.navigation.store.NavigationSectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPersistenceTest {
    static final class Users implements NavigationUserDirectory {
        String current = "101";
        @Override public List<UserOption> lookup(String term, int limit) {
            return List.of(new UserOption("101", "user-a"), new UserOption("102", "user-b"));
        }
        @Override public boolean exists(String key) { return Set.of("101", "102").contains(key); }
        @Override public String currentUserKey() { return current; }
    }

    static ApplicationContextRunner database(String url, String ddl, Users users, AtomicBoolean admin) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
            DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, NavigationAutoConfiguration.class))
            .withPropertyValues("platform.navigation.enabled=true", "spring.datasource.url=" + url,
                "spring.jpa.hibernate.ddl-auto=" + ddl, "spring.jpa.open-in-view=false")
            .withBean(SubsystemRegistry.class, () -> new SubsystemRegistry(
                "org.ipro.navigation.fixture.original", new MetadataResolver()))
            .withBean(NavigationFunctions.class, () -> NavigationCatalogTest.functions(NavigationCatalogTest.BASE_LISTS))
            .withBean(NavigationAdministrationAccess.class, () -> admin::get)
            .withBean(NavigationUserDirectory.class, () -> users);
    }

    private static ApplicationContextRunner database(Users users, AtomicBoolean admin) {
        return database("jdbc:h2:mem:nav_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "create-drop", users, admin);
    }

    static NavigationMenuService initialize(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        context.getBean(NavigationStructureBootstrap.class).initialize();
        return context.getBean(NavigationMenuService.class);
    }

    @Test
    void mainIsSeededOnceAndTheDatabaseRejectsASecondMain() {
        database(new Users(), new AtomicBoolean(true)).run(context -> {
            var service = initialize(context);
            var main = service.menus().getFirst();
            assertThat(service.menus()).containsExactly(main);
            assertThat(main.main()).isTrue();
            assertThat(main.name()).isEqualTo("Основное меню");
            var initial = service.structure(main.id());
            assertThat(initial.sections()).extracting(NavigationStructure.Section::id).containsExactlyInAnyOrder("root", "child");
            assertThat(initial.entries().stream().filter(entry -> entry.sectionId().equals("child"))
                .sorted(java.util.Comparator.comparingInt(NavigationStructure.Entry::position)).toList())
                .extracting(NavigationStructure.Entry::id).containsExactly("first", "second");
            initialize(context);
            assertThat(service.structure(main.id())).isEqualTo(initial);
            assertThatThrownBy(() -> context.getBean(NavigationMenuRepository.class).saveAndFlush(
                new NavigationMenuEntity("Duplicate main", "duplicate main", true, 1, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> service.delete(main.id(), main.version())).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void copyPreservesPlacementKeysAndImportanceAndCanBeEditedIndependently() {
        database(new Users(), new AtomicBoolean(true)).run(context -> {
            var service = initialize(context);
            long main = service.menus().getFirst().id();
            var original = service.structure(main);
            var changed = original.entries().stream().map(entry -> entry.id().equals("first")
                ? new NavigationStructure.Entry(entry.id(), entry.sectionId(), entry.functionKey(), null, entry.position(),
                    NavigationImportance.IMPORTANT) : entry).toList();
            var saved = service.save(main, original.version(), withEntries(original, changed));
            var copy = service.copy(main, "Технолог");
            var copied = service.structure(copy.id());
            assertThat(copied.sections()).isEqualTo(saved.sections());
            assertThat(copied.entries()).isEqualTo(saved.entries());
            assertThat(service.placements().get("list:first")).containsEntry(main, 1L).containsEntry(copy.id(), 1L);
            service.save(copy.id(), copied.version(), withEntries(copied, List.of()));
            assertThat(service.structure(main)).isEqualTo(saved);
            service.save(main, saved.version(), withEntries(saved, List.of()));
            assertThat(service.structure(copy.id()).sections()).isEqualTo(copied.sections());
            assertThatThrownBy(() -> service.save(main, original.version(), original))
                .isInstanceOf(NavigationConflictException.class);
            assertThat(service.functions()).allSatisfy(function -> assertThat(function.firstSeenAt()).isNotNull());
        });
    }

    @Test
    void assignmentReplacesPreviousMenuAndDeletionReturnsUserToMain() {
        var users = new Users();
        database(users, new AtomicBoolean(true)).run(context -> {
            var service = initialize(context);
            var reader = context.getBean(NavigationMenuReader.class);
            long main = service.menus().getFirst().id();
            var empty = service.createEmpty("Empty");
            assertThat(service.structure(empty.id()).sections()).isEmpty();
            assertThat(service.structure(empty.id()).entries()).isEmpty();
            var copy = service.copy(main, "Copy");
            assertThat(reader.currentMenu().menuId()).isEqualTo(main);
            service.assign(empty.id(), Set.of("101"), Set.of());
            assertThat(reader.currentMenu().menuId()).isEqualTo(empty.id());
            service.assign(copy.id(), Set.of("101"), Set.of());
            service.assign(empty.id(), Set.of(), Set.of("101")); // A stale removal from the former menu is harmless.
            assertThat(service.assignmentOf("101")).contains(copy.id());
            assertThat(service.assignees(copy.id())).containsExactly("101");
            assertThat(service.menus()).filteredOn(menu -> menu.id() == copy.id()).singleElement()
                .satisfies(menu -> assertThat(menu.assignedUsers()).isEqualTo(1));
            assertThat(reader.currentMenu().menuId()).isEqualTo(copy.id());
            users.current = "102";
            assertThat(reader.currentMenu().menuId()).isEqualTo(main);
            users.current = "101";
            service.delete(copy.id(), copy.version());
            assertThat(service.assignmentOf("101")).isEmpty();
            assertThat(reader.currentMenu().menuId()).isEqualTo(main);
            var renamed = service.rename(empty.id(), empty.version(), "  Технолог  ");
            assertThat(renamed.name()).isEqualTo("Технолог");
            assertThatThrownBy(() -> service.createEmpty("ТЕХНОЛОГ")).isInstanceOf(NavigationConflictException.class);
            assertThatThrownBy(() -> service.assign(empty.id(), Set.of("missing"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void reconciliationUsesEachMenusAreaLinkAndKeepsDeletedListsDeleted() {
        database(new Users(), new AtomicBoolean(true)).run(context -> {
            var service = initialize(context);
            long main = service.menus().getFirst().id();
            var copy = service.copy(main, "Copy");
            var original = service.structure(copy.id());
            var links = original.sections().stream().map(section -> new NavigationStructure.Section(section.id(),
                section.parentId(), section.title(), section.icon(), section.position(),
                section.id().equals("root") ? "child" : "root")).toList();
            var existing = original.entries().stream().filter(entry -> !entry.id().equals("first")).toList();
            service.save(copy.id(), original.version(), new NavigationStructure(copy.id(), original.version(),
                original.seededAt(), links, existing));
            var functions = new ArrayList<>(NavigationCatalogTest.BASE_LISTS);
            functions.add(NavigationCatalogTest.list("new-item", Object.class, "New item", "child", 0));
            functions.add(new NavigationFunction("create:first", Object.class, NavigationFunction.Kind.CREATE,
                "Create", "", 0, "child"));
            var bootstrap = new NavigationStructureBootstrap(context.getBean(SubsystemRegistry.class),
                new NavigationFunctionsProvider(NavigationCatalogTest.functions(functions)),
                context.getBean(NavigationMenuRepository.class), context.getBean(NavigationSectionRepository.class),
                context.getBean(NavigationEntryRepository.class), context.getBean(NavigationKnownFunctionRepository.class),
                context.getBean(PlatformTransactionManager.class));
            bootstrap.initialize();

            var updatedMain = service.structure(main);
            var updatedCopy = service.structure(copy.id());
            assertThat(updatedMain.entries()).filteredOn(entry -> entry.id().equals("new-item")).singleElement()
                .satisfies(entry -> assertThat(entry.sectionId()).isEqualTo("child"));
            assertThat(updatedCopy.entries()).filteredOn(entry -> entry.id().equals("new-item")).singleElement()
                .satisfies(entry -> assertThat(entry.sectionId()).isEqualTo("root"));
            assertThat(updatedCopy.entries()).filteredOn(entry -> !entry.id().equals("new-item"))
                .containsExactlyInAnyOrderElementsOf(existing);
            assertThat(updatedMain.entries()).noneMatch(entry -> entry.functionKey().equals("create:first"));
            bootstrap.initialize();
            assertThat(service.structure(copy.id())).isEqualTo(updatedCopy);
        });
    }

    @Test
    void invalidTreeAndCrossMenuDraftLeaveStructureUntouched() {
        database(new Users(), new AtomicBoolean(true)).run(context -> {
            var service = initialize(context);
            var main = service.menus().getFirst();
            var before = service.structure(main.id());
            var child = before.sections().stream().filter(section -> section.id().equals("child")).findFirst().orElseThrow();
            for (String invalidParent : List.of("child", "missing")) {
                var root = new NavigationStructure.Section("root", invalidParent, "Root", "", 0, "root");
                var invalid = new NavigationStructure(main.id(), before.version(), before.seededAt(),
                    List.of(root, child), before.entries());
                assertThatThrownBy(() -> service.save(main.id(), before.version(), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> service.save(main.id(), before.version(),
                new NavigationStructure(main.id() + 1, before.version(), before.seededAt(), before.sections(), before.entries())))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.structure(main.id())).isEqualTo(before);
        });
    }

    @Test
    void accessIsCheckedBeforeValidatingAnyAdministrativeArguments() {
        var admin = new AtomicBoolean(true);
        database(new Users(), admin).run(context -> {
            var service = initialize(context);
            admin.set(false);
            List<Runnable> calls = List.of(service::menus, () -> service.structure(-1),
                () -> service.save(-1, 0, null), () -> service.copy(-1, null), () -> service.createEmpty(null),
                () -> service.rename(-1, 0, null), () -> service.delete(-1, 0), () -> service.assignees(-1),
                () -> service.assign(-1, null, null), () -> service.assignmentOf(null),
                () -> service.lookupUsers(null, 0), service::functions, service::placements);
            for (var call : calls) assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
            assertThat(context.getBean(NavigationMenuReader.class).currentMenu().sections()).isNotEmpty();
        });
    }

    @Test
    void importPreservesKeysAndReplacingChecksVersion() {
        database(new Users(), new AtomicBoolean(true)).run(context -> {
            var service = initialize(context);
            var original = service.structure(service.menus().getFirst().id());
            var imported = service.importMenu("Imported", original);
            var saved = service.structure(imported.id());
            assertThat(saved.sections()).containsExactlyInAnyOrderElementsOf(original.sections());
            assertThat(saved.entries()).containsExactlyInAnyOrderElementsOf(original.entries());
            assertThat(service.assignees(imported.id())).isEmpty();
            var replaced = service.replaceMenu(imported.id(), saved.version(), withEntries(saved, List.of()));
            assertThat(replaced.entries()).isEmpty();
            assertThatThrownBy(() -> service.replaceMenu(imported.id(), saved.version(), original))
                .isInstanceOf(NavigationConflictException.class);
            assertThat(service.structure(imported.id())).isEqualTo(replaced);
        });
    }

    private static NavigationStructure withEntries(NavigationStructure source, List<NavigationStructure.Entry> entries) {
        return new NavigationStructure(source.menuId(), source.version(), source.seededAt(), source.sections(), entries);
    }
}
