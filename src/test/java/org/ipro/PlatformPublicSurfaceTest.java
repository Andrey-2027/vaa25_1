package org.ipro;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 — классификация публичной поверхности платформы <b>по типам</b>, а не по пакетам.
 *
 * <p>Разбор D1/D2 нашёл здесь дыру, которая сводила карту к описанию: roadmap требовал
 * «классифицировать классы как API, SPI или internal», а документ дал таблицу по пакетам и
 * прямо оговорился, что полного решения «тип → роль» нет. Направления зависимостей были
 * закрыты восемью правилами ArchUnit, но правило ArchUnit — это blacklist: зависимость,
 * не упомянутая ни в одном правиле, проходит молча. Прикладной код мог начать называть ещё
 * один внутренний класс платформы, и ни один гейт этого бы не заметил.</p>
 *
 * <p>Забор построен на reviewed-реестре {@code platform-public-surface.txt}: каждая роль —
 * решение, записанное рядом с FQN, а не вывод, который тест делает сам (иначе проверялось бы
 * то же правило, которым список построен).</p>
 *
 * <ul>
 * <li><b>API</b> — платформа называет тип, но не наследует. Пропажа ломает сборку.</li>
 * <li><b>SPI</b> — приложение реализует/расширяет, платформа вызывает обратно. Ломать только
 *     с планом.</li>
 * <li><b>legacy-internal</b> — тип, который приложение называть не должно (reach-through в
 *     реализацию подсистемы), но называет сегодня. Реестр фиксирует <b>точный набор файлов,
 *     которые его называют</b>: новый файл ломает сборку, снятая ссылка обязана исчезнуть из
 *     реестра. Список только сокращается — бюджет ниже.</li>
 * </ul>
 *
 * <p>Роль внутреннего типа — это долг, а не разрешение: запись в реестре означает «здесь
 * ссылка уже есть», а не «здесь ссылку можно добавлять».</p>
 *
 * <p><b>Почему ссылка распознаётся не только по {@code import}.</b> Первая версия этого забора
 * измерения читала строки {@code import}, и этого оказалось мало: приложение пять раз писало тип
 * fully-qualified ({@code implements org.ipro.form.spi.WorkspaceGateway}, {@code org.ipro.jr.run.JpqlDatasetRunner},
 * {@code org.ipro.metadata.annotation.SectionRlsPolicy}, {@code org.ipro.jr.service.JrxmlTemplateService},
 * {@code org.ipro.reportstudio.query.ServiceParams}), а четыре файла импортировали пакет
 * wildcard-ом. Ни один из пяти типов не попадал в измерение, значит не попадал и в реестр — то
 * есть правило «приложение не называет незарегистрированный тип» существовало только для тех,
 * кто пишет импорты единообразно. Теперь ссылка распознаётся во всех трёх синтаксисах, а два из
 * них ещё и запрещены отдельно: wildcard-импорт платформенного пакета скрывает набор типов, а
 * fully-qualified ссылку нельзя перечислить в реестре, не разрешив её по classpath.</p>
 */
class PlatformPublicSurfaceTest {

    private static final Path APP_MAIN_SOURCES = Path.of("src/main/java");

    /** Пакет приложения: платформа обязана работать без него, он же — источник ссылок. */
    private static final Path APPLICATION_PACKAGE = Path.of("src/main/java/org/ip");

    private static final Path REGISTRY = Path.of("src/test/resources/platform-public-surface.txt");

    /**
     * reviewed-бюджет внутренних ссылок. Сокращать — задача этапа D (снятие reach-through),
     * увеличивать — только осознанным решением с объяснением, почему новый внутренний тип
     * обязан быть виден приложению.
     */
    /** Снятые по фазам ссылки: CurrentUser (4 файла) и JpaGlobalSearchProvider (1) — до D3.4,
     *  LookupService/ServiceLocator — D3.5, report-типы — D3.7. */
    private static final int LEGACY_INTERNAL_BUDGET = 72;

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(org\\.ipro\\.[A-Za-z0-9_.]+);", Pattern.MULTILINE);

