package org.ipro;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.ipro.crud.AbstractBaseService;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Заборы миграции C4.6/C4.7 (ADR-0007 §3): compatibility-механизмы не могут расширяться,
 * а списки исключений обязаны сокращаться до нуля.
 *
 * <p>Каждый allowlist ниже проверяется в обе стороны: «ничего лишнего» (новый наследник или
 * новая зависимость) и «ничего устаревшего» (список не должен переживать удалённый класс).
 * Поэтому список можно только уменьшать — молчаливое расширение невозможно.</p>
 */
class CompatibilityMigrationArchitectureTest {

    /** Наследники compatibility base на входе C4.6; цель C4.7 — пустой набор. */
    private static final Set<String> COMPATIBILITY_BASE_SUBCLASSES = Set.of(
            "org.ip.service.AttributeValueService",
            "org.ip.service.GridFormViewService",
            "org.ip.service.SklNomOpaService",
            "org.ipro.ureport.service.UreportTemplateService");

    /** Сущности, чья модель ещё ссылается на application service через {@code @EntityMetadata}. */
    private static final Set<String> MODEL_TO_SERVICE_DEPENDENCIES = Set.of(
            "org.ip.model.AttributeValue",
            "org.ip.model.NomSklAttribute",
            "org.ip.model.SklNomOpa");

    /** Единственная известная UI-утечка persistence context; цель C4.7 — пустой набор. */
    private static final Set<String> PERSISTENCE_CONTEXT_IN_UI = Set.of(
            "org.ipro.form.registry.FormResolver");

    private static final String ENTITY_MANAGER = "jakarta.persistence.EntityManager";

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.ip", "org.ipro");
    }

    @Test
    void onlyTrackedClassesInheritCompatibilityBaseService() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> !c.getName().equals(AbstractBaseService.class.getName()))
                .filter(c -> c.isAssignableTo(AbstractBaseService.class))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("новый наследник compatibility base — миграция обязана уменьшать список, не расширять")
                .isSubsetOf(COMPATIBILITY_BASE_SUBCLASSES);
        assertThat(COMPATIBILITY_BASE_SUBCLASSES)
                .as("устаревшая запись allowlist: класс удалён или перебазирован — уберите его из списка")
                .isSubsetOf(actual);
    }

    @Test
    void applicationModelDoesNotDependOnServiceClasses() {
        JavaClasses classes = productionClasses();
        Set<String> actual = classes.stream()
                .filter(c -> c.getPackageName().equals("org.ip.model"))
                .filter(c -> dependsOnServicePackage(c))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual)
                .as("модель получила новую ссылку на application service")
                .isSubsetOf(MODEL_TO_SERVICE_DEPENDENCIES);
        assertThat(MODEL_TO_SERVICE_DEPENDENCIES)
                .as("устаревшая запись allowlist: модель больше не ссылается на service — уберите её")
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
                .as("устаревшая запись allowlist: утечка закрыта — уберите класс из списка")
                .isSubsetOf(actual);
    }

    private static boolean dependsOnServicePackage(JavaClass javaClass) {
        return javaClass.getDirectDependenciesFromSelf().stream()
                .anyMatch(target -> target.getTargetClass().getPackageName().startsWith("org.ip.service"));
    }
}
