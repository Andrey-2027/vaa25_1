package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * D1 — исполняемый реестр строковых зависимостей платформы на прикладной пакет.
 *
 * <p>{@link PlatformArchitectureTest} закрывает <b>bytecode</b>-связи: платформа не
 * должна импортировать и не должна включать в свои сигнатуры классы {@code org.ip.*}.
 * Этого недостаточно: платформа может зависеть от приложения <b>строкой</b> —
 * {@code basePackages} сканирования, default значения свойства, pointcut по имени
 * пакета. Компилятор такую связь не видит, а для физического выделения платформы
 * (D2/D3) она так же блокирующая, как импорт: модуль продолжает знать, как называется
 * прикладной пакет.</p>
 *
 * <p>Критерий двусторонний и <b>по литералам, а не по именам файлов</b>: сравниваются
 * точные наборы строк, поэтому второй литерал в уже разрешённом файле ломает сборку так
 * же, как новый файл. Иначе реестр «закреплял» бы файл целиком и пропускал расширение
 * связи внутри него. Как и в C4.7-заборе
 * {@code CompatibilityMigrationArchitectureTest}, набор обязан совпадать с reviewed-списком
 * в обе стороны: новая связка ломает сборку, снятая обязана исчезнуть из списка.</p>
 *
 * <p>Сканируется <b>код без комментариев</b>: упоминание пакета в javadoc — это
 * документация, а не зависимость, и оно не должно попадать в реестр (иначе реестр
 * раздувается записями, которые невозможно снять, не переписав пояснение).</p>
 *
 * <p>Отдельный смысл теста — <b>негативный</b>: он ловит возврат
 * {@code @EnableJpaRepositories({"org.ip", ...})} в платформенную авто-конфигурацию.
 * Именно так платформа раньше объявляла состав репозиториев приложения; теперь это
 * делает сам {@code Application}.</p>
 */
class PlatformStringDependencyTest {

    private static final Path PLATFORM_SOURCE = Path.of("src/main/java/org/ipro");

    /**
     * Платформенные артефакты. После D2 часть платформы лежит вне дерева, и строковая
     * связка в модуле блокирует ровно так же, как в дереве — более того, её труднее
     * заметить: модуль читается как чужая зависимость. Реестр один на все исходники
     * платформы, пути в нём — от корня проекта.
     */
    private static final List<Path> PLATFORM_ARTIFACT_SOURCES = artifactSources();

    /** Прикладной пакет, имя которого платформа знать не должна. */
    private static final String APPLICATION_PACKAGE = "org.ip";

    /**
     * Строковый литерал, содержащий имя прикладного пакета. Суффикс {@code (?!\w)}
     * важен: без него {@code org.ipro.*} — платформенные имена, они законны — матчились бы
     * как {@code org.ip}.
     */
    private static final Pattern STRING_LITERAL_WITH_APPLICATION_PACKAGE = Pattern.compile(
        "\"[^\"\n]*(?<![\\w.])" + Pattern.quote(APPLICATION_PACKAGE) + "(?!\\w)[^\"\n]*\"");

    /** Литерал default-свойства сканирования подсистем. */
    private static final String SUBSYSTEM_SCAN_PACKAGE =
        "\"${platform.subsystem-scan-package:org.ip}\"";

    /** Литерал default-свойства сканирования RLS-измерений. */
    private static final String RLS_SCAN_PACKAGE = "\"${rls.dimension-scan-package:org.ip}\"";

    /** Литерал default-свойства сканирования констант настроек. */
    private static final String SETTINGS_SCAN_PACKAGE = "\"${settings.scan-package:org.ip.settings}\"";

    /** Pointcut timing-аспекта по прикладным сервисам. */
    private static final String SERVICE_POINTCUT = "\"execution(* org.ip.service..*(..))\"";

    /**
     * Reviewed-реестр: {@code file -> точный набор литералов} плюс причина. Каждая запись —
     * осознанное решение, а не «исторически сложилось»: реестр законен только пока у записи
     * назван момент снятия.
     */
    private static final Map<String, Reviewed> REVIEWED_STRING_DEPENDENCIES =
        reviewedDependencies();

    /** Запись реестра: набор литералов в файле и причина, по которой связь ещё существует. */
    private record Reviewed(Set<String> literals, String reason) {
    }

