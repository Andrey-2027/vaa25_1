package org.ip.vaadin.explorer;

import com.vaadin.flow.component.Component;
import org.ip.model.Nomenclature;
import org.ip.model.NomSklAttribute;
import org.ip.model.PrdSpecMtr;
import org.ip.model.ReceivingDocument;
import org.ip.model.SklNomOpa;
import org.ip.views.admin.CardSections;
import org.ip.views.admin.EntitySummaryPanel;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 4: аспект связи на <b>прикладных</b> типах — замер площадки до строк и пилоты после.
 *
 * <p><b>Что измеряется, а не ожидается.</b> План среза описывал происхождение цели двумя
 * состояниями («явно» и «выведено»), а владелец метаданных различает три и называет их точнее
 * ({@code EXPLICIT} — объявленный {@code @Lookup.entity}, {@code JPA_MAPPING} — тип ассоциации,
 * {@code JAVA_TYPE} — обычный тип Java). Замер показывает, какие ветви в этом приложении есть на
 * самом деле, сколько объявлений цели избыточны и нет ли цели, которой метаданные не знают.
 * Отчёт печатается строками {@code [E3.2.0-4.1]} — он читается без UI.</p>
 *
 * <p><b>Почему на настоящих типах и настоящем сборщике.</b> Строки цели строит тот же
 * {@link EntitySummaryAssembler}, что и карточку, а факты ему отдаёт {@code FieldMetadataInfo}
 * приложения: тест не объявляет ни одной цели сам, поэтому каждая норма — сверка сборщика с
 * владельцем, а не фикстура против самой себя.</p>
 *
 * <p><b>Что закреплено как норма.</b> Цель у поля одна (строки нет ни у не-ссылки, ни у ссылки без
 * цели); происхождение — владельца, не выведенное в UI; символ — место объявления поля; порядок
 * строк задаёт сборщик; примечание об избыточном объявлении появляется ровно там, где объявленная
 * цель совпадает с типом ссылки.</p>
 *
 * <p><b>Раздел карточки.</b> Раздел «Связи» принадлежит карточке (владелец компоновки — шаг 5
 * среза): строки сводки проверяются здесь, а разметка — пилотом 5.4 и
 * {@code EntitySummaryPanelTextTest}; ячейки рендерит клиент, и «текст на экране» подменить нечем.</p>
 */
class LookupTargetFactsIT {

    private static final String BASE_PACKAGE = "org.ip";

    private MetadataResolver metadataResolver;
    private EntitySummaryAssembler assembler;
    private Set<Class<?>> metadataTypes;

