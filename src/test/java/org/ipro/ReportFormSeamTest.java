package org.ipro;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * D3.5.7 — контракт шва <b>Report → Form</b>: report-слой называет типы UI-платформы, и этот
 * набор обязан быть reviewed, узким и односторонним.
 *
 * <p><b>Зачем отдельный забор.</b> Физическую односторонность держит
 * {@code VaadinModuleCompositionTest} (platform-vaadin не знает ни report, ни DR/JR/UReport) —
 * но это ответ только на половину вопроса. Вторая половина не проверялась ничем: какой именно
 * поверхностью report вправе пользоваться. Сегодня ответ «правильный» держится на дисциплине
 * автора, а срез D3.6 (report становится отдельным артефактом-потребителем) выполняется ровно
 * по этому набору типов: незаписанный новый тип означал бы, что контракт шва расширился молча,
 * а взятый из реализации тип (INTERNAL) — что {@code report-vaadin} унесёт с собой утечку,
 * которую потом заморозят как API (та же ошибка, от которой план D3.5 защищался порядком
 * шагов).</p>
 *
 * <p><b>Почему измерение по исходникам, а не по байткоду.</b> Это измеренный факт, а не
 * стилистика. {@code org.ipro.form.SelectionForm} попадает в шов только через каст
 * ({@code (SelectionForm) assembler.assemble(...)} в {@code ReportParamForm}): проверка
 * байткод-зависимостей ArchUnit'а ({@code getDirectDependenciesFromSelf}) такой ссылки не
 * видит, и шов оказался бы на тип уже — а «уже» здесь означает «контракт неполон».
 * Взамен забор обязан сам компенсировать слабость сканирования: комментарии и строковые
 * литералы снимаются (иначе упоминание типа в javadoc считалось бы употреблением), а корни
 * слоя объявлены в реестре — то есть проверка не выводит слой из путей, а читает решение.</p>
 *
 * <p><b>Почему забор не умрёт при переезде.</b> Корни слоя — reviewed-строки реестра, а
 * держатели записаны FQN (D3.5.4 сохранил FQN при переносе). При переезде report в артефакт
 * меняются строки {@code layer}, а не измерения. Если строку не обновить, забор упадёт с
 * «корень слоя не найден» — то есть отсутствие измерения остаётся видимым, а не превращается
 * в зелёное «шов пуст».</p>
 */
class ReportFormSeamTest {

    private static final Path REGISTRY = Path.of("src/test/resources/report-form-seam.txt");

    /** Единственный источник роли типа: реестр ролей UI-платформы (D3.5.0). */
    private static final Path PLATFORM_SURFACE = Path.of("src/test/resources/platform-vaadin-surface.txt");

    /** Относительные пути корней слоя в реестре читаются отсюда. */
    private static final Path PROJECT_ROOT = Path.of(".");

    /** Пространство имён UI-платформы: этим исчерпывается «свой» код платформы. */
    private static final String PLATFORM_FORM = "org.ipro.form.";

    private static final String PLATFORM_VAADIN = "org.ipro.vaadin.";

    private static final Set<String> PUBLISHED_ROLES = Set.of("APP_API", "APP_SPI");