    /** Wildcard-импорт пакета платформы: за ним скрыт неизвестный набор типов. */
    private static final Pattern PLATFORM_WILDCARD =
        Pattern.compile("^import\\s+(?:static\\s+)?org\\.ipro\\.[A-Za-z0-9_.]*\\*;", Pattern.MULTILINE);

    /** Fully-qualified употребление типа платформы: импорт для него не требуется. */
    private static final Pattern QUALIFIED = Pattern.compile("(?<![\\w.])org\\.ipro\\.[A-Za-z0-9_.]+");

    /** Строка замера в документе: {@code ключ=число}. */
    private static final Pattern MEASUREMENT = Pattern.compile("^\\s*([a-z-]+)\\s*=\\s*(\\d+)\\s*$");

    private static final String WRITE_MEASUREMENTS_PROPERTY = "platform.measurements.write";

    private enum Role {
        API, SPI, LEGACY_INTERNAL
    }

    private record Registry(Map<String, Role> roles, Map<String, Set<String>> internalUsages) {
    }

    @Test
    void everyPlatformTypeTheApplicationNamesHasAReviewedRole() {
        Registry registry = readRegistry();

        Set<String> named = namedPlatformTypes();
        Set<String> unclassified = new TreeSet<>();
        for (String fqn : named) {
            if (!registry.roles().keySet().stream().anyMatch(owner -> covers(owner, fqn))) {
                unclassified.add(fqn);
            }
        }

        assertThat(unclassified)
            .as("приложение называет тип платформы, которого нет в reviewed-реестре. Пока роль"
                + " не назначена, граница держится только на честном слове: это и есть дыра,"
                + " из-за которой 165 внутренних типов оказались публичными. Добавьте строку"
                + " в %s с ролью API, SPI или legacy-internal", REGISTRY);
    }

    @Test
    void registryDoesNotCarryTypesTheApplicationNoLongerNames() {
        Registry registry = readRegistry();

        Set<String> named = namedPlatformTypes();
        Set<String> stale = new TreeSet<>();
        for (String fqn : registry.roles().keySet()) {
            if (!named.contains(fqn) && named.stream().noneMatch(used -> covers(fqn, used))) {
                stale.add(fqn);
            }
        }

        assertThat(stale)
            .as("реестр описывает тип, который приложение больше не называет: список обязан"
                + " совпадать с фактом в обе стороны, иначе он превращается в перечень желаний")
            .isEmpty();
    }

    @Test
    void legacyInternalBudgetOnlyShrinks() {
        Registry registry = readRegistry();

        long internal = registry.roles().values().stream()
            .filter(role -> role == Role.LEGACY_INTERNAL)
            .count();

        assertThat(internal)
            .as("reach-through в реализацию — это измеренный долг, а не нормa. Бюджет может"
                + " только уменьшаться (снятие ссылки); если новый тип обязан быть виден"
                + " приложению, это отдельное решение, и его надо назвать здесь явно")
            .isLessThanOrEqualTo(LEGACY_INTERNAL_BUDGET);
    }

    @Test
    void legacyInternalReferencesAreFrozenPerFile() {
        Registry registry = readRegistry();

        Map<String, Set<String>> actual = actualInternalUsages(registry);
        Map<String, Set<String>> reviewed = registry.internalUsages();

        for (Map.Entry<String, Set<String>> entry : reviewed.entrySet()) {
            Set<String> added = new TreeSet<>(actual.getOrDefault(entry.getKey(), Set.of()));
            added.removeAll(entry.getValue());
            Set<String> removed = new TreeSet<>(entry.getValue());
            removed.removeAll(actual.getOrDefault(entry.getKey(), Set.of()));

            assertThat(added)
                .as("новый файл приложения начал называть внутренний тип %s. Это ровно та"
                    + " ссылка, которую реестр запрещает: внутренний тип приложение знать не"
                    + " должно. Если тип нужен по делу — его место в API/SPI, и это решение,"
                    + " а не строка в списке", entry.getKey())
                .isEmpty();
            assertThat(removed)
                .as("файл больше не называет %s: снятую ссылку надо убрать из реестра, он"
                    + " только сокращается", entry.getKey())
                .isEmpty();
        }
    }

