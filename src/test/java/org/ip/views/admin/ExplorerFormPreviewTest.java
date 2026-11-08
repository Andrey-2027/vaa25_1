package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import org.ip.model.*;
import org.ip.service.NomSklAttributeService;
import org.ip.views.forms.*;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.*;
import org.ipro.vaadin.explorer.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplorerFormPreviewTest {
    private final MetadataResolver metadata = new MetadataResolver();

    private EntitySummary summary(Class<?> type) {
        var summary = mock(EntitySummary.class);
        when(summary.forms()).thenReturn(List.of(new EntitySummary.FormRow(FormType.ITEM, null,
            type == Nomenclature.class ? "кастомная фабрика" : "платформенная автогенерация",
            type != Nomenclature.class, "", type == Nomenclature.class ? FactOrigin.REGISTRATION : FactOrigin.PLATFORM_DEFAULT, "",
            type == Nomenclature.class ? NomenclatureItemForm.class : ItemForm.class, "", type == Nomenclature.class,
            type == Nomenclature.class ? List.of(NomenclatureItemFormConfig.class.getName()) : List.of())));
        return summary;
    }

    private EntitySummary.FormRow registration(EntitySummary summary) {
        return summary.forms().stream().filter(f -> f.formType() == FormType.ITEM).findFirst().orElseThrow();
    }

    @Test void emptyAndSampleUseRegisteredClassAndRealSectionsWithoutPersistentIds() {
        for (Class<?> type : List.of(Nomenclature.class, ReceivingDocument.class)) {
            var summary = summary(type);
            var registration = registration(summary);
            var empty = ExplorerFormPreview.build(type, registration, false);
            var example = ExplorerFormPreview.build(type, registration, true);
            assertThat(empty.getClass()).isEqualTo(registration.formClass());
            assertThat(example.getClass()).isEqualTo(empty.getClass());
            assertThat(empty.peekEntity().getId()).isNull();
            assertThat(example.peekEntity().getId()).isNull();
            assertThat(empty.getTableSections()).hasSize(1).allSatisfy(t -> {
                assertThat(t.getRows()).isEmpty(); assertThat(t.isReadOnly()).isTrue();
            });
            assertThat(example.getTableSections()).hasSize(1).allSatisfy(t -> {
                assertThat(t.getRows()).hasSize(type == Nomenclature.class ? 1 : 2);
                assertThat(t.getRows()).allSatisfy(r -> assertThat(r.getId()).isNull());
            });
            assertThat(descendants(empty).filter(Grid.class::isInstance).map(Grid.class::cast))
                .allSatisfy(grid -> assertThat(grid.getColumns()).isNotEmpty());
            assertThat(descendants(example).filter(Button.class::isInstance).map(Button.class::cast))
                .noneSatisfy(b -> assertThat(b.getText()).isIn("Сохранить", "Сохранить и закрыть", "Провести"));
            if (type == Nomenclature.class) {
                var nom = (Nomenclature) example.peekEntity();
                assertThat(((Nomenclature) empty.peekEntity()).getCode()).isNull();
                assertThat(nom.getUnitOfMeasurement().getId()).isNull();
                assertThat(nom.getGroupNom().getId()).isNull();
                var row = (NomAttributeValue) example.getTableSections().getFirst().getRows().getFirst();
                assertThat(row.getNomenclature()).isSameAs(nom);
                assertThat(row.getAttrType().getId()).isNull();
                assertThat(row.getAttrValue().getId()).isNull();
            } else {
                var doc = (ReceivingDocument) example.peekEntity();
                assertThat(((ReceivingDocument) empty.peekEntity()).getNumber()).isNull();
                assertThat(doc.getDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 20));
                assertThat(doc.getJournal().getId()).isNull();
                assertThat(doc.getReceivingWorkshop().getId()).isNull();
                assertThat(doc.getTransferringWorkshop().getId()).isNull();
                var rows = example.tableSection(ReceivingDocumentItem.class).getRows();
                assertThat(rows.get(0)).isNotEqualTo(rows.get(1));
                assertThat(rows).allSatisfy(r -> {
                    assertThat(r.getDocument()).isSameAs(doc);
                    assertThat(r.getNomenclature().getId()).isNull();
                });
            }
            assertThat(empty.isDirty()).isFalse(); assertThat(example.isDirty()).isFalse();
        }
    }

    @Test void previewBlocksSectionDataOperationsEvenIfInvokedDirectly() {
        var summary = summary(Nomenclature.class);
        var preview = ExplorerFormPreview.build(Nomenclature.class, registration(summary), true);
        assertThatThrownBy(() -> preview.getTableSections().getFirst().commit(null))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> preview.getTableSections().getFirst().validateRows(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unsupportedRegistrationIsDisabledWithoutCallingFactory() {
        var summary = summary(Nomenclature.class);
        for (var row : List.of(
                new EntitySummary.FormRow(FormType.LIST, null, "platform", true, ""),
                new EntitySummary.FormRow(FormType.ITEM, "other", "custom", false, ""),
                new EntitySummary.FormRow(FormType.ITEM, null, "platform", true, ""))) {
            assertThat(ExplorerFormPreview.unavailableReason(Nomenclature.class, row)).isNotBlank();
            assertThatIllegalArgumentException().isThrownBy(() -> ExplorerFormPreview.build(Nomenclature.class, row, false));
        }
        assertThat(ExplorerFormPreview.unavailableReason(PrdSpec.class, registration(summary))).isNotBlank();
        assertThat(ExplorerFormPreview.unavailableReason(ReceivingDocument.class,
            new EntitySummary.FormRow(FormType.ITEM, null, "platform", true, ""))).isNotBlank();
        assertThat(ExplorerFormPreview.unavailableReason(Nomenclature.class,
            new EntitySummary.FormRow(FormType.LIST, null, "platform", true, ""))).isNotBlank();
    }

    @Test @SuppressWarnings("unchecked") void referenceSearchUsesOnlyLocalObjectsOfTheRequestedType() {
        Journal journal = new Journal(); journal.setCode("ПРИМЕР"); journal.setName("Локальный журнал");
        Workshop workshop = new Workshop("Ц-01", "Локальный цех");
        var fields = new ExplorerFormPreview.LocalFields(List.of(journal, workshop));
        var form = new ItemForm<ReceivingDocument>(metadata.resolve(ReceivingDocument.class), fields);
        fields.bind(form);
        var search = (org.ipro.form.SearchFunction<HasDisplayName>) org.springframework.test.util.ReflectionTestUtils
            .getField(form.entityField("journal"), "searchFunction");
        assertThat(search.search("")).containsExactly(journal);
        assertThat(search.search("ЖУРНАЛ")).containsExactly(journal);
        assertThat(search.search("Ц-01")).isEmpty();
        var emptyFields = new ExplorerFormPreview.LocalFields(List.of());
        var empty = new ItemForm<ReceivingDocument>(metadata.resolve(ReceivingDocument.class), emptyFields);
        emptyFields.bind(empty);
        var emptySearch = (org.ipro.form.SearchFunction<HasDisplayName>) org.springframework.test.util.ReflectionTestUtils
            .getField(empty.entityField("journal"), "searchFunction");
        assertThat(emptySearch.search("")).isEmpty();
        assertThat(journal.getId()).isNull(); assertThat(workshop.getId()).isNull();
    }

    @Test @SuppressWarnings("unchecked") void dialogSwitchesFreshInstancesClosesAndReopensEmpty() {
        UI ui = new UI();
        UI.setCurrent(ui);
        var session = mock(com.vaadin.flow.server.VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        ui.getInternals().setSession(session);
        try {
            for (Class<?> type : List.of(Nomenclature.class, ReceivingDocument.class)) {
                var summary = summary(type);
                var dialog = new ExplorerFormPreviewDialog(type, registration(summary));
                ui.add(dialog);
                dialog.open();
                assertThat(dialog.getElement().getAttribute("data-preview-mode")).isEqualTo("empty");
                var original = descendants(dialog).filter(ItemForm.class::isInstance).findFirst().orElseThrow();
                var modes = (RadioButtonGroup<String>) descendants(dialog).filter(RadioButtonGroup.class::isInstance).findFirst().orElseThrow();
                modes.setValue("С примером");
                var sample = descendants(dialog).filter(ItemForm.class::isInstance).findFirst().orElseThrow();
                assertThat(sample).isNotSameAs(original);
                modes.setValue("Пустая");
                assertThat(descendants(dialog).filter(ItemForm.class::isInstance)).singleElement().satisfies(f -> {
                    assertThat(f).isNotSameAs(original).isNotSameAs(sample);
                    assertThat(((ItemForm<?>) f).getTableSections().getFirst().getRows()).isEmpty();
                });
                dialog.close();
                assertThat(descendants(dialog).filter(ItemForm.class::isInstance)).isEmpty();
                var reopened = new ExplorerFormPreviewDialog(type, registration(summary));
                assertThat(reopened.getElement().getAttribute("data-preview-mode")).isEqualTo("empty");
            }
        } finally { UI.setCurrent(null); }
    }

    @Test void workingFormStateAndOtherPreviewRemainUntouched() {
        var bindings = mock(NomSklAttributeService.class);
        var working = new NomenclatureItemForm(metadata.resolve(Nomenclature.class),
            new ExplorerFormPreview.LocalFields(List.of()), bindings, List.of());
        var value = new Nomenclature(); value.setName("Несохранённое значение рабочей формы");
        working.setEntity(value);
        ((com.vaadin.flow.component.textfield.TextField) working.getField("name")).setValue("Правка в рабочей форме");
        var summary = summary(Nomenclature.class);
        var first = ExplorerFormPreview.build(Nomenclature.class, registration(summary), true);
        ((Nomenclature) first.peekEntity()).setName("Изменённый локальный пример");
        var second = ExplorerFormPreview.build(Nomenclature.class, registration(summary), true);
        assertThat(((Nomenclature) second.peekEntity()).getName()).contains("Заготовка");
        assertThat(working.peekEntity()).isSameAs(value);
        assertThat(value.getName()).isEqualTo("Несохранённое значение рабочей формы");
        assertThat(((com.vaadin.flow.component.textfield.TextField) working.getField("name")).getValue())
            .isEqualTo("Правка в рабочей форме");
        assertThat(working.isDirty()).isTrue();
        verifyNoInteractions(bindings);
    }

    private static Stream<Component> descendants(Component c) {
        return Stream.concat(Stream.of(c), c.getChildren().flatMap(ExplorerFormPreviewTest::descendants));
    }
}
