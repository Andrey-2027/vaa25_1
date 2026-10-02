package org.ipro.rest.service;

import jakarta.persistence.metamodel.Attribute;
import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rest.catalog.RestResourceProjection;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate 3B Acceptance Tests (F-REST-READ-3 В§9):
 * Verifies executable server pipeline, pilots 3.4a (un-RLS) & 3.4b (row RLS),
 * projection without OSIV, and content/count parity.
 */
class RestReadGate3BPipelineAcceptanceTest {

    public static class NomenclatureEntity {
        private String code;
        public NomenclatureEntity(String code) { this.code = code; }
        public String getCode() { return code; }
    }

    public static class JournalEntity {
        private Long id;
        public JournalEntity(Long id) { this.id = id; }
        public Long getId() { return id; }
    }

    public static class PrdSpecEntity {
        private Long id;
        private String code;
        private NomenclatureEntity nomenclature;
        private JournalEntity journal;

        public PrdSpecEntity(Long id, String code, NomenclatureEntity nomenclature, JournalEntity journal) {
            this.id = id;
            this.code = code;
            this.nomenclature = nomenclature;
            this.journal = journal;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public NomenclatureEntity getNomenclature() { return nomenclature; }
        public JournalEntity getJournal() { return journal; }
    }

    private RestResourceCatalog catalog;
    private EntityReadAccess readAccess;
    private C5PermissionEvaluator c5Evaluator;
    private RlsCurrentUser currentUser;
    private RestReferenceAuthorizationValidator referenceValidator;
    private RestReadService service;
    private ResolvedRestResource specResource;

    private ResolvedRestPath idPath;
    private ResolvedRestPath codePath;
    private ResolvedRestPath nomCodePath;
    private ResolvedRestPath journalIdPath;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catalog = mock(RestResourceCatalog.class);
        readAccess = mock(EntityReadAccess.class);
        c5Evaluator = mock(C5PermissionEvaluator.class);
        currentUser = mock(RlsCurrentUser.class);
        referenceValidator = mock(RestReferenceAuthorizationValidator.class);

        when(currentUser.username()).thenReturn("pipeline_operator");

        specResource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        Mockito.doReturn(PrdSpecEntity.class).when(definition).resourceType();
        when(definition.maxPageSize()).thenReturn(100);
        when(definition.defaultPageSize()).thenReturn(20);
        when(definition.defaultSort()).thenReturn(Optional.empty());
        when(specResource.definition()).thenReturn((RestResourceDefinition) definition);
        when(specResource.key()).thenReturn(new RestResourceKey("specifications", 1));

        idPath = mock(ResolvedRestPath.class);
        when(idPath.source()).thenReturn("id");
        when(idPath.segments()).thenReturn(List.of(new ResolvedRestPath.Segment(
            "id", PrdSpecEntity.class, Long.class, Attribute.PersistentAttributeType.BASIC, false, false)));
        ResolvedRestField idField = mock(ResolvedRestField.class);
        when(idField.source()).thenReturn(idPath);

        codePath = mock(ResolvedRestPath.class);
        when(codePath.source()).thenReturn("code");
        when(codePath.segments()).thenReturn(List.of(new ResolvedRestPath.Segment(
            "code", PrdSpecEntity.class, String.class, Attribute.PersistentAttributeType.BASIC, false, false)));
        ResolvedRestField codeField = mock(ResolvedRestField.class);
        when(codeField.source()).thenReturn(codePath);

        nomCodePath = mock(ResolvedRestPath.class);
        when(nomCodePath.source()).thenReturn("nomenclature.code");
        when(nomCodePath.segments()).thenReturn(List.of(
            new ResolvedRestPath.Segment("nomenclature", PrdSpecEntity.class, NomenclatureEntity.class, Attribute.PersistentAttributeType.MANY_TO_ONE, true, true),
            new ResolvedRestPath.Segment("code", NomenclatureEntity.class, String.class, Attribute.PersistentAttributeType.BASIC, false, false)
        ));
        ResolvedRestField nomField = mock(ResolvedRestField.class);
        when(nomField.source()).thenReturn(nomCodePath);

        journalIdPath = mock(ResolvedRestPath.class);
        when(journalIdPath.source()).thenReturn("journal.id");
        when(journalIdPath.segments()).thenReturn(List.of(
            new ResolvedRestPath.Segment("journal", PrdSpecEntity.class, JournalEntity.class, Attribute.PersistentAttributeType.MANY_TO_ONE, true, true),
            new ResolvedRestPath.Segment("id", JournalEntity.class, Long.class, Attribute.PersistentAttributeType.BASIC, false, false)
        ));
        ResolvedRestField journalField = mock(ResolvedRestField.class);
        when(journalField.source()).thenReturn(journalIdPath);

        when(specResource.fields()).thenReturn(Map.of(
            "id", idField,
            "code", codeField,
            "nomenclatureCode", nomField,
            "journalId", journalField
        ));
        when(specResource.sorts()).thenReturn(Map.of("id", idPath, "code", codePath));
        when(specResource.filters()).thenReturn(Map.of("journalId", journalIdPath));

        RestResourceProjection projection = new RestResourceProjection(
            RestReadOperation.LIST,
            List.of("id", "code", "nomenclatureCode", "journalId"),
            List.of("id", "code"),
            Map.of()
        );
        when(specResource.projection(RestReadOperation.LIST)).thenReturn(projection);
        when(specResource.fixedFetchPaths(RestReadOperation.LIST)).thenReturn(
            List.of("id", "code", "nomenclature.code", "journal.id"));

