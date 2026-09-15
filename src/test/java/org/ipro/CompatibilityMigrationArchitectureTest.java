package org.ipro;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.ipro.crud.BaseService;
import org.ipro.crud.jpa.ValidatedJpaCrudService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Заборы миграции C4.6/C4.7 (ADR-0007 §3): compatibility-механизмы не возвращаются,
 * а списки исключений проверяются в обе стороны — «ничего лишнего» (новая generic база или
 * новый наследник) и «ничего устаревшего» (запись пережила удалённый класс). Поэтому список
 * можно только уменьшать, а молчаливое расширение невозможно.
 */
class CompatibilityMigrationArchitectureTest {

    /** Удалён в C4.7; возврат вернул бы вторую generic CRUD-базу рядом с canonical path. */
    private static final String REMOVED_COMPATIBILITY_BASE = "org.ipro.crud.AbstractBaseService";

    /**
     * Все production-реализации {@link BaseService} — проверенный список, а не «сколько
     * получилось». Новый CRUD-путь обязан появиться здесь осознанно: либо это типизированный
     * use case с предметным доменом, либо canonical handle, либо internal-store adapter.
     */
    private static final Set<String> BASE_SERVICE_IMPLEMENTATIONS = Set.of(
            "org.ipro.crud.jpa.ValidatedJpaCrudService",
            "org.ipro.data.CanonicalEntityService",
            "org.ip.service.AttributeValueService",
            "org.ip.service.GridFormViewService",
            "org.ip.service.NomSklAttributeService",
            "org.ip.service.SklNomOpaService",
            "org.ip.service.UserService",
            "org.ipro.jr.service.JrxmlTemplateService",
            "org.ipro.reportstudio.service.ReportTemplateService",
            "org.ipro.ureport.service.UreportTemplateService");

    /** ADR-0007 §3: {@code ValidatedJpaCrudService} обслуживает только non-metadata storage. */
    private static final Set<String> INTERNAL_STORE_ADAPTER_SUBCLASSES = Set.of(
            "org.ipro.jr.service.JrxmlTemplateService",
            "org.ipro.reportstudio.service.ReportTemplateService",
            "org.ipro.ureport.service.UreportTemplateService");

    /**
     * Сущности, чья модель ссылается на application service через {@code @EntityMetadata}.
     * C4.6 волна E: пусто — ни одна модель не называет сервисный класс, а сервисы
     * прикладного домена регистрируются по entity type в {@code ServiceLocator}.
     */
    private static final Set<String> MODEL_TO_SERVICE_DEPENDENCIES = Set.of();

    /**
     * C4.8: UI/form больше не владеет persistence context. Последняя утечка —
     * {@code FormResolver}, державший {@code EntityManager} ради grouping — закрыта
     * выносом адаптера в {@code org.ipro.data.grouping}. Набор пуст и обязан таким остаться.
     */
    private static final Set<String> PERSISTENCE_CONTEXT_IN_UI = Set.of();

    private static final String ENTITY_MANAGER = "jakarta.persistence.EntityManager";

