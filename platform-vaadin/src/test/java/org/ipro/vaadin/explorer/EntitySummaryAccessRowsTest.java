package org.ipro.vaadin.explorer;

import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.vaadin.explorer.rlsfixture.GateMarker;
import org.ipro.vaadin.explorer.rlsfixture.GrantProbe;
import org.ipro.vaadin.explorer.rlsfixture.NullableProbe;
import org.ipro.vaadin.explorer.rlsfixture.PlainProbe;
import org.ipro.vaadin.explorer.rlsfixture.PolicyProbe;
import org.ipro.vaadin.explorer.rlsunscanned.UnscannedProbe;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3.2.0 шаг 3.3: строки аспекта доступа в сводке — измерение, его род, правило значения, каталог
 * грантов и происхождение.
 *
 * <p><b>Почему на настоящем реестре.</b> Пробы — реальные JPA-типы с настоящими {@code @Filter}/
 * {@code @FilterDef}: реестр проходит на них те же fail-fast контракты, что на боевых типах
 * (FILTERABLE ⇔ фильтр с тем же именем, CHECK_ONLY ⇔ фильтра нет, read/write parity для сложной
 * политики). Мок реестра проверил бы сборку строк на мире, которого не бывает.</p>
 *
 * <p><b>Почему не на типах приложения.</b> Прикладные пилоты ({@code Branch}, {@code ReceivingDocument},
 * {@code PrdSpec}) — предмет шага 3.4. Платформенный модуль не знает {@code org.ip}, и тест,
 * знающий приложение, был бы проверкой приложения внутри платформы.</p>
 *
 * <p><b>Что здесь нормируется.</b> Записанный у сложной политики дефолт {@code valuePaths} не
 * публикуется как правило; порядок строк задаёт сборщик, а не владелец; отказ регистрации даёт
 * диагностику, а не пустую секцию и не падение.</p>
 */
class EntitySummaryAccessRowsTest {

    /** Пакет проб: тот же корень, что и у скана измерений, — иначе пробы были бы незарегистрированы. */
    private static final String BASE_PACKAGE = "org.ipro.vaadin.explorer.rlsfixture";

    @Test
    void declaredDimensionCarriesItsKindRuleGrantCatalogAndOrigin() {
        EntitySummary.AccessRow branch = row(summarize(GrantProbe.class), "PROBE_BRANCH");

        assertThat(branch.key().kind()).isEqualTo(FacetKind.RLS_DIMENSION);
        assertThat(branch.key().entityClass()).isEqualTo(GrantProbe.class);
        assertThat(branch.key().fieldName()).isEqualTo("PROBE_BRANCH");
        assertThat(branch.key().variant())
            .as("измерение не зависит от варианта формы — в отличие от действий")
            .isNull();
        assertThat(branch.dimensionKind()).isEqualTo(RlsDimensionKind.FILTERABLE);
        assertThat(branch.grantCatalog())
            .as("каталог грантов — факт объявления, а не список значений")
            .isTrue();
        assertThat(branch.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(branch.value().symbol())
            .as("символ — класс-носитель, полученный у владельца, а не имя конфиг-класса")
            .isEqualTo(GrantProbe.class.getName());
        assertThat(branch.note()).contains("фильтруется").contains("источником значений грантов");

        assertThat(branch.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.key().kind()).isEqualTo(FacetKind.RLS_VALUE_RULE);
            assertThat(rule.key().fieldName())
                .as("измерение входит в адрес правила: одно имя объявляют разные типы")
                .isEqualTo("PROBE_BRANCH/id");
            assertThat(rule.key().variant()).isNull();
            assertThat(rule.path()).isEqualTo("id");
            assertThat(rule.nullsNotApplicable()).isFalse();
            assertThat(rule.value().origin()).isEqualTo(FactOrigin.EXPLICIT);
            assertThat(rule.value().symbol()).isEqualTo(GrantProbe.class.getName());
        });

