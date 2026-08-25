package org.ipro.reportstudio.param;

import org.ipro.crud.BaseEntity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportContextFactoryTest {

    private final ReportContextFactory factory = new ReportContextFactory(
            () -> "test-user", Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneId.of("UTC")));

    @Test
    void forEntityCarriesOnlyClassAndIdentifier() {
        TestEntity entity = entity(17L);

        ReportContext context = factory.forEntity(entity, "journal-form");

        assertEquals(TestEntity.class, context.entityClass());
        assertEquals(17L, context.entityId());
        assertEquals(List.of(17L), context.selectedIds());
        assertEquals("journal-form", context.viewId());
    }

    @Test
    void forSelectionUsesExplicitCurrentAndSelectedIdentifiers() {
        ReportContext context = factory.forSelection(
                TestEntity.class, 8L, List.of(8L, 11L), "journal-list");

        assertEquals(TestEntity.class, context.entityClass());
        assertEquals(8L, context.entityId());
        assertEquals(List.of(8L, 11L), context.selectedIds());
        assertEquals("journal-list", context.viewId());
    }

    @Test
    void forEntitiesUsesFirstEntityAsCurrentAndAllPersistedIdentifiersAsSelection() {
        ReportContext context = factory.forEntities(
                List.of(entity(3L), entity(5L)), "receiving-document-form");

        assertEquals(TestEntity.class, context.entityClass());
        assertEquals(3L, context.entityId());
        assertEquals(List.of(3L, 5L), context.selectedIds());
    }

    @Test
    void unsavedOrAbsentEntityCreatesSafeEmptySelection() {
        ReportContext unsaved = factory.forEntity(new TestEntity(), "new-journal");
        ReportContext absent = factory.forEntities(List.of(), "journal-list");

        assertEquals(TestEntity.class, unsaved.entityClass());
        assertNull(unsaved.entityId());
        assertEquals(List.of(), unsaved.selectedIds());
        assertNull(absent.entityClass());
        assertNull(absent.entityId());
        assertEquals(List.of(), absent.selectedIds());
    }

    private static TestEntity entity(long id) {
        TestEntity entity = new TestEntity();
        entity.setId(id);
        return entity;
    }

    private static final class TestEntity extends BaseEntity {
    }
}
