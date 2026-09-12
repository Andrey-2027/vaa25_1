package org.ipro.crud;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.repository.JournalRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.events.AggregateSavingEvent;
import org.ipro.events.EntityChangedEvent;
import org.ipro.events.EntitySavedEvent;
import org.ipro.events.EntitySavingEvent;
import org.ipro.events.EventSource;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavioural parity for aggregate lifecycle after removal of typed use cases. */
@SpringBootTest(classes = org.ip.Application.class)
@Import(MetadataDrivenAggregateEventIT.RecordingConfig.class)
class MetadataDrivenAggregateEventIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregateSaveService;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private EventRecorder recorder;

    @BeforeEach
    void clearRecordedEvents() {
        recorder.clear();
    }

    @Test
    void publishesChangedOnlyAfterSuccessfulAggregateCommit() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            Workshop receiving = workshopRepository.save(
                new Workshop("RW-" + suffix, "Receiving " + suffix));
            Workshop transferring = workshopRepository.save(
                new Workshop("TW-" + suffix, "Transferring " + suffix));
            Journal journal = new Journal();
            journal.setCode("J-" + suffix);
            journal.setName("Journal " + suffix);
            journalRepository.save(journal);

            ReceivingDocument document = new ReceivingDocument(
                "OK-" + suffix, LocalDate.now(), receiving, transferring);
            document.setJournal(journal);

            aggregateSaveService.save(document, List.of(), EventSource.SYSTEM);

            assertThat(recorder.lifecycle()).containsExactly(
                "AggregateSavingEvent", "EntitySavingEvent",
                "EntitySavedEvent", "EntityChangedEvent");
            assertThat(recorder.changedEvents()).singleElement()
                .satisfies(event -> assertThat(event.context().operationName())
                    .isEqualTo("save:ReceivingDocument"));
        });
    }

    static final class EventRecorder {
        private final List<String> lifecycle = new CopyOnWriteArrayList<>();
        private final List<EntityChangedEvent<?>> changedEvents = new CopyOnWriteArrayList<>();

        @EventListener
        public void onAggregateSaving(AggregateSavingEvent<?> event) {
            lifecycle.add("AggregateSavingEvent");
        }

        @EventListener
        public void onEntitySaving(EntitySavingEvent<?> event) {
            lifecycle.add("EntitySavingEvent");
        }

        @EventListener
        public void onEntitySaved(EntitySavedEvent<?> event) {
            lifecycle.add("EntitySavedEvent");
        }

        @EventListener
        public void onEntityChanged(EntityChangedEvent<?> event) {
            lifecycle.add("EntityChangedEvent");
            changedEvents.add(event);
        }

        List<String> lifecycle() {
            return List.copyOf(lifecycle);
        }

        List<EntityChangedEvent<?>> changedEvents() {
            return List.copyOf(changedEvents);
        }

        void clear() {
            lifecycle.clear();
            changedEvents.clear();
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }
}
