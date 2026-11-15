package org.ipro.query.list;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;
import org.ipro.query.QueryProjectionCompiler;
import org.ipro.query.config.QueryListAutoConfiguration;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryListRegistryTest {
    private static final QueryDefinition QUERY = new QueryDefinition(
            "select x.code as code from Item x where x.code = :code", null, null, null,
            List.of(QueryParameter.required("code")));

    @Test
    void codecKeepsSourcesAndGridLayoutAndRejectsUnknownVersions() {
        var codec = new QueryListDefinitionCodec();
        var definition = definition("parts", QueryProvenance.REGISTERED);
        var json = codec.encode(definition);
        assertThat(codec.decode(definition.key(), definition.title(), json)).isEqualTo(definition);
        assertThatThrownBy(() -> codec.decode("parts", "Parts", json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryAcceptsAnEntityWithoutAnIdentityColumnForCreation() {
        var compiler = compiler();
        var entityName = "org.example.Item";
        when(compiler.isCommandEntity(entityName)).thenReturn(true);
        var createOnly = new QueryListDefinition("stock.parts", "Номенклатура", QUERY, null, null,
                new QueryListDefinition.CommandObject(entityName, null),
                Set.of(QueryListDefinition.Command.CREATE), QueryProvenance.REGISTERED);
        var wrongColumn = new QueryListDefinition("stock.parts", "Номенклатура", QUERY, null, null,
                new QueryListDefinition.CommandObject(entityName, "code"),
                Set.of(QueryListDefinition.Command.EDIT), QueryProvenance.REGISTERED);

        database("jdbc:h2:mem:fq7_" + UUID.randomUUID(), "create-drop", new AtomicBoolean(true), compiler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(QueryListRegistry.class);
                    assertThat(registry.check(wrongColumn).diagnostic()).contains("идентификатор");
                    var saved = registry.create(createOnly, true);
                    registry.recheck();
                    assertThat(registry.findOpenable(saved.key()).orElseThrow().definition()).isEqualTo(createOnly);
                });
    }

    @Test
    void writesRequireMenuAdminAndEnforceKeysVersionsAndEnabledState() {
        var admin = new AtomicBoolean();
        var compiler = compiler();
        var registered = definition("stock.parts", QueryProvenance.REGISTERED);
        var declared = definition("stock.built-in", QueryProvenance.DECLARED);
        database("jdbc:h2:mem:fq6_" + UUID.randomUUID(), "create-drop", admin, compiler)
                .withBean(QueryListDeclaration.class, () -> () -> declared)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(QueryListRegistry.class);
                    assertThatThrownBy(() -> registry.create(registered, true)).isInstanceOf(RlsAccessDeniedException.class);
                    assertThatThrownBy(() -> registry.update(registered, 0, true)).isInstanceOf(RlsAccessDeniedException.class);
                    assertThatThrownBy(() -> registry.delete(registered.key(), 0)).isInstanceOf(RlsAccessDeniedException.class);
                    admin.set(true);
                    assertThatThrownBy(() -> registry.create(definition(declared.key(), QueryProvenance.REGISTERED), true))
                            .hasMessageContaining("уже занят");
                    var saved = registry.create(registered, true);
                    assertThat(saved.modifiedBy()).isEqualTo("admin");
                    assertThat(registry.findOpenable(registered.key())).contains(saved);
                    assertThatThrownBy(() -> registry.create(registered, true)).hasMessageContaining("уже занят");
                    var disabled = registry.update(registered, saved.version(), false);
                    assertThat(disabled.version()).isGreaterThan(saved.version());
                    assertThat(registry.findOpenable(registered.key())).isEmpty();
                    assertThatThrownBy(() -> registry.update(registered, saved.version(), true))
                            .isInstanceOf(OptimisticLockingFailureException.class);
                    assertThatThrownBy(() -> registry.delete(registered.key(), saved.version()))
                            .isInstanceOf(OptimisticLockingFailureException.class);
                    registry.delete(registered.key(), disabled.version());
                    assertThat(registry.administrativeEntries()).extracting(QueryListRegistry.Entry::key)
                            .containsExactly(declared.key());
                });
    }

    @Test
    void diagnosisRefreshesAfterSchemaDriftAndExistingDatabaseCollision() {
        var admin = new AtomicBoolean(true);
        var compiler = compiler();
        var registered = definition("stock.parts", QueryProvenance.REGISTERED);
        var declared = definition("stock.collision", QueryProvenance.DECLARED);
        database("jdbc:h2:mem:fq6_" + UUID.randomUUID(), "create-drop", admin, compiler)
                .withBean(QueryListDeclaration.class, () -> () -> declared)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(QueryListRegistry.class);
                    registry.create(registered, true);
                    var row = new QueryListEntity();
                    row.key = declared.key();
                    row.title = "Старый список";
                    row.body = new QueryListDefinitionCodec().encode(definition(declared.key(), QueryProvenance.REGISTERED));
                    row.enabled = true;
                    row.modifiedBy = "admin";
                    row.modifiedAt = java.time.Instant.now();
                    context.getBean(QueryListRepository.class).saveAndFlush(row);
                    registry.initialize();
                    assertThat(registry.administrativeEntries()).anySatisfy(entry -> {
                        assertThat(entry.key()).isEqualTo(declared.key());
                        assertThat(entry.diagnostic()).contains("объявлен в коде");
                    });
                    assertThat(registry.findOpenable(declared.key()).orElseThrow().definition()).isEqualTo(declared);
                    when(compiler.inspect(QUERY)).thenThrow(new IllegalArgumentException("Колонка удалена"));
                    registry.recheck();
                    assertThat(registry.findOpenable(registered.key())).isEmpty();
                    assertThat(registry.openingDiagnostic(registered.key())).contains("Колонка удалена");
                    admin.set(false);
                    assertThat(registry.openingDiagnostic(registered.key())).isEmpty();
                });
    }

    @Test
    void reevaluatesReadAccessForEveryOpen() {
        var compiler = compiler();
        var registered = definition("parts", QueryProvenance.REGISTERED);
        database("jdbc:h2:mem:fq6_" + UUID.randomUUID(), "create-drop", new AtomicBoolean(true), compiler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(QueryListRegistry.class);
                    registry.create(registered, true);
                    assertThat(registry.findOpenable(registered.key())).isPresent();
                    doThrow(new RlsAccessDeniedException("Отзыв прав")).when(compiler).requireReadAccess(QUERY);
                    assertThat(registry.findOpenable(registered.key())).isEmpty();
                });
    }

    @Test
    void anotherRegistryCanDisableAListAlreadyCachedByThisRegistry() {
        var compiler = compiler();
        var registered = definition("parts", QueryProvenance.REGISTERED);
        database("jdbc:h2:mem:fq6_" + UUID.randomUUID(), "create-drop", new AtomicBoolean(true), compiler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var first = context.getBean(QueryListRegistry.class);
                    var second = new QueryListRegistry(context.getBean(QueryListRepository.class), () -> true,
                            () -> "admin", compiler, List.of(), context.getBean(PlatformTransactionManager.class));
                    var saved = first.create(registered, true);
                    assertThat(first.findOpenable(registered.key())).isPresent();
                    second.update(registered, saved.version(), false);
                    assertThat(first.findOpenable(registered.key())).isEmpty();
                });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FQ0_TEST_JDBC_URL",
            matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/fq0_[a-f0-9]{32}")
    void postgresMigrationSupportsJpaAndSavedDefinitionSurvivesRestart() throws Exception {
        var url = System.getenv("FQ0_TEST_JDBC_URL");
        var username = System.getenv("FQ0_TEST_JDBC_USERNAME");
        var password = System.getenv("FQ0_TEST_JDBC_PASSWORD");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var script = getClass().getResourceAsStream("/db/query-list-postgresql.sql")) {
            assertThat(script).isNotNull();
            statement.execute(new String(script.readAllBytes(), StandardCharsets.UTF_8));
        }
        var runner = database(url, "validate", new AtomicBoolean(true), compiler())
                .withPropertyValues("spring.datasource.username=" + username, "spring.datasource.password=" + password);
        var registered = definition("parts", QueryProvenance.REGISTERED);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(QueryListRegistry.class).create(registered, true);
        });
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var registry = context.getBean(QueryListRegistry.class);
            assertThat(registry.findOpenable(registered.key()).orElseThrow().definition()).isEqualTo(registered);
        });
    }

    @Test
    void entriesCarryTheEntitiesOfTheQueryAndCheckingWritesNothing() {
        var admin = new AtomicBoolean();
        var compiler = compiler();
        var registered = definition("stock.parts", QueryProvenance.REGISTERED);
        var declared = definition("stock.built-in", QueryProvenance.DECLARED);
        database("jdbc:h2:mem:fq6_" + UUID.randomUUID(), "create-drop", admin, compiler)
                .withBean(QueryListDeclaration.class, () -> () -> declared)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(QueryListRegistry.class);
                    assertThatThrownBy(() -> registry.check(registered)).isInstanceOf(RlsAccessDeniedException.class);
                    admin.set(true);
                    var check = registry.check(registered);
                    assertThat(check.ok()).isTrue();
                    assertThat(check.entities()).containsExactly("Item", "Warehouse");
                    assertThat(check.columns()).containsExactly("code");
                    assertThat(check.rowKey()).containsExactly("Код");
                    assertThat(registry.administrativeEntries()).extracting(QueryListRegistry.Entry::key)
                            .containsExactly(declared.key());
                    var saved = registry.create(registered, true);
                    assertThat(saved.entities()).containsExactly("Item", "Warehouse");
                    assertThat(saved.declared()).isFalse();
                    assertThat(registry.administrativeEntries()).filteredOn(QueryListRegistry.Entry::declared)
                            .extracting(QueryListRegistry.Entry::key).containsExactly(declared.key());
                    when(compiler.inspect(QUERY)).thenThrow(new IllegalArgumentException("Поле «Склад» удалено"));
                    assertThat(registry.check(registered).diagnostic()).contains("Склад");
                    assertThat(registry.check(registered).entities()).isEmpty();
                    registry.recheck();
                    assertThat(registry.checkedAt()).isNotNull();
                    var broken = registry.administrativeEntries().stream()
                            .filter(entry -> entry.key().equals(registered.key())).findFirst().orElseThrow();
                    assertThat(broken.diagnostic()).contains("Склад");
                    assertThatThrownBy(() -> registry.update(registered, broken.version(), false))
                            .hasMessageContaining("Склад");
                    var off = registry.setEnabled(registered.key(), broken.version(), false);
                    assertThat(off.enabled()).isFalse();
                    assertThat(off.diagnostic()).contains("Склад");
                    assertThatThrownBy(() -> registry.setEnabled(registered.key(), broken.version(), true))
                            .isInstanceOf(OptimisticLockingFailureException.class);
                    admin.set(false);
                    assertThatThrownBy(() -> registry.setEnabled(registered.key(), off.version(), true))
                            .isInstanceOf(RlsAccessDeniedException.class);
                });
    }

    @Test
    void writingOneListDoesNotRecheckTheOthersAndOpeningTakesNoRegistryLock() {
        var compiler = compiler();
        var first = definition("stock.first", QueryProvenance.REGISTERED);
        var second = definition("stock.second", QueryProvenance.REGISTERED);
        database("jdbc:h2:mem:fq6_" + UUID.randomUUID(), "create-drop", new AtomicBoolean(true), compiler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(QueryListRegistry.class);
                    registry.create(first, true);
                    registry.create(second, true);
                    int checks = org.mockito.Mockito.mockingDetails(compiler).getInvocations().size();

                    assertThat(registry.administrativeEntries()).hasSize(2);
                    assertThat(registry.findOpenable(first.key())).isPresent();

                    assertThat(org.mockito.Mockito.mockingDetails(compiler).getInvocations().stream()
                            .filter(call -> call.getMethod().getName().equals("inspect")).count())
                            .isEqualTo(4);
                    assertThat(org.mockito.Mockito.mockingDetails(compiler).getInvocations().size() - checks)
                            .isEqualTo(1); // только requireReadAccess при открытии

                    // Пока другой поток держит замок записи, открытие списка не ждёт.
                    var lock = new java.util.concurrent.CountDownLatch(1);
                    var release = new java.util.concurrent.CountDownLatch(1);
                    var holder = new Thread(() -> {
                        synchronized (ReflectionTestUtils.getField(registry, "writeLock")) {
                            lock.countDown();
                            try { release.await(); } catch (InterruptedException ignored) { }
                        }
                    });
                    holder.start();
                    try {
                        lock.await();
                        assertThat(java.util.concurrent.CompletableFuture
                                .supplyAsync(() -> registry.findOpenable(first.key()))
                                .get(5, java.util.concurrent.TimeUnit.SECONDS)).isPresent();
                    } finally {
                        release.countDown();
                        holder.join();
                    }
                });
    }

    private static QueryProjectionCompiler compiler() {
        var compiler = mock(QueryProjectionCompiler.class);
        when(compiler.inspect(any())).thenReturn(new QueryProjectionCompiler.Inspection(
                List.of(new org.ipro.filtergrid.projection.ColumnMeta("code", "Код",
                        org.ipro.filtergrid.projection.DataType.STRING, true, true, true, null, false)),
                List.of("Item", "Warehouse"), List.of("code")));
        return compiler;
    }

    private static ApplicationContextRunner database(String url, String ddl, AtomicBoolean admin,
                                                     QueryProjectionCompiler compiler) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                        QueryListAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + url, "spring.jpa.hibernate.ddl-auto=" + ddl)
                .withBean(QueryListAdminAccess.class, () -> admin::get)
                .withBean(RlsCurrentUser.class, () -> () -> "admin")
                .withBean(QueryProjectionCompiler.class, () -> compiler);
    }

    private static QueryListDefinition definition(String key, QueryProvenance provenance) {
        var view = new GridViewState(List.of(new ColumnPath.Spec("code", "Код")), List.of(), null,
                FilterConditionNode.of(new FilterCondition("code", FilterOperator.EQ, "A", null, FilterDataType.TEXT)),
                Map.of("query.group", "code"));
        return new QueryListDefinition(key, "Номенклатура", QUERY, view, ProjectionExecutionMode.LAZY,
                null, Set.of(QueryListDefinition.Command.CREATE), provenance);
    }
}