    @BeforeEach
    void setUp() {
        metadataResolver = new MetadataResolver();
        ReferenceIndex referenceIndex = new ReferenceIndex(BASE_PACKAGE);
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();
        SectionMetadataRegistry sections = new SectionMetadataRegistry(BASE_PACKAGE, metadataResolver);
        sections.afterPropertiesSet();

        metadataTypes = new LinkedHashSet<>(
            AnnotationClassScanner.scanAnnotated(BASE_PACKAGE, EntityMetadata.class));
        assembler = new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver, new FormRegistry(),
            referenceIndex, numbering, subsystems, FacetResolver.none(), null, null, null,
            sections, null, null, null, null, null, null);
    }

    // ---------------------------------------------------------------- замер

    /**
     * Замер: сколько полей с целью, какие происхождения встречаются, сколько объявлений избыточно,
     * есть ли цель вне метаданных. Числа печатаются и тут же проверяются как инварианты.
     */
    @Test
    void measurementsOfTheApplicationsLookupFacts() {
        Map<FactOrigin, Integer> byOrigin = new TreeMap<>();
        Map<Class<?>, Integer> byTarget = new TreeMap<>((a, b) -> a.getName().compareTo(b.getName()));
        List<String> redundant = new ArrayList<>();
        List<String> notMetadata = new ArrayList<>();
        List<String> variants = new ArrayList<>();
        int rows = 0;
        int entities = 0;

        for (Class<?> entity : metadataTypes) {
            EntitySummary summary = assembler.summarize(entity);
            if (!summary.lookupTargets().isEmpty()) {
                entities++;
            }
            for (EntitySummary.LookupRow row : summary.lookupTargets()) {
                rows++;
                byOrigin.merge(row.value().origin(), 1, Integer::sum);
                byTarget.merge(row.targetType(), 1, Integer::sum);
                if (!row.variant().isBlank()) {
                    variants.add(entity.getSimpleName() + "#" + row.fieldName() + " [" + row.variant() + "]");
                }
                if (!row.note().isBlank()) {
                    redundant.add(entity.getSimpleName() + "#" + row.fieldName());
                }
                if (!metadataTypes.contains(row.targetType())) {
                    notMetadata.add(entity.getSimpleName() + "#" + row.fieldName()
                        + " -> " + row.targetType().getName());
                }
            }
        }

        report("полей с целью: " + rows + " в " + entities + " типах из " + metadataTypes.size());
        report("происхождение: " + byOrigin);
        report("цели: " + byTarget.entrySet().stream()
            .map(e -> e.getKey().getSimpleName() + "×" + e.getValue()).toList());
        report("избыточные объявления цели: " + redundant);
        report("варианты формы выбора: " + variants);
        report("цель вне метаданных: " + notMetadata);

        assertThat(rows)
            .as("в приложении есть связи: замер обязан быть содержательным")
            .isGreaterThan(0);
        assertThat(byOrigin.keySet())
            .as("происхождение цели — словарь владельца; «выведено» и «платформа» здесь не значат ничего")
            .isSubsetOf(Set.of(FactOrigin.EXPLICIT, FactOrigin.JPA_MAPPING, FactOrigin.JAVA_TYPE));
        assertThat(notMetadata)
            .as("цель, которой нет в метаданных, — ошибка старта, а не тихая строка")
            .isEmpty();
        assertThat(variants)
            .as("вариант формы выбора в этом приложении не объявляется: если появится, это замер")
            .isEmpty();
    }

    // ---------------------------------------------------------------- инварианты строк

    @Test
    void everyRowAddressesAFieldOfItsOwnTypeAndNeverAVariantOfTheForm() {
        for (Class<?> entity : metadataTypes) {
            EntitySummary summary = assembler.summarize(entity);
            EntityMetadataInfo meta = metadataResolver.resolve(entity);
            List<String> rowFields = summary.lookupTargets().stream()
                .map(EntitySummary.LookupRow::fieldName).toList();

            assertThat(rowFields)
                .as("порядок строк задаёт сборщик (по имени поля), а не порядок объявлений: %s",
                    entity.getSimpleName())
                .isSorted()
                .as("строка появляется ровно у полей с целью: %s", entity.getSimpleName())
                .hasSameSizeAs(meta.getAllAnnotatedFields().stream()
                    .filter(FieldMetadataInfo::hasLookup).toList())
                .containsExactlyElementsOf(meta.getAllAnnotatedFields().stream()
                    .filter(FieldMetadataInfo::hasLookup).map(FieldMetadataInfo::getName)
                    .sorted().toList());

            for (EntitySummary.LookupRow row : summary.lookupTargets()) {
                FieldMetadataInfo field = meta.getFieldByName(row.fieldName());
                assertThat(field).as("строка без поля — выдумка: %s#%s",
                    entity.getSimpleName(), row.fieldName()).isNotNull();
                assertThat(row.key().kind()).isEqualTo(FacetKind.LOOKUP_TARGET);
                assertThat(row.key().entityClass()).isEqualTo(entity);
                assertThat(row.key().variant())
                    .as("цель не зависит от варианта формы")
                    .isNull();
                assertThat(row.value().origin())
                    .as("происхождение — у владельца, а не выведено сборщиком")
                    .isEqualTo(field.getReferenceOrigin());
                assertThat(row.value().value())
                    .as("цель не переименовывается по дороге")
                    .isEqualTo(field.getLookupEntity().getSimpleName());
                assertThat(row.targetType()).isEqualTo(field.getLookupEntity());
                assertThat(row.variant()).isEqualTo(field.getLookupVariant());
                assertThat(row.value().symbol())
                    .as("символ — место объявления поля: %s#%s", entity.getSimpleName(), row.fieldName())
                    .isEqualTo(field.getField().getDeclaringClass().getName()
                        + "#" + field.getName());
                assertThat(row.value().symbol()).doesNotContain(".java");
            }
        }
    }

    /**
     * Примечание об избыточности стоит ровно там, где объявленная цель совпала с типом ссылки, и
     * нигде больше: иначе «видно» было бы случайным.
     */
    @Test
    void theNoteNamesExactlyTheRedundantDeclarations() {
        Set<String> noted = new TreeSet<>();
        Set<String> measured = new TreeSet<>();

        for (Class<?> entity : metadataTypes) {
            EntitySummary summary = assembler.summarize(entity);
            EntityMetadataInfo meta = metadataResolver.resolve(entity);
            for (EntitySummary.LookupRow row : summary.lookupTargets()) {
                FieldMetadataInfo field = meta.getFieldByName(row.fieldName());
                String address = entity.getSimpleName() + "#" + row.fieldName();
                if (!row.note().isBlank()) {
                    noted.add(address);
                    assertThat(row.note()).contains("избыточно");
                }
                if (field.getReferenceOrigin() == FactOrigin.EXPLICIT
                        && field.getLookupEntity() == field.getField().getType()) {
                    measured.add(address);
                }
            }
        }

        report("избыточные объявления: " + measured);
        assertThat(noted).isEqualTo(measured);
    }

    // ---------------------------------------------------------------- пилоты

    /**
     * Объявленная цель на ассоциации: факт есть, новой информации объявление не несёт.
     *
     * <p>В этом приложении <b>все семь</b> объявленных целей совпадают с типом ссылки (см. замер),
     * поэтому примечание об избыточности — не редкость, а обычное состояние реестра: {@code @Lookup}
     * здесь объявляет сценарий выбора, а не цель.</p>
     */
    @Test
    void catalogRowDeclaresItsTargetExplicitlyAndSaysSo() {
        EntitySummary.LookupRow nomenclature = row(NomSklAttribute.class, "nomenclature");

        assertThat(nomenclature.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(nomenclature.value().value()).isEqualTo(Nomenclature.class.getSimpleName());
        assertThat(nomenclature.targetType()).isEqualTo(Nomenclature.class);
        assertThat(nomenclature.value().symbol())
            .isEqualTo(NomSklAttribute.class.getName() + "#nomenclature");
        assertThat(nomenclature.note()).contains("избыточно");
    }

    /**
     * Найденное замером: у поля может быть цель, но не быть самого поля в реестре.
     *
     * <p>{@code SklNomOpa.nomenclature} объявляет {@code @Lookup}, но не объявляет
     * {@code @FieldMetadata}, и в реестр полей не попадает — значит, у карточки нет ни строки поля,
     * ни строки цели. Это факт приложения, а не потеря аспекта: связь есть, а показать её нечем,
     * потому что поле не показано вовсе.</p>
     */
    @Test
    void aLookupWithoutFieldMetadataIsNotAFieldAndSoHasNoRow() throws Exception {
        assertThat(SklNomOpa.class.getDeclaredField("nomenclature").getAnnotation(Lookup.class))
            .as("поле действительно объявляет цель")
            .isNotNull();
        assertThat(metadataResolver.resolve(SklNomOpa.class).getFieldByName("nomenclature"))
            .as("но без @FieldMetadata оно в реестр полей не входит")
            .isNull();
        assertThat(assembler.summarize(SklNomOpa.class).lookupTargets())
            .as("нет поля — нет и строки цели: аспект не показывает то, чего карточка не показывает")
            .isEmpty();
    }

    /** Цель из типа ассоциации: {@code @Lookup} объявляет только сценарий выбора. */
    @Test
    void documentRowDerivesItsTargetFromTheAssociationType() {
        EntitySummary.LookupRow prdSpec = row(PrdSpecMtr.class, "prdSpecMtr");
        EntitySummary.LookupRow nomenclature = row(PrdSpecMtr.class, "nomenclature");

        assertThat(prdSpec.value().origin()).isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(prdSpec.targetType()).isEqualTo(org.ip.model.PrdSpec.class);
        assertThat(prdSpec.note())
            .as("выведенная цель не выдаётся за объявленную")
            .isEmpty();
        assertThat(nomenclature.value().origin()).isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(nomenclature.targetType()).isEqualTo(Nomenclature.class);
    }

    /** Поля документа с ссылками видны как строки, и их цель — объявленная. */
    @Test
    void documentReferencesAppearWithTheirDeclaredTargets() {
        EntitySummary summary = assembler.summarize(ReceivingDocument.class);
        List<String> fields = summary.lookupTargets().stream()
            .map(EntitySummary.LookupRow::fieldName).toList();

        report("ReceivingDocument: " + fields);
        assertThat(fields).contains("journal");
        assertThat(summary.lookupTargets())
            .filteredOn(row -> "journal".equals(row.fieldName()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
                assertThat(row.value().value()).isEqualTo("Journal");
            });
    }

    /** Не ссылка — строки нет: у справочника такого поля, как код, связи не бывает. */
    @Test
    void aPlainFieldOfACatalogGetsNoRow() {
        EntitySummary summary = assembler.summarize(Nomenclature.class);

        assertThat(summary.lookupTargets().stream().map(EntitySummary.LookupRow::fieldName))
            .doesNotContain("code");
    }

    // ---------------------------------------------------------------- вспомогательное

    private EntitySummary.LookupRow row(Class<?> entity, String field) {
        return assembler.summarize(entity).lookupTargets().stream()
            .filter(candidate -> field.equals(candidate.fieldName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("нет строки цели " + entity.getSimpleName()
                + "#" + field + ", строки: " + assembler.summarize(entity).lookupTargets().stream()
                    .map(EntitySummary.LookupRow::fieldName).toList()));
    }

    /**
     * E3.2.0 шаг 5.4: сводка приложения доходит до карточки — у поля с объявленной целью раздел
     * «Связи» изображён, а у ссылки без метаданных строки нет и раздела тоже (связь есть, но
     * факта для карточки нет).
     */
    @Test
    void theCardDrawsTheLookupSectionForTheDeclaredTargetPilot() {
        EntitySummaryPanel declared = new EntitySummaryPanel(null);
        declared.show(assembler.summarize(NomSklAttribute.class));
        assertThat(sectionTitles(declared)).contains("Связи");

        EntitySummaryPanel notAField = new EntitySummaryPanel(null);
        notAField.show(assembler.summarize(SklNomOpa.class));
        assertThat(sectionTitles(notAField))
            .as("связь есть, но @FieldMetadata нет: факта для раздела нет и раздел не рисуется")
            .doesNotContain("Связи");
    }

    private static List<String> sectionTitles(Component root) {
        return CardSections.titles(root);
    }

    private static void report(String line) {
        System.out.println("[E3.2.0-4.1] " + line);
    }
}
