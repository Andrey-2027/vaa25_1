package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Контракт скрипта бутстрапа: проверка манифеста не зависит от инструментария сборки.
 *
 * <p>Разбор D1/D2/D3 запустил {@code -ValidateOnly} на машине без Maven в PATH и получил падение
 * не на несоответствии манифеста, а на разрешении Maven: скрипт искал и запускал {@code mvn}
 * раньше, чем доходил до ветки проверок. Это делало самую дешёвую проверку воспроизводимости
 * недоступной именно там, где она нужнее всего — на чистой машине и в CI до установки
 * инструментария, — а отчёт при этом выглядел как дефект манифеста.</p>
 *
 * <p>Тест фиксирует обе половины контракта: {@code -ValidateOnly} обязан пройти <b>с
 * несуществующим</b> Maven, а настоящая сборка обязана по-прежнему требовать его и назвать
 * причину. Второе не менее важно первого: иначе «починили» бы порядок так, что сборка молча
 * собирала бы чем попало.</p>
 *
 * <p>{@code -AllowSourceDrift} передаётся осознанно: в этом воркспейсе внешние форки
 * (DynamicReports 7, UReport3) имеют собственные истории правок, и их дрейф — предмет отдельной
 * политики, а не этого теста. Предмет здесь — порядок разрешения инструментария.</p>
 */
class BootstrapScriptContractTest {

    private static final Path SCRIPT = Path.of("scripts/bootstrap-local-dependencies.ps1");

    private static final Path WINDOWS_POWERSHELL = Path.of(
        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
        "System32", "WindowsPowerShell", "v1.0", "powershell.exe");

    private static final String MISSING_MAVEN = "C:\\definitely-no-maven-here\\mvn.cmd";

    private static final long TIMEOUT_SECONDS = 300;

    @Test
    void validateOnlyRunsWithoutMaven() {
        assumeWindowsWithPowerShell();

        ProcessResult result = run("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", SCRIPT.toString(),
            "-ValidateOnly",
            "-AllowSourceDrift",
            "-MavenCommand", MISSING_MAVEN);

        assertThat(result.exitCode())
            .as("проверка манифеста обязана проходить без Maven: иначе она недоступна на чистой"
                + " машине и в CI, то есть там, где воспроизводимость и проверяют.%n%s", result.output())
            .isZero();
        assertThat(result.output())
            .as("скрипт должен назвать явно, что инструментарий в этом режиме не разрешается —"
                + " иначе следующий читатель снова будет искать причину в манифесте")
            .contains("without Maven");
    }

    @Test
    void aRealBuildStillRequiresMaven() {
        assumeWindowsWithPowerShell();

        ProcessResult result = run("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", SCRIPT.toString(),
            "-AllowSourceDrift",
            "-MavenCommand", MISSING_MAVEN);

        assertThat(result.exitCode())
            .as("реальная сборка без Maven обязана падать: иначе проверка порядка превратилась бы"
                + " в отключение сборки%n%s", result.output())
            .isNotZero();
        assertThat(result.output())
            .as("падение должно называть причину — отсутствующий executable, а не манифест")
            .contains("Maven executable not found");
    }

    /** Скрипт — PowerShell, поэтому проверка осмысленна только на Windows с PowerShell. */
    private static void assumeWindowsWithPowerShell() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
            "bootstrap-скрипт исполняется PowerShell: вне Windows проверка пропускается");
        assumeTrue(Files.isRegularFile(SCRIPT), "скрипт бутстрапа отсутствует в чекауте");
        assumeTrue(Files.isRegularFile(WINDOWS_POWERSHELL),
            "Windows PowerShell 5.1 executable отсутствует: " + WINDOWS_POWERSHELL);
    }

    private static ProcessResult run(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("bootstrap-скрипт не завершился за "
                    + TIMEOUT_SECONDS + " секунд: " + String.join(" ", command));
            }
            return new ProcessResult(process.exitValue(), output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ожидание bootstrap-скрипта прервано", e);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
