package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (persistence slice): entity и репозитории не могут потеряться молча.
 *
 * <p>Карта D1 §3.5 назвала главным риском выноса то, что persistence-типы регистрируются
 * <b>централизованно</b>: {@code @EntityScan} в приложении и {@code @EnableJpaRepositories}
 * в платформенном хабе. Пока всё в одном артефакте, забытая строка — вопрос вкуса. После
 * выноса та же строка означает, что артефакт не поднимется, а признак может появиться
 * поздно: репозиторий просто не станет бином, entity — не попадёт в persistence unit.</p>
 *
 * <p>Этот тест делает состав регистраций проверяемым: каждый {@code @Entity} в дереве и в
 * модулях обязан попадать в объявленный {@code @EntityScan}-пакет, каждый
 * {@code JpaRepository} — в объявленный {@code @EnableJpaRepositories}-пакет, а сам список
 * объявленных пакетов — reviewed. Ничего не запускается: проверка читает объявления,
 * поэтому ловит забытую строку до старта контекста.</p>
 */
class PersistenceTypeRegistrationTest {

    private static final List<Path> SOURCE_ROOTS = List.of(
        Path.of("src/main/java"),
        Path.of("platform-contracts/src/main/java"),
        Path.of("platform-events/src/main/java"),
        Path.of("platform-persistence/src/main/java"),
        Path.of("platform-numbering/src/main/java"),
        Path.of("platform-settings/src/main/java"),
        Path.of("platform-telemetry/src/main/java"),
        Path.of("platform-rls/src/main/java"));

    /** Пакеты, которые обязаны быть объявлены в @EntityScan (reviewed). */
    private static final Set<String> REVIEWED_ENTITY_PACKAGES = Set.of(
        "org.ip.model",
        "org.ipro.telemetry.model",
        "org.ipro.rls",
        "org.ipro.reportstudio.dom",
        "org.ipro.numbering",
        "org.ipro.settings",
        "org.ipro.ureport.dom",
        "org.ipro.jr.dom");

    /** Пакеты, которые обязаны быть объявлены в @EnableJpaRepositories (reviewed). */
    private static final Set<String> REVIEWED_REPOSITORY_PACKAGES = Set.of(
        "org.ip",
        "org.ipro.rls",
        "org.ipro.reportstudio",
        "org.ipro.numbering",
        "org.ipro.settings",
        "org.ipro.ureport",
        "org.ipro.telemetry.repository",
        "org.ipro.jr");

    /**
     * Артефакты, которые объявляют себе persistence-регистрацию (reviewed). Список
     * выводится из объявлений, а не из имён каталогов: модуль без {@code @EntityScan} и
     * {@code @EnableJpaRepositories} сюда не попадёт и в проверке срезов участвовать не будет.
     */
    private static final Set<String> REVIEWED_SELF_REGISTERING_MODULES = Set.of(
        "platform-persistence", "platform-numbering", "platform-settings", "platform-telemetry",
        "platform-rls");

    private static final Pattern SLICE = Pattern.compile("^\\s*@DataJpaTest\\b", Pattern.MULTILINE);
    /**
     * Строка импорта. Регистрация должна быть <b>использована</b>: лежащий без дела импорт
     * ничего не подключает, поэтому при проверке ссылки импорты убираются.
     */
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+[^;]+;\\s*$", Pattern.MULTILINE);
    private static final Pattern AUTO_CONFIGURATION = Pattern.compile(
        "^\\s*@AutoConfiguration\\b", Pattern.MULTILINE);

