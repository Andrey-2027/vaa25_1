package org.ip.form;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.*;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** AF5.6: the standard autoform retains the document's full aggregate save path. */
@SpringBootTest(classes = Application.class)
@Transactional
class ReceivingDocumentAutoformIT {
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver forms;
    @Autowired MetadataResolver metadata;
    @Autowired ServiceLocator services;
    @Autowired FormSaveHandler saveHandler;
    @Autowired EntityManager em;
    @Autowired AccessGrantRepository grants;
    private Journal journal;
    private Workshop receiving, transferring;
    private Nomenclature nomenclature;
    private String suffix;

    @BeforeEach void seed() {
        String user = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, user, ReceivingDocument.class, ReceivingDocumentItem.class,
            Journal.class, Workshop.class, Nomenclature.class, UnitOfMeasurement.class);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        journal = new Journal(); journal.setCode("J" + suffix); journal.setName("Journal " + suffix);
        journal = services.<Journal, Long>findService(Journal.class).save(journal);
        receiving = services.<Workshop, Long>findService(Workshop.class).save(new Workshop("R" + suffix, "Receiving " + suffix));
        transferring = services.<Workshop, Long>findService(Workshop.class).save(new Workshop("T" + suffix, "Transferring " + suffix));
        var unit = services.<UnitOfMeasurement, Long>findService(UnitOfMeasurement.class)
            .save(new UnitOfMeasurement(suffix.substring(0, 4), "Unit " + suffix, "U" + suffix));
        nomenclature = services.<Nomenclature, Long>findService(Nomenclature.class)
            .save(new Nomenclature("N" + suffix, "Item " + suffix, unit));
        em.flush();
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void genericCompositionRetainsAllFiveRequiredFieldsAndThePositionSection() {
        var form = forms.resolveItemForm(ReceivingDocument.class, null, null, null);
        assertThat(form).isExactlyInstanceOf(ItemForm.class);
        assertThat(forms.getFormRegistry().has(ReceivingDocument.class, FormType.ITEM, null)).isFalse();
        assertThat(form.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName())
            .containsExactly("journal", "number", "date", "receivingWorkshop", "transferringWorkshop");
        assertThat(metadata.resolve(ReceivingDocument.class).getFormFields()).allMatch(field -> field.isRequired());
        assertThat(form.getTableSections()).hasSize(1);
        assertThat(form.tableSection(ReceivingDocumentItem.class).getRowClass()).isEqualTo(ReceivingDocumentItem.class);
        var listPaths = metadata.resolve(ReceivingDocument.class).getListColumnPaths().stream()
            .map(org.ipro.metadata.ColumnPath::getKey).toList();
        assertThat(listPaths).containsExactly("id", "number", "date", "journal.code", "receivingWorkshop.code",
            "transferringWorkshop", "transferringWorkshop.name");
        assertThat(metadata.resolve(ReceivingDocument.class).getSelectColumnPaths()).extracting(org.ipro.metadata.ColumnPath::getKey)
            .containsExactlyElementsOf(listPaths);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void createSaveAndReopenKeepHeaderRowsAndCleanBaseline() {
        var form = forms.resolveItemForm(ReceivingDocument.class, null, null, null);
        form.setSaveHandler(saveHandler);
        var draft = form.initializeNewEntity(Map.of("journal", journal));
        assertThat(form.<Journal>entityField("journal").getValue()).isSameAs(journal);
        assertThat(form.isDirty()).isFalse();
        assertThat(form.validate()).noneMatch(message -> message.startsWith("Номер:"))
            .anyMatch(message -> message.startsWith("Дата:"))
            .anyMatch(message -> message.startsWith("Цех приёмщик:"))
            .anyMatch(message -> message.startsWith("Цех сдатчик:"));
        form.getEntityField("date", LocalDate.class).setValue(LocalDate.of(2026, 11, 8));
        form.<Workshop>entityField("receivingWorkshop").setValue(receiving);
        form.<Workshop>entityField("transferringWorkshop").setValue(transferring);
        var positions = form.<ReceivingDocumentItem>tableSection(ReceivingDocumentItem.class);
        var row = new ReceivingDocumentItem(nomenclature, new BigDecimal("2.500"));
        row.setDocument(draft);
        positions.applyPersistedRows(draft, List.of(row)); positions.markDirty();
        assertThat(form.validate()).isEmpty(); assertThat(form.isDirty()).isTrue();
        var result = form.save();
        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        var saved = ((FormSaveResult.Success<ReceivingDocument>) result).saved();
        assertThat(saved.getId()).isNotNull(); assertThat(form.isDirty()).isFalse();
        String assignedNumber = saved.getNumber();
        assertThat(assignedNumber).startsWith("РН-2026-");
        assertThat(form.getEntityField("number", String.class).getValue()).isEqualTo(assignedNumber);
        assertThat(positions.getRows()).singleElement().satisfies(value -> {
            assertThat(value.getId()).isNotNull(); assertThat(value.getLineNumber()).isEqualTo(1);
        });
        em.flush(); em.clear();
        var reopened = forms.resolveItemForm(ReceivingDocument.class, null, saved.getId(), null);
        reopened.setEntity(services.<ReceivingDocument, Long>findService(ReceivingDocument.class)
            .findById(saved.getId(), reopened.getReadPaths()).orElseThrow());
        assertThat(reopened.getEntityField("number", String.class).getValue()).isEqualTo(assignedNumber);
        assertThat(reopened.getEntityField("date", LocalDate.class).getValue()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(reopened.<Journal>entityField("journal").getValue().getId()).isEqualTo(journal.getId());
        assertThat(reopened.<Workshop>entityField("receivingWorkshop").getValue().getId()).isEqualTo(receiving.getId());
        assertThat(reopened.<Workshop>entityField("transferringWorkshop").getValue().getId()).isEqualTo(transferring.getId());
        assertThat(reopened.<ReceivingDocumentItem>tableSection(ReceivingDocumentItem.class).getRows())
            .singleElement().satisfies(value -> {
                assertThat(value.getNomenclature().getId()).isEqualTo(nomenclature.getId());
                assertThat(value.getQuantity()).isEqualByComparingTo("2.500");
                assertThat(value.getLineNumber()).isEqualTo(1);
            });
        assertThat(reopened.isDirty()).isFalse();
    }
}
