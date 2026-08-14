package org.ipro.reportstudio.param;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.MappingMetamodelImplementor;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Резолвинг параметров (Фаза 3) на моках: источники значения
 * (FORM/DEFAULT/COMPUTED/CONTEXT), PERIOD → два бинд-имени, сущностные
 * параметры (перезапрос делегируется EntityParamRefresher'у, здесь только
 * прохождение id и жёсткие ошибки), required, ошибки конфигурации.
 * Поведение самого перезапроса под RLS — ReportParamResolutionIT.
 */
class ReportParamResolverTest {

    private static final String USER = "alice";
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    private EntityParamRefresher refresher;
    private AccessService accessService;
    private SessionFactoryImplementor sessionFactory;
    private ReportParamResolver resolver;

    @BeforeEach
    void setUp() {
        refresher = mock(EntityParamRefresher.class);
        accessService = mock(AccessService.class);
        RlsCurrentUser currentUser = () -> USER;
        sessionFactory = mock(SessionFactoryImplementor.class);
        MappingMetamodelImplementor metamodel = mock(MappingMetamodelImplementor.class);
        when(sessionFactory.getMappingMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntityDescriptor(JournalLike.class))
            .thenReturn(mock(org.hibernate.persister.entity.EntityPersister.class));
        resolver = new ReportParamResolver(refresher, accessService, currentUser,
            sessionFactory, new ObjectMapper(), "BRANCH");
    }

    // === FORM ===