    /**
     * Wildcard-импорт платформенного пакета запрещён отдельно.
     *
     * <p>Ссылка через {@code import org.ipro.metadata.annotation.*} видна реестру только как
     * пакет: какой именно тип использован, знает компилятор, а не строка импорта. Пока такие
     * импорты существуют, полнота реестра держится на догадке; после запрета — на синтаксисе.</p>
     */
    @Test
    void applicationCodeDoesNotUsePlatformWildcardImports() {
        Map<String, Set<String>> offenders = new TreeMap<>();
        for (Path source : javaSources(APPLICATION_PACKAGE)) {
            Matcher wildcard = PLATFORM_WILDCARD.matcher(withoutCommentsAndLiterals(read(source)));
            while (wildcard.find()) {
                offenders.computeIfAbsent(wildcard.group(), ignored -> new TreeSet<>())
                    .add(APP_MAIN_SOURCES.relativize(source).toString().replace('\\', '/'));
            }
        }

        assertThat(offenders)
            .as("wildcard-импорт пакета платформы скрывает используемые типы: реестр видел бы"
                + " пакет, а не типы, и полнота ролей перестала бы быть проверяемой. Замените его"
                + " явными импортами")
            .isEmpty();
    }

    /**
     * Каждая ссылка {@code org.ipro.*} обязана разрешаться по classpath компиляции.
     *
     * <p>Если token не разрешается, значит он либо опечатка, либо package-префикс, вырванный из
     * контекста, либо тип, которого нет в сборке. Во всех трёх случаях измерение молча теряет
     * ссылку — ровно то, из-за чего пять production-типов не попали в реестр.</p>
     */
    @Test
    void everyPlatformReferenceResolvesToATypeOnTheCompileClasspath() {
        References references = platformReferences();

        assertThat(references.unresolved())
            .as("ссылка org.ipro.* не разрешается ни сама, ни одним из своих префиксов: ссылка"
                + " невидима реестру, поэтому невидима и граница модуля. Исправьте ссылку или"
                + " назовите тип явно через импорт")
            .isEmpty();
        assertThat(references.types())
            .as("проверка не должна быть вакуумной")
            .isNotEmpty();
    }

    /**
     * Fully-qualified ссылка на платформу в прикладном коде запрещена.
     *
     * <p>Это не стилистика. Ссылка без импорта — тот самый синтаксис, которым реестр обходили: пока
     * такие ссылки были, замер читал не всё, и пять production-типов жили вне классификации.
     * Измерение теперь их видит, но правило «одна ссылка — один импорт» держит границу читаемой:
     * что приложение берёт у платформы, видно в шапке файла, а не в середине метода.</p>
     *
     * <p>Текущий набор ссылок мигрирован на явные импорты (17 файлов, 39 ссылок), поэтому здесь
     * проверяется не бюджет, а отсутствие: новая ссылка запрещена целиком.</p>
     */
    @Test
    void applicationCodeDoesNotUseFullyQualifiedPlatformReferences() {
        Map<String, Set<String>> offenders = new TreeMap<>();
        for (Path source : javaSources(APPLICATION_PACKAGE)) {
            Set<String> tokens = new TreeSet<>();
            for (String line : withoutCommentsAndLiterals(read(source)).split("\n")) {
                if (line.stripLeading().startsWith("import")) {
                    continue;
                }
                Matcher qualified = QUALIFIED.matcher(line);
                while (qualified.find()) {
                    if (resolveAgainstClasspath(qualified.group()) != null) {
                        tokens.add(qualified.group());
                    }
                }
            }
            if (!tokens.isEmpty()) {
                offenders.put(APP_MAIN_SOURCES.relativize(source).toString().replace('\\', '/'), tokens);
            }
        }

        assertThat(offenders)
            .as("fully-qualified ссылка на платформу обходит и реестр, и импорт: тип виден в коде,"
                + " но не в шапке файла. Замените её явным импортом — это и есть способ сделать"
                + " зависимость от платформы проверяемой")
            .isEmpty();
    }

