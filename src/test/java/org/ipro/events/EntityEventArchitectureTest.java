package org.ipro.events;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Entity events are a platform contract and must remain independent of UI/application code. */
@AnalyzeClasses(packages = "org.ipro.events", importOptions = ImportOption.DoNotIncludeTests.class)
class EntityEventArchitectureTest {

    @ArchTest
    static final ArchRule eventLayerMustNotDependOnVaadin =
        noClasses().that().resideInAnyPackage("org.ipro.events..")
            .should().dependOnClassesThat().resideInAnyPackage("com.vaadin..", "org.ip..");

    @ArchTest
    static final ArchRule eventLayerMustNotDependOnFieldAudit =
        noClasses().that().resideInAnyPackage("org.ipro.events..")
            .should().dependOnClassesThat().resideInAnyPackage("org.ipro.telemetry..");
}
