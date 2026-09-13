package org.ip;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Граница DAC-13: прикладные сервисы не держат прямого JPA-доступа.
 *
 * <p>Динамические чтения идут через RLS-aware {@code LookupService}, блокировки —
 * через repository-методы, разрешение имён сущностей — через платформенный
 * {@code ManagedEntityCatalog}. Прямой {@code EntityManager} в
 * {@code org.ip.service/org.ip.application} означал бы параллельный незащищённый
 * вход в обход enforcement.</p>
 */
@AnalyzeClasses(packages = "org.ip", importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationBoundaryTest {

    @ArchTest
    static final ArchRule noEntityManagerInApplicationServices =
            noClasses().that().resideInAnyPackage("org.ip.service..", "org.ip.application..")
                    .should().dependOnClassesThat().areAssignableTo(EntityManager.class);

    @ArchTest
    static final ArchRule noEntityManagerFactoryInApplicationServices =
            noClasses().that().resideInAnyPackage("org.ip.service..", "org.ip.application..")
                    .should().dependOnClassesThat().areAssignableTo(EntityManagerFactory.class);

    @ArchTest
    static final ArchRule noHibernateSessionInApplicationServices =
            noClasses().that().resideInAnyPackage("org.ip.service..", "org.ip.application..")
                    .should().dependOnClassesThat().areAssignableTo(Session.class);
}
