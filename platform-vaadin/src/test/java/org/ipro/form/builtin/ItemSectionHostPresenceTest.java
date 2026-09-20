package org.ipro.form.builtin;

import org.ipro.crud.BaseEntity;
import org.ipro.form.SectionPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemSectionHostPresenceTest {

    @Test
    void absentSectionIsNotReportedAndProducesAbsentPayload() {
        ItemSectionHost<TestDocument> host = new ItemSectionHost<>(() -> null);
        ItemTable<TestRow, TestDocument> attachedTable = tableFor(TestRow.class, List.of());
        host.addTableSection("Строки", attachedTable);

        assertThat(host.hasAttachedSection(TestRow.class)).isTrue();
        assertThat(host.hasAttachedSection(OtherRow.class)).isFalse();
        assertThat(host.attachedSectionClasses()).containsExactly(TestRow.class);
        assertThat(host.sectionPayload(OtherRow.class)).isEqualTo(SectionPayload.absent());
    }

    @Test
    void attachedEmptySectionProducesAttachedEmptyPayload() {
        ItemSectionHost<TestDocument> host = new ItemSectionHost<>(() -> null);
        host.addTableSection("Строки", tableFor(TestRow.class, List.of()));

        SectionPayload<TestRow> payload = host.sectionPayload(TestRow.class);

        assertThat(payload.isAttached()).isTrue();
        assertThat(payload.rows()).isEmpty();
    }

    @Test
    void readOnlyPolicyAppliesOnlyToAttachedSection() {
        ItemSectionHost<TestDocument> host = new ItemSectionHost<>(() -> null);
        ItemTable<TestRow, TestDocument> editable = tableFor(TestRow.class, List.of());
        ItemTable<OtherRow, TestDocument> readOnly = tableFor(OtherRow.class, List.of());
        host.setReadOnlySections(Set.of(OtherRow.class));

        host.addTableSection("Материалы", editable);
        host.addTableSection("Операции", readOnly);

        verify(readOnly).setReadOnly(true);
        // read-only политика не трогает подключённую, но не read-only секцию
        verify(editable, never()).setReadOnly(anyBoolean());
        assertThat(host.isSectionReadOnly(OtherRow.class)).isTrue();
        assertThat(host.isSectionReadOnly(TestRow.class)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <R extends org.ipro.identity.IdentifiableEntity> ItemTable<R, TestDocument> tableFor(
            Class<R> rowClass, List<R> rows) {
        ItemTable<R, TestDocument> table = mock(ItemTable.class);
        when(table.getElement()).thenReturn(new com.vaadin.flow.dom.Element("div"));
        when(table.getRowClass()).thenReturn(rowClass);
        when(table.getRows()).thenReturn(rows);
        return table;
    }

    static class TestDocument extends BaseEntity {
    }

    static class TestRow extends BaseEntity {
    }

    static class OtherRow extends BaseEntity {
    }
}
