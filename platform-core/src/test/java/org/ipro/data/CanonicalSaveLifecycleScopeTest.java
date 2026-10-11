package org.ipro.data;

import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntitySaveContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CanonicalSaveLifecycleScopeTest {
    @Test
    void onlyTheIssuedEntityAndEventContextHaveACallbackToken() {
        IdentifiableEntity<?> entity = mock(IdentifiableEntity.class);
        EventContext event = EventContext.forEntity(entity.getClass(), 1L, EventSource.SYSTEM, "save:fixture");
        EntitySaveContext<?> context = new EntitySaveContext<>(entity, event);
        assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isNull();
        try (var scope = CanonicalSaveLifecycleScope.open(entity, event)) {
            assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isSameAs(scope);
            assertThat(CanonicalSaveLifecycleScope.tokenFor(new EntitySaveContext<>(entity,
                event.withAggregateId(1L)))).isNull();
            assertThat(CanonicalSaveLifecycleScope.tokenFor(new EntitySaveContext<>(
                mock(IdentifiableEntity.class), event))).isNull();
        }
        assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isNull();
    }

    @Test
    void nestedCallbacksRestoreTheOuterTokenAndIssueDistinctTokens() {
        IdentifiableEntity<?> entity = mock(IdentifiableEntity.class);
        EventContext event = EventContext.forEntity(entity.getClass(), 1L, EventSource.SYSTEM, "save:fixture");
        var context = new EntitySaveContext<>(entity, event);
        try (var outer = CanonicalSaveLifecycleScope.open(entity, event)) {
            try (var inner = CanonicalSaveLifecycleScope.open(entity, event)) {
                assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isSameAs(inner).isNotSameAs(outer);
                assertThatThrownBy(outer::close).hasMessageContaining("close in order");
            }
            assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isSameAs(outer);
        }
        assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isNull();
    }

    @Test
    void failureDoesNotLeaveCallbackAuthorityOnTheThread() {
        IdentifiableEntity<?> entity = mock(IdentifiableEntity.class);
        EventContext event = EventContext.forEntity(entity.getClass(), 1L, EventSource.SYSTEM, "save:fixture");
        var context = new EntitySaveContext<>(entity, event);
        assertThatThrownBy(() -> {
            try (var ignored = CanonicalSaveLifecycleScope.open(entity, event)) {
                throw new IllegalStateException("callback failed");
            }
        }).hasMessage("callback failed");
        assertThat(CanonicalSaveLifecycleScope.tokenFor(context)).isNull();
    }
}
