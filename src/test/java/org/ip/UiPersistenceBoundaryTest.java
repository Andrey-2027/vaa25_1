package org.ip;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * UI may depend on application services, never on persistence entry points.
 *
 * <p>Правило покрывает оба UI-пакета приложения: {@code org.ip.views..} и
 * {@code org.ip.groupgrid..}. Второй появился из разбора `DAC-08`: там лежали демо-панели
 * (`GroupingPanelView`, `NomenclatureLazyGroupsView`) с прямыми `EntityManager`-запросами
 * и ни одной production-ссылки на них. Спайк перенесён в test-исходники (он остаётся
 * запускаемым из IDE, но вне production-классpath), а пакет оставлен в правиле — чтобы
 * новый UI-код там не открыл тот же канал заново.</p>
 */
@AnalyzeClasses(packages = "org.ip", importOptions = ImportOption.DoNotIncludeTests.class)
class UiPersistenceBoundaryTest {

    @ArchTest
    static final ArchRule viewsMustNotUseRepositories =
        noClasses().that().resideInAnyPackage("org.ip.views..", "org.ip.groupgrid..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule viewsMustNotUseEntityManager =
        noClasses().that().resideInAnyPackage("org.ip.views..", "org.ip.groupgrid..")
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.EntityManager");
}
