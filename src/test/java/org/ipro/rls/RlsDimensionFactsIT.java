package org.ipro.rls;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.SklNomOpa;
import org.ip.model.Workshop;
import org.ip.reports.ReportRights;
import org.ip.subsystem.Subsystems;
import org.hibernate.annotations.Filter;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.DataOperation;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 3.1: замер площадки доступа (RLS) на настоящем реестре приложения — до того, как
 * карточка начнёт показывать этот аспект.
 *
 * <p><b>Почему замер и почему тест.</b> План среза описывал аспект доступа через ожидания
 * («измерение пришло через {@code valuePaths}», «место объявления есть и у правила»), и часть
 * этих ожиданий код не подтверждает. Замер фиксирует то, что есть: сколько измерений и сайтов
 * объявления, кто носитель измерения, какие правила значений объявлены, что именно реестр отдаёт
 * наружу и чего он не отдаёт вовсе. Числа печатаются в отчёт теста ({@code [E3.2.0-3.1]}) — замер
 * читается без UI.</p>
 *
 * <p><b>Почему не фикстура.</b> Реестр поднимается настоящей авто-конфигурацией {@code org.ipro.rls}
 * на пакете приложения ({@code rls.dimension-scan-package=org.ip}), поэтому fail-fast контракты
 * (FILTERABLE ⇔ {@code @Filter}, CHECK_ONLY ⇔ фильтра нет, read/write parity) уже отработали к
 * моменту чтения: факт берётся у владельца, записанного при старте, а не из чтения аннотаций.</p>
 *
 * <p><b>Что закреплено как норма.</b> Набор измерений и сайтов объявления — reviewed: новое
 * измерение требует правки этого теста и записи в документации, как reviewed-бюджеты модулей.
 * Именно поэтому здесь {@code containsExactlyInAnyOrder}, а не {@code contains}.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class RlsDimensionFactsIT {

    /** RLS dimensions, объявленные приложением: восемь имён и двенадцать сайтов-аннотаций. */
    private static final Set<String> DIMENSIONS = Set.of(
        "BRANCH", "JOURNAL", "REPORTS:JPQL_PREVIEW",
        "SETTINGS:Directories", "SETTINGS:Documents", "SETTINGS:ProductionDocuments",
        "SETTINGS:Production");

    /** Аннотированные CHECK_ONLY RLS dimensions: четыре SETTINGS:* и REPORTS:*. */
    private static final Set<String> CHECK_ONLY = Set.of(
        "REPORTS:JPQL_PREVIEW", "SETTINGS:Directories",
        "SETTINGS:Documents", "SETTINGS:ProductionDocuments", "SETTINGS:Production");

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private RlsDimensionRegistry dimensions;

    @Autowired
    private EntityDescriptorCatalog entityDescriptors;

    /** Определения операций, объявленные владельцами: из capability именованные не выводятся. */
    @Autowired
    private org.ipro.rls.c5.C5OperationPermissionRegistry operationDefinitions;

    /**
     * Измерение не приходит на класс извне: аннотация объявляется только на типе
     * ({@code @Target(TYPE)}), не наследуется ({@code @Inherited} нет) и не стоит на членах.
     * Место объявления = класс-носитель; «выведенного из {@code valuePaths}» измерения не
     * существует, и это видно из самой аннотации, а не из наблюдений за UI.
     */
    @Test
    void dimensionIsDeclaredOnTheTypeItselfAndIsNotInherited() {
        assertThat(RlsDimension.class.isAnnotationPresent(Inherited.class))
            .as("наследования измерений нет: иначе сущность участвовала бы в измерении, не объявив его")
            .isFalse();
        assertThat(RlsDimension.class.getAnnotation(Target.class).value())
            .as("аннотация объявляется только на типе: измерение не живёт на поле или методе")
            .containsExactly(ElementType.TYPE);
    }

    /** Аннотированные RLS dimensions плюс динамические ключи C5: read (FQN) и операционные. */
    @Test
    void applicationHasSevenAnnotatedDimensionsAndC5Keys() {
        Set<String> expected = new TreeSet<>(DIMENSIONS);
        expected.addAll(canonicalEntityReadKeys());
        expected.addAll(c5OperationKeys());
        assertThat(dimensions.dimensions())
            .as("реестр содержит reviewed @RlsDimension, ключи canonical C5 entity read и ключи операций")
            .containsExactlyInAnyOrderElementsOf(expected);
        reportDimensions();
    }

    /**
     * Двенадцать сайтов объявления на восьми измерениях: три измерения объявлены повторно, и
     * каждое объявление несёт своё правило. Место объявления возвращается самим реестром
     * ({@code dimensionsOf}), то есть берётся у владельца факта, а не выводится в UI.
     */
    @Test
    void elevenDeclarationSitesAcrossTenCarryingTypes() {
        Map<String, Set<Class<?>>> sites = declarationSites();
        assertThat(sites.keySet())
            .as("каждое измерение обязано иметь носителя: неизвестного измерения в реестре нет")
            .containsExactlyInAnyOrderElementsOf(DIMENSIONS);
        assertThat(sites.get("BRANCH"))
            .containsExactlyInAnyOrder(Branch.class, ReceivingDocument.class, Workshop.class);
        assertThat(sites.get("JOURNAL"))
            .containsExactlyInAnyOrder(Journal.class, PrdSpec.class, ReceivingDocument.class);
        assertThat(sites.get("SETTINGS:Production"))
            .as("маркерное измерение объявлено вложенным типом, а не сущностью")
            .containsExactly(Subsystems.Production.class);
        assertThat(sites.get("REPORTS:JPQL_PREVIEW"))
            .containsExactly(ReportRights.JpqlPreview.class);

        int sitesCount = sites.values().stream().mapToInt(Set::size).sum();
        assertThat(sitesCount)
            .as("одиннадцать сайтов объявления: одинарные Journal/Branch/PrdSpec/Workshop, два у"
                + " ReceivingDocument, четыре SETTINGS:* и один REPORTS:*")
            .isEqualTo(11);
    }

    /** Носителем измерения бывает вложенный интерфейс-маркер: у него нет ни сущности, ни таблицы. */
    @Test
    void markerCarriersAreNestedTypesNotEntities() {
        for (Class<?> marker : List.of(ReportRights.JpqlPreview.class, Subsystems.Directories.class,
                Subsystems.Documents.class, Subsystems.ProductionDocuments.class,
                Subsystems.Production.class)) {
            assertThat(marker.isAnnotationPresent(jakarta.persistence.Entity.class))
                .as("%s несёт CHECK_ONLY-измерение, но сущностью не является", marker.getName())
                .isFalse();
            assertThat(marker.getEnclosingClass())
                .as("носитель измерения-маркера — вложенный тип, а не сам класс-маркер")
                .isNotNull();
        }
    }

    /**
     * Правило значения объявлено там же, где измерение (атрибут аннотации), — то есть
     * {@code EXPLICIT}; {@code valuePaths} не создают измерения. Единственные нетривиальные
     * стандартные правила — {@code PrdSpec} и {@code Workshop}.
     */
    @Test
    void valueRulesAreDeclaredWithTheDimension() {
        assertThat(rule(PrdSpec.class, "JOURNAL").paths())
            .as("правило PrdSpec читает журнал через ассоциацию, а не через id записи")
            .containsExactly("journal.id");
        assertThat(rule(PrdSpec.class, "JOURNAL").custom()).isFalse();
        assertThat(rule(PrdSpec.class, "JOURNAL").nullsNotApplicable()).isFalse();

        RlsPolicyDescriptor.ValueRule workshop = rule(Workshop.class, "BRANCH");
        assertThat(workshop.paths()).containsExactly("branch.id");
        assertThat(workshop.nullsNotApplicable())
            .as("null в пути означает «измерение к записи не применимо», и это объявлено")
            .isTrue();
        assertThat(workshop.custom()).isFalse();

        assertThat(rule(Branch.class, "BRANCH").paths())
            .as("дефолт аннотации: правило читает id самой записи")
            .containsExactly("id");
        assertThat(rule(Journal.class, "JOURNAL").paths()).containsExactly("id");
    }

    /**
     * Запись правила у сложной политики несёт <b>дефолт аннотации</b> {@code valuePaths = {"id"}},
     * хотя измерение объявлено без пути: реестр сохраняет атрибут как есть. Действующий
     * read-предикат сложного измерения берётся из {@code readCondition} и сверяется с
     * {@code @Filter} при старте ({@code expectedFilterCondition}), а значения поставляет запись
     * через {@link RlsDimensionValue} — то есть записанный {@code id} не участвует ни в чтении,
     * ни в записи.
     *
     * <p>Для аспекта это важная ловушка: если бы карточка показывала {@code valuePaths} как
     * «правило значения» без оговорки, она выдала бы недействующий дефолт за правило фильтрации.</p>
     */
    @Test
    void customPolicyCarriesTheDefaultPathThatEnforcementIgnores() {
        RlsPolicyDescriptor policy = dimensions.policyOf(ReceivingDocument.class);
        assertThat(policy.dimensions().keySet())
            .containsExactlyInAnyOrder("JOURNAL", "BRANCH");
        for (String dimension : List.of("JOURNAL", "BRANCH")) {
            RlsPolicyDescriptor.ValueRule rule = policy.valueRules().get(dimension);
            assertThat(rule.custom()).as("%s: политика типа объявлена сложной", dimension).isTrue();
            assertThat(rule.paths())
                .as("%s: записан дефолт аннотации, а не действующий путь правила", dimension)
                .containsExactly("id");
            assertThat(rule.nullsNotApplicable()).isFalse();
        }

        // Действующий предикат сложных измерений — объявленные условия, а не записанный id.
        assertThat(filterConditions(ReceivingDocument.class))
            .containsEntry("JOURNAL", ReceivingDocument.JOURNAL_READ_CONDITION)
            .containsEntry("BRANCH", ReceivingDocument.BRANCH_READ_CONDITION);
    }

    /**
     * У стандартного правила путь — действующий, и это видно по предикату фильтра: он выведен из
     * пути (и из {@code nullsNotApplicable}, когда null означает «измерение не применимо»).
     * Именно этот контраст отличает «путь действует» от «атрибут записан» из предыдущего теста.
     */
    @Test
    void standardRulePathIsDerivedIntoTheFilterCondition() {
        assertThat(rule(PrdSpec.class, "JOURNAL").paths()).containsExactly("journal.id");
        assertThat(filterConditions(PrdSpec.class))
            .containsEntry("JOURNAL", "journal_id in (:allowedIds)");

        assertThat(rule(Workshop.class, "BRANCH").paths()).containsExactly("branch.id");
        assertThat(rule(Workshop.class, "BRANCH").nullsNotApplicable()).isTrue();
        assertThat(filterConditions(Workshop.class))
            .as("null-ветвь в предикате — следствие объявленного nullsNotApplicable, а не догадка")
            .containsEntry("BRANCH", "(branch_id is null or branch_id in (:allowedIds))");
    }

    /** C5 keys also use CHECK_ONLY storage; they have no filter or record-value catalog. */
    @Test
    void annotatedRlsAndDynamicC5DimensionsHaveTheExpectedKinds() {
        Map<String, RlsDimensionKind> kinds = new TreeMap<>();
        for (String dimension : dimensions.dimensions()) {
            kinds.put(dimension, dimensions.kindOf(dimension));
        }
        Set<String> expectedCheckOnly = new TreeSet<>(CHECK_ONLY);
        expectedCheckOnly.addAll(canonicalEntityReadKeys());
        expectedCheckOnly.addAll(c5OperationKeys());
        assertThat(kinds.entrySet().stream()
                .filter(entry -> entry.getValue() == RlsDimensionKind.CHECK_ONLY)
                .map(Map.Entry::getKey).toList())
            .containsExactlyInAnyOrderElementsOf(expectedCheckOnly);
        assertThat(kinds.entrySet().stream()
                .filter(entry -> entry.getValue() == RlsDimensionKind.FILTERABLE)
                .map(Map.Entry::getKey).toList())
            .as("фильтруемые измерения — те, у которых есть @Filter с тем же именем")
            .containsExactlyInAnyOrder("BRANCH", "JOURNAL");
        assertThat(dimensions.kindOf(C5PermissionKeys.entity(ReceivingDocument.class)))
            .as("P-U: C5 entity-ключ зарегистрирован динамически как CHECK_ONLY")
            .isEqualTo(RlsDimensionKind.CHECK_ONLY);
    }

    /**
     * Гранты — данные, и каталог значений есть только у фильтруемых измерений: два корня
     * ({@code Branch}, {@code Journal}). Значения грантов замер не читает — они живут в
     * AccessGrant и решаются по конкретной записи.
     */
    @Test
    void onlyFilterableDimensionsHaveGrantValueRoots() {
        assertThat(dimensions.grantValueDimensions()).containsExactlyInAnyOrder("BRANCH", "JOURNAL");
        assertThat(dimensions.grantValueType("BRANCH")).isEqualTo(Branch.class);
        assertThat(dimensions.grantValueType("JOURNAL")).isEqualTo(Journal.class);
        assertThat(dimensions.grantValueDimensions())
            .as("каталог грантов для CHECK_ONLY невозможен: грант на ворота не построчный")
            .doesNotContainAnyElementsOf(List.of(C5PermissionKeys.entity(ReceivingDocument.class),
                "REPORTS:JPQL_PREVIEW", "SETTINGS:Directories"));
    }

    /** Тип без объявления измерений в RLS не участвует — это факт, а не отсутствие данных. */
    @Test
    void typeWithoutDeclarationDoesNotParticipate() {
        for (Class<?> type : List.of(Nomenclature.class, SklNomOpa.class)) {
            assertThat(dimensions.dimensionsOf(type)).as("%s: измерений нет", type.getSimpleName())
                .isEmpty();
            assertThat(dimensions.policyOf(type).protectedEntity()).isFalse();
        }
    }

    /**
     * У набора измерений владельца <b>нет контракта порядка</b>: внутри скан копит имена в
     * {@code TreeSet}, но наружу отдаётся {@code Set.copyOf} ({@code frozenClasses}), а копия
     * порядок не сохраняет — на этом прогоне тройка {@code ReceivingDocument} пришла как
     * {@code [JOURNAL, ENTITY:ReceivingDocument, BRANCH]}, то есть не отсортированной.
     *
     * <p>Следствие для аспекта: детерминированный порядок строк (по имени измерения) обязан
     * обеспечить потребитель, а не рассчитывать на реестр.</p>
     */
    @Test
    void declarationSetCarriesNoOrderContract() {
        assertThat(dimensions.dimensionsOf(ReceivingDocument.class))
            .as("состав — факт; порядок — нет")
            .containsExactlyInAnyOrder("BRANCH", "JOURNAL");
        System.out.println("[E3.2.0-3.1] orderOf(ReceivingDocument)="
            + dimensions.dimensionsOf(ReceivingDocument.class));
    }

    // ---------------------------------------------------------------- вспомогательное

    /** Измерение → носители, по самому реестру (владелец факта), а не по аннотациям на лету. */
    private Map<String, Set<Class<?>>> declarationSites() {
        Map<String, Set<Class<?>>> sites = new LinkedHashMap<>();
        for (Class<?> carrier : carrierTypes()) {
            for (String dimension : dimensions.dimensionsOf(carrier)) {
                sites.computeIfAbsent(dimension, key -> new TreeSet<>(
                    (left, right) -> left.getName().compareTo(right.getName()))).add(carrier);
            }
        }
        report(dimensions.dimensionsOf(ReceivingDocument.class), ReceivingDocument.class);
        return sites;
    }

    private Set<String> canonicalEntityReadKeys() {
        Set<FetchScenario> canonicalReads = Set.of(
            FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);
        return entityDescriptors.all().stream()
            .filter(descriptor -> descriptor.capabilities().readScenarios().stream()
                .anyMatch(canonicalReads::contains))
            .map(EntityDescriptor::type)
            .map(C5PermissionKeys::entityRead)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    /**
     * Ключи операций C5: определение получает тип с canonical write-capability, а сам ключ —
     * короткое имя {@code ENTITY:<SimpleName>} (ADR-0017 §3). Регистратор реестра измерений
     * объявляет их динамически как CHECK_ONLY, чтобы право на операцию можно было выдать
     * строкой гранта — как и любой другой ключ. Это вторая половина словаря C5: read-ключи
     * остаются FQN и с операционными не смешиваются.
     */
    /**
     * Ключи операций, которые владельцы объявили при старте. Два источника, и оба обязательны:
     * entity-операции выводятся из canonical capability типа (иначе замер не проверял бы, что
     * реестр не разошёлся с политикой типов), а именованные операции — из реестра определений
     * (ADR-0017 §5, ADR-0019), потому что из capability они не выводятся вовсе: определение
     * объявляет владелец операции, а не descriptor типа.
     */
    private Set<String> c5OperationKeys() {
        Set<DataOperation> writeIntentions = Set.of(
            DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE);
        Set<String> keys = entityDescriptors.all().stream()
            .filter(descriptor -> descriptor.capabilities().writes().stream()
                .anyMatch(writeIntentions::contains))
            .map(EntityDescriptor::type)
            .map(C5PermissionKeys::entityOperations)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        operationDefinitions.definitions().stream()
            .map(org.ipro.rls.c5.C5PermissionDefinition::key)
            .filter(key -> key.startsWith("ACTION:"))
            .forEach(keys::add);
        return keys;
    }

    /** Десять типов-носителей: пять сущностей и пять вложенных маркеров. */
    private List<Class<?>> carrierTypes() {
        List<Class<?>> carriers = new ArrayList<>(List.of(Branch.class, Journal.class, PrdSpec.class,
            ReceivingDocument.class, Workshop.class, ReportRights.JpqlPreview.class,
            Subsystems.Directories.class, Subsystems.Documents.class,
            Subsystems.ProductionDocuments.class, Subsystems.Production.class));
        assertThat(carriers).as("замер обязан покрывать всех носителей, иначе факт неполон")
            .hasSize(10);
        return carriers;
    }

    private Map<String, String> filterConditions(Class<?> type) {
        Map<String, String> conditions = new TreeMap<>();
        for (Filter filter : type.getAnnotationsByType(Filter.class)) {
            conditions.put(filter.name(), filter.condition());
        }
        return conditions;
    }

    private RlsPolicyDescriptor.ValueRule rule(Class<?> type, String dimension) {
        RlsPolicyDescriptor.ValueRule rule = dimensions.policyOf(type).valueRules().get(dimension);
        assertThat(rule).as("%s/%s: правило значения обязано быть объявлено", type.getSimpleName(),
            dimension).isNotNull();
        return rule;
    }

    private void reportDimensions() {
        for (String dimension : dimensions.dimensions()) {
            System.out.println("[E3.2.0-3.1] " + dimension + " kind=" + dimensions.kindOf(dimension)
                + " grantRoot=" + (dimensions.grantValueDimensions().contains(dimension)
                    ? dimensions.grantValueType(dimension).getName() : "-"));
        }
    }

    private void report(Set<String> declared, Class<?> carrier) {
        System.out.println("[E3.2.0-3.1] " + carrier.getName() + " declared=" + declared
            + " rules=" + declared.stream()
                .map(dimension -> dimension + ":" + dimensions.policyOf(carrier)
                    .valueRules().get(dimension).paths()).toList());
    }
}
