package org.ipro.navigation;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.navigation.config.NavigationAutoConfiguration;
import org.ipro.navigation.internal.DefaultNavigationProfileReader;
import org.ipro.navigation.store.NavigationAssignmentEntity;
import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationOverrideEntity;
import org.ipro.navigation.store.NavigationOverrideRepository;
import org.ipro.navigation.store.NavigationProfileEntity;
import org.ipro.navigation.store.NavigationProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPersistenceTest {
    static final class Users implements NavigationUserDirectory {
        final ThreadLocal<String> current = ThreadLocal.withInitial(() -> "101");
        @Override public List<UserOption> lookup(String term, int limit) {
            return List.of(new UserOption("101", "user-a"), new UserOption("102", "user-b")).stream().limit(limit).toList();
        }
        @Override public boolean exists(String key) { return List.of("101", "102").contains(key); }
        @Override public String currentUserKey() { return current.get(); }
    }

    static ApplicationContextRunner database(String url, String ddl) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
            DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, NavigationAutoConfiguration.class))
            .withPropertyValues("platform.navigation.enabled=true", "spring.datasource.url=" + url,
                "spring.jpa.hibernate.ddl-auto=" + ddl, "spring.jpa.open-in-view=false")
            .withBean(SubsystemRegistry.class, () -> new SubsystemRegistry("org.ipro.navigation.fixture.original", new MetadataResolver()));
    }

    static ApplicationContextRunner runner(AtomicBoolean admin, Users users) {
        return runner(admin, users, NavigationCatalogTest.BASE_LISTS);
    }

    private static ApplicationContextRunner runner(AtomicBoolean admin, Users users, List<NavigationFunction> functions) {
        return database("jdbc:h2:mem:nav_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "create-drop")
            .withBean(NavigationFunctions.class, () -> NavigationCatalogTest.functions(functions))
            .withBean(NavigationAdministrationAccess.class, () -> admin::get)
            .withInitializer(context -> context.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                    context.getBean(org.ipro.navigation.internal.NavigationStructureBootstrap.class).initialize();
                }
            }))
            .withBean(NavigationUserDirectory.class, () -> users);
    }

    @Test
    void structureStorageEnforcesAreaUniquenessAndVersionsTheSingleton() {
        database("jdbc:h2:mem:nav_storage;DB_CLOSE_DELAY=-1", "create-drop")
            .withBean(NavigationFunctions.class, () -> NavigationCatalogTest.functions(List.of()))
            .withBean(NavigationAdministrationAccess.class, () -> () -> true)
            .withBean(NavigationUserDirectory.class, Users::new).run(context -> {
            assertThat(context).hasNotFailed();
            var structures = context.getBean(org.ipro.navigation.store.NavigationStructureRepository.class);
            var sections = context.getBean(org.ipro.navigation.store.NavigationSectionRepository.class);
            var entries = context.getBean(org.ipro.navigation.store.NavigationEntryRepository.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var now = java.time.Instant.parse("2026-11-13T00:00:00Z");
            var initial = structures.saveAndFlush(new org.ipro.navigation.store.NavigationStructureEntity(now));
            transaction.executeWithoutResult(status -> {
                var state = structures.lockSingleton().orElseThrow();
                assertThat(state.getSeededAt()).isEqualTo(now);
                var parent = sections.save(new org.ipro.navigation.store.NavigationSectionEntity(
                    "production", null, "Производство", "FACTORY", 0, "production"));
                var child = sections.save(new org.ipro.navigation.store.NavigationSectionEntity(
                    "standards", parent, "Нормативы", "", 0, null));
                entries.save(new org.ipro.navigation.store.NavigationEntryEntity(
                    "prd-spec", child, "list:prd-spec", null, 0));
                state.touch();
            });
            assertThat(structures.findById(initial.getId()).orElseThrow().getVersion())
                .isGreaterThan(initial.getVersion());
            assertThatThrownBy(() -> sections.saveAndFlush(new org.ipro.navigation.store.NavigationSectionEntity(
                "other", null, "Другой раздел", "", 1, "production")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        });
    }

    @Test
    void storedCatalogReflectsMovesAndBootstrapDoesNotRestoreDeletedEntries() {
        var functions = List.of(
            new NavigationFunction("list:first", Object.class, NavigationFunction.Kind.LIST, "First", "", 1, "child"),
            new NavigationFunction("list:second", String.class, NavigationFunction.Kind.LIST, "Second", "", 2, "root"));
        runner(new AtomicBoolean(true), new Users(), functions).run(context -> {
                assertThat(context).hasNotFailed();
                var repositories = context.getBean(org.ipro.navigation.store.NavigationEntryRepository.class);
                var bootstrap = new org.ipro.navigation.internal.NavigationStructureBootstrap(
                    context.getBean(SubsystemRegistry.class), context.getBean(NavigationFunctionsProvider.class),
                    context.getBean(org.ipro.navigation.store.NavigationStructureRepository.class),
                    context.getBean(org.ipro.navigation.store.NavigationSectionRepository.class), repositories,
                    context.getBean(org.ipro.navigation.store.NavigationKnownFunctionRepository.class),
                    context.getBean(PlatformTransactionManager.class));
                bootstrap.initialize();
                var catalog = context.getBean(NavigationCatalogProvider.class);
                assertThat(catalog.get().findSubsystem("child").orElseThrow().items())
                    .extracting(NavigationItem::key).containsExactly("entry:first");
                var service = context.getBean(NavigationStructureService.class);
                var before = service.structure();
                assertThat(before.sections()).extracting(NavigationStructure.Section::id)
                    .containsExactlyInAnyOrder("root", "child");
                var loop = new NavigationStructure.Section("root", "child", "Root", "", 0, "root");
                var child = before.sections().stream().filter(section -> section.id().equals("child")).findFirst().orElseThrow();
                assertThatThrownBy(() -> service.save(before.version(), new NavigationStructure(
                    before.version(), before.seededAt(), List.of(loop, child), before.entries())))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThat(service.structure()).isEqualTo(before);
                var first = before.entries().stream().filter(entry -> entry.id().equals("first")).findFirst().orElseThrow();
                var saved = service.save(before.version(), new NavigationStructure(before.version(), before.seededAt(),
                    before.sections(), List.of(new NavigationStructure.Entry(first.id(), "root", first.functionKey(), null, 0))));
                assertThat(saved.version()).isGreaterThan(before.version());
                assertThatThrownBy(() -> service.save(before.version(), before))
                    .isInstanceOf(NavigationConflictException.class);
                bootstrap.initialize();
                assertThat(catalog.get().findSubsystem("root").orElseThrow().items())
                    .extracting(NavigationItem::key).containsExactly("entry:first");
                var filtered = new NavigationResolver().resolve(catalog.get(),
                    Map.of("entry:first", NavigationVisibility.HIDE), type -> true);
                assertThat(filtered.roots()).isEmpty();
                assertThat(catalog.get().findItem("second")).isEmpty();
                assertThat(service.unplaced()).extracting(row -> row.function().key()).containsExactly("list:second");
                var draft = service.structure();
                var extra = new NavigationStructure.Section("temporary", null, "Temporary", "", 100, null);
                var withExtra = new ArrayList<>(draft.sections());
                withExtra.add(extra);
                var updated = service.save(draft.version(), new NavigationStructure(draft.version(),
                    draft.seededAt(), withExtra, draft.entries()));
                var preview = service.rebuildPreview();
                assertThat(preview.removedSections()).singleElement().satisfies(section ->
                    assertThat(section.id()).startsWith("s-"));
                var profiles = context.getBean(NavigationAdministrationService.class);
                var profile = profiles.createProfile("Пересборка");
                profiles.saveProfile(profile.id(), profile.version(),
                    Map.of("section:" + preview.removedSections().getFirst().id(), NavigationVisibility.HIDE));
                assertThat(service.rebuildPreview().affectedOverrides()).isEqualTo(1);
                var rebuilt = service.rebuild(updated.version());
                assertThat(rebuilt.entries()).extracting(NavigationStructure.Entry::id)
                    .containsExactlyInAnyOrder("first", "second");
                var nextFunctions = new NavigationFunctionsProvider(new NavigationFunctions() {
                    public List<NavigationFunction> all() {
                        return List.of(functions.get(0), functions.get(1),
                            new NavigationFunction("list:new", Integer.class, NavigationFunction.Kind.LIST,
                                "New", "", 0, "root"),
                            new NavigationFunction("list:unplaced", Long.class, NavigationFunction.Kind.LIST,
                                "Unplaced", "", 0, null),
                            new NavigationFunction("create:second", String.class, NavigationFunction.Kind.CREATE,
                                "Create", "", 0, "root"),
                            new NavigationFunction("list:second:variant", String.class, NavigationFunction.Kind.LIST_VARIANT,
                                "Variant", "", 0, "root", "variant", null));
                    }

                    public java.util.Optional<NavigationFunction> find(String key) {
                        return all().stream().filter(function -> function.key().equals(key)).findFirst();
                    }
                });
                var nextBootstrap = new org.ipro.navigation.internal.NavigationStructureBootstrap(
                    context.getBean(SubsystemRegistry.class), nextFunctions,
                    context.getBean(org.ipro.navigation.store.NavigationStructureRepository.class),
                    context.getBean(org.ipro.navigation.store.NavigationSectionRepository.class), repositories,
                    context.getBean(org.ipro.navigation.store.NavigationKnownFunctionRepository.class),
                    context.getBean(PlatformTransactionManager.class));
                nextBootstrap.initialize();
                var reconciled = context.getBean(NavigationStructureReader.class).current();
                assertThat(NavigationCatalog.build(reconciled, nextFunctions.indexed())
                    .findSubsystem("root").orElseThrow().items()).extracting(NavigationItem::id)
                    .containsExactly("second", "new");
                assertThat(reconciled.entries()).extracting(NavigationStructure.Entry::functionKey)
                    .doesNotContain("list:unplaced", "create:second", "list:second:variant");
                nextBootstrap.initialize();
                assertThat(context.getBean(NavigationStructureReader.class).current()).isEqualTo(reconciled);
            });
    }

    @Test
    void movingAreaLinkKeepsExistingEntriesAndAppendsNewFunctionsToNewSection() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var service = context.getBean(NavigationStructureService.class);
            var initial = service.structure();
            var sections = initial.sections().stream().map(section -> new NavigationStructure.Section(
                section.id(), section.parentId(), section.title(), section.icon(), section.position(),
                section.id().equals("root") ? "child" : "root")).toList();
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("update navigation_section set created_at = TIMESTAMP '2020-01-01 12:00:00', created_by = 'original' where section_key = 'child'");
            jdbc.update("update navigation_entry set created_at = TIMESTAMP '2020-01-01 12:00:00', created_by = 'original' where entry_key = 'first'");
            String sectionAudit = "select id, created_at, created_by from navigation_section where section_key = 'child'";
            String entryAudit = "select id, created_at, created_by from navigation_entry where entry_key = 'first'";
            var originalSection = jdbc.queryForMap(sectionAudit);
            var originalEntry = jdbc.queryForMap(entryAudit);
            var saved = service.save(initial.version(),
                new NavigationStructure(initial.version(), initial.seededAt(), sections, initial.entries()));
            assertThat(saved.entries()).containsExactlyInAnyOrderElementsOf(initial.entries());
            assertThat(jdbc.queryForMap(sectionAudit)).isEqualTo(originalSection);
            assertThat(jdbc.queryForMap(entryAudit)).isEqualTo(originalEntry);

            var next = new ArrayList<>(NavigationCatalogTest.BASE_LISTS);
            next.add(NavigationCatalogTest.list("new-item", Integer.class, "New item", "child", 0));
            var bootstrap = new org.ipro.navigation.internal.NavigationStructureBootstrap(
                context.getBean(SubsystemRegistry.class), new NavigationFunctionsProvider(NavigationCatalogTest.functions(next)),
                context.getBean(org.ipro.navigation.store.NavigationStructureRepository.class),
                context.getBean(org.ipro.navigation.store.NavigationSectionRepository.class),
                context.getBean(org.ipro.navigation.store.NavigationEntryRepository.class),
                context.getBean(org.ipro.navigation.store.NavigationKnownFunctionRepository.class),
                context.getBean(PlatformTransactionManager.class));
            bootstrap.initialize();
            var reconciled = context.getBean(NavigationStructureReader.class).current();
            assertThat(reconciled.entries()).containsAll(initial.entries());
            assertThat(reconciled.entries()).filteredOn(entry -> entry.functionKey().equals("list:new-item"))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.sectionId()).isEqualTo("root");
                    assertThat(entry.position()).isGreaterThan(saved.entries().stream()
                        .filter(previous -> previous.sectionId().equals("root"))
                        .mapToInt(NavigationStructure.Entry::position).max().orElseThrow());
                });
        });
    }

    @Test
    void rejectsUnknownParentsAndCyclesWithoutChangingPersistedStructure() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var service = context.getBean(NavigationStructureService.class);
            var original = service.structure();
            var child = original.sections().stream().filter(row -> row.id().equals("child")).findFirst().orElseThrow();
            for (String invalidParent : List.of("missing", "child")) {
                var root = new NavigationStructure.Section("root", invalidParent, "Root", "", 0, "root");
                var draft = new NavigationStructure(original.version(), original.seededAt(),
                    List.of(root, child), original.entries());
                assertThatThrownBy(() -> service.save(original.version(), draft))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThat(service.structure()).isEqualTo(original);
            }
        });
    }

    @Test
    void profileStaleKeysUseTheCatalogPassedByTheMenu() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var profiles = context.getBean(NavigationAdministrationService.class);
            var profile = profiles.createProfile("Snapshot");
            profiles.saveProfile(profile.id(), profile.version(), Map.of("entry:first", NavigationVisibility.SHOW));
            profiles.assignUser("101", profile.id());
            var catalogs = context.getBean(NavigationCatalogProvider.class);
            var oldCatalog = catalogs.get();
            var structures = context.getBean(NavigationStructureService.class);
            var old = structures.structure();
            structures.save(old.version(), new NavigationStructure(old.version(), old.seededAt(), old.sections(),
                old.entries().stream().filter(entry -> !entry.id().equals("first")).toList()));

            var reader = context.getBean(NavigationProfileReader.class);
            assertThat(reader.currentProfile(oldCatalog).orElseThrow().staleKeys()).isEmpty();
            assertThat(reader.currentProfile(catalogs.get()).orElseThrow().staleKeys()).containsExactly("entry:first");
        });
    }

    @Test
    void disabledIsInertWithoutJpaMetadataOrSecurityAdapters() {
        for (String enabled : List.of("false", "")) {
            var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(NavigationAutoConfiguration.class));
            if (!enabled.isEmpty()) runner = runner.withPropertyValues("platform.navigation.enabled=false");
            runner.run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(NavigationCatalogProvider.class)
                    .doesNotHaveBean(NavigationAdministrationService.class).doesNotHaveBean(NavigationProfileReader.class)
                    .doesNotHaveBean(NavigationProfileRepository.class).doesNotHaveBean(EntityManagerFactory.class);
            });
        }
    }

    @Test
    void enabledRequiresBothSecurityAndUserAdapters() {
        database("jdbc:h2:mem:nav_missing_access", "create-drop")
            .withBean(NavigationFunctions.class, () -> NavigationCatalogTest.functions(List.of()))
            .withBean(NavigationUserDirectory.class, Users::new).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("NavigationAdministrationAccess");
            });
        database("jdbc:h2:mem:nav_missing_users", "create-drop")
            .withBean(NavigationFunctions.class, () -> NavigationCatalogTest.functions(List.of()))
            .withBean(NavigationAdministrationAccess.class, () -> () -> false).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("NavigationUserDirectory");
            });
    }

    @Test
    void createSaveAssignReassignResetAndUnassignUseDetachedVersionedReadModels() {
        var admin = new AtomicBoolean(true);
        var users = new Users();
        runner(admin, users).run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(NavigationAdministrationService.class);
            var reader = context.getBean(NavigationProfileReader.class);
            assertThat(reader.currentProfile()).isEmpty();
            var first = service.createProfile(" Склад ");
            assertThat(first.name()).isEqualTo("Склад");
            var saved = service.saveProfile(first.id(), first.version(), Map.of(
                "entry:first", NavigationVisibility.HIDE, "entry:second", NavigationVisibility.DEFAULT));
            assertThat(saved.version()).isGreaterThan(first.version());
            assertThat(saved.overrides()).containsExactlyEntriesOf(Map.of("entry:first", NavigationVisibility.HIDE));
            var second = service.createProfile("Производство");
            service.assignUser("101", first.id());
            service.assignUser("102", second.id());
            assertThat(reader.currentProfile()).contains(saved);
            users.current.set("102");
            assertThat(reader.currentProfile()).contains(second);
            service.assignUser("102", first.id());
            assertThat(service.assignmentForUser("102")).contains(first.id());
            var reset = service.resetProfile(saved.id(), saved.version());
            assertThat(reset.overrides()).isEmpty();
            assertThat(reset.version()).isGreaterThan(saved.version());
            assertThat(service.assignmentForUser("101")).contains(first.id());
            assertThat(reader.currentProfile()).contains(reset);
            service.unassignUser("102");
            assertThat(reader.currentProfile()).isEmpty();
            assertThatThrownBy(() -> saved.overrides().clear()).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void allAdministrativeMethodsDenyDirectCallsBeforeValidationOrDatabaseWork() {
        var admin = new AtomicBoolean(true);
        var users = new Users();
        runner(admin, users).run(context -> {
            var service = context.getBean(NavigationAdministrationService.class);
            var profile = service.createProfile("Admin");
            service.assignUser("101", profile.id());
            admin.set(false);
            List<Runnable> forbidden = List.of(service::listProfiles, () -> service.profile(profile.id()),
                () -> service.createProfile(null), () -> service.renameProfile(profile.id(), 0, null),
                () -> service.saveProfile(profile.id(), 0, null), () -> service.resetProfile(profile.id(), 0),
                () -> service.lookupUsers(null, 0), () -> service.assignUser(null, 0),
                () -> service.unassignUser(null), () -> service.assignmentForUser(null));
            for (Runnable operation : forbidden) assertThatThrownBy(operation::run).isInstanceOf(AccessDeniedException.class);
            assertThat(context.getBean(NavigationProfileReader.class).currentProfile()).contains(profile);
            admin.set(true);
            assertThat(service.listProfiles()).containsExactly(profile);
        });
    }

    @Test
    void oldStoredKeysSurviveOrdinarySaveButCannotBeIntroducedOrEditedAndResetRemovesThem() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var service = context.getBean(NavigationAdministrationService.class);
            var profile = service.createProfile("Stale");
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("insert into navigation_override (profile_id,target_key,visibility,version) values (?,?,?,0)",
                profile.id(), "entry:removed", "HIDE");
            var saved = service.saveProfile(profile.id(), profile.version(), Map.of("entry:first", NavigationVisibility.SHOW));
            assertThat(saved.staleKeys()).containsExactly("entry:removed");
            assertThat(saved.overrides()).containsEntry("entry:removed", NavigationVisibility.HIDE);
            var savedAgain = service.saveProfile(saved.id(), saved.version(), saved.overrides());
            assertThat(savedAgain.staleKeys()).containsExactly("entry:removed");
            assertThatThrownBy(() -> service.saveProfile(saved.id(), savedAgain.version(), Map.of("entry:new", NavigationVisibility.HIDE)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.saveProfile(saved.id(), savedAgain.version(), Map.of("entry:removed", NavigationVisibility.SHOW)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.profile(saved.id())).isEqualTo(savedAgain);
            assertThat(service.resetProfile(saved.id(), savedAgain.version()).staleKeys()).isEmpty();
        });
    }

    @Test
    void staleVersionsCannotOverwriteNameOrCompositionAndNamesAreUniqueIgnoringCaseAndEdgeSpaces() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var service = context.getBean(NavigationAdministrationService.class);
            var original = service.createProfile("Warehouse");
            var renamed = service.renameProfile(original.id(), original.version(), "New name");
            assertThat(renamed.version()).isGreaterThan(original.version());
            assertThatThrownBy(() -> service.saveProfile(original.id(), original.version(), Map.of()))
                .isInstanceOf(NavigationConflictException.class);
            assertThatThrownBy(() -> service.resetProfile(original.id(), original.version()))
                .isInstanceOf(NavigationConflictException.class);
            assertThatThrownBy(() -> service.renameProfile(original.id(), original.version(), "Lost update"))
                .isInstanceOf(NavigationConflictException.class);
            assertThatThrownBy(() -> service.createProfile(" NEW NAME ")).isInstanceOf(NavigationConflictException.class);
            assertThatThrownBy(() -> service.createProfile(" \t ")).isInstanceOf(IllegalArgumentException.class);
            var other = service.createProfile("Other");
            assertThatThrownBy(() -> service.renameProfile(other.id(), other.version(), "new NAME"))
                .isInstanceOf(NavigationConflictException.class);
            assertThat(service.profile(other.id())).isEqualTo(other);
            assertThat(service.profile(original.id())).isEqualTo(renamed);
        });
    }

    @Test
    void concurrentSavesHaveOneWinnerAndWholeAggregateRollsBackWithOuterTransaction() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var service = context.getBean(NavigationAdministrationService.class);
            var original = service.createProfile("Concurrent");
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<java.util.concurrent.Future<Object>>();
                for (var visibility : List.of(NavigationVisibility.HIDE, NavigationVisibility.SHOW)) {
                    futures.add(pool.submit(() -> {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                        try { return service.saveProfile(original.id(), original.version(), Map.of("entry:first", visibility)); }
                        catch (NavigationConflictException conflict) { return conflict; }
                    }));
                }
                start.countDown();
                List<Object> results = new ArrayList<>();
                for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
                assertThat(results.stream().filter(NavigationProfile.class::isInstance)).hasSize(1);
                assertThat(results.stream().filter(NavigationConflictException.class::isInstance)).hasSize(1);
            }
            var winner = service.profile(original.id());
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> {
                service.saveProfile(winner.id(), winner.version(), Map.of("entry:second", NavigationVisibility.HIDE));
                service.assignUser("101", winner.id());
                throw new IllegalStateException("force rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");
            assertThat(service.profile(winner.id())).isEqualTo(winner);
            assertThat(service.assignmentForUser("101")).isEmpty();
        });
    }

    @Test
    void unknownUserAndProfileAreRejectedAndLookupIsBounded() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var service = context.getBean(NavigationAdministrationService.class);
            var profile = service.createProfile("Users");
            assertThatThrownBy(() -> service.assignUser("missing", profile.id())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.assignUser("101", Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.assignmentForUser("101")).isEmpty();
            assertThat(service.lookupUsers("", 1)).hasSize(1);
            assertThatThrownBy(() -> service.lookupUsers("", 51)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void storageTypesHaveNoCanonicalCrudCapabilitiesAndDatabaseConstraintsProtectMembership() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var managed = new ManagedEntityCatalog(context.getBean(EntityManagerFactory.class));
            var metadata = new MetadataResolver();
            var sections = new SectionMetadataRegistry("org.ipro.navigation.store", metadata);
            sections.afterPropertiesSet();
            var descriptors = new EntityDescriptorCatalog(managed, sections, metadata, List.of());
            for (var type : List.of(NavigationProfileEntity.class, NavigationOverrideEntity.class, NavigationAssignmentEntity.class)) {
                assertThat(type.isAnnotationPresent(EntityMetadata.class)).isFalse();
                assertThat(descriptors.descriptorOf(type).exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
                assertThat(descriptors.descriptorOf(type).capabilities().writes()).isEmpty();
                assertThat(descriptors.descriptorOf(type).capabilities().readScenarios()).isEmpty();
            }
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThatThrownBy(() -> jdbc.update("insert into navigation_assignment (user_key,profile_id,version) values ('101',999,0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var service = context.getBean(NavigationAdministrationService.class);
            var profile = service.createProfile("Constraint");
            service.assignUser("101", profile.id());
            assertThatThrownBy(() -> jdbc.update("insert into navigation_assignment (user_key,profile_id,version) values ('101',?,0)", profile.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            service.saveProfile(profile.id(), profile.version(), Map.of("entry:first", NavigationVisibility.HIDE));
            assertThatThrownBy(() -> jdbc.update("insert into navigation_override (profile_id,target_key,visibility,version) values (?,'entry:first','SHOW',0)", profile.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        });
    }

    @Test
    void profileReadFailureIsNotSilentlyConvertedToNoAssignment() {
        runner(new AtomicBoolean(true), new Users()).run(context -> {
            var failed = (NavigationAssignmentRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {NavigationAssignmentRepository.class},
                (proxy, method, arguments) -> { throw new DataAccessResourceFailureException("database unavailable"); });
            var reader = new DefaultNavigationProfileReader(new Users(), failed,
                context.getBean(NavigationOverrideRepository.class), context.getBean(NavigationCatalogProvider.class),
                context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(reader::currentProfile).isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("database unavailable");
        });
    }
}