    /**
     * C4.8: query-методы без единого production-потребителя удалены после перевода
     * тестовых fixture-lookup'ов на canonical-совместимый доступ. Возврат такого метода
     * снова завёл бы дубль canonical search под видом предметного query.
     */
    private static final Map<String, Set<String>> REMOVED_REPOSITORY_QUERIES = Map.ofEntries(
            Map.entry("org.ip.repository.AttributeTypeRepository",
                    Set.of("findByCode", "existsByCode")),
            Map.entry("org.ip.repository.BranchRepository",
                    Set.of("findByCode", "existsByCode")),
            Map.entry("org.ip.repository.JournalRepository",
                    Set.of("findByCode", "existsByCode")),
            Map.entry("org.ip.repository.NomenclatureRepository",
                    Set.of("findByCode", "existsByCode")),
            Map.entry("org.ip.repository.UnitOfMeasurementRepository",
                    Set.of("findByCode", "existsByCode")),
            Map.entry("org.ip.repository.WorkshopRepository",
                    Set.of("findByCode", "existsByCode")),
            Map.entry("org.ip.repository.PrdSpecRepository",
                    Set.of("findByCodeSpec", "existsByCodeSpec")),
            Map.entry("org.ip.repository.ReceivingDocumentRepository",
                    Set.of("findByNumber", "existsByNumber")),
            Map.entry("org.ip.repository.SklNomOpaRepository",
                    Set.of("existsByNomenclature")),
            Map.entry("org.ip.repository.SklNomOpaValueRepository",
                    Set.of("findByValue")),
            Map.entry("org.ip.repository.UserRepository",
                    Set.of("existsByUsername")),
            Map.entry("org.ip.repository.GridFormViewRepository",
                    Set.of("findByIdAndFormKey")),
            Map.entry("org.ip.repository.NomSklAttributeRepository",
                    Set.of("findByNomenclatureOrderByAttrType")));

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.ip", "org.ipro");
    }

    /**
     * Compatibility base удалён, и это проверяется по факту: возврат класса (в любом пакете,
     * с тем же FQN) снова дал бы production-код, наследующий generic CRUD-базу.
     */
    @Test
    void compatibilityBaseServiceStaysRemoved() {
        assertThatThrownBy(() -> Class.forName(REMOVED_COMPATIBILITY_BASE))
                .as("удалённая compatibility-база вернулась: canonical path не должен получить"
                        + " вторую generic CRUD-базу рядом с собой")
                .isInstanceOf(ClassNotFoundException.class);

        Set<String> reintroduced = productionClasses().stream()
                .filter(c -> c.getName().equals(REMOVED_COMPATIBILITY_BASE))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(reintroduced).isEmpty();
    }

    /** Каждая production-реализация {@link BaseService} проходит ревью и попадает в список. */
    @Test
    void baseServiceImplementationsAreReviewed() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> !c.getName().equals(BaseService.class.getName()))
                .filter(c -> c.isAssignableTo(BaseService.class))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("новый BaseService-путь — осознанное решение, а не побочный эффект")
                .isSubsetOf(BASE_SERVICE_IMPLEMENTATIONS);
        assertThat(BASE_SERVICE_IMPLEMENTATIONS)
                .as("устаревшая запись списка: класс удалён или перебазирован — уберите её")
                .isSubsetOf(actual);
    }

    /**
     * ADR-0007 §3: {@code ValidatedJpaCrudService} — internal-store adapter. Он не может
     * обслуживать metadata-driven root, и его область не расширяется незаметно.
     */
    @Test
    void internalStoreAdapterOnlyServesNonMetadataStores() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> !c.getName().equals(ValidatedJpaCrudService.class.getName()))
                .filter(c -> c.isAssignableTo(ValidatedJpaCrudService.class))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("internal-store adapter не должен получать новых наследников молча")
                .isSubsetOf(INTERNAL_STORE_ADAPTER_SUBCLASSES);
        assertThat(INTERNAL_STORE_ADAPTER_SUBCLASSES)
                .as("устаревшая запись списка: наследник удалён — уберите её")
                .isSubsetOf(actual);
    }

    @Test
    void applicationModelDoesNotDependOnServiceClasses() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> c.getPackageName().equals("org.ip.model"))
                .filter(CompatibilityMigrationArchitectureTest::dependsOnServicePackage)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("модель получила новую ссылку на application service")
                .isSubsetOf(MODEL_TO_SERVICE_DEPENDENCIES);
        assertThat(MODEL_TO_SERVICE_DEPENDENCIES)
                .as("устаревшая запись списка: модель больше не ссылается на service — уберите её")
                .isSubsetOf(actual);
    }

    @Test
    void uiDoesNotDependOnRepositories() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> c.getPackageName().startsWith("org.ip.views"))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(target -> target.getTargetClass().getPackageName().startsWith("org.ip.repository")))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("UI обязан идти через canonical data access, а не через application repository")
                .isEmpty();
    }

    @Test
    void uiDoesNotOwnPersistenceContext() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> c.getPackageName().startsWith("org.ip.views")
                        || c.getPackageName().startsWith("org.ipro.form"))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(target -> target.getTargetClass().getName().equals(ENTITY_MANAGER)))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("новая UI-утечка persistence context")
                .isSubsetOf(PERSISTENCE_CONTEXT_IN_UI);
        assertThat(PERSISTENCE_CONTEXT_IN_UI)
                .as("устаревшая запись списка: утечка закрыта — уберите класс из списка")
                .isSubsetOf(actual);
    }

    /** C4.8: удалённые мёртвые query-методы не возвращаются ни в одной форме. */
    @Test
    void removedDeadRepositoryQueriesDoNotComeBack() {
        for (Map.Entry<String, Set<String>> entry : REMOVED_REPOSITORY_QUERIES.entrySet()) {
            Class<?> repository;
            try {
                repository = Class.forName(entry.getKey());
            } catch (ClassNotFoundException missing) {
                throw new AssertionError("repository исчез: " + entry.getKey(), missing);
            }
            Set<String> present = Arrays.stream(repository.getMethods())
                    .map(Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(present)
                    .as("C4.8 удалил %s у %s: у метода не было production-потребителя,"
                            + " а возврат завёл бы дубль canonical search",
                            entry.getValue(), entry.getKey())
                    .doesNotContainAnyElementsOf(entry.getValue());
        }
    }

    private static boolean dependsOnServicePackage(JavaClass javaClass) {
        return javaClass.getDirectDependenciesFromSelf().stream()
                .anyMatch(target -> target.getTargetClass().getPackageName().startsWith("org.ip.service"));
    }
}