    /**
     * Владелец покрывает свой вложенный тип: роль вложенного public-типа наследуется, если для
     * него нет отдельной записи (реестр ведётся именами, которые видны в коде).
     */
    private static boolean covers(String owner, String fqn) {
        return fqn.equals(owner) || fqn.startsWith(owner + ".");
    }

    @Test
    void registryIsWellFormedAndNonVacuous() {
        Registry registry = readRegistry();

        assertThat(registry.roles())
            .as("реестр не должен быть пустым: пустой список прошёл бы все проверки выше")
            .isNotEmpty();
        assertThat(registry.internalUsages().keySet())
            .as("у внутренних ролей обязан быть зафиксирован набор файлов, иначе забор"
                + " «новая ссылка запрещена» не работает")
            .isEqualTo(registry.roles().entrySet().stream()
                .filter(entry -> entry.getValue() == Role.LEGACY_INTERNAL)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * Ссылки прикладного кода на платформу: импорты плюс fully-qualified употребления.
     *
     * <p>{@code unresolved} — это ссылки {@code org.ipro.*}, для которых ни сам token, ни его
     * префиксы не являются классом на classpath компиляции: опечатка либо ссылка на тип, который
     * в сборку не попадает. Такие случаи нельзя молча проглотить: тогда ссылка просто исчезнет из
     * измерения, а это и есть найденная дыра.</p>
     */
    private record References(Set<String> types, Map<String, Set<String>> unresolved) {
    }

    private static References platformReferences() {
        Set<String> types = new TreeSet<>();
        Map<String, Set<String>> unresolved = new TreeMap<>();
        for (Path source : javaSources(APPLICATION_PACKAGE)) {
            String code = withoutCommentsAndLiterals(read(source));
            Matcher imports = IMPORT.matcher(code);
            while (imports.find()) {
                types.add(imports.group(1));
            }
            for (String line : code.split("\n")) {
                if (line.stripLeading().startsWith("import")) {
                    continue;
                }
                Matcher qualified = QUALIFIED.matcher(line);
                while (qualified.find()) {
                    String token = qualified.group();
                    String resolved = resolveAgainstClasspath(token);
                    if (resolved == null) {
                        unresolved.computeIfAbsent(token, ignored -> new TreeSet<>())
                            .add(APP_MAIN_SOURCES.relativize(source).toString().replace('\\', '/'));
                    } else {
                        types.add(resolved);
                    }
                }
            }
        }
        return new References(types, unresolved);
    }

    /** Разрешение fully-qualified ссылки по classpath компиляции: тип или его вложенный тип. */
    private static String resolveAgainstClasspath(String token) {
        ClassLoader loader = PlatformPublicSurfaceTest.class.getClassLoader();
        String candidate = token;
        while (candidate.lastIndexOf('.') > 0) {
            if (isLoadable(candidate, loader)) {
                return candidate;
            }
            int dot = candidate.lastIndexOf('.');
            String nested = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
            if (isLoadable(nested, loader)) {
                // Вложенный тип возвращается в нотации исходника: реестр ведётся именами,
                // которые видны в коде, а не binary-именами JVM.
                return candidate;
            }
            candidate = candidate.substring(0, dot);
        }
        return null;
    }

    private static boolean isLoadable(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return false;
        }
    }

    /**
     * Типы платформы, которые называет прикладной код. Заменяет методику D1 §1 «по импортам»:
     * та не видела ни fully-qualified, ни wildcard-ссылок.
     */
    private static Set<String> namedPlatformTypes() {
        return platformReferences().types();
    }

    /**
     * Убирает комментарии и строковые литералы: {@code // ор.ipro.foo.Bar} в комментарии —
     * не зависимость. Без этого шага отчёт о ссылках наполняется текстом документации.
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
                while (index < text.length()
                        && !(text.charAt(index) == '*' && index + 1 < text.length()
                            && text.charAt(index + 1) == '/')) {
                    index++;
                }
                index += 2;
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

    /**
     * Фактические ссылки на внутренние типы: файл называется «называет тип», если имя типа
     * встречается в нём как отдельное слово. Критерий по имени, а не по импорту, намеренно:
     * fully-qualified ссылка в коде — такая же ссылка, а импорт можно и не писать.
     */
    private static Map<String, Set<String>> actualInternalUsages(Registry registry) {
        Map<String, Set<String>> usages = new TreeMap<>();
        List<Path> sources = javaSources(APPLICATION_PACKAGE);
        for (String fqn : registry.internalUsages().keySet()) {
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            Pattern reference = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
            Set<String> files = new TreeSet<>();
            for (Path source : sources) {
                if (reference.matcher(read(source)).find()) {
                    files.add(APP_MAIN_SOURCES.relativize(source).toString().replace('\\', '/'));
                }
            }
            usages.put(fqn, files);
        }
        return usages;
    }

    private static Registry readRegistry() {
        Map<String, Role> roles = new LinkedHashMap<>();
        Map<String, Set<String>> internalUsages = new LinkedHashMap<>();
        for (String line : lines(REGISTRY)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalStateException("Строка реестра не разобрана: " + line);
            }
            Role role;
            try {
                role = Role.valueOf(parts[0].toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Неизвестная роль в реестре: " + line, e);
            }
            String fqn = parts[1];
            roles.put(fqn, role);
            if (role == Role.LEGACY_INTERNAL) {
                internalUsages.put(fqn, new LinkedHashSet<>(List.of(parts).subList(2, parts.length)));
            } else if (parts.length > 2) {
                throw new IllegalStateException(
                    "Список файлов допустим только у legacy-internal: " + line);
            }
        }
        return new Registry(roles, internalUsages);
    }

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

    private static List<Path> javaSources(Path root) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
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

    @Test
    void rolesAreCountedForTheDocumentation() {
        Registry registry = readRegistry();
        Map<Role, Integer> counts = new LinkedHashMap<>();
        List<Role> order = new ArrayList<>(List.of(Role.values()));
        for (Role role : order) {
            counts.put(role, 0);
        }
        registry.roles().values().forEach(role -> counts.merge(role, 1, Integer::sum));
        assertThat(counts.values()).allSatisfy(count ->
            assertThat(count).as("каждая роль должна быть представлена: роль без типов означает,"
                + " что забор закрывает не то, что думали").isGreaterThan(0));
    }

    /**
     * Числа D1 больше не живут в тексте: они генерируются и сверяются.
     *
     * <p>Исходная карта D1 смешала исторический снимок и текущее состояние — таблица говорила
     * об 11 файлах строковых связей, а ниже стояло 9; реестр называл 194 типа, а замер давал
     * 200. Разбор D1/D2 назвал причину: числа были написаны руками и никем не проверялись,
     * поэтому документ перестал быть источником истины. Здесь числа выводятся из кода, а файл
     * {@code d1-surface-measurements.md} только их показывает — расхождение ложится сборкой.</p>
     */
    @Test
    void documentedMeasurementsMatchTheCode() {
        Map<String, Integer> actual = measure();
        Path document = Path.of("docs/architecture/d1-surface-measurements.md");

        if (Boolean.getBoolean(WRITE_MEASUREMENTS_PROPERTY)) {
            write(document, actual);
            return;
        }

        Map<String, Integer> documented = readMeasurements(document);
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            assertThat(documented.get(entry.getKey()))
                .as("замер '%s' в %s разошёлся с кодом. Обновите документ намеренно:"
                    + " -D%s=true", entry.getKey(), document, WRITE_MEASUREMENTS_PROPERTY)
                .isEqualTo(entry.getValue());
        }
        assertThat(documented.keySet())
            .as("в документе есть замер, которого больше нет в коде: список должен совпадать"
                + " в обе стороны")
            .isEqualTo(actual.keySet());
    }

