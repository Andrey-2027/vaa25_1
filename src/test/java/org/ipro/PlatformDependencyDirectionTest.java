package org.ipro;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * D1 — исполняемая фиксация допустимых направлений зависимостей внутри платформы.
 *
 * <p>{@link PlatformArchitectureTest} держит внешнюю границу (платформа не зависит от
 * приложения). Этого мало для D2: чтобы резать платформу на модули, нужно знать
 * направления <b>внутри</b> неё. Слои здесь — не вкусовая иерархия, а порядок, в котором
 * срез возможен физически: сначала выносится нижний контракт (metadata, data, events),
 * затем исполнение (fetch, lifecycle, rls, search), затем UI-слои
 * (form, reportstudio, ureport, jr). Правило «нижний не знает верхнего» — это то, что
 * делает первый extraction slice возможным без цикла.</p>
 *
 * <p>Правила ниже не декларация намерения, а замер: они зеленели на состоянии checkout
 * на момент D1. Каждое направление, которое уже было нарушено, <b>не</b> записано сюда
 * «на будущее» — оно либо исправлено (см. порт группировки в
 * {@code org.ipro.data.grouping}: {@code data -> form} был реальной утечкой),
 * либо зафиксировано как исключение в {@code docs/architecture/d1-platform-boundary-map.md}
 * с причиной и этапом снятия (например {@code metadata -> form}: explorer —
 * read-model форм и переезжает вместе с form-срезом).</p>
 *
 * <p>Замечание о классах: ArchUnit анализирует весь classpath, поэтому под {@code org.ipro.crud..}
 * попадают и классы внешнего артефакта {@code org.ipro.crudui:crudui-core}. Это ожидаемо —
 * правило проверяет причину (нижний слой не тянет UI-слой), а не то, в каком артефакте
 * лежит класс.</p>
 */
@AnalyzeClasses(packages = "org.ipro", importOptions = ImportOption.DoNotIncludeTests.class)
class PlatformDependencyDirectionTest {

    /**
     * Слой канонического доступа к данным — фундамент C4. Он не знает ни форм, ни отчётов:
     * data access не может зависеть от того, кто его вызывает.
     */
    @ArchTest
    static final ArchRule dataAccessDoesNotDependOnUiLayers =
            noClasses().that().resideInAnyPackage("org.ipro.data..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.ipro.form..", "org.ipro.reportstudio..",
                            "org.ipro.ureport..", "org.ipro.jr..")
                    .because("data access — нижний слой: UI зависит от него, а не наоборот");

    /**
     * Metadata ядро поднимается без слоя доступа к данным и без отчётов (расширение
     * ADR-0006-правила из {@link PlatformArchitectureTest}).
     */
    @ArchTest
    static final ArchRule metadataDoesNotDependOnDataAccessOrReports =
            noClasses().that().resideInAnyPackage("org.ipro.metadata..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.ipro.data..", "org.ipro.reportstudio..")
                    .because("metadata — самый нижний слой: он описывает данные, а не читает их");

    /**
     * Fetch/instance-name — контракт C3: исполняет планы загрузки, но не знает UI и отчётов.
     */
    @ArchTest
    static final ArchRule fetchPlanDoesNotDependOnUiOrReports =
            noClasses().that().resideInAnyPackage("org.ipro.fetch..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.ipro.form..", "org.ipro.reportstudio..")
                    .because("FetchPlan — контракт загрузки, а не форма и не отчёт");

    /**
     * RLS — сквозная политика доступа; она применяется к формам, но не строится из них.
     */
    @ArchTest
    static final ArchRule rlsDoesNotDependOnFormLayer =
            noClasses().that().resideInAnyPackage("org.ipro.rls..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ipro.form..")
                    .because("RLS — сквозной слой: форма подчиняется политике, а не наоборот");

    /**
     * Lifecycle/ownership owned row — предметная механика сохранения, не UI.
     */
    @ArchTest
    static final ArchRule lifecycleDoesNotDependOnFormLayer =
            noClasses().that().resideInAnyPackage("org.ipro.lifecycle..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ipro.form..")
                    .because("lifecycle не должен знать о формах");

    /**
     * Event-контракты — нейтральный слой: подписчики зависят от них, они ни от кого.
     */
    @ArchTest
    static final ArchRule eventContractsDoNotDependOnDataAccess =
            noClasses().that().resideInAnyPackage("org.ipro.events..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ipro.data..")
                    .because("event-контракты — самый нижний слой, на который подписываются");

    /**
     * Search — read-механика; отчётный слой её использует, обратной связи нет.
     */
    @ArchTest
    static final ArchRule searchDoesNotDependOnReportSubsystems =
            noClasses().that().resideInAnyPackage("org.ipro.search..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.ipro.reportstudio..", "org.ipro.ureport..")
                    .because("search не зависит от отчётных подсистем");

    /**
     * Базовый CRUD/data-слой платформы не знает отчётных подсистем: отчёты — optional add-on
     * (D3), поэтому направление обязано быть односторонним уже сейчас.
     */
    @ArchTest
    static final ArchRule crudCoreDoesNotDependOnReportSubsystems =
            noClasses().that().resideInAnyPackage("org.ipro.crud..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.ipro.reportstudio..", "org.ipro.ureport..")
                    .because("отчётные подсистемы — optional add-on, базовый CRUD от них не зависит");
}
