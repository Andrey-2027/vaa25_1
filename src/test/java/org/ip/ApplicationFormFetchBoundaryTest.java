package org.ip;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ipro.form.builder.ItemFormCustomization;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADX-07 (критерий закрытия): кастомизация формы не содержит EntityGraph hints, констант
 * глубины, перечитывания сущности по ID ради lazy proxy и обработки LazyInitializationException.
 *
 * <p>Правило проверяет это по bytecode, а не по ревью: приложение получает представление
 * сценария из FetchPlan, поэтому тип из {@code jakarta.persistence}/{@code org.hibernate} или
 * ручной fetch-адаптер в кастомизации невозможны — ни сейчас, ни в новых формах.</p>
 */
@AnalyzeClasses(packages = "org.ip", importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationFormFetchBoundaryTest {

    @ArchTest
    static final ArchRule formCustomizationsDoNotKnowPersistence =
            noClasses().that().areAssignableTo(ItemFormCustomization.class)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..", "org.hibernate..")
                    .because("ADX-07: форма не управляет persistence context");

    @ArchTest
    static final ArchRule formCustomizationsDoNotBuildFetchGraphsThemselves =
            noClasses().that().areAssignableTo(ItemFormCustomization.class)
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "org.ipro.metadata.FetchGraphs")
                    .because("ADX-07: граф загрузки определяет FetchPlan, а не форма");

    @ArchTest
    static final ArchRule formCustomizationsDoNotReloadEntities =
            noClasses().that().areAssignableTo(ItemFormCustomization.class)
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "org.ipro.crud.LookupService")
                    .because("ADX-07: перечитывание выбранного значения по ID в форме запрещено");
}
