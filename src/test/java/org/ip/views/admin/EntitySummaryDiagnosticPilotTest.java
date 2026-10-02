package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.MetadataDiagnosticCodes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.1 шаг 6.2: адрес настоящих диагностик. Пилот берёт snapshot у владельца метаданных и сводку
 * собирает боевым сборщиком приложения: карточка обязана вести туда, куда запись действительно
 * адресована, а не туда, куда её удобно было бы отнести.
 *
 * <p>Мерятся три вида, дающих ключ владельца (замер §0 п.4 плана): строение поля, цель выбора и
 * отказ сканирования измерений RLS. Отдельно — запись без известного вида: она остаётся уровнем
 * сущности.</p>
 *
 * <p>Отказ измерений получается не выдумкой: реестр собран над пакетом платформы, поэтому
 * {@code ReceivingDocument} с объявленными измерениями в скан не попал — тот же замер, что у
 * платформенной пробы: владелец отказывает, сборщик публикует {@code RLS_SCAN}, а строк аспекта нет.
 * Поэтому у этой записи адрес есть, а перехода нет: раздела в карточке не существует.</p>
 */
class EntitySummaryDiagnosticPilotTest {

    private static final String BASE_PACKAGE = "org.ip";

    @Test
    void thePlaceOfARealFieldStructureDiagnosticFollowsTheFieldProjection() {
        EntitySummary summary = assembler(List.of(redundantRequired()), null)
            .summarize(ReceivingDocument.class);

        EntitySummary.DiagnosticRow row =
            diagnosticOf(summary, MetadataDiagnosticCodes.REDUNDANT_REQUIRED);
        assertThat(row.key().kind()).isEqualTo(FacetKind.FIELD_STRUCTURE);
        CardSection.Location location = CardSection.locate(summary, row);

        boolean inForm = showsField(summary.fieldsForm(), "number");
        boolean inGrid = showsField(summary.fieldsGrid(), "number");
        System.out.println("[E3.2.1-6.2] number: форма=" + inForm + ", грид=" + inGrid
            + ", место=" + EntitySummaryPanel.placeText(location));

        assertThat(location.tab()).isEqualTo(CardTab.FIELDS);
        assertThat(inForm).as("поле объявлено в форме").isTrue();
        assertThat(inGrid).as("и тем же объявлением — в гриде").isTrue();
        assertThat(CardSection.locateAll(summary, row)).extracting(EntitySummaryPanel::placeText)
            .containsExactly("Поля и колонки · Поля — форма", "Поля и колонки · Поля — грид");
    }