    /** Замеры D1, которые можно получить из кода и реестра без домыслов. */
    private static Map<String, Integer> measure() {
        Registry registry = readRegistry();
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("app-source-files", javaSources(APPLICATION_PACKAGE).size());
        values.put("platform-tree-files", javaSources(Path.of("src/main/java/org/ipro")).size());
        values.put("platform-artifact-files", artifactSourceFiles());
        values.put("named-platform-types", namedPlatformTypes().size());
        values.put("api-types", countRole(registry, Role.API));
        values.put("spi-types", countRole(registry, Role.SPI));
        values.put("legacy-internal-types", countRole(registry, Role.LEGACY_INTERNAL));
        values.put("legacy-internal-usage-links", registry.internalUsages().values().stream()
            .mapToInt(Set::size).sum());
        return values;
    }

    private static int countRole(Registry registry, Role role) {
        return (int) registry.roles().values().stream().filter(value -> value == role).count();
    }

    /** Исходники платформенных артефактов: в этом чекауте плюс соседний identity-модуль. */
    private static int artifactSourceFiles() {
        int files = 0;
        try (Stream<Path> directories = Files.list(Path.of("."))) {
            List<Path> roots = new ArrayList<>(directories
                .filter(path -> path.getFileName().toString().startsWith("platform-"))
                .map(path -> path.resolve("src/main/java"))
                .filter(Files::isDirectory)
                .sorted()
                .toList());
            // Нейтральные leaf-контракты живут в реакторе соседнего crudui: их публикует не этот
            // чекаут, но в измерении они участвуют наравне с остальными платформенными модулями.
            roots.add(Path.of("../crudui/platform-identity-api/src/main/java"));
            roots.add(Path.of("../crudui/platform-crud-api/src/main/java"));
            for (Path root : roots) {
                files += javaSources(root).size();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static Map<String, Integer> readMeasurements(Path document) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String line : lines(document)) {
            if (!line.contains("=") || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = MEASUREMENT.matcher(line);
            if (matcher.find()) {
                values.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
            }
        }
        return values;
    }

    private static void write(Path document, Map<String, Integer> values) {
        StringBuilder text = new StringBuilder();
        text.append("# D1: замеры публичной поверхности (числа генерируются)\n\n")
            .append("**Статус:** числа проверяются тестом `PlatformPublicSurfaceTest`; расхождение")
            .append(" с кодом роняет сборку. Обновление — `mvn test -D")
            .append(WRITE_MEASUREMENTS_PROPERTY).append("=true`.\n\n")
            .append("Карта [`d1-platform-boundary-map.md`](d1-platform-boundary-map.md) — исторический")
            .append(" снимок на baseline `3793165` / тег `c4.8-d1-baseline`: её числа описывают")
            .append(" состояние на тот момент и намеренно не правятся задним числом. Актуальные")
            .append(" замеры — ниже.\n\n")
            .append("```text\n");
        values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        text.append("```\n\n")
            .append("Расшифровка:\n\n")
            .append("- `app-source-files` — прикладной код (`org.ip`, `src/main`);\n")
            .append("- `platform-tree-files` — платформа, оставшаяся в дереве репозитория (`org.ipro`,")
            .append(" `src/main`);\n")
            .append("- `platform-artifact-files` — исходники вынесенных платформенных артефактов")
            .append(" (`platform-*` плюс нейтральные leaf-контракты `platform-identity-api`")
            .append(" и `platform-crud-api` соседнего реактора `crudui`);\n")
            .append("- `named-platform-types` — типы платформы, которые называет приложение: импорты")
            .append(" плюс fully-qualified ссылки, разрешённые по classpath (wildcard-импорты")
            .append(" платформенных пакетов запрещены отдельной проверкой: они скрывали типы);\n")
            .append("- `api-types` / `spi-types` / `legacy-internal-types` — роли из reviewed-реестра")
            .append(" `platform-public-surface.txt`;\n")
            .append("- `legacy-internal-usage-links` — общее число зафиксированных ссылок приложения на")
            .append(" внутренние типы (измеренный reach-through, бюджет только уменьшается).\n\n")
            .append("Строковые связки платформы на `org.ip` в этом документе не считаются: их реестр")
            .append(" пуст и принадлежит `PlatformStringDependencyTest` — там же и критерий.\n");
        try {
            Files.writeString(document, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
