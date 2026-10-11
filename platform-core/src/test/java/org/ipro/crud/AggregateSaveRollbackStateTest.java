package org.ipro.crud;

import jakarta.persistence.Column;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import org.ipro.events.AggregateSection;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AggregateSaveRollbackStateTest {
    static class GenericIdentity<ID> implements IdentifiableEntity<ID> {
        private ID id;
        public ID getId() { return id; }
        public void setId(ID id) { this.id = id; }
    }

    static class LongRow extends GenericIdentity<Long> {
        @Version Long version;
        @Column(updatable = false) String created;
        @OneToMany List<LongRow> children = new ArrayList<>();
        LongRow parent;
        Integer line;
        String business;
    }

    static class StringRow extends GenericIdentity<String> { }

    @Test
    void checksDeclaredIdentityEvenWhenIdIsNull() {
        LongRow row = new LongRow();
        assertThat(AggregateSaveRollbackState.requireLongIdentity(row)).isSameAs(row);
        row.setId(7L);
        assertThat(AggregateSaveRollbackState.requireLongIdentity(row).getId()).isEqualTo(7L);
        assertThatThrownBy(() -> AggregateSaveRollbackState.requireLongIdentity(new StringRow()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IdentifiableEntity<Long>");
        assertThatThrownBy(() -> AggregateSaveRollbackState.requireLongIdentity(new GenericIdentity<Long>()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contributionRejectsAnIncompatibleTransientIdentity() {
        LongRow row = new LongRow();
        AggregateSaveContribution valid = contribution(List.of("payload", row));
        assertThat(valid.rollbackEntities()).hasSize(1);
        assertThat(valid.rollbackEntities().getFirst()).isSameAs(row);
        assertThatThrownBy(() -> contribution(List.of(new StringRow())).rollbackEntities())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IdentifiableEntity<Long>");
    }

    @Test
    void rollbackRestoresEarliestTechnicalStateAndKeepsBusinessEdits() throws Exception {
        LongRow row = new LongRow();
        LongRow originalParent = new LongRow();
        row.parent = originalParent;
        row.line = 12;
        row.created = "original";
        List<LongRow> originalChildren = row.children;
        TableSectionMetadataInfo descriptor = mock(TableSectionMetadataInfo.class);
        when(descriptor.getParentField()).thenReturn(LongRow.class.getDeclaredField("parent"));
        when(descriptor.hasLineNumberField()).thenReturn(true);
        when(descriptor.getLineNumberField()).thenReturn(LongRow.class.getDeclaredField("line"));
        AggregateSaveRollbackState state = new AggregateSaveRollbackState();
        state.capture(row, descriptor);
        row.setId(41L);
        row.version = 3L;
        row.created = "generated";
        row.children = new ArrayList<>();
        row.parent = new LongRow();
        row.line = 1;
        row.business = "user edit";
        state.capture(row, descriptor);
        state.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(row.getId()).isNull();
        assertThat(row.version).isNull();
        assertThat(row.created).isEqualTo("original");
        assertThat(row.children).isSameAs(originalChildren);
        assertThat(row.parent).isSameAs(originalParent);
        assertThat(row.line).isEqualTo(12);
        assertThat(row.business).isEqualTo("user edit");
    }

    @Test
    void commitAndUnknownCompletionKeepGeneratedIdentity() {
        for (int status : List.of(TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_UNKNOWN)) {
            LongRow row = new LongRow();
            AggregateSaveRollbackState state = new AggregateSaveRollbackState();
            state.capture(row, null);
            row.setId(8L);
            state.afterCompletion(status);
            assertThat(row.getId()).isEqualTo(8L);
        }
    }

    @Test
    void transactionUsesOneSnapshotForRepeatedSaves() {
        assertThatThrownBy(AggregateSaveRollbackState::current).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(AggregateSaveRollbackState.current()).isSameAs(AggregateSaveRollbackState.current());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static AggregateSaveContribution contribution(List<?> rows) {
        return new AggregateSaveContribution() {
            public String key() { return "test"; }
            public List<AggregateSection> eventSections() {
                return List.of(new AggregateSection(Object.class, rows));
            }
        };
    }
}
