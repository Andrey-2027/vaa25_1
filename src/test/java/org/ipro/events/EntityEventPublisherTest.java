package org.ipro.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class EntityEventPublisherTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private EntityEventPublisher publisher;

    @AfterEach
    void cleanThreadState() {
        MDC.remove("traceId");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void contextUsesExistingTelemetryCorrelationAndKeepsAttachedSections() {
        MDC.put("traceId", "trace-42");

        EventContext context = EventContext.builder(TestDocument.class)
            .source(EventSource.UI)
            .formVariant("full")
            .attachSection(TestRow.class)
            .operationName("save:TestDocument")
            .build();

        assertThat(context.correlationId()).isEqualTo("trace-42");
        assertThat(context.source()).isEqualTo(EventSource.UI);
        assertThat(context.formVariant()).isEqualTo("full");
        assertThat(context.isSectionAttached(TestRow.class)).isTrue();
        assertThat(context.isSectionAttached(OtherRow.class)).isFalse();
    }

    @Test
    void beforeSaveEventIsSynchronousAndListenerFailureVetoesOperation() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        IllegalStateException veto = new IllegalStateException("blocked");
        doThrow(veto).when(applicationEventPublisher).publishEvent(any(EntitySavingEvent.class));

        assertThatThrownBy(() -> publisher.publishSaving(new TestDocument(), context()))
            .isSameAs(veto);
    }

    @Test
    void changedEventWaitsForCommit() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishChanged(new TestDocument(), context());

        verifyNoInteractions(applicationEventPublisher);
        TransactionSynchronization synchronization =
            TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();

        verify(applicationEventPublisher).publishEvent(any(EntityChangedEvent.class));
    }

    @Test
    void changedEventIsNotDeliveredAfterRollback() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishChanged(new TestDocument(), context());

        TransactionSynchronization synchronization =
            TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void lifecycleEventsKeepPreCommitOrderingAndDeferChangedUntilCommit() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        List<String> delivered = new ArrayList<>();
        org.mockito.stubbing.Answer<Void> record = invocation -> {
            delivered.add(invocation.getArgument(0).getClass().getSimpleName());
            return null;
        };
        org.mockito.Mockito.doAnswer(record).when(applicationEventPublisher)
            .publishEvent(any(EntitySavingEvent.class));
        org.mockito.Mockito.doAnswer(record).when(applicationEventPublisher)
            .publishEvent(any(EntitySavedEvent.class));
        org.mockito.Mockito.doAnswer(record).when(applicationEventPublisher)
            .publishEvent(any(EntityChangedEvent.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishSaving(new TestDocument(), context());
        publisher.publishSaved(new TestDocument(), context());
        publisher.publishChanged(new TestDocument(), context());

        assertThat(delivered).containsExactly("EntitySavingEvent", "EntitySavedEvent");
        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
        assertThat(delivered).containsExactly(
            "EntitySavingEvent", "EntitySavedEvent", "EntityChangedEvent");
    }

    @Test
    void changedEventWithoutTransactionFailsClosed() {
        publisher = new EntityEventPublisher(applicationEventPublisher);

        assertThatThrownBy(() -> publisher.publishChanged(new TestDocument(), context()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires an active transaction");

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void deletedEventWithoutTransactionFailsClosed() {
        publisher = new EntityEventPublisher(applicationEventPublisher);

        assertThatThrownBy(() -> publisher.publishDeleted(new TestDocument(), context()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires an active transaction");

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void afterCommitListenerFailureDoesNotEscapeAfterTransactionIsCommitted() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        doThrow(new IllegalStateException("external system is down"))
            .when(applicationEventPublisher).publishEvent(any(EntityChangedEvent.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishChanged(new TestDocument(), context());
        TransactionSynchronization synchronization =
            TransactionSynchronizationManager.getSynchronizations().get(0);

        synchronization.afterCommit();

        verify(applicationEventPublisher).publishEvent(any(EntityChangedEvent.class));
    }

    @Test
    void aggregateEventRequiresItsSectionsToBeMarkedAttached() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        EventContext context = EventContext.builder(TestDocument.class)
            .attachSection(TestRow.class)
            .operationName("save:TestDocument")
            .build();

        publisher.publishAggregateSaving(new TestDocument(),
            List.of(AggregateSection.attached(TestRow.class, List.of())), context);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(AggregateSavingEvent.class);
        AggregateSavingEvent<?> aggregateEvent = (AggregateSavingEvent<?>) event.getValue();
        assertThat(aggregateEvent.sections()).singleElement()
            .satisfies(section -> {
                assertThat(section.rowType()).isEqualTo(TestRow.class);
                assertThat(section.rows()).isEmpty();
            });
    }

    @Test
    void deletedEventWaitsForCommitAndFiresAfterSuccessfulCommit() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishDeleted(new TestDocument(), context());

        verifyNoInteractions(applicationEventPublisher);
        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

        verify(applicationEventPublisher).publishEvent(any(EntityDeletedEvent.class));
    }

    @Test
    void deletedEventIsNotDeliveredAfterRollback() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishDeleted(new TestDocument(), context());

        TransactionSynchronizationManager.getSynchronizations().get(0)
            .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void deletingEventIsSynchronousAndListenerFailureVetoesOperation() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        IllegalStateException veto = new IllegalStateException("blocked");
        doThrow(veto).when(applicationEventPublisher).publishEvent(any(EntityDeletingEvent.class));

        assertThatThrownBy(() -> publisher.publishDeleting(new TestDocument(), context()))
            .isSameAs(veto);
    }

    @Test
    void aggregateScopeDoesNotScheduleDuplicateAfterCommitEvents() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        EventContext context = EventContext.builder(TestDocument.class)
            .source(EventSource.UI)
            .operationName("save:TestDocument")
            .build();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try (EntityEventPublisher.EventScope scope =
                 publisher.openAggregateOperation(context)) {
            publisher.publishSaved(new TestDocument(), context);
            publisher.publishChanged(new TestDocument(), context);
        }

        // Saved — синхронно один раз; Changed — ровно одна after-commit синхронизация
        verify(applicationEventPublisher).publishEvent(any(EntitySavedEvent.class));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
        verify(applicationEventPublisher).publishEvent(any(EntityChangedEvent.class));
    }

    @Test
    void contextForRefreshesAggregateIdWithinActiveOperation() {
        publisher = new EntityEventPublisher(applicationEventPublisher);
        EventContext open = EventContext.builder(TestDocument.class)
            .source(EventSource.SYSTEM)
            .operationName("entity:TestDocument")
            .build();

        try (EntityEventPublisher.EventScope ignored = publisher.openOperation(open)) {
            EventContext saved = publisher.contextFor(
                TestDocument.class, 42L, EventSource.SYSTEM, "save:TestDocument");

            assertThat(saved.aggregateId()).isEqualTo(42L);
            assertThat(saved.correlationId()).isEqualTo(open.correlationId());
            assertThat(saved.operationName()).isEqualTo("save:TestDocument");
        }
    }

    @Test
    void savingEventDoesNotDependOnVaadinOrApplicationTypes() {
        assertThat(EntitySavingEvent.class.getPackageName()).isEqualTo("org.ipro.events");
        assertThat(EntitySavingEvent.class.getClassLoader()).isNotNull();
    }

    private static EventContext context() {
        return EventContext.builder(TestDocument.class)
            .source(EventSource.SYSTEM)
            .operationName("save:TestDocument")
            .build();
    }

    static class TestDocument {
    }

    static class TestRow {
    }

    static class OtherRow {
    }
}
