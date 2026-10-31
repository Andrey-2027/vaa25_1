package org.ipro.unidr.query;
import org.ip.unidr.query.UniDrQueryAdapter;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.model.UniDrDefinitions;

import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.run.*;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.report.data.*;
import org.ipro.query.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrQueryServiceTest {
    private final UniDrAccess access = new UniDrAccess(() -> true, () -> "alice");
    private final UniDrQueryAdapter adapter = mock(UniDrQueryAdapter.class);
    private final UniDrReferenceService references = mock(UniDrReferenceService.class);
    private final ReportQueryExecutor executor = mock(ReportQueryExecutor.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UniDrRunRequest request = new UniDrRunRequest(UniDrDefinitions.receivingItemsSummary(),
        Map.of("unit", new Literal(ValueType.REFERENCE, List.of("1"))), ZoneOffset.UTC);
    private final Instant now = Instant.parse("2026-10-01T00:00:00Z");
    private final ReportQueryAssembler assembled = new ReportQueryAssembler("safe query", Map.of(), schema(), List.of());
    static Map<String,Class<?>> schemaMap(){ var result=new LinkedHashMap<String,Class<?>>();schema().forEach(field->result.put(field.name(),field.javaType()));return result; }
    private UniDrQueryService service() {
        return new UniDrQueryService(access, new UniDrRunParameterResolver(new UniDrDefinitionValidator(UniDrDefinitions.catalog())),
            references, adapter, executor, transactions, 6, 30000);
    }
    static List<QueryField> schema() {
        return Stream.of("rowId", "documentId", "documentNumber", "date", "receivingWorkshopId", "receivingWorkshopName",
            "transferringWorkshopId", "transferringWorkshopName", "nomenclatureId", "nomenclatureCode", "nomenclatureName", "unitId", "unit", "quantity")
            .map(id -> QueryField.scalar(id, id.endsWith("Id") ? Long.class : id.equals("quantity") ? BigDecimal.class : id.equals("date") ? LocalDate.class : String.class)).toList();
    }
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(adapter.expectedSchema()).thenReturn(schemaMap());
        when(adapter.assemble(any(), any(), any())).thenReturn(assembled);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void detectsOverflowWithExtraRowAndReturnsNoPartialData() {
        var rows = mock(ReportDataset.class); when(rows.rowCount()).thenReturn(7); when(executor.execute(assembled, 7, 30000)).thenReturn(rows);
        assertThatThrownBy(() -> service().read(request, now, "alice")).isInstanceOfSatisfying(UniDrRunException.class,
            error -> assertThat(error.code()).isEqualTo(UniDrRunException.Code.LIMIT_EXCEEDED));
        verify(transactions).rollback(any());
    }
    @Test void emptyResultHasMessageAndCommitsReadTransaction() {
        var empty = new ReportDataset(schema().toArray(QueryField[]::new), new ReportRow[0]);
        when(executor.execute(assembled, 7, 30000)).thenReturn(empty);
        assertThat(service().read(request, now, "alice").message()).isEqualTo("По выбранным условиям данных нет");
        verify(transactions).commit(any()); verify(references).requireVisible("unit", 1L);
    }
    static Stream<RuntimeException> timeouts() { return Stream.of(new jakarta.persistence.QueryTimeoutException("SQL secret"),
        new org.springframework.dao.QueryTimeoutException("SQL secret"), new org.springframework.transaction.TransactionTimedOutException("SQL secret"),
        new RuntimeException(new java.sql.SQLException("SQL secret", "57014")),
        new org.hibernate.QueryTimeoutException("SQL secret", new java.sql.SQLException(), "select secret")); }
    @ParameterizedTest @MethodSource("timeouts") void mapsTimeoutWithoutLeakingSql(RuntimeException failure) {
        when(executor.execute(assembled, 7, 30000)).thenThrow(failure);
        assertThatThrownBy(() -> service().read(request, now, "alice")).isInstanceOfSatisfying(UniDrRunException.class, error -> {
            assertThat(error.code()).isEqualTo(UniDrRunException.Code.TIMEOUT); assertThat(error.getMessage()).doesNotContain("secret");
        }); verify(transactions).rollback(any());
    }
    @Test void schemaChangeStopsBeforeSql() {
        when(adapter.assemble(any(), any(), any())).thenReturn(new ReportQueryAssembler("bad schema", Map.of(), List.of(QueryField.scalar("rowId", String.class)), List.of()));
        assertThatThrownBy(() -> service().read(request, now, "alice")).isInstanceOfSatisfying(UniDrRunException.class,
            error -> assertThat(error.code()).isEqualTo(UniDrRunException.Code.SCHEMA_MISMATCH)); verifyNoInteractions(executor);
    }
    @Test void c5DenialAndUnexpectedSqlFailureHaveSafeMessages() {
        when(executor.execute(assembled, 7, 30000)).thenThrow(new org.ipro.rls.c5.C5AccessDeniedException("internal secret"));
        assertThatThrownBy(() -> service().read(request, now, "alice")).isInstanceOfSatisfying(UniDrRunException.class,
            error -> assertThat(error.code()).isEqualTo(UniDrRunException.Code.ACCESS_DENIED)).hasMessageNotContaining("secret");
        doThrow(new IllegalStateException("select secret")).when(executor).execute(assembled, 7, 30000);
        assertThatThrownBy(() -> service().read(request, now, "alice")).isInstanceOfSatisfying(UniDrRunException.class,
            error -> assertThat(error.code()).isEqualTo(UniDrRunException.Code.QUERY_FAILED)).hasMessageNotContaining("secret");
    }
    @Test void invalidRunAndWrongOwnerFailBeforeTransactionOrSql() {
        var invalid = new UniDrRunRequest(request.definition(), Map.of(), ZoneOffset.UTC);
        assertThatThrownBy(() -> service().read(invalid, now, "alice")).isInstanceOf(UniDrValidationException.class);
        assertThatThrownBy(() -> service().read(request, now, "bob")).isInstanceOf(UniDrRunException.class);
        verifyNoInteractions(transactions, references, adapter, executor);
    }
}