    @Test
    void thePlaceOfARealLookupTargetDiagnosticIsTheLinksSection() {
        EntitySummary summary = assembler(List.of(referenceTargetNotMetadata()), null)
            .summarize(ReceivingDocument.class);

        EntitySummary.DiagnosticRow row =
            diagnosticOf(summary, MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA);
        assertThat(row.key().kind()).isEqualTo(FacetKind.LOOKUP_TARGET);
        CardSection.Location location = CardSection.locate(summary, row);
        assertThat(location.tab()).isEqualTo(CardTab.FIELDS);
        assertThat(location.section()).isEqualTo(CardSection.section("table-sections"));
        assertThat(EntitySummaryPanel.placeText(location)).contains("Табличные части", "nomenclature");

        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);
        assertThat(CardSections.titles(panel)).contains("Связи");
        assertThat(panel.canFocus(location))
            .as("раздел «Связи» нарисован — переход предлагается")
            .isTrue();
        Component cell = panel.diagnosticWhereCell(summary, row);
        assertThat(CardSections.textIn(cell)).contains("Табличные части", "nomenclature");
        assertThat(CardSections.buttonIn(cell)).isNotNull();
    }

    @Test
    void thePlaceOfARefusedDimensionScanNamesTheAccessTabWithoutASectionToGoTo() {
        EntitySummary summary = assembler(List.of(), unscannedRegistry())
            .summarize(ReceivingDocument.class);

        EntitySummary.DiagnosticRow row = diagnosticOf(summary, "RLS_SCAN");
        assertThat(row.key().kind()).isEqualTo(FacetKind.RLS_DIMENSION);
        assertThat(row.fieldName()).isEmpty();
        assertThat(summary.accessRows())
            .as("отказ владельца: строк аспекта нет — это и есть причина записи")
            .isEmpty();

        CardSection.Location location = CardSection.locate(summary, row);
        assertThat(location.tab()).isEqualTo(CardTab.ACCESS);
        assertThat(location.section()).isEqualTo(CardSection.section("dimensions"));
        assertThat(EntitySummaryPanel.placeText(location)).isEqualTo("Доступ");

        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);
        assertThat(CardSections.titles(panel)).doesNotContain("Доступ");
        assertThat(panel.canFocus(location))
            .as("раздела нет — вести некуда, и это измеренный факт, а не ошибка карточки")
            .isFalse();
        assertThat(CardSections.buttonIn(panel.diagnosticWhereCell(summary, row))).isNull();
    }

    @Test
    void aRealDiagnosticWithoutAKnownKindStaysAtTheEntityLevel() {
        EntitySummary summary = assembler(List.of(redundantRequired(),
            new MetadataDiagnostic(MetadataDiagnostic.Severity.WARNING, "FUTURE_DIAGNOSTIC_CODE",
                ReceivingDocument.class.getName(), "number", "validator",
                "новая диагностика без известной грани")), null)
            .summarize(ReceivingDocument.class);

        EntitySummary.DiagnosticRow row = diagnosticOf(summary, "FUTURE_DIAGNOSTIC_CODE");
        assertThat(row.key()).isNull();
        CardSection.Location location = CardSection.locate(summary, row);
        assertThat(location.addressed()).isFalse();
        assertThat(EntitySummaryPanel.placeText(location)).isEqualTo("уровень сущности");

        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);
        assertThat(panel.canFocus(location)).isFalse();
        Component cell = panel.diagnosticWhereCell(summary, row);
        assertThat(CardSections.textIn(cell)).isEqualTo("уровень сущности");
        assertThat(CardSections.buttonIn(cell)).isNull();
    }

    // ---------------------------------------------------------------- вспомогательное

    private static boolean showsField(List<EntitySummary.FieldRow> rows, String name) {
        return rows.stream().anyMatch(row -> row.name().equals(name));
    }

    private static EntitySummary.DiagnosticRow diagnosticOf(EntitySummary summary, String code) {
        return summary.diagnostics().stream()
            .filter(row -> row.code().equals(code))
            .findFirst()
            .orElseThrow(() -> new AssertionError("нет диагностики " + code + ", есть: "
                + summary.diagnostics().stream().map(EntitySummary.DiagnosticRow::code).toList()));
    }

    private static MetadataDiagnostic redundantRequired() {
        return new MetadataDiagnostic(MetadataDiagnostic.Severity.INFO,
            MetadataDiagnosticCodes.REDUNDANT_REQUIRED, ReceivingDocument.class.getName(),
            "number", "@FieldMetadata.required",
            "явное REQUIRED совпадает с контрактом записи");
    }

    private static MetadataDiagnostic referenceTargetNotMetadata() {
        return new MetadataDiagnostic(MetadataDiagnostic.Severity.WARNING,
            MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA,
            ReceivingDocumentItem.class.getName(), "nomenclature", "@Lookup / тип ссылки",
            "цель выбора не объявлена как metadata-driven");
    }

    /** Реестр над пакетом платформы: объявления приложения в скан не попали — владелец отказывает. */
    private static RlsDimensionRegistry unscannedRegistry() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("org.ipro.metadata");
        registry.afterPropertiesSet();
        return registry;
    }

    private static EntitySummaryAssembler assembler(List<MetadataDiagnostic> diagnostics,
                                                     RlsDimensionRegistry dimensions) {
        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex references = new ReferenceIndex(BASE_PACKAGE);
        references.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();
        SectionMetadataRegistry sections = new SectionMetadataRegistry(BASE_PACKAGE, metadataResolver);
        sections.afterPropertiesSet();
        MetadataConsistencyStartupCheck startupCheck = mock(MetadataConsistencyStartupCheck.class);
        when(startupCheck.diagnostics()).thenReturn(List.copyOf(diagnostics));
        return new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver, new FormRegistry(),
            references, numbering, subsystems, FacetResolver.none(), null, null, null,
            sections, startupCheck, null, null, null, null, dimensions);
    }
}
