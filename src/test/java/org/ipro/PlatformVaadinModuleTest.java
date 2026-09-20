package org.ipro;

import org.junit.jupiter.api.Test;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.config.FormAutoConfiguration;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.registry.FormRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.search.GlobalSearchHeader;
import org.ipro.vaadin.search.GlobalSearchNavigationAdapter;
import org.ipro.vaadin.telemetry.TelemetryErrorHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.4: приложение как <b>потребитель</b> UI-модуля платформы.
 *
 * <p>Дополняет {@code VaadinModuleCompositionTest}, который живёт внутри модуля и проверяет
 * состав и зависимости изнутри. Здесь проверяется то, чего модуль о себе знать не может —
 * свойства, для которых нужно одновременно видеть обе стороны:</p>
 *
 * <ol>
 * <li><b>Копии типов в дереве нет.</b> Модуль мог быть собран, установлен и при этом оставить
 *     в приложении старую версию класса: компилятор возьмёт то, что раньше в classpath
 *     (application {@code target/classes} идёт первым), и «перенос» не изменит ничего.
 *     Поэтому проверяются и исходники, и то, откуда классы реально загружаются.</li>
 * <li><b>Регистрация бинов сменила владельца.</b> Раньше эти конфигурации перечислял
 *     imports-файл приложения, а telemetry-адаптер спасал только component scan пакета
 *     {@code org.ip}. Теперь владелец — модуль; приложение не должно оставить дубль
 *     (двойное применение) и не должно потерять подсистему.</li>
 * <li><b>Направление «наблюдение без UI» сохранилось.</b> Адаптеры телеметрии уехали
 *     в UI-модуль, и это единственная причина, по которой platform-telemetry имеет право
 *     оставаться без Vaadin: обратной зависимости быть не должно.</li>
 * </ol>
 *
 * <p>Семантические роли 93 типов и их бюджет держит D1-реестр
 * {@code platform-vaadin-surface.txt} ({@code PlatformVaadinSurfaceTest}) — здесь роли
 * не дублируются, иначе решение жило бы в двух местах и расходилось.</p>
 */
class PlatformVaadinModuleTest {

    private static final Path MODULE = Path.of("platform-vaadin");

    private static final Path APPLICATION_SOURCES = Path.of("src/main/java");

    private static final Path APPLICATION_TESTS = Path.of("src/test/java");

    private static final Path TELEMETRY_MODULE = Path.of("platform-telemetry");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Конфигурации, владельцем которых обязан быть модуль, а не приложение. */
    private static final Set<String> MODULE_AUTO_CONFIGURATIONS = Set.of(
        "org.ipro.form.config.FormAutoConfiguration",
        "org.ipro.vaadin.explorer.config.EntityExplorerAutoConfiguration",
        "org.ipro.vaadin.search.config.GlobalSearchVaadinAutoConfiguration",
        "org.ipro.vaadin.telemetry.TelemetryVaadinConfiguration");

    /** Представители всех трёх перенесённых корней: form (корень, builtin, coordinator, registry), vaadin, telemetry. */
    private static final List<Class<?>> SAMPLE = List.of(
        FormCoordinator.class,
        FormRegistry.class,
        FormAutoConfiguration.class,
        ItemForm.class,
        ListForm.class,
        EntitySummaryAssembler.class,
        GlobalSearchHeader.class,
        GlobalSearchNavigationAdapter.class,
        TelemetryErrorHandler.class);

