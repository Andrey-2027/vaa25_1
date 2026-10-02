package org.ipro.vaadin.explorer;

import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.vaadin.explorer.lookupfixture.LookupProbe;
import org.ipro.vaadin.explorer.lookupfixture.LookupTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 4.2: строки грани {@link FacetKind#LOOKUP_TARGET} — куда ведёт ссылка, откуда это
 * известно и где объявлено.
 *
 * <p><b>Цель и происхождение берутся у владельца.</b> Проба — настоящий JPA-тип, и её факты
 * считает {@code FieldMetadataInfo}: ожидания теста построены не на «так задумано», а на том же
 * источнике, что читает сборщик. Проверяется не значение ради значения, а то, что сборщик не
 * подменяет источник ({@code JPA_MAPPING}), не выдумывает цель и не теряет символ места.</p>
 *
 * <p><b>Прикладные типы здесь не участвуют.</b> Пилоты на {@code PrdSpecMtr}, {@code SklNomOpa} и
 * {@code ReceivingDocument} — предмет прикладного замера: платформенный модуль не знает
 * {@code org.ip}, и тест, знающий приложение, был бы проверкой приложения внутри платформы.</p>
 *
 * <p><b>Что нормируется.</b> Цель у поля одна (в строке поля её больше нет); цель без объявления
 * остаётся фактом с происхождением «JPA-маппинг»; поле без цели строки не получает; порядок строк
 * задаёт сборщик, а не владелец.</p>
 */
class EntitySummaryLookupRowsTest {

    private static final String BASE_PACKAGE = "org.ipro.vaadin.explorer.lookupfixture";

    @Test
    void declaredTargetIsExplicitAndRedundancyIsNamedInTheNote() {
        EntitySummary.LookupRow declared = row("declared");

        assertThat(declared.key().kind())
            .as("цель выбора — своя грань, а не поле: у неё своё происхождение")
            .isEqualTo(FacetKind.LOOKUP_TARGET);
        assertThat(declared.key().entityClass()).isEqualTo(LookupProbe.class);
        assertThat(declared.key().variant())
            .as("цель не зависит от варианта формы — вариант принадлежит строке")
            .isNull();
        assertThat(declared.value().value()).isEqualTo("LookupTarget");
        assertThat(declared.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(declared.value().symbol())
            .as("символ — место объявления поля, а не конфиг-класс и не класс цели")
            .isEqualTo(LookupProbe.class.getName() + "#declared");
        assertThat(declared.targetType())
            .as("класс цели — факт, по нему карточка открывает структуру")
            .isEqualTo(LookupTarget.class);
        assertThat(declared.note())
            .as("объявление цели, совпавшей с типом ссылки, — видимый факт, а не молчание")
            .contains("объявление избыточно");
    }

    @Test
    void derivedTargetKeepsItsOwnOriginAndIsNotDressedAsDeclared() {
        EntitySummary.LookupRow inferred = row("inferred");

        assertThat(inferred.value().value()).isEqualTo("LookupTarget");
        assertThat(inferred.value().origin())
            .as("цель выведена из типа ассоциации — это не то же самое, что объявленная")
            .isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(inferred.value().symbol()).isEqualTo(LookupProbe.class.getName() + "#inferred");
        assertThat(inferred.note()).isEmpty();
    }

    @Test
    void variantIsPartOfTheFactAndNotATextOfTheTarget() {
        EntitySummary.LookupRow variant = row("variantTarget");

        assertThat(variant.variant()).isEqualTo("probe");
        assertThat(variant.value().value())
            .as("вариант не приклеен к имени цели: это разные признаки")
            .isEqualTo("LookupTarget");
        assertThat(variant.targetType()).isEqualTo(LookupTarget.class);
    }

    @Test
    void aFieldWithoutATargetGetsNoRowAndFieldsWithOneGetExactlyOne() {
        EntitySummary summary = summarize(LookupProbe.class);
        List<String> fields = summary.lookupTargets().stream()
            .map(EntitySummary.LookupRow::fieldName).toList();

        assertThat(fields)
            .as("строки нет не только у не-ссылки, но и у ссылки без цели: «?» владелец не сообщает")
            .containsExactly("declared", "inferred", "variantTarget");
        assertThat(fields).doesNotContain("plainText");
        assertThat(fields)
            .as("порядок строк задаёт сборщик, а не order объявлений")
            .isSorted();
    }

    /** Инвариант «ни одной выдуманной и ни одной потерянной строки» — сверка с владельцем. */
    @Test
    void rowCountEqualsTheNumberOfFieldsTheOwnerReportsAsReferences() {
        EntityMetadataInfo meta = new MetadataResolver().resolve(LookupProbe.class);
        long withTarget = meta.getAllAnnotatedFields().stream()
            .filter(FieldMetadataInfo::hasLookup).count();

        assertThat(withTarget).isEqualTo(3);
        assertThat(summarize(LookupProbe.class).lookupTargets()).hasSize((int) withTarget);
    }

    /**
     * Второго представления цели нет. Строка поля больше не несёт ни текста цели, ни её класса:
     * иначе карточка могла бы показать цель без происхождения, а два источника одного факта
     * разошлись бы молча.
     */
    @Test
    void fieldRowCarriesNoLookupComponents() {
        List<String> components = Arrays.stream(EntitySummary.FieldRow.class.getRecordComponents())
            .map(RecordComponent::getName).toList();

        assertThat(components).doesNotContain("lookupTarget", "lookupEntityClass");
        assertThat(components).containsExactly("key", "name", "typeLabel", "value",
            "required", "readOnly", "requiredOrigin", "typeOrigin");
    }

    /** Компоненты строки цели: значение с происхождением, класс цели, вариант и примечание. */
    @Test
    void lookupRowExposesTheFactAndNothingFromTheRecordsValues() {
        List<String> components = Arrays.stream(EntitySummary.LookupRow.class.getRecordComponents())
            .map(RecordComponent::getName).toList();

        assertThat(components).containsExactly("key", "fieldName", "value", "targetType",
            "variant", "note");
    }

    // === Вспомогательное ===

    private static EntitySummary.LookupRow row(String fieldName) {
        return summarize(LookupProbe.class).lookupTargets().stream()
            .filter(candidate -> fieldName.equals(candidate.fieldName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("нет строки цели для поля " + fieldName));
    }

    private static EntitySummary summarize(Class<?> entityClass) {
        MetadataResolver resolver = new MetadataResolver();
        SectionMetadataRegistry sections = new SectionMetadataRegistry(BASE_PACKAGE, resolver);
        sections.afterPropertiesSet();
        ReferenceIndex referenceIndex = new ReferenceIndex(BASE_PACKAGE);
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();

        return new EntitySummaryAssembler(BASE_PACKAGE, resolver, new FormRegistry(), referenceIndex,
            numbering, subsystems, FacetResolver.none(), null, null, null, sections,
            null, null, null, null, null, null).summarize(entityClass);
    }
}
