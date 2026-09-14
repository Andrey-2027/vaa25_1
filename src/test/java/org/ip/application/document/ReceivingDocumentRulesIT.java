package org.ip.application.document;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ValidationException;
import org.ipro.events.EntityDeletedEvent;
import org.ipro.events.EntityDeletingEvent;
import org.ipro.events.EntitySavedEvent;
import org.ipro.events.EntitySavingEvent;
import org.ipro.events.EventSource;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Правила накладной в typed lifecycle-контуре, проверенные на живом Spring-контексте.
 *
 * <p>Ключевые утверждения:</p>
 * <ul>
 *   <li>{@code AbstractBaseService.save} публикует {@code EntitySavingEvent} — значит
 *       правило шапки применяется на любом пути сохранения, а не только там, где оно
 *       было прописано;</li>
 *   <li>правило «цеха не могут совпадать» больше не лежит в typed-сервисе документа,
 *       но по-прежнему отменяет сохранение через generic-путь;</li>
 *   <li>правило «номенклатура не повторяется» живёт только в lifecycle handler и отменяет
 *       metadata-driven aggregate save без дублирования логики.</li>
 * </ul>
 */
@SpringBootTest
@Import(ReceivingDocumentRulesIT.RecordingConfig.class)
class ReceivingDocumentRulesIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private GenericOwnedSectionService sectionService;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregateSaveService;

    @Autowired
    private SectionMetadataRegistry sectionRegistry;

    @Autowired
    private ReceivingDocumentRepository documentRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private EventRecorder recorder;

    @BeforeEach
    void clearRecordedEvents() {
        recorder.clear();
    }

    /** C4.6 волна B: у {@code ReceivingDocument} нет typed-сервиса — резолв идёт canonical handle. */
    private CanonicalEntityService<ReceivingDocument> documents() {
        return (CanonicalEntityService<ReceivingDocument>) serviceLocator
            .<ReceivingDocument, Long>findService(ReceivingDocument.class);
    }

    @Test
    void genericSavePublishesEntitySavingEventBeforePersistence() {
        String suffix = suffix();

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            ReceivingDocument document = documentWithDifferentWorkshops(suffix);

            documents().save(document);

            assertThat(recorder.entitySavingEvents()).singleElement().satisfies(event -> {
                assertThat(event.entity()).isInstanceOf(ReceivingDocument.class);
                assertThat(event.context().aggregateType()).isEqualTo(ReceivingDocument.class);
                assertThat(event.context().source()).isEqualTo(EventSource.SYSTEM);
                assertThat(event.context().operationName()).isEqualTo("save:ReceivingDocument");
                assertThat(event.context().correlationId()).isNotBlank();
            });
            assertThat(documentRepository.findAll())
                .anyMatch(saved -> ("РН-" + suffix + "-A").equals(saved.getNumber()));
        });
    }

    @Test
    void entitySavingListenerVetoesEqualWorkshopsOnGenericPath() {
        String suffix = suffix();

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            Journal journal = persistJournal(suffix);
            Workshop workshop = workshopRepository.save(new Workshop("W-" + suffix, "Цех " + suffix));
            ReceivingDocument document = new ReceivingDocument(
                "РН-" + suffix + "-B", LocalDate.now(), workshop, workshop);
            document.setJournal(journal);

            assertThatThrownBy(() -> documents().save(document))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Цех-приемщик и цех-сдатчик не могут быть одинаковыми");

            assertThat(documentRepository.findAll())
                .noneMatch(saved -> ("РН-" + suffix + "-B").equals(saved.getNumber()));
        });
    }

    @Test
    void deletePublishesDeletingBeforeDeleteAndDeletedAfterCommit() {
        String suffix = suffix();

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            Nomenclature nomenclature = persistNomenclature(suffix);
            ReceivingDocument document = documentWithDifferentWorkshops(suffix);
            ReceivingDocument saved = aggregateSaveService.save(document,
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    ReceivingDocumentItem.class,
                    List.of(new ReceivingDocumentItem(nomenclature, BigDecimal.ONE)))),
                EventSource.SYSTEM).aggregate();

            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(
                ReceivingDocumentItem.class).orElseThrow();
            assertThat(sectionService.findByParent(saved, descriptor)).hasSize(1);

            documents().delete(saved.getId());

            // veto-capable событие пришло синхронно до удаления, факт удаления — после commit
            assertThat(recorder.deletingEvents()).singleElement().satisfies(event -> {
                assertThat(event.entity()).isInstanceOf(ReceivingDocument.class);
                assertThat(event.context().operationName()).isEqualTo("delete:ReceivingDocument");
                assertThat(event.context().aggregateId()).isEqualTo(saved.getId());
            });
            assertThat(recorder.deletedEvents()).singleElement().satisfies(event -> {
                assertThat(event.entity()).isInstanceOf(ReceivingDocument.class);
                assertThat(event.context().operationName()).isEqualTo("delete:ReceivingDocument");
            });
            assertThat(documentRepository.findAll())
                .noneMatch(existing -> ("РН-" + suffix + "-A").equals(existing.getNumber()));
            assertThat(sectionService.findByParent(saved, descriptor)).isEmpty();
        });
    }

    @Test
    void entityDeletingListenerVetoCancelsDeleteOnGenericPath() {
        String suffix = suffix();

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            ReceivingDocument document = documentWithDifferentWorkshops(suffix);
            ReceivingDocument saved = documents().save(document);
            recorder.blockDeletesFor(saved.getId());

            assertThatThrownBy(() -> documents().delete(saved.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Удаление заблокировано слушателем");

            assertThat(documentRepository.findAll())
                .anyMatch(existing -> ("РН-" + suffix + "-A").equals(existing.getNumber()));
            assertThat(recorder.deletedEvents()).isEmpty();
        });
    }

    @Test
    void aggregateSavingListenerVetoesDuplicatedRowsOnDocumentPath() {
        String suffix = suffix();
        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setId(1L);
        nomenclature.setName("Деталь " + suffix);

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            ReceivingDocument document = documentWithDifferentWorkshops(suffix);
            List<ReceivingDocumentItem> rows = List.of(
                new ReceivingDocumentItem(nomenclature, BigDecimal.ONE),
                new ReceivingDocumentItem(nomenclature, BigDecimal.TEN));

            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(
                ReceivingDocumentItem.class).orElseThrow();
            // Generic section validation отвечает за структуру, listener — за предметный duplicate rule.
            assertThat(sectionService.validateRows(document, rows, descriptor)).isEmpty();

            assertThatThrownBy(() -> aggregateSaveService.save(document,
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        ReceivingDocumentItem.class, rows)), EventSource.UI))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("указана в накладной более одного раза");

            assertThat(documentRepository.findAll())
                .noneMatch(saved -> ("РН-" + suffix + "-A").equals(saved.getNumber()));
        });
    }

    private ReceivingDocument documentWithDifferentWorkshops(String suffix) {
        Workshop receiving = workshopRepository.save(new Workshop("RW-" + suffix, "Принимающий " + suffix));
        Workshop transferring = workshopRepository.save(new Workshop("TW-" + suffix, "Передающий " + suffix));
        ReceivingDocument document = new ReceivingDocument(
            "РН-" + suffix + "-A", LocalDate.now(), receiving, transferring);
        document.setJournal(persistJournal(suffix));
        return document;
    }

    private Journal persistJournal(String suffix) {
        Journal journal = new Journal();
        journal.setCode("J-" + suffix);
        journal.setName("Journal " + suffix);
        return journalRepository.save(journal);
    }

    private Nomenclature persistNomenclature(String suffix) {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setCode("U-" + suffix);
        unit.setShortCode("шт");
        unit.setName("Штука " + suffix);
        unitOfMeasurementRepository.save(unit);

        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setCode("N-" + suffix);
        nomenclature.setName("Деталь " + suffix);
        nomenclature.setUnitOfMeasurement(unit);
        return nomenclatureRepository.save(nomenclature);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Наблюдатель событий: доказывает, что generic-путь публикует lifecycle-события. */
    static final class EventRecorder {

        private final List<EntitySavingEvent<?>> entitySavingEvents = new CopyOnWriteArrayList<>();
        private final List<EntityDeletingEvent<?>> deletingEvents = new CopyOnWriteArrayList<>();
        private final List<EntityDeletedEvent<?>> deletedEvents = new CopyOnWriteArrayList<>();
        private volatile Long blockedDeleteId;

        @EventListener
        public void onEntitySaving(EntitySavingEvent<?> event) {
            entitySavingEvents.add(event);
        }

        @EventListener
        public void onDeleting(EntityDeletingEvent<?> event) {
            deletingEvents.add(event);
            if (event.context().aggregateId() != null
                && event.context().aggregateId().equals(blockedDeleteId)) {
                throw new ValidationException("Удаление заблокировано слушателем");
            }
        }

        @EventListener
        public void onDeleted(EntityDeletedEvent<?> event) {
            deletedEvents.add(event);
        }

        void blockDeletesFor(Long id) {
            this.blockedDeleteId = id;
        }

        List<EntitySavingEvent<?>> entitySavingEvents() {
            return List.copyOf(entitySavingEvents);
        }

        List<EntityDeletingEvent<?>> deletingEvents() {
            return List.copyOf(deletingEvents);
        }

        List<EntityDeletedEvent<?>> deletedEvents() {
            return List.copyOf(deletedEvents);
        }

        void clear() {
            entitySavingEvents.clear();
            deletingEvents.clear();
            deletedEvents.clear();
            blockedDeleteId = null;
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