    private static final Pattern ENTITY = Pattern.compile("^\\s*@Entity\\b", Pattern.MULTILINE);
    private static final Pattern REPOSITORY = Pattern.compile(
        "interface\\s+\\w+.*?extends\\s+[^{]*\\b(JpaRepository|CrudRepository|"
            + "PagingAndSortingRepository|JpaSpecificationExecutor)\\b", Pattern.DOTALL);
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TYPE_NAME = Pattern.compile(
        "(?:class|interface|record|enum)\\s+(\\w+)");
    /**
     * Аннотация ищется только в начале строки и только в коде без комментариев: упоминание
     * {@code @EnableJpaRepositories} в javadoc зависимостью не является, а скобка чужой
     * аннотации легко попала бы в разбор.
     */
    private static final Pattern ANNOTATION = Pattern.compile(
        "^\\s*@(EntityScan|EnableJpaRepositories)\\b", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");

    @Test
    void everyEntityIsCoveredByADeclaredEntityScanPackage() {
        Set<String> declared = new TreeSet<>();
        for (Path source : allSources()) {
            declared.addAll(annotationPackages(withoutComments(read(source)), "EntityScan"));
        }

        List<String> uncovered = new ArrayList<>();
        for (Path source : allSources()) {
            if (!isEntity(source)) {
                continue;
            }
            String type = qualifiedName(source);
            if (!isCoveredBy(type, declared)) {
                uncovered.add(type);
            }
        }

        assertThat(uncovered)
            .as("entity вне объявленного @EntityScan не попадёт в persistence unit — это"
                + " ошибка конфигурации, которая в худшем случае проявится только при запросе")
            .isEmpty();
        assertThat(declared)
            .as("список @EntityScan-пакетов reviewed: расширение или сужение — отдельное"
                + " решение, а не следствие переноса типа")
            .isEqualTo(new TreeSet<>(REVIEWED_ENTITY_PACKAGES));
    }

    @Test
    void everyRepositoryIsCoveredByADeclaredEnableJpaRepositoriesPackage() {
        Set<String> declared = new TreeSet<>();
        for (Path source : allSources()) {
            declared.addAll(annotationPackages(withoutComments(read(source)),
                "EnableJpaRepositories"));
        }

        List<String> uncovered = new ArrayList<>();
        for (Path source : allSources()) {
            if (!isRepository(source)) {
                continue;
            }
            String type = qualifiedName(source);
            if (!isCoveredBy(type, declared)) {
                uncovered.add(type);
            }
        }

        assertThat(uncovered)
            .as("репозиторий вне объявленного @EnableJpaRepositories не станет бином:"
                + " при выносе артефакта это самый тихий способ потерять persistence")
            .isEmpty();
        assertThat(declared)
            .as("список @EnableJpaRepositories-пакетов reviewed. Деклараций может быть"
                + " несколько (приложение, платформенный хаб, модуль) — они не перекрывают"
                + " друг друга, и это проверяется контекстом в PersistenceRegistrationIT")
            .isEqualTo(new TreeSet<>(REVIEWED_REPOSITORY_PACKAGES));
    }

    /**
     * Срезы (`@DataJpaTest`) отключают авто-конфигурации, поэтому пакеты модуля в них надо
     * подключать явно. Именно на этом сломался реальный прогон дважды: сначала срез перечислял
     * `org.ipro.jr` в своём `@EnableJpaRepositories`, но сущность осталась без `@EntityScan`,
     * потом то же повторилось с `org.ipro.settings`.
     *
     * <p>Проверка ловит класс ошибки, а не конкретный файл, и не знает заранее ни одного
     * имени: модули и их пакеты берутся из объявлений самих модулей, поэтому новый вынесенный
     * модуль попадает под правило сам, без правки теста.</p>
     */
    @Test
    void sliceConfigurationsThatNameModulePackagesImportTheModuleRegistration() {
        List<ModuleRegistration> modules = moduleRegistrations();
        assertThat(modules.stream().map(ModuleRegistration::artifact).sorted().toList())
            .as("модули, объявляющие себе persistence-регистрацию, — reviewed: новый модуль с"
                + " сущностями должен появиться здесь вместе со своим решением о регистрации")
            .containsExactlyElementsOf(REVIEWED_SELF_REGISTERING_MODULES.stream().sorted().toList());

        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = withoutComments(read(source));
                if (!SLICE.matcher(text).find()) {
                    continue;
                }
                Set<String> literals = new TreeSet<>();
                Matcher matcher = STRING_LITERAL.matcher(text);
                while (matcher.find()) {
                    literals.add(matcher.group(1));
                }
                for (ModuleRegistration module : modules) {
                    String named = touchedPackage(module, text, literals);
                    if (named == null) {
                        continue;
                    }
                    String body = IMPORT.matcher(text).replaceAll(" ");
                    boolean registrationUsed = module.configurations().stream()
                        .anyMatch(body::contains);
                    if (!registrationUsed) {
                        problems.add(source + ": срез трогает " + named + " из "
                            + module.artifact() + ", но не подключает его регистрацию ("
                            + String.join(" / ", module.configurations()) + ")");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(problems)
            .as("@DataJpaTest отключает авто-конфигурации: без явного подключения модуль теряет"
                + " свои @EntityScan/@EnableJpaRepositories, и сущность выпадает из persistence unit")
            .isEmpty();
    }

    @Test
    void extractedSettingsTypesLiveInTheModuleAndNotInTheTree() {
        assertThat(Path.of("src/main/java/org/ipro/settings/SettingValue.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/settings/SettingsService.java")).doesNotExist();
        assertThat(Path.of("platform-settings/src/main/java/org/ipro/settings/SettingValue.java"))
            .exists();
    }

    @Test
    void extractedTelemetryTypesLiveInTheModuleAndNotInTheTree() {
        assertThat(Path.of("src/main/java/org/ipro/telemetry/api/Telemetry.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/core/TelemetryService.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/model/OperationLogEntity.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/repository/OperationLogRepository.java"))
            .doesNotExist();
        assertThat(Path.of("platform-telemetry/src/main/java/org/ipro/telemetry/api/Telemetry.java"))
            .exists();
        assertThat(Path.of("platform-telemetry/src/main/java/org/ipro/telemetry/core/TelemetryService.java"))
            .exists();
    }

    /**
     * Артефакт, объявивший себе persistence-регистрацию: его persistence-типы, их пакеты и
     * классы-регистраторы.
     *
     * <p>Срез обязан подключать регистрацию тогда, когда он трогает persistence-типы модуля
     * (сущности и Spring Data репозитории). Бин модуля сам по себе — не повод: срез вправе
     * сконструировать его вручную или замокать (`CanonicalWritePathIT` мокает
     * `NumberingService` и не нуждается ни в одной сущности нумерации).</p>
     */
    private record ModuleRegistration(String artifact, Set<String> packages, Set<String> types,
                                      Set<String> configurations) {}

    /**
     * Модули выводятся из reviewed-списка {@link #SOURCE_ROOTS}: «модуль» — всё, что не дерево
     * приложения. Пакет считается принадлежащим модулю, если модуль объявил его сам, а класс —
     * регистратором, если он несёт {@code @AutoConfiguration}.
     */
    private static List<ModuleRegistration> moduleRegistrations() {
        List<ModuleRegistration> modules = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            if (root.equals(Path.of("src/main/java"))) {
                continue;
            }
            Set<String> declared = new TreeSet<>();
            Set<String> packages = new TreeSet<>();
            Set<String> types = new TreeSet<>();
            Set<String> configurations = new TreeSet<>();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = withoutComments(read(source));
                    declared.addAll(annotationPackages(text, "EntityScan"));
                    declared.addAll(annotationPackages(text, "EnableJpaRepositories"));
                    if (isEntity(source) || isRepository(source)) {
                        packages.add(packageOf(source));
                        types.add(qualifiedName(source));
                    }
                    if (AUTO_CONFIGURATION.matcher(text).find()) {
                        configurations.add(source.getFileName().toString().replace(".java", ""));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!declared.isEmpty()) {
                modules.add(new ModuleRegistration(root.getName(0).toString(), packages, types,
                    configurations));
            }
        }
        return modules;
    }

    /**
     * Пакет модуля, который трогает срез: по полному имени persistence-типа (импорт или
     * inline-ссылка) либо по строковому литералу пакета или его подпакета. Возвращает имя
     * пакета или {@code null}, если срез ничего из этого модуля не трогает.
     */
    private static String touchedPackage(ModuleRegistration module, String text,
                                         Set<String> literals) {
        for (String type : module.types()) {
            if (text.contains(type)) {
                return type;
            }
        }
        for (String pkg : module.packages()) {
            boolean named = literals.stream()
                .anyMatch(literal -> literal.equals(pkg) || literal.startsWith(pkg + "."));
            if (named) {
                return pkg;
            }
        }
        return null;
    }

    @Test
    void extractedPersistenceTypesLiveInTheModuleAndNotInTheTree() {
        assertThat(Path.of("src/main/java/org/ipro/jr/dom/JrxmlTemplate.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/jr/JrxmlTemplateRepository.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/crud/BaseEntity.java"))
            .as("база сущностей — часть persistence-капсулы: без неё entity не выносится")
            .doesNotExist();
        assertThat(Path.of("platform-persistence/src/main/java/org/ipro/crud/BaseEntity.java"))
            .exists();
    }

    private static boolean isEntity(Path source) {
        return ENTITY.matcher(read(source)).find();
    }

    private static boolean isRepository(Path source) {
        return REPOSITORY.matcher(read(source)).find();
    }

    /** Spring сканирует и вложенные пакеты, поэтому покрытие — это префикс пакета. */
    private static boolean isCoveredBy(String type, Set<String> declaredPackages) {
        return declaredPackages.stream().anyMatch(pkg -> type.startsWith(pkg + "."));
    }

    private static String qualifiedName(Path source) {
        return packageOf(source) + "." + typeName(source);
    }

    private static String packageOf(Path source) {
        Matcher packageMatcher = PACKAGE.matcher(read(source));
        if (!packageMatcher.find()) {
            throw new IllegalStateException("нет package: " + source);
        }
        return packageMatcher.group(1);
    }

    private static String typeName(Path source) {
        Matcher typeMatcher = TYPE_NAME.matcher(read(source));
        if (!typeMatcher.find()) {
            throw new IllegalStateException("нет объявления типа: " + source);
        }
        return typeMatcher.group(1);
    }

    /**
     * Имена пакетов, перечисленные в аннотации: поддерживаются строка и массив строк. Из
     * параметров аннотации берутся только литералы, похожие на имя пакета, — иначе служебные
     * значения вроде {@code entityManagerFactoryRef = "entityManagerFactory"} попали бы в
     * reviewed-список.
     */
    private static List<String> annotationPackages(String text, String annotation) {
        List<String> packages = new ArrayList<>();
        Matcher matcher = ANNOTATION.matcher(text);
        while (matcher.find()) {
            if (!matcher.group(1).equals(annotation)) {
                continue;
            }
            int open = text.indexOf('(', matcher.end());
            if (open < 0) {
                continue;
            }
            int depth = 0;
            int index = open;
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current == '(') {
                    depth++;
                } else if (current == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                index++;
            }
            Matcher literals = STRING_LITERAL.matcher(text.substring(open, index));
            while (literals.find()) {
                if (literals.group(1).startsWith("org.")) {
                    packages.add(literals.group(1));
                }
            }
        }
        return packages;
    }

    private static String withoutComments(String text) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    private static List<Path> allSources() {
        Set<Path> sources = new LinkedHashSet<>();
        for (Path root : SOURCE_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return List.copyOf(sources);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
