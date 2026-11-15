package org.ipro.utr;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.projection.ColumnMeta;
import org.ipro.filtergrid.projection.DataType;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;
import org.ipro.query.QueryProjectionCompiler;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QuerySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UtrFileStoreTest {
    private static final QueryDefinition QUERY = new QueryDefinition("""
            select r.workshop as workshop, sum(r.quantity) as quantity
            from Receipt r where r.date between :periodFrom and :periodTo group by r.workshop
            """, QuerySource.MANUAL, null, null, List.of(
                    QueryParameter.required("period").withKind(org.ipro.query.model.QueryParameterKind.RANGE)));
    private static final FilterConditionNode WORKSHOP = condition("workshop", "Цех №1");
    private static final GridViewState LAYOUT = new GridViewState(
            List.of(new ColumnPath.Spec("workshop", "Цех", "200px", null, null),
                    new ColumnPath.Spec("quantity", "Количество")), List.of(), null, WORKSHOP,
            Map.of("query.group", "workshop", "query.groupAgg", "quantity:SUM", "query.sort", "quantity:DESC",
                    "query.totals", "quantity:SUM"));

    @TempDir
    Path directory;
    private final QueryProjectionCompiler compiler = mock(QueryProjectionCompiler.class);
    private UtrFileStore store;

    @BeforeEach
    void setUp() {
        login("smirnova");
        store = new UtrFileStore(directory, () -> SecurityContextHolder.getContext().getAuthentication().getName(), compiler);
    }

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesMonthlyReportBeforeFirstRunAndRestoresLayout() {
        var document = report("Поступление по номенклатуре за месяц");
        var saved = store.create(document, "Приёмки за период", UtrVisibility.AUTHOR);
        verifyNoInteractions(compiler);
        describe("workshop", "quantity");

        var opened = store.open(saved.entry().id());
        assertThat(opened.saved()).isEqualTo(saved);
        assertThat(opened.needsRepair()).isFalse();
        assertThat(opened.saved().document().query().parameter("period").required()).isTrue();
        assertThat(opened.saved().document().query().parameter("period").defaultValue()).isNull();
        assertThat(opened.saved().document().layout().userFilter()).isEqualTo(WORKSHOP);
        assertThat(opened.saved().document().layout().options()).containsEntry("query.group", "workshop");
        assertThat(opened.saved().document().mode()).isEqualTo(ProjectionExecutionMode.LAZY);
    }

    @Test
    void privateReportHasTheSameNotFoundResponseOnEveryIdOperation() {
        var saved = store.create(report("Личный"), "", UtrVisibility.AUTHOR);
        long id = saved.entry().id();
        login("viewer");

        assertThat(store.list(null)).isEmpty();
        assertCode(() -> store.find(id), UtrStoreException.Code.NOT_FOUND);
        assertCode(() -> store.open(id), UtrStoreException.Code.NOT_FOUND);
        assertCode(() -> store.copy(id, "Копия"), UtrStoreException.Code.NOT_FOUND);
        assertCode(() -> store.copy(id, report("Черновик"), ""), UtrStoreException.Code.NOT_FOUND);
        assertCode(() -> store.exportJson(id), UtrStoreException.Code.NOT_FOUND);
        assertCode(() -> store.update(id, saved.revision(), report("Подмена"), "", UtrVisibility.ALL),
                UtrStoreException.Code.NOT_FOUND);
        verifyNoInteractions(compiler);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> store.list(null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sharedReportCanBeOpenedCopiedAndExportedAndImportReplacesOwnership() {
        var original = store.create(report("Общий"), "Склад", UtrVisibility.ALL);
        long id = original.entry().id();
        describe("workshop", "quantity");
        login("viewer");

        assertThat(store.list("склад")).containsExactly(original.entry());
        assertThat(store.find(id)).isEqualTo(original.entry());
        assertThat(store.open(id).saved()).isEqualTo(original);
        assertCode(() -> store.update(id, original.revision(), report("Подмена"), "", UtrVisibility.ALL),
                UtrStoreException.Code.FORBIDDEN);
        var copy = store.copy(id, "Моя копия");
        var imported = store.importJson(store.exportJson(id), null);
        assertThat(copy.entry().id()).isNotEqualTo(id);
        assertThat(imported.entry().id()).isNotIn(id, copy.entry().id());
        assertThat(imported.document()).isEqualTo(original.document());
        for (var saved : List.of(copy, imported)) {
            assertThat(saved.entry().author()).isEqualTo("viewer");
            assertThat(saved.entry().visibility()).isEqualTo(UtrVisibility.AUTHOR);
        }
    }

    @Test
    void titleUniquenessUsesVisibilityAndAuthorUnderTheWriteLock() {
        var personal = store.create(report("Остатки"), "", UtrVisibility.AUTHOR);
        store.create(report("остатки "), "", UtrVisibility.ALL);
        assertCode(() -> store.create(report(" остатки"), "", UtrVisibility.AUTHOR), UtrStoreException.Code.DUPLICATE_TITLE);
        assertCode(() -> store.update(personal.entry().id(), personal.revision(), personal.document(), "", UtrVisibility.ALL),
                UtrStoreException.Code.DUPLICATE_TITLE);
        login("viewer");
        store.create(report("остатки"), "", UtrVisibility.AUTHOR);
        assertCode(() -> store.create(report("ОСТАТКИ"), "", UtrVisibility.ALL), UtrStoreException.Code.DUPLICATE_TITLE);
    }

    @Test
    void staleRevisionCannotOverwriteAnotherWindow() {
        var original = store.create(report("Исходный"), "", UtrVisibility.AUTHOR);
        long id = original.entry().id();
        var changed = store.update(id, original.revision(), report("Сохранённый"), "", UtrVisibility.AUTHOR);
        assertThat(changed.revision()).isNotEqualTo(original.revision());
        assertCode(() -> store.update(id, original.revision(), report("Устаревший"), "", UtrVisibility.AUTHOR),
                UtrStoreException.Code.CONFLICT);
        assertThat(store.find(id).title()).isEqualTo("Сохранённый");
        var copy = store.copy(id, report("Устаревший"), "");
        assertThat(copy.entry().title()).isEqualTo("Устаревший");
    }

    @Test
    void concurrentStoresCannotCreateTwoSharedReportsWithTheSameTitle() throws Exception {
        var otherStore = new UtrFileStore(directory,
                () -> SecurityContextHolder.getContext().getAuthentication().getName(), compiler);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> createShared(store, start));
            var second = workers.submit(() -> createShared(otherStore, start));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("created", "DUPLICATE_TITLE");
            assertThat(store.list(null)).hasSize(1);
        }
    }

    private static String createShared(UtrFileStore store, CountDownLatch start) throws InterruptedException {
        login("author");
        try {
            start.await();
            store.create(report("Общий"), "", UtrVisibility.ALL);
            return "created";
        } catch (UtrStoreException error) {
            return error.code().name();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void absentAliasesArePrunedThroughoutTheLayoutAndOnlyAuthorSeesTheWarning() {
        var filter = FilterGroup.or(WORKSHOP, condition("missing", "x"));
        var layout = new GridViewState(List.of(new ColumnPath.Spec("missing", "Старый склад")),
                List.of(), null, filter, Map.of("query.group", "missing,workshop", "query.sort", "missing:DESC",
                        "query.hidden", "missing", "query.groupAgg", "missing:SUM", "query.totals", "missing:COUNT"));
        long id = store.create(report("Отчёт").withLayout(layout), "", UtrVisibility.ALL).entry().id();
        describe("workshop", "quantity");
        var author = store.open(id);
        assertThat(author.missingColumns()).containsExactly("missing");
        var repaired = author.saved().document().layout();
        assertThat(repaired.columns()).isEmpty();
        assertThat(repaired.options()).containsExactlyEntriesOf(Map.of("query.group", "workshop"));
        assertThat(repaired.userFilter()).isEqualTo(FilterGroup.or(WORKSHOP));
        login("viewer");
        assertThat(store.open(id).saved().document().layout()).isEqualTo(repaired);
        assertThat(store.open(id).missingColumns()).isEmpty();
        login("smirnova");
        store.update(id, author.saved().revision(), author.saved().document(), "", UtrVisibility.ALL);
        assertThat(store.open(id).missingColumns()).isEmpty();
    }

    @Test
    void brokenQueryReturnsRepairStateWithDetailsForAuthorOnly() {
        long id = store.create(report("Отчёт"), "", UtrVisibility.ALL).entry().id();
        when(compiler.describe(QUERY)).thenThrow(new IllegalArgumentException("Поле Склад удалено"));
        assertThat(store.open(id).needsRepair()).isTrue();
        assertThat(store.open(id).diagnostic()).isEqualTo("Поле Склад удалено");
        login("viewer");
        assertThat(store.open(id).needsRepair()).isTrue();
        assertThat(store.open(id).diagnostic()).isNull();
    }

    @Test
    void rejectsNonStrictFilesAndRuntimeFields() {
        long id = store.create(report("Отчёт"), "", UtrVisibility.AUTHOR).entry().id();
        String json = store.exportJson(id);
        for (String invalid : List.of(json + " {}", json.replace("\"fileVersion\" : 1", "\"fileVersion\" : 2"),
                json.replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 2"),
                json.replace("\"schemaVersion\"", "\"publication\" : {}, \"schemaVersion\""),
                json.replace("\"schemaVersion\"", "\"runtimeParameters\" : {}, \"schemaVersion\""),
                json.replace("\"id\"", "\"id\" : 1, \"id\""),
                json.replace("\"AUTHOR\"", "\"UNKNOWN\""),
                json.replace("\"userFilter\"", "\"fixedFilter\""))) {
            assertCode(() -> store.importJson(invalid, "Копия"), UtrStoreException.Code.INVALID_FILE);
        }
    }

    @Test
    void restoresVisualQueryAndDeclaredDefaults() {
        var query = new QueryDefinition("", QuerySource.VISUAL, "{\"version\":1}", null,
                List.of(new QueryParameter("limit").withDefaultValue("30")));
        var document = new UtrDocument("Конструктор", query, LAYOUT, ProjectionExecutionMode.IN_MEMORY);
        long id = store.create(document, "", UtrVisibility.AUTHOR).entry().id();
        when(compiler.describe(query)).thenReturn(List.of(ColumnMeta.of("workshop", "Цех", DataType.STRING),
                ColumnMeta.of("quantity", "Количество", DataType.NUMBER)));
        assertThat(store.open(id).saved().document()).isEqualTo(document);
    }

    private void describe(String... aliases) {
        when(compiler.describe(QUERY)).thenReturn(java.util.Arrays.stream(aliases)
                .map(alias -> ColumnMeta.of(alias, alias, DataType.STRING)).toList());
    }

    private static FilterConditionNode condition(String path, String value) {
        return FilterConditionNode.of(new FilterCondition(path, FilterOperator.EQ, value, null, FilterDataType.TEXT));
    }

    private static UtrDocument report(String title) {
        return new UtrDocument(title, QUERY, LAYOUT, null);
    }

    private static void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
    }

    private static void assertCode(Runnable action, UtrStoreException.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(UtrStoreException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
