package org.ipro;

import com.tngtech.archunit.core.domain.Dependency;
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
import java.util.Arrays;
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
 * D1 — полная модель допустимых направлений вместо blacklist'а из отдельных правил.
 *
 * <p>{@link PlatformDependencyDirectionTest} держит восемь правил «нижний слой не знает
 * верхнего». Разбор D1/D2 назвал их слабость точно: это blacklist. Зависимость, не упомянутая
 * ни в одном правиле, проходит молча — а таких пар пакетов большинство. Правило хорошо тогда,
 * когда оно называет причину («data access не знает UI»), но оно не отвечает на вопрос «какие
 * направления вообще разрешены».</p>
 *
 * <p>Здесь ответ даётся перечислением: матрица «пакет → пакет» по <b>всем</b> классам дерева
 * репозитория сравнивается с reviewed allow-list. Любое ребро, которого в списке нет, ломает
 * сборку — включая то, о котором не думало ни одно из восьми правил. Восемь правил остаются:
 * матрица знает «что нельзя», правила объясняют «почему».</p>
 *
 * <p>Гранулярность — подсистема плюс первый подпакет (`org.ipro.form.builtin`). `org.ipro.form`
 * целиком слишком груб: builtin, coordinator и spi живут по разным правилам, и запрет
 * «форма не знает builtin» на этом уровне не выражается. Полный пакет до последнего сегмента,
 * наоборот, сделал бы список шумным и мешал бы движению классов внутри подсистемы.</p>
 *
 * <p>В матрицу попадают только классы, чей исходник лежит в этом репозитории: внешние
 * артефакты (`crudui-core`, `filtergrid-*`, `platform-identity-api`) живут по своим правилам,
 * и этот забор не про них.</p>
 */
class PlatformPackageDependencyMatrixTest {

    private static final Path MATRIX = Path.of("src/test/resources/platform-package-matrix.txt");

    /** Запись вида {@code from -> to}: разрешённое направление на уровне пакета. */
    private static final Pattern EDGE =
        Pattern.compile("^([\\w.]+)\\s*->\\s*([\\w.]+)$");

    /**
     * Запись списка — не ручная работа: {@code mvn test -Dplatform.matrix.write=true}
     * перезаписывает файл фактическими рёбрами. Так список всегда описывает проверенное
     * состояние, а не намерение.
     */
    private static final String WRITE_PROPERTY = "platform.matrix.write";

    @Test
    void everyRepositoryPackageEdgeIsReviewed() {
        Set<String> actual = repositoryEdges();
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            writeMatrix(actual);
            return;
        }
        Set<String> reviewed = reviewedEdges();

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(reviewed);
        Set<String> removed = new TreeSet<>(reviewed);
        removed.removeAll(actual);

        assertThat(added)
            .as("новое направление зависимости между пакетами платформы, которого нет в reviewed"
                + " allow-list. Восемь правил ArchUnit такое ребро пропустили бы: они называют"
                + " запреты, а не полную модель. Если направление законно — добавьте его в %s"
                + " и назовите причину", MATRIX)
            .isEmpty();
        assertThat(removed)
            .as("ребро исчезло: allow-list обязан совпадать с фактом в обе стороны, иначе он"
                + " описывает прошлое состояние, а не текущее решение")
            .isEmpty();
    }

    @Test
    void matrixIsNonVacuous() {
        assertThat(repositoryEdges())
            .as("матрица должна быть непустой: пустой allow-list означал бы, что сканирование"
                + " не нашло классов и забор закрыт на замок без двери")
            .isNotEmpty();
    }

    /** Рёбра «пакет → пакет» по классам, чей исходник лежит в этом репозитории. */
    private static Set<String> repositoryEdges() {
        List<Path> roots = repositorySourceRoots();
        Set<String> edges = new TreeSet<>();
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.ipro")
            .stream()
            .filter(javaClass -> residesInRepository(javaClass.getName(), roots))
            .forEach(javaClass -> {
                String from = layerOf(javaClass.getPackageName());
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.getPackageName().startsWith("org.ipro")) {
                        continue;
                    }
                    if (!residesInRepository(target.getName(), roots)) {
                        continue;
                    }
                    String to = layerOf(target.getPackageName());
                    if (!from.equals(to)) {
                        edges.add(from + " -> " + to);
                    }
                }
            });
        return edges;
    }

    private static Set<String> reviewedEdges() {
        Set<String> reviewed = new TreeSet<>();
        for (String line : readLines(MATRIX)) {
            if (line.startsWith("#")) {
                continue;
            }
            Matcher matcher = EDGE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException("Строка матрицы не разобрана: " + line);
            }
            reviewed.add(matcher.group(1) + " -> " + matcher.group(2));
        }
        return reviewed;
    }

    /**
     * Уровень ребра: {@code org.ipro.<подсистема>.<первый подпакет>}. Первые два сегмента —
     * корень пакета платформы, поэтому «до четырёх сегментов» — это один уровень ниже
     * подсистемы, а не «как получится».
     */
    private static String layerOf(String packageName) {
        String[] segments = packageName.split("\\.");
        return String.join(".", Arrays.copyOf(segments, Math.min(segments.length, 4)));
    }

    private static boolean residesInRepository(String className, List<Path> roots) {
        int nested = className.indexOf('$');
        String topLevel = nested < 0 ? className : className.substring(0, nested);
        String relative = topLevel.replace('.', '/') + ".java";
        return roots.stream().anyMatch(root -> Files.exists(root.resolve(relative)));
    }

    private static List<Path> repositorySourceRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("src/main/java"));
        try (Stream<Path> modules = Files.list(Path.of("."))) {
            modules.filter(path -> path.getFileName().toString().startsWith("platform-"))
                .map(path -> path.resolve("src/main/java"))
                .filter(Files::isDirectory)
                .sorted()
                .forEach(roots::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return roots;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Запись reviewed-списка: заголовок плюс рёбра в стабильном порядке. Файл читается как
     * решение («эти направления признаны допустимыми»), а не как дамп — но дамп из
     * кода, а не из памяти автора.
     */
    private static void writeMatrix(Set<String> edges) {
        StringBuilder content = new StringBuilder();
        content.append("# D1: reviewed allow-list направлений между пакетами платформы.\n")
            .append("# Формат: <from> -> <to>; любой пары нет в списке — сборка падает.\n")
            .append("# Перезапись: mvn test -Dplatform.matrix.write=true (см. PlatformPackageDependencyMatrixTest).\n");
        edges.forEach(edge -> content.append(edge).append('\n'));
        try {
            Files.writeString(MATRIX, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Диагностика: сколько рёбер приходится на каждый пакет-источник (для ревью списка). */
    @Test
    void edgesAreCountedPerSourcePackage() {
        Map<String, Integer> perSource = new TreeMap<>();
        for (String edge : repositoryEdges()) {
            perSource.merge(edge.substring(0, edge.indexOf(" -> ")), 1, Integer::sum);
        }
        assertThat(perSource)
            .as("у каждого пакета платформы есть хотя бы одно исходящее ребро: ноль означал бы,"
                + " что пакет не сканируется")
            .doesNotContainValue(0);
    }
}
