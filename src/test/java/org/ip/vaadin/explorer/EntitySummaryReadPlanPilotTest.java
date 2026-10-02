package org.ip.vaadin.explorer;

import com.vaadin.flow.component.Component;
import org.ip.config.EntityClassificationConfig;
import org.ip.model.AttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.FetchPlanInspection;
import org.ipro.fetch.ManagedEntityTypes;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
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
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ip.views.admin.CardSections;
import org.ip.views.admin.EntitySummaryPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.0 шаг 2.5: сценарии чтения на <b>прикладных</b> типах — то, что карточка типа показывает про
 * план чтения этого приложения, а не про модельные ветви.
 *
 * <p><b>Почему на настоящих политиках.</b> Набор сценариев берётся у
 * {@link EntityClassificationConfig}: если бы тест объявлял его сам, он проверял бы свою фикстуру,
 * а не приложение. План — у настоящего {@link FetchPlanRegistry} поверх настоящей metadata
 * приложения, поэтому причины путей здесь те, которые увидит пользователь.</p>
 *
 * <p><b>Что закреплено.</b> У {@code AttributeValue} и {@code SklNomOpa} набор сценариев объявлен
 * приложением (даже когда совпадает с правилом), у {@code Nomenclature} — выведен правилом, а
 * причина пути выбора приходит объявлением {@code @Lookup}. Ветви модели (отказ пути при непустом
 * плане, сценарий без путей) проверены платформенным {@code EntitySummaryReadPlanRowsTest} на
 * пробах, здесь дублировать их нечем.</p>
 *
 * <p><b>Раздел карточки.</b> Секции аспектов принадлежат карточке (владелец компоновки — шаг 5
 * среза): строки сводки проверяются здесь, а разметка — пилотом 5.2 и
 * {@code EntitySummaryPanelTextTest}; ячейки рендерит клиент, и «текст на экране» подменить нечем.</p>
 */
class EntitySummaryReadPlanPilotTest {

    private static final String BASE_PACKAGE = "org.ip";

    private final EntityClassificationConfig classification = new EntityClassificationConfig();

    private EntityDescriptorCatalog descriptors;
    private FetchPlanInspection inspection;
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
        // Как в настоящем persistence unit: owned-строка — JPA-сущность без @EntityMetadata,
        // поэтому сканом аннотаций она не находится. Без неё в наборе управляемых типов её
        // политика (OWNED_ROW) не применялась бы, и замер расходился бы с приложением.
        managedTypes.add(SklNomOpaValue.class);
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(managedTypes);

        descriptors = new EntityDescriptorCatalog(managed, sections, metadataResolver,
            List.of(classification.sklNomOpaValueIsAnOwnedRow()),
            List.of(classification.attributeValueIsCreateOnly(),
                classification.sklNomOpaHasNoGenericWrites()));

        ManagedEntityTypes types = new ManagedEntityTypes(managed);
        FetchPlanRegistry plans = new FetchPlanRegistry(types, metadataResolver,
            new InstanceNameResolver(types.all(), metadataResolver));
        inspection = new FetchPlanInspection(plans, descriptors);

