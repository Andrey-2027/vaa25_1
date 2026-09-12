package org.ip.application.document;

import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ipro.crud.ValidationException;
import org.ipro.events.AggregateSection;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntitySaveContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Канонический lifecycle handler накладной: раньше правила были в
 * {@code ReceivingDocumentService.validateBusinessRules} и legacy section service.
 */
class ReceivingDocumentRulesListenerTest {

    private final ReceivingDocumentLifecycle lifecycle = new ReceivingDocumentLifecycle();

    @Test
    void vetoesWhenReceivingAndTransferringWorkshopsAreEqual() {
        ReceivingDocument document = new ReceivingDocument("РН-1", null,
            workshop(3L), workshop(3L));

        assertThatThrownBy(() -> lifecycle.beforeSave(entitySaving(document)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Цех-приемщик и цех-сдатчик не могут быть одинаковыми");
    }

    @Test
    void passesWhenWorkshopsDiffer() {
        ReceivingDocument document = new ReceivingDocument("РН-1", null,
            workshop(3L), workshop(4L));

        assertThatCode(() -> lifecycle.beforeSave(entitySaving(document)))
            .doesNotThrowAnyException();
    }

    @Test
    void passesWhenWorkshopsAreNotSetYet() {
        ReceivingDocument document = new ReceivingDocument();

        assertThatCode(() -> lifecycle.beforeSave(entitySaving(document)))
            .doesNotThrowAnyException();
    }

    @Test
    void vetoesDuplicatedNomenclatureRows() {
        Nomenclature nomenclature = nomenclature(7L, "Деталь");

        assertThatThrownBy(() -> lifecycle.beforeAggregateSave(aggregateSaving(List.of(
                new ReceivingDocumentItem(nomenclature, BigDecimal.ONE),
                new ReceivingDocumentItem(nomenclature, BigDecimal.TEN)))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("номенклатура \"Деталь\" указана в накладной более одного раза");
    }

    @Test
    void passesForUniqueNomenclatureRows() {
        assertThatCode(() -> lifecycle.beforeAggregateSave(aggregateSaving(List.of(
                new ReceivingDocumentItem(nomenclature(7L, "Деталь"), BigDecimal.ONE),
                new ReceivingDocumentItem(nomenclature(8L, "Узел"), BigDecimal.TEN)))))
            .doesNotThrowAnyException();
    }

    @Test
    void passesForEmptySectionAndRowsWithoutNomenclature() {
        ReceivingDocumentItem withoutNomenclature = new ReceivingDocumentItem();
        withoutNomenclature.setQuantity(BigDecimal.ONE);

        assertThatCode(() -> lifecycle.beforeAggregateSave(aggregateSaving(List.of())))
            .doesNotThrowAnyException();
        assertThatCode(() -> lifecycle.beforeAggregateSave(aggregateSaving(
                List.of(withoutNomenclature, withoutNomenclature))))
            .doesNotThrowAnyException();
    }

    private static EntitySaveContext<ReceivingDocument> entitySaving(ReceivingDocument entity) {
        return new EntitySaveContext<>(entity, EventContext.forEntity(
            ReceivingDocument.class, null, EventSource.SYSTEM, "save:ReceivingDocument"));
    }

    private static AggregateSaveContext<ReceivingDocument> aggregateSaving(
            List<ReceivingDocumentItem> rows) {
        EventContext context = EventContext.builder(ReceivingDocument.class)
            .source(EventSource.UI)
            .attachSection(ReceivingDocumentItem.class)
            .operationName("save:ReceivingDocument")
            .build();
        return new AggregateSaveContext<>(new ReceivingDocument(),
            List.of(AggregateSection.attached(ReceivingDocumentItem.class, rows)), context);
    }

    private static org.ip.model.Workshop workshop(long id) {
        org.ip.model.Workshop workshop = new org.ip.model.Workshop();
        workshop.setId(id);
        return workshop;
    }

    private static Nomenclature nomenclature(long id, String name) {
        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setId(id);
        nomenclature.setName(name);
        return nomenclature;
    }
}
