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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.0 — измерение до переноса: у каждого production-типа будущего модуля
 * {@code platform-vaadin} есть ровно одна семантическая роль.
 *
 * <p>Почему это отдельный забор, а не часть теста модуля, который появится вместе с ним. План D3.5
 * прямо называет риск: перенести 80 + 7 + 3 типа и зафиксировать получившуюся поверхность целиком
 * ({@code TEMPORARY_ALL_PUBLIC}) — значит повторить долг D3.3 в большем масштабе. Роли
 * назначаются <b>до</b> физического переноса, пока решение о каждом типе ещё можно принять, а не
 * подтвердить задним числом. Поэтому забор живёт в дереве приложения и смотрит на три
 * каталога-кандидата.</p>
 *
 * <p>Проверяются три свойства, и все три — про <b>расхождение</b>, а не про наличие списка:</p>
 * <ol>
 * <li><b>Полнота.</b> Множество ролей совпадает с множеством production-типов трёх корней: новый
 *     тип без роли ломает сборку, лишняя запись — тоже (она означала бы, что решение описывает
 *     код, которого нет).</li>
 * <li><b>Самосогласованность файла.</b> Заголовок раздела {@code # ---- ROLE (n)} и число записей
 *     под ним обязаны совпадать, а запись под чужим заголовком — ошибка. Иначе бюджет в шапке
 *     реестра живёт своей жизнью и перестаёт быть проверяемым числом.</li>
 * <li><b>Замкнутый словарь ролей.</b> Черновиковые роли (например {@code APP_API_DRAFT}) не
 *     являются решениями: их присутствие — незакрытая развилка, а не классификация.</li>
 * </ol>
 */
class PlatformVaadinSurfaceTest {

    private static final Path REGISTRY = Path.of("src/test/resources/platform-vaadin-surface.txt");

    /** Корни-кандидаты будущего модуля: forms, Vaadin-слой и адаптер телеметрии. */
    private static final List<Path> FUTURE_MODULE_ROOTS = List.of(
        Path.of("src/main/java/org/ipro/form"),
        Path.of("src/main/java/org/ipro/vaadin"),
        Path.of("src/main/java/org/ip/telemetry/vaadin"));

    private static final Set<String> ROLES = Set.of("APP_API", "APP_SPI", "MODULE_API", "INTERNAL");

    private static final Pattern SECTION = Pattern.compile("^#\\s*-+\\s*(\\w+)\\s*\\((\\d+)\\)\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^(\\w+) (\\S+)$");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Test
    void everyFutureModuleTypeHasExactlyOneReviewedRole() {
        Set<String> actualTypes = productionTypes();
        Set<String> reviewedTypes = new TreeSet<>(readRegistry().keySet());

        Set<String> withoutRole = new TreeSet<>(actualTypes);
        withoutRole.removeAll(reviewedTypes);
        Set<String> stale = new TreeSet<>(reviewedTypes);
        stale.removeAll(actualTypes);

        assertThat(actualTypes)
            .as("забор не должен быть вакуумным: 92 production-типа (form 82, vaadin 7,"
                + " telemetry-vaadin 3) — это замер D3.5.0 плюс FormNavigator из D3.5.1 и"
                + " LookupComboHelper из D3.5.2, а не догадка."
                + " Число меняется только вместе с переносом типов и обновлением реестра")
            .hasSize(92);
        assertThat(withoutRole)
            .as("тип будущего platform-vaadin без роли: классификация — решение, которое нужно"
                + " принять до переноса, а не после него")
            .isEmpty();
        assertThat(stale)
            .as("в реестре есть запись о типе, которого нет в дереве: решение описывает"
                + " несуществующий код")
            .isEmpty();
    }

    @Test
    void registrySectionsAgreeWithTheirPerRoleBudget() {
        Map<String, Integer> declared = new LinkedHashMap<>();
        Map<String, Integer> actual = new LinkedHashMap<>();
        Map<String, String> clashes = new LinkedHashMap<>();
        String current = null;

        for (String line : lines(REGISTRY)) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                current = section.group(1);
                declared.put(current, Integer.valueOf(section.group(2)));
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String role = entry.group(1);
            actual.merge(role, 1, Integer::sum);
            if (current != null && !current.equals(role)) {
                clashes.put(entry.group(2), role + " под заголовком " + current);
            }
        }

        for (String role : ROLES) {
            // Роль с нулём записей — измеренный факт (MODULE_API сегодня пуст), а не пропуск
            // раздела: бюджет обязан совпадать с числом записей, включая нулевой.
            actual.putIfAbsent(role, 0);
        }

        assertThat(declared.keySet())
            .as("разделы реестра обязаны называть ровно четыре роли")
            .containsExactlyInAnyOrderElementsOf(ROLES);
        assertThat(actual.keySet())
            .as("запись с ролью вне словаря — незакрытая развилка, а не решение")
            .containsExactlyInAnyOrderElementsOf(ROLES);
        assertThat(clashes)
            .as("запись под чужим заголовком: бюджет раздела перестаёт быть проверяемым")
            .isEmpty();
        assertThat(actual)
            .as("число записей под заголовком обязано совпадать с бюджетом в нём")
            .isEqualTo(declared);
    }

    /** Production-типы трёх корней: по одному на файл, как их публикует модуль. */
    private static Set<String> productionTypes() {
        Set<String> types = new TreeSet<>();
        for (Path root : FUTURE_MODULE_ROOTS) {
            for (Path source : javaSources(root)) {
                Matcher matcher = PACKAGE.matcher(read(source));
                if (!matcher.find()) {
                    throw new IllegalStateException("нет package: " + source);
                }
                String name = source.getFileName().toString();
                types.add(matcher.group(1) + "."
                    + name.substring(0, name.length() - ".java".length()));
            }
        }
        return types;
    }

    private static Map<String, String> readRegistry() {
        Map<String, String> roles = new LinkedHashMap<>();
        for (String line : lines(REGISTRY)) {
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String previous = roles.put(entry.group(2), entry.group(1));
            if (previous != null) {
                throw new IllegalStateException("тип записан дважды: " + entry.group(2)
                    + " (" + previous + " и " + entry.group(1) + ")");
            }
        }
        return roles;
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
}
