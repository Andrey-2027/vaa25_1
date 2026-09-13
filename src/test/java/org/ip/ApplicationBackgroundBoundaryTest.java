package org.ip;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Превентивная граница DAC-17: фоновых бизнес-задач нет, и создавать их напрямую
 * запрещено.
 *
 * <p>Запрещены в {@code org.ip..}: {@code @Scheduled}/{@code @Async}, прямые
 * {@code TaskScheduler}/{@code TaskExecutor}, {@code ExecutorService}/
 * {@code ScheduledExecutorService} (включая common pool через
 * {@code CompletableFuture}), собственные потоки (конструкторы и наследование
 * {@code Thread} — конструкторы с {@code Runnable} уже покрыты
 * {@link UiThreadingBoundaryTest}).</p>
 *
 * <p>Легальные пути: {@code RetentionPurgeJob} живёт в {@code org.ipro.telemetry..}
 * и работает только с telemetry-данными; асинхронные отчёты идут через
 * {@code ReportTaskExecutor} (снимок субъекта, scope, очистка контекста).</p>
 *
 * <p>При появлении первой фоновой бизнес-задачи исключение в правило не
 * добавляется: сначала потребуется typed job executor с явным субъектом, scope,
 * аудитом и очисткой контекста.</p>
 */
@AnalyzeClasses(packages = "org.ip", importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationBackgroundBoundaryTest {

    @ArchTest
    static final ArchRule noScheduledMethods =
            noMethods().that().areDeclaredInClassesThat().resideInAnyPackage("org.ip..")
                    .should().beAnnotatedWith(Scheduled.class);

    @ArchTest
    static final ArchRule noMetaScheduledMethods =
            noMethods().that().areDeclaredInClassesThat().resideInAnyPackage("org.ip..")
                    .should().beMetaAnnotatedWith(Scheduled.class);

    @ArchTest
    static final ArchRule noAsyncMethods =
            noMethods().that().areDeclaredInClassesThat().resideInAnyPackage("org.ip..")
                    .should().beAnnotatedWith(Async.class);

    @ArchTest
    static final ArchRule noMetaAsyncMethods =
            noMethods().that().areDeclaredInClassesThat().resideInAnyPackage("org.ip..")
                    .should().beMetaAnnotatedWith(Async.class);

    @ArchTest
    static final ArchRule noAsyncClasses =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().beAnnotatedWith(Async.class);

    @ArchTest
    static final ArchRule noMetaAsyncClasses =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().beMetaAnnotatedWith(Async.class);

    @ArchTest
    static final ArchRule noTaskSchedulerDependency =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().dependOnClassesThat().areAssignableTo(TaskScheduler.class);

    @ArchTest
    static final ArchRule noTaskExecutorDependency =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().dependOnClassesThat().areAssignableTo(TaskExecutor.class);

    @ArchTest
    static final ArchRule noExecutorServiceDependency =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().dependOnClassesThat().areAssignableTo(ExecutorService.class);

    @ArchTest
    static final ArchRule noScheduledExecutorServiceDependency =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().dependOnClassesThat().areAssignableTo(ScheduledExecutorService.class);

    @ArchTest
    static final ArchRule noCompletableFutureDependency =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().dependOnClassesThat()
                    .belongToAnyOf(java.util.concurrent.CompletableFuture.class);

    @ArchTest
    static final ArchRule noThreadSubclasses =
            noClasses().that().resideInAnyPackage("org.ip..")
                    .should().beAssignableTo(Thread.class);

    /** Правила чувствительны: fixture с запрещёнными примитивами их нарушает. */
    @Test
    void rulesCatchBackgroundPrimitivesInFixture() {
        JavaClasses fixture = new ClassFileImporter()
            .importPackages("org.ip.backgroundprobe");

        assertThatThrownBy(() -> noScheduledMethods.check(fixture))
            .hasMessageContaining("Scheduled");
        assertThatThrownBy(() -> noMetaScheduledMethods.check(fixture))
            .hasMessageContaining("Scheduled");
        assertThatThrownBy(() -> noAsyncMethods.check(fixture))
            .hasMessageContaining("Async");
        assertThatThrownBy(() -> noMetaAsyncMethods.check(fixture))
            .hasMessageContaining("Async");
        assertThatThrownBy(() -> noAsyncClasses.check(fixture))
            .hasMessageContaining("Async");
        assertThatThrownBy(() -> noMetaAsyncClasses.check(fixture))
            .hasMessageContaining("Async");
    }
}
