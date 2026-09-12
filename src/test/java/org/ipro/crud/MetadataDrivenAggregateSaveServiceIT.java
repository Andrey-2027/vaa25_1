package org.ipro.crud;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.events.EventSource;
import org.ipro.form.ItemFormSaveHandlerRegistry;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormSaveResult;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Integration proof of the metadata-driven atomic aggregate save slice (B3). */
@SpringBootTest(classes = org.ip.Application.class)
class MetadataDrivenAggregateSaveServiceIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregateSaveService;

    @Autowired
    private GenericOwnedSectionService sectionService;

    @Autowired
    private SectionMetadataRegistry sectionRegistry;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private UnitOfMeasurementRepository unitRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private PrdSpecRepository prdSpecRepository;

    @Autowired
    private ReceivingDocumentRepository receivingDocumentRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private ItemFormSaveHandlerRegistry handlerRegistry;

    @Autowired
    private MetadataDrivenItemFormSaveAdapter formSaveAdapter;

    @Autowired
    private MetadataResolver metadataResolver;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Test
    void sameFormCanRetryAfterLateSectionFailureWithoutLosingUserInput() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            PrdSpec spec = createSpec(UUID.randomUUID().toString().substring(0, 8));
            String code = spec.getCodeSpec();
            PrdSpecMtr material = new PrdSpecMtr();
            material.setTypeMtr(1);
            PrdSpecOper invalidOperation = new PrdSpecOper();
            invalidOperation.setId(Long.MAX_VALUE);
            invalidOperation.setRoute("invalid ownership");

            ItemForm<PrdSpec> form = new ItemForm<>(PrdSpec.class, List.of(), mock(FieldFactory.class));
            form.setEntity(spec);
            ItemTable<PrdSpecMtr, PrdSpec> materials = attachTable(form, PrdSpecMtr.class);
            ItemTable<PrdSpecOper, PrdSpec> operations = attachTable(form, PrdSpecOper.class);
            materials.applyPersistedRows(spec, List.of(material));
            operations.applyPersistedRows(spec, List.of(invalidOperation));
            materials.markDirty();
            operations.markDirty();
            form.setSaveHandler(formSaveAdapter::save);

            assertThat(form.save()).isInstanceOf(FormSaveResult.Failure.class);
            assertThat(form.peekEntity()).isSameAs(spec);
            assertThat(form.isDirty()).isTrue();
            assertThat(spec.getCodeSpec()).isEqualTo(code);
            assertThat(material.getTypeMtr()).isEqualTo(1);
            assertThat(prdSpecRepository.findAll()).noneMatch(row -> code.equals(row.getCodeSpec()));

            // Correct the offending row in the same open form; no technical reset/reload.
            PrdSpecOper correctedOperation = new PrdSpecOper();
            correctedOperation.setRoute("corrected route");
            operations.applyPersistedRows(spec, List.of(correctedOperation));
            operations.markDirty();

            assertThat(form.save()).isInstanceOf(FormSaveResult.Success.class);
            assertThat(form.isDirty()).isFalse();
            assertThat(form.peekEntity().getCodeSpec()).isEqualTo(code);
            assertThat(materials.getRows()).singleElement().satisfies(row -> {
                assertThat(row.getId()).isNotNull();
                assertThat(row.getTypeMtr()).isEqualTo(1);
                assertThat(row.getPrdSpec().getId()).isEqualTo(form.peekEntity().getId());
            });
            assertThat(operations.getRows()).singleElement().satisfies(row ->
                assertThat(row.getRoute()).isEqualTo("corrected route"));
            assertThat(prdSpecRepository.findById(form.peekEntity().getId())).isPresent();
        });
    }

    private <R extends IdentifiableEntity> ItemTable<R, PrdSpec> attachTable(
            ItemForm<PrdSpec> form, Class<R> rowClass) {
        TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(rowClass).orElseThrow();
        // Real form/table lifecycle and persistence; field rendering is outside this regression.
        TableSectionMetadataInfo uiDescriptor = new TableSectionMetadataInfo(
            descriptor.getOwnerClass(), rowClass, rowClass.getAnnotation(
                org.ipro.metadata.annotation.TableSectionMetadata.class),
            descriptor.getParentField(), descriptor.getLineNumberField(), List.of(), List.of());
        ItemTable<R, PrdSpec> table = new ItemTable<>(uiDescriptor, mock(FieldFactory.class),
            new MetadataTableSectionService<>(sectionService, descriptor), metadataResolver,
            null, null, null, null);
        form.addTableSection(descriptor.getTitle(), table);
        return table;
    }

    @Test
    void savesHeaderAndOnlyAttachedSections() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            PrdSpec spec = createSpec(suffix);
            PrdSpecOper operation = new PrdSpecOper();
            operation.setRoute("metadata-driven");

            MetadataDrivenAggregateSaveService.AggregateSaveResult<PrdSpec> result =
                aggregateSaveService.save(
                    spec,
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        PrdSpecOper.class, List.of(operation))),
                    EventSource.UI);

            assertThat(result.aggregate().getId()).isNotNull();
            assertThat(result.sections()).extracting(
                MetadataDrivenAggregateSaveService.SectionResult::rowType)
                .containsExactly(PrdSpecOper.class);
            assertThat(result.section(PrdSpecMtr.class)).isEmpty();

            TableSectionMetadataInfo operations = sectionRegistry.findByRow(PrdSpecOper.class)
                .orElseThrow();
            List<PrdSpecOper> persisted = sectionService
                .<PrdSpecOper, PrdSpec>findByParent(result.aggregate(), operations);
            assertThat(persisted).hasSize(1);
            assertThat(persisted.getFirst().getPrdSpec().getId())
                .isEqualTo(result.aggregate().getId());
            assertThat(persisted.getFirst().getOrder()).isEqualTo(1);
        });
    }

    @Test
    void rollsBackHeaderAndEarlierSectionWhenLaterSectionFails() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            PrdSpec spec = createSpec(suffix);

            PrdSpecMtr material = new PrdSpecMtr();
            material.setTypeMtr(1);
            PrdSpecOper invalidOperation = new PrdSpecOper();
            invalidOperation.setId(Long.MAX_VALUE);
            invalidOperation.setRoute("must-fail-ownership-check");

            assertThatThrownBy(() -> aggregateSaveService.save(
                spec,
                List.of(
                    MetadataDrivenAggregateSaveService.SectionInput.attached(
                        PrdSpecMtr.class, List.of(material)),
                    MetadataDrivenAggregateSaveService.SectionInput.attached(
                        PrdSpecOper.class, List.of(invalidOperation))),
                EventSource.UI))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("не принадлежит секции");

            assertThat(spec.getId()).isNull();
            assertThat(prdSpecRepository.findAll())
                .noneMatch(row -> spec.getCodeSpec().equals(row.getCodeSpec()));
        });
    }

    @Test
    void savesReceivingDocumentThroughTheSameMetadataDrivenContract() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Journal journal = journalRepository.save(journal("R-J-" + suffix));
            UnitOfMeasurement unit = unitRepository.save(
                new UnitOfMeasurement("ru" + suffix.substring(0, 2), "Receiving unit " + suffix,
                    "RU" + suffix.substring(0, 2)));
            Nomenclature nomenclature = nomenclatureRepository.save(
                new Nomenclature("R-N-" + suffix, "Receiving nom " + suffix, unit));
            Workshop receiving = workshopRepository.save(new Workshop("R-R-" + suffix, "Receiving"));
            Workshop transferring = workshopRepository.save(new Workshop("R-T-" + suffix, "Transferring"));

            ReceivingDocument document = new ReceivingDocument(
                null, java.time.LocalDate.now(), receiving, transferring);
            document.setJournal(journal);
            ReceivingDocumentItem item = new ReceivingDocumentItem(nomenclature, java.math.BigDecimal.ONE);

            MetadataDrivenAggregateSaveService.AggregateSaveResult<ReceivingDocument> result =
                aggregateSaveService.save(
                    document,
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        ReceivingDocumentItem.class, List.of(item))),
                    EventSource.UI);

            assertThat(result.aggregate().getId()).isNotNull();
            assertThat(result.sections()).extracting(
                MetadataDrivenAggregateSaveService.SectionResult::rowType)
                .containsExactly(ReceivingDocumentItem.class);
            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(ReceivingDocumentItem.class)
                .orElseThrow();
            List<ReceivingDocumentItem> persisted = sectionService
                .<ReceivingDocumentItem, ReceivingDocument>findByParent(result.aggregate(), descriptor);
            assertThat(persisted).hasSize(1);
            assertThat(persisted.getFirst().getDocument().getId())
                .isEqualTo(result.aggregate().getId());
            assertThat(persisted.getFirst().getLineNumber()).isEqualTo(1);
            assertThat(receivingDocumentRepository.findById(result.aggregate().getId())).isPresent();
        });
    }

    @Test
    void standardPilotDocumentsHaveNoCustomSaveOverride() {
        assertThat(handlerRegistry.find(PrdSpec.class)).isEmpty();
        assertThat(handlerRegistry.find(ReceivingDocument.class)).isEmpty();
    }

    @Test
    void standardPilotSectionsUseGenericPersistenceWithoutCustomService() {
        assertThat(sectionRegistry.forOwner(PrdSpec.class))
            .extracting(TableSectionMetadataInfo::getRowClass)
            .containsExactlyInAnyOrder(PrdSpecMtr.class, PrdSpecOper.class);
        assertThat(sectionRegistry.forOwner(PrdSpec.class))
            .allSatisfy(descriptor ->
                assertThat(descriptor.getServiceClass()).isEqualTo(void.class));
        assertThat(sectionRegistry.forOwner(ReceivingDocument.class))
            .extracting(TableSectionMetadataInfo::getRowClass)
            .containsExactly(ReceivingDocumentItem.class);
        assertThat(sectionRegistry.forOwner(ReceivingDocument.class))
            .allSatisfy(descriptor ->
                assertThat(descriptor.getServiceClass()).isEqualTo(void.class));
    }

    private PrdSpec createSpec(String suffix) {
        Journal journal = new Journal();
        journal.setCode("J-" + suffix);
        journal.setName("Journal " + suffix);
        journal = journalRepository.save(journal);
        UnitOfMeasurement unit = unitRepository.save(
            new UnitOfMeasurement("u" + suffix.substring(0, 3), "Unit " + suffix,
                "U" + suffix));
        Nomenclature nomenclature = nomenclatureRepository.save(
            new Nomenclature("N-" + suffix, "Nom " + suffix, unit));
        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec("SPEC-" + suffix);
        return spec;
    }

    private static Journal journal(String code) {
        Journal journal = new Journal();
        journal.setCode(code);
        journal.setName("Journal " + code);
        return journal;
    }
}