    private static Map<String, Reviewed> reviewedDependencies() {
        String subsystemScan = "default свойства platform.subsystem-scan-package; снимается вместе"
            + " с выносом авто-конфигураций метаданных (D3) и переносом значения в конфигурацию"
            + " приложения";
        String rlsScan = "default свойства rls.dimension-scan-package; уходит в D3 вместе с"
            + " авто-конфигурацией RLS";
        String settingsScan = "default свойства settings.scan-package; уходит в D3 вместе с"
            + " авто-конфигурацией констант";

        Map<String, Reviewed> reviewed = new LinkedHashMap<>();
        // `ReferenceIndex` снят с реестра не переписыванием причины, а устранением связи:
        // тип выехал в platform-metadata, и вместе с ним ушло default значение свойства
        // сканирования — оно было мертво (бин всегда создаёт MetadataAutoConfiguration с
        // явным значением) и оставалось единственным местом, где платформенный артефакт
        // называл имя прикладного пакета. Остальные три класса метаданных ещё в дереве.
        reviewed.put("src/main/java/org/ipro/metadata/SectionMetadataRegistry.java",
            new Reviewed(Set.of(SUBSYSTEM_SCAN_PACKAGE), subsystemScan));
        reviewed.put("src/main/java/org/ipro/metadata/SubsystemRegistry.java",
            new Reviewed(Set.of(SUBSYSTEM_SCAN_PACKAGE), subsystemScan));
        reviewed.put("src/main/java/org/ipro/metadata/config/MetadataAutoConfiguration.java",
            new Reviewed(Set.of(SUBSYSTEM_SCAN_PACKAGE), subsystemScan));
        reviewed.put("src/main/java/org/ipro/metadata/explorer/config/EntityExplorerAutoConfiguration.java",
            new Reviewed(Set.of(SUBSYSTEM_SCAN_PACKAGE), subsystemScan));
        // Нумерация выехала в platform-numbering целиком. Одна из двух записей снята
        // устранением связи (мертвый @Value в реестре нумерации — как ранее в ReferenceIndex),
        // вторая переехала в артефакт: default свойства сканирования в её авто-конфигурации
        // загружаемый — без него подсистема потеряет пакет сканирования, если приложение не
        // задало свойство. Снятие этой записи — перенос значения в конфигурацию приложения
        // для всего платформенного семейства сразу, а не по одной подсистеме.
        reviewed.put("platform-numbering/src/main/java/org/ipro/numbering/config/"
            + "NumberingAutoConfiguration.java",
            new Reviewed(Set.of(SUBSYSTEM_SCAN_PACKAGE), subsystemScan));

        reviewed.put("src/main/java/org/ipro/rls/RlsDimensionRegistry.java",
            new Reviewed(Set.of(RLS_SCAN_PACKAGE), rlsScan));
        reviewed.put("src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java",
            new Reviewed(Set.of(RLS_SCAN_PACKAGE), rlsScan));

        reviewed.put("src/main/java/org/ipro/settings/SettingsRegistry.java",
            new Reviewed(Set.of(SETTINGS_SCAN_PACKAGE), settingsScan));
        reviewed.put("src/main/java/org/ipro/settings/SettingsReverseReferenceSource.java",
            new Reviewed(Set.of(SETTINGS_SCAN_PACKAGE), settingsScan));
        reviewed.put("src/main/java/org/ipro/settings/config/SettingsAutoConfiguration.java",
            new Reviewed(Set.of(SETTINGS_SCAN_PACKAGE), settingsScan));

        reviewed.put("src/main/java/org/ipro/telemetry/core/ExecutionTimeAspect.java",
            new Reviewed(Set.of(SERVICE_POINTCUT),
                "pointcut по прикладному пакету (timing @Service приложения); снимается в D3 вместе"
                    + " с модулем телеметрии — переход на собственный маркер/аннотацию вместо имени"
                    + " пакета"));
        return Map.copyOf(reviewed);
    }

    @Test
    void platformDoesNotGainNewStringDependenciesOnTheApplicationPackage() {
        Map<String, Set<String>> actual = literalDependencies();

        assertThat(actual.keySet())
            .as("новая строковая связка платформы на %s: платформа не должна знать имя"
                + " прикладного пакета. Вынесите значение в конфигурацию приложения либо"
                + " добавьте запись в реестр с причиной и моментом снятия", APPLICATION_PACKAGE)
            .isSubsetOf(REVIEWED_STRING_DEPENDENCIES.keySet());

        assertThat(REVIEWED_STRING_DEPENDENCIES.keySet())
            .as("устаревшая запись реестра: строковая связка устранена — уберите файл из"
                + " reviewed-списка, он должен только сокращаться")
            .isSubsetOf(actual.keySet());
    }

