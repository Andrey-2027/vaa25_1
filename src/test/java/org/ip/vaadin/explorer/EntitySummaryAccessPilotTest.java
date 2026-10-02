package org.ip.vaadin.explorer;

import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.SklNomOpa;
import com.vaadin.flow.component.Component;
import org.ip.model.Workshop;
import org.ip.views.admin.CardSections;
import org.ip.views.admin.EntitySummaryPanel;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.0 шаг 3.4: аспект доступа на <b>прикладных</b> типах — то, что карточка типа показывает про
 * RLS этого приложения, а не про модельные ветви.
 *
 * <p><b>Почему на настоящем реестре.</b> Измерения, правила, роды и каталог грантов берутся у
 * {@link RlsDimensionRegistry}, собранного на пакете приложения: тест не объявляет их сам, а значит
 * проверяет приложение, а не свою фикстуру. Заодно это единственный способ увидеть, что аспект не
 * пересказывает конфигурацию, а берёт её у владельца.</p>
 *
 * <p><b>Что закреплено.</b> Одно измерение живёт на нескольких типах с разными правилами (BRANCH —
 * Branch/ReceivingDocument/Workshop, JOURNAL — Journal/PrdSpec/ReceivingDocument), каталог грантов
 * есть только у фильтруемых измерений, а сложная политика документа не публикует дефолтный путь.
 * Ветви модели проверены платформенным {@code EntitySummaryAccessRowsTest} на пробах — здесь
 * дублировать их нечем.</p>
 *
 * <p><b>Раздел карточки.</b> Разделы доступа принадлежат карточке (владелец компоновки — шаг 5
 * среза): строки сводки проверяются здесь, а разметка — пилотом 5.3 и
 * {@code EntitySummaryPanelTextTest}; ячейки рендерит клиент, и «текст на экране» подменить нечем.</p>
 */
class EntitySummaryAccessPilotTest {

    private static final String BASE_PACKAGE = "org.ip";

    private RlsDimensionRegistry dimensions;
    private EntitySummaryAssembler assembler;

