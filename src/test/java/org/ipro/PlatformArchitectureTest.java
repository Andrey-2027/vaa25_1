package org.ipro;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Архитектурная граница платформа/приложение (см. docs/plans/reportstudio-reverse-deps-plan.md).
 *
 * <p>Правило проверяет bytecode-зависимости, а не только import-строки:
 * ловит fully-qualified использования в коде, наследование и типы в сигнатурах.
 * Сегментное сравнение пакетов гарантирует, что {@code org.ip..} не матчит
 * {@code org.ipro.*}. Комментарии/Javadoc и default-строки конфигурации
 * (например, {@code platform.subsystem-scan-package=org.ip}) зависимостями
 * не являются и под правило не попадают.</p>
 *
 * <p>Интеграционные тесты платформы (org.ipro.* в src/test) закономерно используют
 * доменные фикстуры приложения — поэтому критерий распространяется только на
 * production-код (DoNotIncludeTests), как и зафиксировано в плане (критерий src/main only).</p>
 * <p>Осознанные строковые связки (не bytecode, правилом не ловятся, зафиксированы здесь):
 * pointcut {@code org.ip.service..*} в {@code ExecutionTimeAspect},
 * {@code platform.subsystem-scan-package=org.ip}.</p>
 */
@AnalyzeClasses(packages = "org.ipro", importOptions = ImportOption.DoNotIncludeTests.class)
class PlatformArchitectureTest {

    @ArchTest
    static final ArchRule platformIndependentOfApplication =
            noClasses().that().resideInAnyPackage("org.ipro..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ip..");

    /**
     * Metadata core поднимается без компонентов C3 (ADR-0006): пакет
     * {@code org.ipro.metadata} не зависит от FetchPlan/InstanceName
     * ({@code org.ipro.fetch}). Обратное направление — C3 использует metadata —
     * разрешено и ожидаемо.
     */
    @ArchTest
    static final ArchRule metadataCoreIndependentOfFetchPlanAndInstanceName =
            noClasses().that().resideInAnyPackage("org.ipro.metadata..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ipro.fetch..");
}