    @Test
    void reviewedLiteralSetsMatchTheCodeExactly() {
        Map<String, Set<String>> actual = literalDependencies();

        for (Map.Entry<String, Reviewed> entry : REVIEWED_STRING_DEPENDENCIES.entrySet()) {
            assertThat(actual.get(entry.getKey()))
                .as("набор строковых связок в %s разошёлся с reviewed-списком. Новый литерал"
                    + " в уже разрешённом файле — такая же новая связка, как новый файл: либо"
                    + " снимите его, либо обновите реестр с причиной (сейчас: %s)",
                    entry.getKey(), entry.getValue().reason())
                .isEqualTo(entry.getValue().literals());
        }
    }

    @Test
    void scannerSeesASecondLiteralInAnAlreadyReviewedFile() {
        // Защита от регрессии самого критерия: сравнение «по файлам» пропустило бы это.
        String source = "class C {\n"
            + "    String fixed = \"" + "${platform.subsystem-scan-package:org.ip}" + "\";\n"
            + "    String added = \"org.ip.service\";\n"
            + "}\n";

        assertThat(literalsIn(source)).containsExactlyInAnyOrder(
            SUBSYSTEM_SCAN_PACKAGE, "\"org.ip.service\"");
    }

    @Test
    void scannerIgnoresPackageNameMentionedInCommentsOnly() {
        String source = "/** {@code @EnableJpaRepositories(\"org.ip\")} — пояснение, не связка. */\n"
            + "// \"org.ip.comment\"\n"
            + "/* \"org.ip.block\" */\n"
            + "class C {\n"
            + "}\n";

        assertThat(literalsIn(source)).isEmpty();
    }

    /** {@code relative path -> найденные строковые литералы с именем прикладного пакета}. */
    private static Map<String, Set<String>> literalDependencies() {
        Path root = Path.of("").toAbsolutePath();
        Map<String, Set<String>> result = new TreeMap<>();
        List<Path> roots = new java.util.ArrayList<>();
        roots.add(PLATFORM_SOURCE);
        roots.addAll(PLATFORM_ARTIFACT_SOURCES);
        try {
            for (Path sourceRoot : roots) {
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        Set<String> literals = literalsIn(read(path));
                        if (!literals.isEmpty()) {
                            result.put(root.relativize(path.toAbsolutePath())
                                .toString().replace('\\', '/'), literals);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /**
     * Корни исходников платформенных артефактов. Обнаруживаются по имени каталога, а не
     * списком: забытый в перечислении новый модуль выпал бы из забора молча — ровно та
     * ошибка, против которой этот тест и написан.
     */
    private static List<Path> artifactSources() {
        try (Stream<Path> modules = Files.list(Path.of("."))) {
            return modules
                .filter(path -> path.getFileName().toString().startsWith("platform-"))
                .map(path -> path.resolve("src/main/java"))
                .filter(Files::isDirectory)
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> literalsIn(String source) {
        Set<String> literals = new TreeSet<>();
        Matcher matcher = STRING_LITERAL_WITH_APPLICATION_PACKAGE.matcher(stripComments(source));
        while (matcher.find()) {
            literals.add(matcher.group());
        }
        return literals;
    }

    /**
     * Код без комментариев. Строковые литералы и text blocks копируются как есть — иначе
     * {@code //} внутри строки обрезало бы код, а комментарий скрывал бы связку.
     */
    private static String stripComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && startsWith(source, i, "//")) {
                i = endOfLine(source, i);
            } else if (c == '/' && startsWith(source, i, "/*")) {
                i = endOfBlockComment(source, i);
            } else if (c == '"' && startsWith(source, i, "\"\"\"")) {
                i = copyTextBlock(source, i, code);
            } else if (c == '"' || c == '\'') {
                i = copyQuoted(source, i, code);
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    private static boolean startsWith(String source, int offset, String token) {
        return source.startsWith(token, offset);
    }

    private static int endOfLine(String source, int offset) {
        int i = offset;
        while (i < source.length() && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int endOfBlockComment(String source, int offset) {
        int i = offset + 2;
        while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
            i++;
        }
        return Math.min(i + 2, source.length());
    }

    private static int copyQuoted(String source, int offset, StringBuilder code) {
        char quote = source.charAt(offset);
        code.append(quote);
        int i = offset + 1;
        while (i < source.length() && source.charAt(i) != quote) {
            if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                code.append(source.charAt(i)).append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            code.append(source.charAt(i));
            i++;
        }
        if (i < source.length()) {
            code.append(quote);
            i++;
        }
        return i;
    }

    private static int copyTextBlock(String source, int offset, StringBuilder code) {
        code.append("\"\"\"");
        int i = offset + 3;
        while (i + 2 < source.length()
                && !(source.charAt(i) == '"' && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"')) {
            code.append(source.charAt(i));
            i++;
        }
        if (i + 2 < source.length()) {
            code.append("\"\"\"");
            return i + 3;
        }
        return source.length();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
