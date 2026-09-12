package org.ip;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** UI may depend on application services, never on persistence entry points. */
@AnalyzeClasses(packages = "org.ip", importOptions = ImportOption.DoNotIncludeTests.class)
class UiPersistenceBoundaryTest {

    @ArchTest
    static final ArchRule viewsMustNotUseRepositories =
        noClasses().that().resideInAnyPackage("org.ip.views..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule viewsMustNotUseEntityManager =
        noClasses().that().resideInAnyPackage("org.ip.views..")
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.EntityManager");
}