        assembler = new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver, new FormRegistry(),
            referenceIndex, numbering, subsystems, FacetResolver.none(), descriptors, null, null,
            sections, null, null, null, null, inspection);
    }

    // ---------------------------------------------------------------- пилоты

    /** Набор объявлен приложением — и это видно, хотя состав совпадает с правилом. */
    @Test
    void declaredScenarioSetIsShownAsTheApplicationPolicy() {
        for (Class<?> type : List.of(AttributeValue.class, SklNomOpa.class)) {
            List<EntitySummary.ReadPlanRow> rows = rows(type);

            assertThat(rows).extracting(EntitySummary.ReadPlanRow::scenario)
                .as("%s: три сценария правила, состав объявлен приложением", type.getSimpleName())
                .contains("LIST", "DETAIL", "LOOKUP");
            for (EntitySummary.ReadPlanRow row : rows) {
                assertThat(row.value().origin())
                    .as("%s/%s", type.getSimpleName(), row.scenario())
                    .isEqualTo(FactOrigin.REGISTRATION);
                assertThat(row.value().symbol())
                    .as("место объявления в ядре не разрешается — названная граница шага 2")
                    .isEmpty();
            }
            assertThat(rows.stream().map(EntitySummary.ReadPlanRow::note).toList())
                .allSatisfy(note -> assertThat(note)
                    .startsWith("набор сценариев объявлен приложением"));
        }

        assertThat(rows(AttributeValue.class).stream().map(EntitySummary.ReadPlanRow::note).toList())
            .as("причина берётся у политики типа, а не пересказывается карточкой")
            .allSatisfy(note -> assertThat(note).contains("бессмертно"));
    }

    /** Допущенный сценарий с пустым планом: строка есть, путей нет — это факт, а не ошибка. */
    @Test
    void allowedScenarioWithoutPathsIsShownWithZeroPaths() {
        EntitySummary.ReadPlanRow list = row(SklNomOpa.class, "LIST");

        assertThat(list.allowed()).isTrue();
        assertThat(list.pathCount()).isZero();
        assertThat(list.paths()).isEmpty();
        assertThat(list.note())
            .as("отсутствие путей примечанием не объясняется: это видно счётчиком")
            .doesNotContain("canonical path");
    }

    /** Правило экспозиции: набор платформенный, а причина пути выбора приходит объявлением. */
    @Test
    void exposureRuleIsNamedAndLookupReasonComesFromTheDeclaration() {
        List<EntitySummary.ReadPlanRow> rows = rows(Nomenclature.class);

        assertThat(rows).filteredOn(EntitySummary.ReadPlanRow::allowed)
            .allSatisfy(row -> assertThat(row.value().origin())
                .isEqualTo(FactOrigin.PLATFORM_DEFAULT));
        assertThat(rows).filteredOn(EntitySummary.ReadPlanRow::allowed)
            .allSatisfy(row -> assertThat(row.note())
                .isEqualTo("набор сценариев выведен из экспозиции типа (STANDARD_ROOT)"));

        EntitySummary.ReadPlanRow lookup = row(Nomenclature.class, "LOOKUP");
        assertThat(lookup.paths()).extracting(EntitySummary.PathRow::reason)
            .as("причина пути — из плана, с именем владельца объявления")
            .containsExactly("lookup:PrdSpecMtr.nomenclature");
    }

    /** Owned-строка — не предмет карточки: у неё нет metadata, и сводка её не строит. */
    @Test
    void ownedRowIsNotASubjectOfTheCard() {
        assertThat(inspection.scenariosOf(SklNomOpaValue.class))
            .as("факт существует: сценарий ROW приходит от правила экспозиции")
            .singleElement()
            .satisfies(row -> {
                assertThat(row.scenario()).isEqualTo("ROW");
                assertThat(row.allowed()).isTrue();
                assertThat(row.origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
            });

        assertThatThrownBy(() -> assembler.summarize(SklNomOpaValue.class))
            .as("карточка строится по @EntityMetadata-типам: owned-строка в неё не попадает")
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * E3.2.0 шаг 5.2: сводка приложения доходит до карточки — у объявленного набора сценариев
     * изображены оба раздела (сценарии и их пути), а аспект, владельца факта у которого нет, не
     * изображается.
     */
    @Test
    void theCardDrawsBothReadPlanSectionsForTheDeclaredScenarioSet() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(assembler.summarize(AttributeValue.class));

        assertThat(sectionTitles(panel))
            .as("три сценария объявленного набора и их пути доходят до карточки")
            .contains("Сценарии чтения", "Сценарии чтения — пути");
        assertThat(sectionTitles(panel))
            .as("аспекты без владельца факта разделов не рисуют")
            .doesNotContain("Доступ");
    }

    /** Ключ и счётчик: у каждой строки свой адрес, у каждого сценария — свой план. */
    @Test
    void everyRowKeepsItsKeyAndItsPathCount() {
        for (Class<?> type : List.of(AttributeValue.class, SklNomOpa.class, Nomenclature.class)) {
            for (EntitySummary.ReadPlanRow row : rows(type)) {
                assertThat(row.key().kind()).isEqualTo(FacetKind.FETCH_PLAN);
                assertThat(row.key().entityClass()).isEqualTo(type);
                assertThat(row.key().fieldName()).isEqualTo(row.scenario());
                assertThat(row.key().variant()).isNull();
                assertThat(row.pathCount()).isEqualTo(row.paths().size());
                for (EntitySummary.PathRow path : row.paths()) {
                    assertThat(path.key().kind()).isEqualTo(FacetKind.FETCH_PLAN_PATH);
                    assertThat(path.key().fieldName()).isEqualTo(row.scenario() + "/" + path.attributePath());
                    assertThat(path.value().origin()).isEqualTo(FactOrigin.DERIVED);
                    assertThat(path.reason()).isNotBlank();
                }
            }
        }
    }

    // ---------------------------------------------------------------- вспомогательное

    private List<EntitySummary.ReadPlanRow> rows(Class<?> type) {
        return assembler.summarize(type).readPlans();
    }

    private EntitySummary.ReadPlanRow row(Class<?> type, String scenario) {
        return rows(type).stream()
            .filter(candidate -> scenario.equals(candidate.scenario()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("у " + type.getSimpleName()
                + " нет строки сценария " + scenario));
    }

    private static List<String> sectionTitles(Component root) {
        return CardSections.titles(root);
    }
}