    @BeforeEach
    void setUp() {
        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex referenceIndex = new ReferenceIndex(BASE_PACKAGE);
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();
        SectionMetadataRegistry sections =
            new SectionMetadataRegistry(BASE_PACKAGE, metadataResolver);
        sections.afterPropertiesSet();

        Set<Class<?>> managedTypes = new LinkedHashSet<>(
            AnnotationClassScanner.scanAnnotated(BASE_PACKAGE, EntityMetadata.class));
        sections.all().forEach(section -> managedTypes.add(section.getRowClass()));
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(managedTypes);

        dimensions = new RlsDimensionRegistry(BASE_PACKAGE);
        dimensions.afterPropertiesSet();

        assembler = new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver, new FormRegistry(),
            referenceIndex, numbering, subsystems, FacetResolver.none(), null, null, null,
            sections, null, null, null, null, null, dimensions);
    }

    // ---------------------------------------------------------------- пилоты

    /** Справочник: одно измерение, правило по своей записи и каталог значений грантов. */
    @Test
    void branchTakesItsSingleDimensionWithTheGrantCatalogue() {
        EntitySummary.AccessRow branch = row(Branch.class, "BRANCH");

        assertThat(branch.key().kind()).isEqualTo(FacetKind.RLS_DIMENSION);
        assertThat(branch.key().entityClass()).isEqualTo(Branch.class);
        assertThat(branch.key().fieldName()).isEqualTo("BRANCH");
        assertThat(branch.key().variant()).isNull();
        assertThat(branch.dimensionKind()).isEqualTo(RlsDimensionKind.FILTERABLE);
        assertThat(branch.grantCatalog())
            .as("измерение объявлено источником значений грантов — факт объявления, не список")
            .isTrue();
        assertThat(branch.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(branch.value().symbol()).isEqualTo(Branch.class.getName());
        assertThat(branch.note()).contains("фильтруется").contains("источником значений грантов");

        assertThat(branch.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.key().kind()).isEqualTo(FacetKind.RLS_VALUE_RULE);
            assertThat(rule.key().fieldName()).isEqualTo("BRANCH/id");
            assertThat(rule.path()).isEqualTo("id");
            assertThat(rule.nullsNotApplicable()).isFalse();
        });

        assertThat(dimensions.grantValueType("BRANCH")).isEqualTo(Branch.class);
    }

    /**
     * Одно измерение — несколько объявлений: у журнала и спецификации правило разное, и это разные
     * факты с разными адресами. Каталогом грантов служит только сам журнал.
     */
    @Test
    void journalAndSpecificationShareTheDimensionWithDifferentRules() {
        EntitySummary.AccessRow journal = row(Journal.class, "JOURNAL");
        EntitySummary.AccessRow spec = row(PrdSpec.class, "JOURNAL");

        assertThat(journal.key().fieldName()).isEqualTo("JOURNAL");
        assertThat(spec.key().fieldName()).isEqualTo("JOURNAL");
        assertThat(journal.grantCatalog()).isTrue();
        assertThat(spec.grantCatalog())
            .as("флаг отвечает за ИЗМЕРЕНИЕ, а не за тип: каталог значений грантов у JOURNAL один,"
                + " и его корень — журнал; место объявления правила при этом своё")
            .isTrue();
        assertThat(dimensions.grantValueType("JOURNAL"))
            .as("корень каталога грантов — объявивший его тип, а не каждый объявивший измерение")
            .isEqualTo(Journal.class);
        assertThat(journal.value().symbol()).isEqualTo(Journal.class.getName());
        assertThat(spec.value().symbol()).isEqualTo(PrdSpec.class.getName());

        assertThat(journal.rules()).singleElement()
            .satisfies(rule -> assertThat(rule.path()).isEqualTo("id"));
        assertThat(spec.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.key().fieldName()).isEqualTo("JOURNAL/journal.id");
            assertThat(rule.path())
                .as("правило читает журнал через ассоциацию, а не id самой записи")
                .isEqualTo("journal.id");
        });
    }

    /** Цех: правило читает филиал, и null в нём означает «измерение к записи не применимо». */
    @Test
    void workshopRuleDeclaresThatNullMeansNotApplicable() {
        EntitySummary.AccessRow workshop = row(Workshop.class, "BRANCH");

        assertThat(workshop.dimensionKind()).isEqualTo(RlsDimensionKind.FILTERABLE);
        assertThat(workshop.grantCatalog())
            .as("BRANCH — измерение с каталогом грантов, даже когда правило объявил не справочник")
            .isTrue();
        assertThat(workshop.value().symbol())
            .as("место объявления правила — сам цех, а не корень каталога грантов")
            .isEqualTo(Workshop.class.getName());
        assertThat(workshop.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.path()).isEqualTo("branch.id");
            assertThat(rule.nullsNotApplicable()).isTrue();
        });
    }

    /**
     * Накладная: три измерения, все — сложная политика, включая ворота; ни одного правила значения
     * в карточке нет, хотя в записи правил дефолт {@code id} присутствует.
     */
    @Test
    void receivingDocumentShowsThreeCustomDimensionsAndPublishesNoRuleRows() {
        List<EntitySummary.AccessRow> rows = rows(ReceivingDocument.class);

        assertThat(rows).extracting(EntitySummary.AccessRow::dimension)
            .as("порядок строк задаёт сборщик: владелец порядок не гарантирует")
            .containsExactly("BRANCH", "ENTITY:ReceivingDocument", "JOURNAL");
        assertThat(rows).extracting(EntitySummary.AccessRow::dimensionKind)
            .containsExactly(RlsDimensionKind.FILTERABLE, RlsDimensionKind.CHECK_ONLY,
                RlsDimensionKind.FILTERABLE);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.note()).contains("сложная политика");
            assertThat(row.rules())
                .as("%s: записанный дефолт пути не публикуется как правило", row.dimension())
                .isEmpty();
        });
        assertThat(row(ReceivingDocument.class, "ENTITY:ReceivingDocument").note())
            .as("ворота отличаются от фильтров текстом, а не отсутствием строки")
            .contains("проверяется, не фильтруется");

        assertThat(dimensions.policyOf(ReceivingDocument.class).valueRules().values())
            .as("но владелец всё равно хранит дефолт — поэтому его и нельзя показывать")
            .allSatisfy(rule -> {
                assertThat(rule.custom()).isTrue();
                assertThat(rule.paths()).containsExactly("id");
            });
    }

    /** Тип без объявлений в RLS: строк нет — «не участвует» и «регистрация сломана» различимы. */
    @Test
    void typesWithoutDeclarationsHaveNoAccessRows() {
        for (Class<?> type : List.of(Nomenclature.class, SklNomOpa.class)) {
            assertThat(rows(type)).as("%s: измерений нет", type.getSimpleName()).isEmpty();
            assertThat(dimensions.dimensionsOf(type)).isEmpty();
        }
        assertThat(summary(Nomenclature.class).diagnostics())
            .as("и диагностики регистрации тоже нет: отказ потребовал бы объявления")
            .noneMatch(diagnostic -> "RLS_SCAN".equals(diagnostic.code()));
    }

    /** Числовое согласие с владельцем: строк столько, сколько измерений и заявленных путей. */
    @Test
    void rowCountsMatchTheOwnerAccountsForEveryPilot() {
        for (Class<?> type : List.of(Branch.class, Journal.class, PrdSpec.class, Workshop.class,
                ReceivingDocument.class, Nomenclature.class)) {
            List<EntitySummary.AccessRow> rows = rows(type);
            assertThat(rows).extracting(EntitySummary.AccessRow::dimension)
                .as("%s: одна строка на измерение и без выдуманных", type.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(dimensions.dimensionsOf(type));
            assertThat(rows.stream().mapToInt(row -> row.rules().size()).sum())
                .as("%s: строк правил — ровно по объявленным путям стандартных измерений",
                    type.getSimpleName())
                .isEqualTo(dimensions.policyOf(type).valueRules().values().stream()
                    .filter(rule -> !rule.custom())
                    .mapToInt(rule -> rule.paths().size()).sum());
            assertThat(rows(type)).as("%s: два прохода — одна последовательность",
                type.getSimpleName())
                .extracting(EntitySummary.AccessRow::dimension)
                .containsExactlyElementsOf(rows.stream()
                    .map(EntitySummary.AccessRow::dimension).toList());
        }
    }

    /**
     * Гранты и решения по записи в модель не просачиваются: строки строятся по классу, без
     * экземпляра, а единственный тип владельца, который им разрешён, — словарь рода. Это
     * структурная проверка, а не обещание текста.
     */
    @Test
    void grantValuesAndRecordDecisionsNeverEnterTheModel() {
        for (Class<?> record : List.of(EntitySummary.AccessRow.class,
                EntitySummary.AccessRuleRow.class)) {
            for (RecordComponent component : record.getRecordComponents()) {
                assertThat(component.getType())
                    .as("%s.%s", record.getSimpleName(), component.getName())
                    .isNotIn(AccessGrant.class, RlsCheckValue.class, RlsPolicyDescriptor.class,
                        RlsDimensionRegistry.class);
            }
        }

        assertThat(rows(ReceivingDocument.class))
            .as("значение строки — имя измерения, а не набор идентификаторов записи")
            .allSatisfy(row -> assertThat(row.value().value()).isEqualTo(row.dimension()));
    }

    /**
     * E3.2.0 шаг 5.3: сводка приложения доходит до карточки — у цеха (измерение с правилом) видны
     * оба раздела доступа, а у типа без объявлений — ни одного.
     */
    @Test
    void theCardDrawsBothAccessSectionsForTheWorkshopPilot() {
        EntitySummaryPanel workshop = new EntitySummaryPanel(null);
        workshop.show(summary(Workshop.class));
        assertThat(sectionTitles(workshop))
            .as("измерение и его правило значения — два раздела")
            .contains("Доступ", "Доступ — правила значений");
        assertThat(sectionTitles(workshop))
            .as("аспекты без владельца факта разделов не рисуют")
            .doesNotContain("Действия", "Сценарии чтения");

        EntitySummaryPanel noDeclarations = new EntitySummaryPanel(null);
        noDeclarations.show(summary(Nomenclature.class));
        assertThat(sectionTitles(noDeclarations))
            .as("тип без объявлений не изображает аспект разделом")
            .doesNotContain("Доступ");
    }

    /**
     * Сложная политика накладной: измерения на карточке есть, а раздела правил нет — записанный
     * дефолт пути там не действует, и карточка не выдаёт его за правило фильтрации.
     */
    @Test
    void theCardShowsCustomPolicyDimensionsWithoutInventingRuleRows() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summary(ReceivingDocument.class));

        assertThat(sectionTitles(panel))
            .as("три измерения и ворота среди них")
            .contains("Доступ");
        assertThat(sectionTitles(panel))
            .as("у сложной политики правил нет — раздел не рисуется, а не рисуется пустым")
            .doesNotContain("Доступ — правила значений");
    }

    // ---------------------------------------------------------------- вспомогательное

    private List<EntitySummary.AccessRow> rows(Class<?> type) {
        return summary(type).accessRows();
    }

    private static List<String> sectionTitles(Component root) {
        return CardSections.titles(root);
    }

    private EntitySummary summary(Class<?> type) {
        return assembler.summarize(type);
    }

    private EntitySummary.AccessRow row(Class<?> type, String dimension) {
        return rows(type).stream()
            .filter(candidate -> dimension.equals(candidate.dimension()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("у " + type.getSimpleName()
                + " нет строки измерения " + dimension));
    }
}
