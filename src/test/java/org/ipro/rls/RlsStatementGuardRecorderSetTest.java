package org.ipro.rls;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RlsStatementGuardRecorderSetTest {
    private final RlsStatementGuard guard = guard();

    @Test
    void recorderSnapshotAllowsOneTableAndBothBoundRecorderPredicates() {
        assertThatCode(() -> RlsContext.callAsSystem(
            RlsBypassScope.RECORDER_SET_REPLACEMENT,
            "read exact recorder set before replacement",
            "test-user",
            Set.of("register_rows"),
            () -> { guard.audit("select r.id from register_rows r where r.recorder_type=? "
                + "and r.recorder_id=? order by r.line_no"); return null; }))
            .doesNotThrowAnyException();
    }

    @Test
    void recorderSnapshotRejectsAnotherTableOrAnIncompletePredicate() {
        assertThatThrownBy(() -> RlsContext.callAsSystem(
            RlsBypassScope.DEPENDENT_ROW_CLEANUP,
            "delete dependent rows before root deletion",
            "test-user",
            Set.of("register_rows"),
            () -> { guard.audit("select r.id from other_rows r where r.recorder_type=? "
                + "and r.recorder_id=?"); return null; }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("unapproved table");

        assertThatThrownBy(() -> RlsContext.callAsSystem(
            RlsBypassScope.RECORDER_SET_REPLACEMENT,
            "read exact recorder set before replacement",
            "test-user",
            Set.of("register_rows"),
            () -> { guard.audit("select r.id from register_rows r where r.recorder_type=?"); return null; }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("recorder_type and recorder_id");
    }

    @Test
    void recorderSnapshotRejectsWritesAndJoins() {
        assertThatThrownBy(() -> RlsContext.callAsSystem(
            RlsBypassScope.DEPENDENT_ROW_CLEANUP,
            "delete dependent rows before root deletion",
            "test-user",
            Set.of("register_rows"),
            () -> { guard.audit("delete from register_rows where recorder_type=? and recorder_id=?"); return null; }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("read-only");

        assertThatThrownBy(() -> RlsContext.callAsSystem(
            RlsBypassScope.RECORDER_SET_REPLACEMENT,
            "read exact recorder set before replacement",
            "test-user",
            Set.of("register_rows"),
            () -> { guard.audit("select r.id from register_rows r join other_rows o on o.id=r.id "
                + "where r.recorder_type=? and r.recorder_id=?"); return null; }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("one direct SELECT");
    }

    private static RlsStatementGuard guard() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.filterableDimensionsByTable()).thenReturn(Map.of());
        return new RlsStatementGuard(registry, true);
    }
}