        assertThat(row(summarize(GrantProbe.class), "PROBE_ZULU").grantCatalog())
            .as("каталогом служит только измерение, объявившее grantValues")
            .isFalse();
    }

    @Test
    void nullInTheRuleMeansNotApplicableAndIsDeclared() {
        EntitySummary.AccessRow row = row(summarize(NullableProbe.class), "PROBE_ROW");

        assertThat(row.dimensionKind()).isEqualTo(RlsDimensionKind.FILTERABLE);
        assertThat(row.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.path())
                .as("путь — объявленный в аннотации, а не имя колонки: правило читает запись")
                .isEqualTo("branchId");
            assertThat(rule.nullsNotApplicable()).isTrue();
        });
    }

    @Test
    void customPolicyPublishesNoRuleRowsEvenThoughTheRecordCarriesADefaultPath() {
        RlsDimensionRegistry registry = registry();
        EntitySummary summary = summarize(PolicyProbe.class, registry);

        EntitySummary.AccessRow custom = row(summary, "PROBE_CUSTOM");
        assertThat(custom.dimensionKind()).isEqualTo(RlsDimensionKind.FILTERABLE);
        assertThat(custom.note()).contains("сложная политика").contains("значения поставляет запись");
        assertThat(custom.rules())
            .as("дефолт valuePaths у сложной политики не действует — строк правила нет")
            .isEmpty();

        RlsPolicyDescriptor.ValueRule recorded = registry.policyOf(PolicyProbe.class)
            .valueRules().get("PROBE_CUSTOM");
        assertThat(recorded.custom()).isTrue();
        assertThat(recorded.paths())
            .as("но в записи правила дефолт аннотации присутствует — поэтому его нельзя показывать")
            .containsExactly("id");
    }

    @Test
    void checkOnlyDimensionIsTextNotAbsenceOfARow() {
        EntitySummary.AccessRow gate = row(summarize(PolicyProbe.class), "PROBE_GATE");

        assertThat(gate.dimensionKind()).isEqualTo(RlsDimensionKind.CHECK_ONLY);
        assertThat(gate.note()).contains("проверяется, не фильтруется");
        assertThat(gate.grantCatalog())
            .as("грантов на ворота не бывает: грант на CHECK_ONLY не построчный")
            .isFalse();
    }

    /**
     * Порядок строк — гарантия сборщика: владелец отдаёт Set без контракта порядка
     * ({@code Set.copyOf} поверх {@code TreeSet}), проба объявляет измерения в обратном алфавитном
     * порядке, и карточка обязана получить отсортированные строки при любом порядке скана.
     */
    @Test
    void rowsAreSortedByNameBecauseTheOwnerGivesNoOrderContract() {
        EntitySummary first = summarize(GrantProbe.class);
        EntitySummary second = summarize(GrantProbe.class);

        assertThat(first.accessRows()).extracting(EntitySummary.AccessRow::dimension)
            .as("объявление ZULU раньше BRANCH не делает порядок объявления порядком строк")
            .containsExactly("PROBE_BRANCH", "PROBE_ZULU");
        assertThat(second.accessRows()).extracting(EntitySummary.AccessRow::dimension)
            .as("два прохода дают одну последовательность")
            .containsExactlyElementsOf(first.accessRows().stream()
                .map(EntitySummary.AccessRow::dimension).toList());
    }

    /** Носитель измерения-ворот — маркер, а не сущность: карточке сущности он не принадлежит. */
    @Test
    void markerCarrierIsRegisteredButAttachedToNoEntity() {
        RlsDimensionRegistry registry = registry();

        assertThat(registry.dimensionsOf(GateMarker.class)).containsExactly("PROBE_SETTINGS_GATE");
        assertThat(registry.grantValueDimensions()).contains("PROBE_BRANCH");
        assertThat(summarize(PlainProbe.class, registry).accessRows())
            .as("тип без объявления измерений: строк нет, и это факт")
            .isEmpty();
        assertThat(summarize(GrantProbe.class, registry).accessRows())
            .extracting(EntitySummary.AccessRow::dimension)
            .as("измерение маркера не привязывается к сущности по совпадению пакета")
            .doesNotContain("PROBE_SETTINGS_GATE");
    }

    /**
     * Отказ регистрации (класс объявляет измерение вне пакета скана) не роняет карточку и не
     * выдаётся за «измерений нет»: причина идёт диагностикой, строк аспекта нет.
     */
    @Test
    void unregisteredDeclarationBecomesADiagnosticInsteadOfACrash() {
        RlsDimensionRegistry registry = registry();
        assertThatThrownBy(() -> registry.dimensionsOf(UnscannedProbe.class))
            .as("владелец отказывает — и это правильное поведение enforcement-пути")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not registered");

        EntitySummary summary = summarize(UnscannedProbe.class, registry);

        assertThat(summary.accessRows()).isEmpty();
        assertThat(summary.diagnostics())
            .filteredOn(diagnostic -> "RLS_SCAN".equals(diagnostic.code()))
            .singleElement()
            .satisfies(diagnostic -> {
                assertThat(diagnostic.key()).isNotNull();
                assertThat(diagnostic.key().kind()).isEqualTo(FacetKind.RLS_DIMENSION);
                assertThat(diagnostic.key().entityClass()).isEqualTo(UnscannedProbe.class);
                assertThat(diagnostic.value().value()).isNotBlank();
            });
    }

    /** Без коллаборатора строк нет — тестовый путь формы, а не «у типа ничего нет». */
    @Test
    void withoutTheOwnerThereAreNoRows() {
        assertThat(summarize(GrantProbe.class, null).accessRows()).isEmpty();
    }

    // === Вспомогательное ===

    private static EntitySummary.AccessRow row(EntitySummary summary, String dimension) {
        return summary.accessRows().stream()
            .filter(candidate -> dimension.equals(candidate.dimension()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("нет строки измерения " + dimension
                + ", строки: " + summary.accessRows().stream()
                    .map(EntitySummary.AccessRow::dimension).toList()));
    }

    private static EntitySummary summarize(Class<?> entityClass) {
        return summarize(entityClass, registry());
    }

    private static EntitySummary summarize(Class<?> entityClass, RlsDimensionRegistry registry) {
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
            null, null, null, null, null, registry).summarize(entityClass);
    }

    /** Настоящий реестр над пакетом проб: те же fail-fast контракты, что в приложении. */
    private static RlsDimensionRegistry registry() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry(BASE_PACKAGE);
        registry.afterPropertiesSet();
        return registry;
    }
}