    /** Ожидаемый минимум исходников слоя: сознательная помеха «слой стал пустым / не тем». */
    private static final int MIN_LAYER_SOURCES = 100;

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    private static final Pattern IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(org\\.ipro\\.(?:form|vaadin)\\.[A-Za-z0-9_.]+)\\s*;",
        Pattern.MULTILINE);

    private static final Pattern REFERENCE =
        Pattern.compile("(?<![\\w.])org\\.ipro\\.(?:form|vaadin)\\.[A-Za-z0-9_.]+");

    /** Production-классы приложения и платформы: потребители типов шва считаются по ним. */
    private static final Set<JavaClass> PRODUCTION_CLASSES = new LinkedHashSet<>(
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.ipro", "org.ip"));

    @Test
    void everyPlatformTypeTheReportLayerNamesIsReviewed() {
        Registry registry = readRegistry();
        Seam measured = measuredSeam();

        Set<String> undeclared = new TreeSet<>(measured.holders().keySet());
        undeclared.removeAll(registry.holders().keySet());
        Set<String> stale = new TreeSet<>(registry.holders().keySet());
        stale.removeAll(measured.holders().keySet());

        assertThat(undeclared)
            .as("report-слой начал называть тип UI-платформы, которого нет в реестре шва. Пока"
                + " тип не записан, контракт расширяется молча, а срез D3.6 придётся делать по"
                + " неизвестному набору. Добавьте строку `seam <fqn> <держатель>` в %s и решите,"
                + " опубликован ли этот тип (роль в platform-vaadin-surface.txt)",
                REGISTRY.getFileName())
            .isEmpty();
        assertThat(stale)
            .as("реестр шва описывает тип, которого слой больше не называет: список обязан"
                + " совпадать с фактом в обе стороны, иначе он превращается в перечень желаний")
            .isEmpty();
    }

    /**
     * Роль берётся из реестра ролей, а не повторяется здесь: два записанных решения об одном
     * типе расходятся (именно так D1 держал {@code legacy-internal} на типах, объявленных
     * {@code APP_API}), поэтому сверка роли — настоящая проверка, а не пересказ.
     */
    @Test
    void everySeamTypeHasAPublishedRole() {
        Registry registry = readRegistry();
        Map<String, String> roles = platformRoles();

        Map<String, String> unpublished = new TreeMap<>();
        for (String type : registry.holders().keySet()) {
            String owner = ownerOf(type, roles.keySet());
            if (owner == null) {
                unpublished.put(type, "<нет записи в platform-vaadin-surface.txt>");
            } else if (!PUBLISHED_ROLES.contains(roles.get(owner))) {
                unpublished.put(type, roles.get(owner));
            }
        }

        assertThat(unpublished)
            .as("тип шва обязан иметь опубликованную роль (APP_API/APP_SPI). INTERNAL в шве"
                + " означает, что report опирается на реализацию, а не на контракт: при срезе"
                + " D3.6 такая ссылка уедет в report-vaadin и будет заморожена там как API —"
                + " ровно то, от чего план D3.5 защищался порядком шагов. Либо тип повышается до"
                + " роли осознанно, либо слой перестаёт его называть")
            .isEmpty();
    }

    /**
     * Держатели — это и есть чек-лист среза D3.6: они переезжают вместе со слоем. Записаны
     * FQN, поэтому переезд правит только корни слоя, а не сами записи.
     */
    @Test
    void declaredHoldersMatchTheCode() {
        Registry registry = readRegistry();
        Seam measured = measuredSeam();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : registry.holders().entrySet()) {
            Set<String> actual = measured.holders().getOrDefault(entry.getKey(), Set.of());
            Set<String> added = new TreeSet<>(actual);
            added.removeAll(entry.getValue());
            Set<String> removed = new TreeSet<>(entry.getValue());
            removed.removeAll(actual);
            if (!added.isEmpty()) {
                problems.add(entry.getKey() + ": новый держатель " + added);
            }
            if (!removed.isEmpty()) {
                problems.add(entry.getKey() + ": держателя больше нет — " + removed);
            }
        }

        assertThat(problems)
            .as("набор держателей шва разошёлся с кодом. Держатель — это место, которое при"
                + " переезде report обязано уехать вместе со слоем: новый означает, что шов"
                + " расширился, пропавший — что запись переехала или класс раздроблен, и это"
                + " решение, а не случайность")
            .isEmpty();
    }

    /**
     * Типы, которых вне шва не касается никто: пока это так, слой можно переносить без
     * последствий. Новый потребитель — не поломка, а сигнал, что у типа начинается жизнь вне
     * шва, и решение об этом принимается явно.
     */
    @Test
    void typesHeldOnlyByTheSeamHaveNoOtherConsumers() {
        Registry registry = readRegistry();
        Set<String> holders = new TreeSet<>();
        registry.holders().values().forEach(holders::addAll);

        List<String> problems = new ArrayList<>();
        for (String type : registry.onlyTypes()) {
            Set<String> outside = new TreeSet<>();
            for (JavaClass clazz : PRODUCTION_CLASSES) {
                String name = clazz.getName();
                if (holders.contains(stripNested(name)) || isPlatformClass(name)) {
                    continue;
                }
                boolean uses = clazz.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().getName().startsWith(type));
                if (uses) {
                    outside.add(name);
                }
            }
            if (!outside.isEmpty()) {
                problems.add(type + ": " + outside);
            }
        }

        assertThat(problems)
            .as("тип, объявленный живущим только за счёт шва, стал нужен вне его. Это не ошибка"
                + " сама по себе, но решение: либо потребитель законен (тогда снимите строку"
                + " `only` и принимайте тип как обычный API), либо ссылка случайна. Оставить"
                + " реестр нельзя — иначе он описывает не тот шов, который есть")
            .isEmpty();
    }

    /**
     * Вакуум страшнее ошибки: пустой или неразрешимый корень слоя превратил бы все проверки
     * выше в зелёные «шов пуст». Ровно этот класс ошибки ловили в D3.5.5, когда
     * {@code ApplicationContextRunner.withBean} маскировал отсутствие измерения.
     */
    @Test
    void everyDeclaredLayerRootResolvesAndYieldsSources() {
        Registry registry = readRegistry();
        List<String> problems = new ArrayList<>();
        int total = 0;

        for (String relative : registry.layers()) {
            Path root = PROJECT_ROOT.resolve(relative);
            if (!Files.exists(root)) {
                problems.add(relative + ": корень слоя не найден — при переезде слоя обновите"
                    + " строку `layer`; отсутствие измерения не имеет права быть зелёным");
                continue;
            }
            List<Path> sources = sources(root);
            if (sources.isEmpty()) {
                problems.add(relative + ": корень не содержит исходников");
            }
            total += sources.size();
        }

        assertThat(problems).as("объявленные корни слоя обязаны разрешаться").isEmpty();
        assertThat(total)
            .as("слой обязан быть непустым: забор на пустом множестве не проверяет ничего")
            .isGreaterThanOrEqualTo(MIN_LAYER_SOURCES);
    }

    @Test
    void registryIsWellFormedAndNonVacuous() {
        Registry registry = readRegistry();

        assertThat(registry.layers())
            .as("корни слоя обязаны быть объявлены: без них забор не знает, что измерять")
            .isNotEmpty();
        assertThat(registry.holders())
            .as("шов не должен быть пустым: пустой список прошёл бы проверки выше")
            .isNotEmpty();
        assertThat(registry.onlyTypes())
            .as("объявления `only` обязаны быть подмножеством шва: иначе они описывают тип,"
                + " которого в контракте нет")
            .isSubsetOf(registry.holders().keySet());
        assertThat(registry.holders().values())
            .as("у каждого типа шва обязан быть хотя бы один объявленный держатель")
            .allSatisfy(holders -> assertThat(holders).isNotEmpty());
    }

    // === Реестр ===

    private record Registry(List<String> layers, Map<String, Set<String>> holders,
                            Set<String> onlyTypes) {
    }

    private static Registry readRegistry() {
        assertThat(Files.exists(REGISTRY))
            .as("реестр шва обязан быть в поставке. Гейт, чьи данные игнорируются VCS, защищает"
                + " только машину автора: на чистом checkout файла просто нет, а тест читает его"
                + " как источник истины. Проверьте, что для %s есть исключение `!` в .gitignore —"
                + " это уже дважды случалось с README платформенных модулей", REGISTRY)
            .isTrue();

        List<String> layers = new ArrayList<>();
        Map<String, Set<String>> holders = new LinkedHashMap<>();
        Set<String> onlyTypes = new LinkedHashSet<>();

        for (String line : lines(REGISTRY)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalStateException("Строка реестра шва не разобрана: " + line);
            }
            switch (parts[0]) {
                case "layer" -> layers.add(parts[1]);
                case "seam" -> holders.put(parts[1],
                    new LinkedHashSet<>(List.of(parts).subList(2, parts.length)));
                case "only" -> onlyTypes.add(parts[1]);
                default -> throw new IllegalStateException("Неизвестный вид строки реестра шва: " + line);
            }
        }
        return new Registry(layers, holders, onlyTypes);
    }

    private static Map<String, String> platformRoles() {
        Map<String, String> roles = new LinkedHashMap<>();
        for (String line : lines(PLATFORM_SURFACE)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            roles.put(parts[1], parts[0]);
        }
        return roles;
    }

    /** Владелец покрывает вложенный тип: роль вложенного наследуется от владельца. */
    private static String ownerOf(String type, Set<String> owners) {
        String candidate = type;
        while (candidate != null) {
            if (owners.contains(candidate)) {
                return candidate;
            }
            int dot = candidate.lastIndexOf('.');
            candidate = dot > 0 ? candidate.substring(0, dot) : null;
        }
        return null;
    }

    // === Измерение слоя ===

    private record Seam(Map<String, Set<String>> holders) {
    }

    private static Seam measuredSeam() {
        Map<String, Set<String>> byType = new TreeMap<>();
        for (String relative : readRegistry().layers()) {
            Path root = PROJECT_ROOT.resolve(relative);
            if (!Files.exists(root)) {
                continue;
            }
            for (Path file : sources(root)) {
                String code = withoutCommentsAndLiterals(read(file));
                Matcher packageMatcher = PACKAGE.matcher(code);
                if (!packageMatcher.find()) {
                    continue;
                }
                String name = file.getFileName().toString();
                String fqn = packageMatcher.group(1) + "."
                    + name.substring(0, name.length() - ".java".length());

                Set<String> tokens = new TreeSet<>();
                Matcher imports = IMPORT.matcher(code);
                while (imports.find()) {
                    tokens.add(imports.group(1));
                }
                Matcher references = REFERENCE.matcher(code);
                while (references.find()) {
                    tokens.add(references.group());
                }
                tokens.forEach(type ->
                    byType.computeIfAbsent(type, key -> new TreeSet<>()).add(fqn));
            }
        }
        return new Seam(byType);
    }

    private static List<Path> sources(Path root) {
        if (Files.isRegularFile(root)) {
            return root.toString().endsWith(".java") ? List.of(root) : List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Свой код платформы: внутреннее употребление типа не делает его «нужным снаружи шва». */
    private static boolean isPlatformClass(String name) {
        return name.startsWith(PLATFORM_FORM) || name.startsWith(PLATFORM_VAADIN);
    }

    private static String stripNested(String name) {
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    // === Чтение и очистка текста ===

    private static List<String> lines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Убирает комментарии и строковые литералы: имя типа в javadoc или в строке — не
     * зависимость. Та же нестрогость нашлась в D1 (там комментарий считался употреблением), и
     * здесь она закрыта сразу.
     */
    private static String withoutCommentsAndLiterals(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < text.length() && text.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index < text.length() && !(text.charAt(index) == '*'
                        && index + 1 < text.length() && text.charAt(index + 1) == '/')) {
                    index++;
                }
                index += 2;
            } else if (current == '"') {
                index++;
                while (index < text.length() && text.charAt(index) != '"') {
                    index += text.charAt(index) == '\\' ? 2 : 1;
                }
                index++;
            } else if (current == '"') {
                index++;
                while (index < text.length() && text.charAt(index) != '"') {
                    index += text.charAt(index) == '\\' ? 2 : 1;
                }
                index++;
            } else if (current == '\'') {
                index++;
                while (index < text.length() && text.charAt(index) != '\'') {
                    index += text.charAt(index) == '\\' ? 2 : 1;
                }
                index++;
            } else {
                out.append(current);
                index++;
            }
        }
        return out.toString();
    }
}
