package org.ip;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Фоновая работа приложения идёт через управляемый исполнитель
 * ({@code ReportTaskExecutor} через {@code ReportExecutionService.executeAsync}), а не
 * через собственный поток.
 *
 * <p>Свой {@code new Thread} означает ручной перенос аутентификации и HttpSession и
 * ручную очистку ThreadLocal — именно так появился разрыв канала `DAC-11` в
 * {@code JrxmlRunDialog}: контекст выставлялся вручную и снимался только в собственном
 * {@code finally}, который легко забыть. Платформенный исполнитель делает снимок
 * субъекта и очистку гарантированно, поэтому в приложении потоков быть не должно.</p>
 *
 * <p>Правило намеренно ограничено приложением ({@code org.ip..}): собственные
 * инфраструктурные демоны платформы ({@code AsyncEventSink}, {@code WindowReporter}) и
 * сам исполнитель живут в {@code org.ipro..}.</p>
 */
@AnalyzeClasses(packages = "org.ip", importOptions = ImportOption.DoNotIncludeTests.class)
class UiThreadingBoundaryTest {

    @ArchTest
    static final ArchRule applicationMustNotCreateThreadWithRunnable =
        noClasses().that().resideInAnyPackage("org.ip..")
            .should().callConstructor(Thread.class, Runnable.class);

    @ArchTest
    static final ArchRule applicationMustNotCreateNamedThread =
        noClasses().that().resideInAnyPackage("org.ip..")
            .should().callConstructor(Thread.class, Runnable.class, String.class);
}
