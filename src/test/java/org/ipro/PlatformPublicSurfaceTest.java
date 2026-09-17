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
    private static final int LEGACY_INTERNAL_BUDGET = 73;

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(org\\.ipro\\.[A-Za-z0-9_.]+);", Pattern.MULTILINE);

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

        Set<String> unclassified = new TreeSet<>(namedPlatformTypes());
        unclassified.removeAll(registry.roles().keySet());

        assertThat(unclassified)
            .as("приложение называет тип платформы, которого нет в reviewed-реестре. Пока роль"
                + " не назначена, граница держится только на честном слове: это и есть дыра,"
                + " из-за которой 165 внутренних типов оказались публичными. Добавьте строку"
                + " в %s с ролью API, SPI или legacy-internal", REGISTRY);
    }

    @Test
    void registryDoesNotCarryTypesTheApplicationNoLongerNames() {
        Registry registry = readRegistry();

        Set<String> stale = new TreeSet<>(registry.roles().keySet());
        stale.removeAll(namedPlatformTypes());

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

    /** Типы платформы, которые называет прикладной код: по импортам, как в методике D1 §1. */
    private static Set<String> namedPlatformTypes() {
        Set<String> types = new TreeSet<>();
        for (Path source : javaSources(APPLICATION_PACKAGE)) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                types.add(matcher.group(1));
            }
        }
        return types;
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
            roots.add(Path.of("../crudui/platform-identity-api/src/main/java"));
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
            .append("- `named-platform-types` — типы платформы, которые называет приложение (методика")
            .append(" D1 §1: по импортам);\n")
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