    /** Пакет из объявления: тесты модуля и приложения сравниваются по пакету, а не по пути. */
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * Импорт типа модуля: им компилируется потребительский тест. Считается именно импорт, а не
     * упоминание пакета в тексте — иначе счётчик наполнялся бы комментариями и объяснениями
     * в заборах, которые сами живут в приложении.
     */
    private static final Pattern MODULE_IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(?:org\\.ipro\\.form|org\\.ipro\\.vaadin)[\\w.]*;",
        Pattern.MULTILINE);

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /**
     * Импорт Vaadin-типа — это и есть зависимость: {@code import com.vaadin...} или
     * {@code import org.ipro.vaadin...} (второй вариант — обратная ссылка на сам UI-модуль).
     * Простое упоминание пакета в строке (reflection, фильтр кадров) сюда не попадает.
     */
    private static final Pattern VAADIN_IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(com\\.vaadin[\\w.]*|org\\.ipro\\.vaadin[\\w.]*);",
        Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    @Test
    void everyModuleTypeLeftTheApplicationTree() {
        List<String> copies = new ArrayList<>();
        List<String> missingInModule = new ArrayList<>();
        int types = 0;
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            String relative = MODULE.resolve("src/main/java").relativize(source).toString()
                .replace('\\', '/');
            types++;
            if (Files.exists(APPLICATION_SOURCES.resolve(relative))) {
                copies.add(relative);
            }
            if (!Files.exists(source)) {
                missingInModule.add(relative);
            }
        }

        assertThat(types)
            .as("забор не должен быть вакуумным: модуль обязан публиковать 93 типа (D3.5.4)")
            .isEqualTo(93);
        assertThat(copies)
            .as("тип есть и в модуле, и в дереве приложения — это split package: компилятор"
                + " различает такие копии по порядку classpath, а не по замыслу, поэтому"
                + " «перенос» остался бы только на бумаге")
            .isEmpty();
        assertThat(missingInModule)
            .as("исходник модуля исчез между обходом каталога и проверкой")
            .isEmpty();
    }

    /**
     * Не только «файла нет», но и «класс приходит из артефакта». Разница принципиальная: стейл
     * {@code target/classes} после переноса даёт ровно тот же зелёный результат по файлам и
     * полностью маскирует, что приложение собрано против старого дерева.
     */
    @Test
    void moduleClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : SAMPLE) {
            var codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource)
                .as("%s должен быть доступен приложению", type.getName())
                .isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-vaadin, а не из target/classes"
                    + " приложения — иначе тесты проверяют не тот байткод", type.getName())
                .contains("platform-vaadin");
        }
    }

    @Test
    void registrationOwnerIsTheModuleNotTheApplication() {
        assertThat(registeredIn(MODULE))
            .as("конфигурации UI-слоя регистрирует модуль: приложение не сканирует org.ipro,"
                + " поэтому отсутствие строки в imports-файле выключает подсистему молча")
            .isEqualTo(new TreeSet<>(MODULE_AUTO_CONFIGURATIONS));
        assertThat(registeredIn(Path.of(".")))
            .as("приложение больше не регистрирует чужие авто-конфигурации: двойная регистрация"
                + " делает порядок применения неопределённым, а владельца — непроверяемым")
            .doesNotContainAnyElementsOf(MODULE_AUTO_CONFIGURATIONS);
    }

    /**
     * D3.5.8: тест приложения не живёт в пакете модуля.
     *
     * <p>Опасность здесь не формальная. Внутри совпадающего имени пакета package-private тип
     * модуля доступен из другого артефакта (пока приложение собирается на classpath), причём
     * <b>невидимо для заборов</b>: импорта нет — тип лежит в том же пакете, поэтому ни скан
     * импортов, ни реестр ролей такого обращения не видят. Ровно так жил
     * {@code PrdSpecRowCancelAcceptanceTest}, обращаясь к package-private {@code RowDraft}; в
     * D3.5.8 он переехал в прикладной пакет. Прикладные тесты живут в {@code org.ip..}, поэтому
     * совпадение с любым пакетом модуля — дефект адресации, а не стилистика.</p>
     */
    @Test
    void applicationTestsDoNotOccupyTheModulesPackages() {
        Set<String> modulePackages = declaredPackages(MODULE.resolve("src/main/java"));
        modulePackages.addAll(declaredPackages(MODULE.resolve("src/test/java")));

        Map<String, String> offenders = new TreeMap<>();
        for (Path source : javaSources(APPLICATION_TESTS)) {
            Matcher matcher = PACKAGE.matcher(read(source));
            if (matcher.find() && modulePackages.contains(matcher.group(1))) {
                offenders.put(APPLICATION_TESTS.relativize(source).toString().replace('\\', '/'),
                    matcher.group(1));
            }
        }

        assertThat(offenders)
            .as("тест приложения живёт в пакете модуля — это test-side split package: внутри него"
                + " package-private тип модуля доступен через границу артефакта, и это обращение"
                + " не видит ни один забор (импорта нет, тип в том же пакете). Тесты приложения"
                + " должны лежать в org.ip..")
            .isEmpty();
    }

    /**
     * D3.5.8: потребительская сторона не могла опустеть незаметно.
     *
     * <p>Проверка ловит два разных дефекта одним числом. Первый: приложение перестало компилировать
     * тесты против модуля — тогда «перенос проверен» становится утверждением без проверки. Второй:
     * обратный переезд, когда тест «переносят» в модуль, унося с собой прикладные фикстуры, — и в
     * приложении не остаётся ни одной проверки на реальных сущностях.</p>
     */
    @Test
    void consumerTestsStillCompileAgainstTheModule() {
        List<Path> consumers = javaSources(APPLICATION_TESTS).stream()
            .filter(source -> MODULE_IMPORT.matcher(read(source)).find())
            .toList();

        assertThat(consumers)
            .as("потребительские тесты приложения: замерено 32 файла (D3.5.8), порог 30 —"
                + " сокращение ниже порога означает потерю потребительской проверки, а не экономию")
            .hasSizeGreaterThanOrEqualTo(30);
    }

    @Test
    void applicationDeclaresTheModuleAsADependency() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(Path.of("pom.xml")));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }

        assertThat(declared)
            .as("модуль обязан быть объявлен приложением явно: иначе типы попадают в classpath"
                + " транзитивно, и объявленная граница существует только в манифесте")
            .contains("platform-vaadin");
    }

    /**
     * Направление «наблюдение обязано работать без UI» после переезда адаптеров.
     *
     * <p>Пока адаптеры жили в дереве приложения, это свойство было очевидным: модуль их просто
     * не видел. Теперь они лежат в другом платформенном артефакте, и обратная ссылка
     * (telemetry → vaadin) стала технически возможной — а вместе с ней и потеря независимости
     * наблюдения.</p>
     *
     * <p><b>Что именно считается зависимостью.</b> Не «упоминание слова vaadin»: модуль
     * намеренно знает этот стек <i>по имени</i> — берёт версию через
     * {@code Class.forName("com.vaadin.flow.server.Version")} (наблюдение должно работать и
     * когда UI нет) и отсеивает кадры Vaadin в снимках стека по строковому префиксу. Такие
     * обращения не создают compile-связи и не мешают поднимать модуль без UI, поэтому запрещено
     * именно то, что делает зависимость настоящей: объявленный артефакт и импорт типа.</p>
     */
    @Test
    void telemetryModuleDoesNotDependOnTheVaadinModule() {
        assertThat(declaredArtifacts(TELEMETRY_MODULE.resolve("pom.xml")))
            .as("platform-telemetry не может зависеть от UI-модуля: наблюдение обязано"
                + " подниматься без UI, это и был смысл выворота цикла на шаге 8а")
            .noneMatch(artifact -> artifact.contains("vaadin"));

        List<String> imports = new ArrayList<>();
        for (Path source : javaSources(TELEMETRY_MODULE.resolve("src/main/java"))) {
            Matcher matcher = VAADIN_IMPORT.matcher(withoutComments(read(source)));
            while (matcher.find()) {
                imports.add(source.getFileName() + " -> " + matcher.group(1));
            }
        }

        assertThat(imports)
            .as("Vaadin-адаптеры живут в platform-vaadin (org.ipro.vaadin.telemetry), а не"
                + " в platform-telemetry: импорт Vaadin-типа сделал бы модуль наблюдения"
                + " UI-зависимым и отнял бы право подниматься без UI")
            .isEmpty();
    }

    private static Set<String> declaredPackages(Path root) {
        Set<String> packages = new TreeSet<>();
        for (Path source : javaSources(root)) {
            Matcher matcher = PACKAGE.matcher(read(source));
            if (matcher.find()) {
                packages.add(matcher.group(1));
            }
        }
        return packages;
    }

    private static Set<String> registeredIn(Path root) {
        Path file = root.resolve("src/main/resources").resolve(IMPORTS_RESOURCE);
        if (!Files.exists(file)) {
            return Set.of();
        }
        return new TreeSet<>(lines(file));
    }

    private static Set<String> declaredArtifacts(Path pom) {
        Set<String> artifacts = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(pom));
        while (matcher.find()) {
            artifacts.add(matcher.group(1));
        }
        return artifacts;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
