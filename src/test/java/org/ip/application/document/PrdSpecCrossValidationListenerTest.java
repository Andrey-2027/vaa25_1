package org.ip.application.document;

import org.ipro.crud.ValidationException;
import org.ipro.events.AggregateSection;
import org.ipro.events.EventContext;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdSpecCrossValidationListenerTest {

    private final PrdSpecLifecycle lifecycle = new PrdSpecLifecycle();

    @Test
    void vetoesWhenComponentReferencesAssemblyNomenclature() {
        Nomenclature assembly = nomenclature(5L);
        PrdSpec header = new PrdSpec();
        header.setNomenclature(assembly);
        PrdSpecMtr row = new PrdSpecMtr();
        row.setNomenclature(nomenclature(5L));

        assertThatThrownBy(() -> lifecycle.beforeAggregateSave(
                event(header, List.of(AggregateSection.attached(PrdSpecMtr.class, List.of(row))))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Строка 1")
            .hasMessageContaining("собираемой единицей");
    }

    @Test
    void passesWhenComponentReferencesDifferentNomenclature() {
        PrdSpec header = new PrdSpec();
        header.setNomenclature(nomenclature(5L));
        PrdSpecMtr row = new PrdSpecMtr();
        row.setNomenclature(nomenclature(7L));

        assertThatCode(() -> lifecycle.beforeAggregateSave(
                event(header, List.of(AggregateSection.attached(PrdSpecMtr.class, List.of(row))))))
            .doesNotThrowAnyException();
    }

    @Test
    void passesForEmptyAttachedSection() {
        PrdSpec header = new PrdSpec();
        header.setNomenclature(nomenclature(5L));

        assertThatCode(() -> lifecycle.beforeAggregateSave(
                event(header, List.of(AggregateSection.attached(PrdSpecMtr.class, List.of())))))
            .doesNotThrowAnyException();
    }

    @Test
    void ignoresNonMaterialSections() {
        PrdSpec header = new PrdSpec();
        header.setNomenclature(nomenclature(5L));
        PrdSpecOper oper = new PrdSpecOper();
        oper.setRoute("10");

        assertThatCode(() -> lifecycle.beforeAggregateSave(
                event(header, List.of(AggregateSection.attached(PrdSpecOper.class, List.of(oper))))))
            .doesNotThrowAnyException();
    }

    @Test
    void passesWhenHeaderHasNoNomenclatureYet() {
        PrdSpec header = new PrdSpec();
        PrdSpecMtr row = new PrdSpecMtr();
        row.setNomenclature(nomenclature(5L));

        assertThatCode(() -> lifecycle.beforeAggregateSave(
                event(header, List.of(AggregateSection.attached(PrdSpecMtr.class, List.of(row))))))
            .doesNotThrowAnyException();
    }

    private static AggregateSaveContext<PrdSpec> event(PrdSpec header,
                                                       List<AggregateSection> sections) {
        EventContext.Builder context = EventContext.builder(PrdSpec.class)
            .operationName("save:PrdSpec");
        for (AggregateSection section : sections) {
            context.attachSection(section.rowType());
        }
        return new AggregateSaveContext<>(header, sections, context.build());
    }

    private static Nomenclature nomenclature(long id) {
        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setId(id);
        return nomenclature;
    }
}
