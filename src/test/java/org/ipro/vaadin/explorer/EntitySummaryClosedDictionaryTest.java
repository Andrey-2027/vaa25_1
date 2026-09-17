package org.ipro.vaadin.explorer;

import org.ipro.vaadin.explorer.EntitySummaryAssembler.EntityRef;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FactSource;
import org.ipro.metadata.facet.ResolvedValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Замкнутость словаря {@link EntitySummary} (П1, срез 1 Entity Explorer): в
 * {@link EntitySummary} и вложенных records нет {@code Object payload}, Vaadin-типов,
 * callback'ов ({@code Function}/{@code Supplier}) и строкового SQL — только готовые
 * резолвнутые факты. Проверка — общий {@link ClosedDictionaryVerifier}, рекурсивно
 * по вложенным records.
 */
class EntitySummaryClosedDictionaryTest {

    @Test
    void entitySummaryAndNestedRecordsAreClosedDictionary() {
        ClosedDictionaryVerifier.verify(List.of(
            EntitySummary.class,
            EntitySummary.OverviewRow.class,
            EntitySummary.FieldRow.class,
            EntitySummary.ColumnRow.class,
            EntitySummary.SectionRow.class,
            EntitySummary.FormRow.class,
            EntitySummary.FilterRow.class,
            EntitySummary.SelectionRow.class,
            EntitySummary.ReferenceRow.class,
            EntitySummary.NumberingRow.class,
            EntityRef.class,
            FacetKey.class,
            ResolvedValue.class));
        // Enum'ы словаря проверять нечего, но держим в списке осознанно.
        assertThat(FacetKind.values()).isNotEmpty();
        assertThat(FactSource.values()).isNotEmpty();
    }
}
