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
            .withBean(SubsystemRegistry.class, () -> new SubsystemRegistry("org.ipro.navigation.fixture.original", new org.ipro.metadata.MetadataResolver()))
            .withBean(NavigationEntityKeys.class, () -> NavigationEntityKeys::derive);
    }

    static ApplicationContextRunner runner(AtomicBoolean admin, Users users) {
        return database("jdbc:h2:mem:nav_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "create-drop")
            .withBean(NavigationAdministrationAccess.class, () -> admin::get)
            .withBean(NavigationUserDirectory.class, () -> users);
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
            .withBean(NavigationUserDirectory.class, Users::new).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("NavigationAdministrationAccess");
            });
        database("jdbc:h2:mem:nav_missing_users", "create-drop")
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
                "item:first", NavigationVisibility.HIDE, "item:second", NavigationVisibility.DEFAULT));
            assertThat(saved.version()).isGreaterThan(first.version());
            assertThat(saved.overrides()).containsExactlyEntriesOf(Map.of("item:first", NavigationVisibility.HIDE));
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
                profile.id(), "item:removed", "HIDE");
            var saved = service.saveProfile(profile.id(), profile.version(), Map.of("item:first", NavigationVisibility.SHOW));
            assertThat(saved.staleKeys()).containsExactly("item:removed");
            assertThat(saved.overrides()).containsEntry("item:removed", NavigationVisibility.HIDE);
            var savedAgain = service.saveProfile(saved.id(), saved.version(), saved.overrides());
            assertThat(savedAgain.staleKeys()).containsExactly("item:removed");
            assertThatThrownBy(() -> service.saveProfile(saved.id(), savedAgain.version(), Map.of("item:new", NavigationVisibility.HIDE)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.saveProfile(saved.id(), savedAgain.version(), Map.of("item:removed", NavigationVisibility.SHOW)))
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
                        try { return service.saveProfile(original.id(), original.version(), Map.of("item:first", visibility)); }
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
                service.saveProfile(winner.id(), winner.version(), Map.of("item:second", NavigationVisibility.HIDE));
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
            service.saveProfile(profile.id(), profile.version(), Map.of("item:first", NavigationVisibility.HIDE));
            assertThatThrownBy(() -> jdbc.update("insert into navigation_override (profile_id,target_key,visibility,version) values (?,'item:first','SHOW',0)", profile.id()))
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