    @Test
    void scalarFromFormIsBound() {
        ReportParam p = scalar("code", ReportParamSource.FORM, true);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("code", "B"));
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).containsEntry("code", "B");
    }

    @Test
    void requiredScalarMissingFromFormFails() {
        ReportParam p = scalar("code", ReportParamSource.FORM, true);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains(":code"));
    }

    @Test
    void optionalScalarMissingIsNotBound() {
        ReportParam p = scalar("code", ReportParamSource.FORM, false);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).doesNotContainKey("code");
    }

    // === DEFAULT (JSON-константа) ===

    @Test
    void defaultLongNumberIsBound() {
        ReportParam p = scalar("limit", ReportParamSource.DEFAULT, false);
        p.setDefaultValue("42");
        ResolvedParams resolved = resolver.resolve(List.of(p), ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).containsEntry("limit", 42L);
    }

    @Test
    void defaultStringAndBooleanAreDecoded() {
        ReportParam s = scalar("name", ReportParamSource.DEFAULT, false);
        s.setDefaultValue("\"Итого\"");
        ReportParam b = scalar("active", ReportParamSource.DEFAULT, false);
        b.setDefaultValue("true");
        ResolvedParams resolved = resolver.resolve(List.of(s, b), ReportContext.empty(USER), Map.of());
        assertThat(resolved.bindings())
            .containsEntry("name", "Итого")
            .containsEntry("active", true);
    }

    @Test
    void defaultPlainTextIsTreatedAsString() {
        ReportParam p = scalar("name", ReportParamSource.DEFAULT, false);
        p.setDefaultValue("Итого");
        ResolvedParams resolved = resolver.resolve(List.of(p), ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).containsEntry("name", "Итого");
    }

    @Test
    void invalidDefaultJsonIsHardError() {
        ReportParam p = scalar("limit", ReportParamSource.DEFAULT, false);
        p.setDefaultValue("{broken");
        ResolvedParams resolved = resolver.resolve(List.of(p), ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains("defaultValue"));
    }

    // === COMPUTED ===

    @Test
    void computedNowUsesFixedContextMoment() {
        ReportParam p = scalar("ts", ReportParamSource.COMPUTED, true);
        p.setComputed(ReportComputedValue.NOW);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER, NOW), Map.of());
        assertThat(resolved.bindings()).containsEntry("ts", NOW);
    }

    @Test
    void computedCurrentUserResolvesToContextUser() {
        ReportParam p = scalar("who", ReportParamSource.COMPUTED, true);
        p.setComputed(ReportComputedValue.CURRENT_USER);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER, NOW), Map.of());
        assertThat(resolved.bindings()).containsEntry("who", USER);
    }

    @Test
    void computedRlsOrgUsesSingleReadableId() {
        ReportParam p = scalar("org", ReportParamSource.COMPUTED, true);
        p.setComputed(ReportComputedValue.RLS_ORG);
        when(accessService.getReadableIds("BRANCH", USER)).thenReturn(List.of(7L));
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER, NOW), Map.of());
        assertThat(resolved.bindings()).containsEntry("org", 7L);
    }

    @Test
    void computedRlsOrgWithoutUniqueOrgFails() {
        ReportParam p = scalar("org", ReportParamSource.COMPUTED, true);
        p.setComputed(ReportComputedValue.RLS_ORG);
        when(accessService.getReadableIds("BRANCH", USER))
            .thenReturn(List.of(1L, 2L));
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER, NOW), Map.of());
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains(":org"));
    }

    @Test
    void computedNoneIsHardError() {
        ReportParam p = scalar("x", ReportParamSource.COMPUTED, false);
        p.setComputed(ReportComputedValue.NONE);
        ResolvedParams resolved = resolver.resolve(List.of(p), ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isFalse();
    }

    // === CONTEXT ===

    @Test
    void contextScalarIsRejected() {
        ReportParam p = scalar("x", ReportParamSource.CONTEXT, false);
        ResolvedParams resolved = resolver.resolve(List.of(p), ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains("CONTEXT"));
    }

    @Test
    void contextMatchingEntityIsBoundAfterRefresh() {
        ReportParam p = entity("journal", ReportParamKind.ENTITY, ReportParamSource.CONTEXT, true);
        Object journal = new Object();
        when(refresher.refresh(JournalLike.class, 5L)).thenReturn(journal);
        ReportContext context = ReportContext.of(JournalLike.class, 5L, List.of(), "doc",
            USER, NOW);
        ResolvedParams resolved = resolver.resolve(List.of(p), context, Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).containsEntry("journal", journal);
    }

    @Test
    void contextNonMatchingRequiredEntityFails() {
        ReportParam p = entity("journal", ReportParamKind.ENTITY, ReportParamSource.CONTEXT, true);
        ReportContext context = ReportContext.of(String.class, 1L, List.of(), "doc", USER, NOW);
        ResolvedParams resolved = resolver.resolve(List.of(p), context, Map.of());
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains(":journal"));
    }

    @Test
    void contextEntityListUsesSelectedIds() {
        ReportParam p = entity("journals", ReportParamKind.ENTITY_LIST, ReportParamSource.CONTEXT, true);
        Object journalA = new Object();
        when(refresher.refresh(JournalLike.class, 1L)).thenReturn(journalA);
        ReportContext context = ReportContext.of(JournalLike.class, 1L, List.of(1L), "grid",
            USER, NOW);
        ResolvedParams resolved = resolver.resolve(List.of(p), context, Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings().get("journals")).isEqualTo(List.of(journalA));
    }

    // === ENTITY (FORM) ===

    @Test
    void entityFromFormIsRefreshed() {
        ReportParam p = entity("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);
        Object journal = new Object();
        when(refresher.refresh(JournalLike.class, 11L)).thenReturn(journal);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("journal", 11L));
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings()).containsEntry("journal", journal);
    }

    @Test
    void entityNotFoundOrUnavailableIsHardError() {
        ReportParam p = entity("journal", ReportParamKind.ENTITY, ReportParamSource.FORM, true);
        when(refresher.refresh(JournalLike.class, 11L)).thenReturn(null);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("journal", 11L));
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors())
            .anyMatch(e -> e.contains(":journal") && e.contains("id=11"));
    }

    @Test
    void entityListFromFormRefusesUnknownElement() {
        ReportParam p = entity("journals", ReportParamKind.ENTITY_LIST, ReportParamSource.FORM, true);
        Object journalA = new Object();
        when(refresher.refresh(JournalLike.class, 1L)).thenReturn(journalA);
        when(refresher.refresh(JournalLike.class, 2L)).thenReturn(null);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("journals", List.of(1L, 2L)));
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors())
            .anyMatch(e -> e.contains(":journals") && e.contains("id=2"));
    }

    @Test
    void entityWithUnknownClassFails() {
        ReportParam p = entity("x", ReportParamKind.ENTITY, ReportParamSource.FORM, true);
        p.setEntityClass("no.such.Class");
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("x", 1L));
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains("не найден"));
    }

    @Test
    void entityWithNonEntityClassFails() {
        ReportParam p = entity("x", ReportParamKind.ENTITY, ReportParamSource.FORM, true);
        p.setEntityClass(String.class.getName());
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("x", 1L));
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains("не является JPA-сущностью"));
    }

    // === PERIOD ===

    @Test
    void periodFromFormBindsTwoNames() {
        ReportParam p = period("created", ReportParamSource.FORM, true);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of("createdFrom", "2026-01-01", "createdTo", "2026-02-01"));
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings())
            .containsEntry("createdFrom", "2026-01-01")
            .containsEntry("createdTo", "2026-02-01");
    }

    @Test
    void periodRequiredButEmptyFails() {
        ReportParam p = period("created", ReportParamSource.FORM, true);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.errors()).anyMatch(e -> e.contains(":created"));
    }

    @Test
    void periodFromDefaultJsonArray() {
        ReportParam p = period("created", ReportParamSource.DEFAULT, false);
        p.setDefaultValue("[\"2026-01-01\", \"2026-02-01\"]");
        ResolvedParams resolved = resolver.resolve(List.of(p), ReportContext.empty(USER), Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings())
            .containsEntry("createdFrom", "2026-01-01")
            .containsEntry("createdTo", "2026-02-01");
    }

    @Test
    void periodComputedNowBindsFixedMomentTwice() {
        ReportParam p = period("created", ReportParamSource.COMPUTED, true);
        p.setComputed(ReportComputedValue.NOW);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER, NOW), Map.of());
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.bindings())
            .containsEntry("createdFrom", NOW)
            .containsEntry("createdTo", NOW);
    }

    @Test
    void periodComputedNonNowIsRejected() {
        ReportParam p = period("created", ReportParamSource.COMPUTED, true);
        p.setComputed(ReportComputedValue.CURRENT_USER);
        ResolvedParams resolved = resolver.resolve(List.of(p),
            ReportContext.empty(USER, NOW), Map.of());
        assertThat(resolved.ok()).isFalse();
    }

    // === helpers ===

    private static ReportParam scalar(String name, ReportParamSource source, boolean required) {
        ReportParam p = base(name, ReportParamKind.SCALAR, source, required);
        return p;
    }

    private static ReportParam entity(String name, ReportParamKind kind,
                                      ReportParamSource source, boolean required) {
        ReportParam p = base(name, kind, source, required);
        p.setEntityClass(JournalLike.class.getName());
        return p;
    }

    private static ReportParam period(String name, ReportParamSource source, boolean required) {
        return base(name, ReportParamKind.PERIOD, source, required);
    }

    private static ReportParam base(String name, ReportParamKind kind,
                                    ReportParamSource source, boolean required) {
        ReportParam p = new ReportParam();
        p.setName(name);
        p.setKind(kind);
        p.setValueSource(source);
        p.setRequired(required);
        return p;
    }

    private static final class JournalLike {
    }
}