        when(catalog.find("specifications", 1)).thenReturn(Optional.of(specResource));

        // Default: permissions on root are granted
        when(c5Evaluator.isApiPermitted("pipeline_operator", "specifications", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("pipeline_operator", PrdSpecEntity.class)).thenReturn(true);
        when(c5Evaluator.isAttributeReadPermitted("pipeline_operator", PrdSpecEntity.class, "id")).thenReturn(true);
        when(c5Evaluator.isAttributeReadPermitted("pipeline_operator", PrdSpecEntity.class, "code")).thenReturn(true);
        when(c5Evaluator.isAttributeReadPermitted("pipeline_operator", PrdSpecEntity.class, "nomenclature")).thenReturn(true);
        when(c5Evaluator.isAttributeReadPermitted("pipeline_operator", PrdSpecEntity.class, "journal")).thenReturn(true);

        service = new RestReadService(catalog, readAccess, c5Evaluator, currentUser, referenceValidator);
    }

    @Test
    @DisplayName("Gate 3B.1 (Pilot 3.4a): Target without row RLS (PrdSpec -> Nomenclature) succeeds when permitted")
    @SuppressWarnings("unchecked")
    void pilot34a_unRlsTargetSucceedsWhenPermitted() {
        when(referenceValidator.authorizeReferencePath("pipeline_operator", PrdSpecEntity.class, nomCodePath))
            .thenReturn(true);

        PrdSpecEntity item = new PrdSpecEntity(10L, "SPEC-001", new NomenclatureEntity("NOM-42"), null);
        when(readAccess.list(any(), any(), any(), anyCollection()))
            .thenReturn(new PageImpl<>(List.of(item), Pageable.ofSize(10), 1));

        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id", "code", "nomenclatureCode"), Map.of(), null, null, 0, 10);

        RestPageResult result = service.list(query);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        Map<String, Object> row = result.content().getFirst();
        assertThat(row).containsEntry("id", 10L);
        assertThat(row).containsEntry("code", "SPEC-001");
        assertThat(row).containsEntry("nomenclatureCode", "NOM-42");
    }

    @Test
    @DisplayName("Gate 3B.2 (Pilot 3.4a): Target without row RLS rejected when reference validator denies")
    void pilot34a_unRlsTargetDeniedWhenValidatorRejects() {
        when(referenceValidator.authorizeReferencePath("pipeline_operator", PrdSpecEntity.class, nomCodePath))
            .thenReturn(false);

        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id", "code", "nomenclatureCode"), Map.of(), null, null, 0, 10);

        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("nomenclatureCode");
    }

    @Test
    @DisplayName("Gate 3B.3 (Pilot 3.4b): Target with row RLS (PrdSpec -> Journal) succeeds with common RLS dimension")
    @SuppressWarnings("unchecked")
    void pilot34b_rowRlsTargetSucceeds() {
        when(referenceValidator.authorizeReferencePath("pipeline_operator", PrdSpecEntity.class, journalIdPath))
            .thenReturn(true);

        PrdSpecEntity item = new PrdSpecEntity(11L, "SPEC-002", null, new JournalEntity(500L));
        when(readAccess.list(any(), any(), any(), anyCollection()))
            .thenReturn(new PageImpl<>(List.of(item), Pageable.ofSize(10), 1));

        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id", "journalId"), Map.of(), null, null, 0, 10);

        RestPageResult result = service.list(query);
        assertThat(result.totalElements()).isEqualTo(1);
        Map<String, Object> row = result.content().getFirst();
        assertThat(row).containsEntry("id", 11L);
        assertThat(row).containsEntry("journalId", 500L);
    }

    @Test
    @DisplayName("Gate 3B.4: Scalar projection produces pure detached Map without JPA proxies (no OSIV leak)")
    @SuppressWarnings("unchecked")
    void scalarProjectionWithoutOsiv() {
        PrdSpecEntity item = new PrdSpecEntity(12L, "SPEC-003", new NomenclatureEntity("NOM-99"), new JournalEntity(777L));
        when(readAccess.list(any(), any(), any(), anyCollection()))
            .thenReturn(new PageImpl<>(List.of(item), Pageable.ofSize(10), 1));
        when(referenceValidator.authorizeReferencePath(any(), any(), any())).thenReturn(true);

        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id", "code"), Map.of(), null, null, 0, 10);

        RestPageResult result = service.list(query);
        Map<String, Object> row = result.content().getFirst();

        // Must be unmodifiable Map
        assertThatThrownBy(() -> row.put("newField", 123))
            .isInstanceOf(UnsupportedOperationException.class);
        // Does not contain unrequested fields
        assertThat(row).doesNotContainKey("nomenclatureCode");
        assertThat(row).doesNotContainKey("journalId");
    }

    @Test
    @DisplayName("Gate 3B.5: Deterministic tie-break id ASC is appended to sort")
    @SuppressWarnings("unchecked")
    void deterministicTieBreakAppended() {
        when(readAccess.list(any(), any(), any(), anyCollection()))
            .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(10), 0));

        RestListQuery query = new RestListQuery("specifications", 1,
            List.of("id", "code"), Map.of(), "code", RestSortDirection.DESC, 0, 10);

        service.list(query);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(readAccess).list(any(), any(), pageableCaptor.capture(), anyCollection());

        Sort sort = pageableCaptor.getValue().getSort();
        assertThat(sort.getOrderFor("code")).isNotNull();
        assertThat(sort.getOrderFor("code").getDirection()).isEqualTo(Sort.Direction.DESC);
        // id ASC appended as tie-break
        assertThat(sort.getOrderFor("id")).isNotNull();
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}