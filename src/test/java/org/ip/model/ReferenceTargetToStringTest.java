package org.ip.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.2b: у сущности-цели ссылки есть стабильный {@code toString()}.
 *
 * <p>Зачем. Редактор условий отбора FilterGrid сохраняет значение ссылочного поля как
 * {@code String.valueOf(entity)}, то есть через {@code toString()}, а при компиляции ищет это
 * значение среди отданных вариантов, сопоставляя его с display-именем или с {@code toString()}
 * варианта. Если у сущности нет своего {@code toString()}, наследуется {@code Object.toString()} —
 * «org.ip.model.Journal@1a2b3c»: подпись в комбобоксе при этом человеческая (её даёт
 * {@code getDisplayName()}), поэтому дефект не виден в UI и проявляется только после перезапуска
 * JVM, когда сохранённый вид перестаёт разрешать своё условие по ссылке.</p>
 *
 * <p>Множество целей выводится из исходников, а не перечислено руками: цель — тип, которым
 * объявлено поле любой сущности app-модели. Поэтому новый ссылочный тип без {@code toString()}
 * ломает сборку, а не тихо возвращает дефект. Невакуумность забора проверяется отдельно —
 * пустое множество целей тоже ошибка.</p>
 */
class ReferenceTargetToStringTest {

    private static final Path MODEL = Path.of("src/main/java/org/ip/model");

    private static final Pattern ENTITY = Pattern.compile("@Entity\\b");
    private static final Pattern TO_STRING = Pattern.compile("public String toString\\(\\)");
    private static final String IDENTIFIER = "[a-z][A-Za-z0-9]*";

    /** Ручная проверка: именно эти четыре типа ломали сохранённые условия до D3.5.2b. */
    private static final Set<String> REVIEWED_TARGETS = Set.of("Journal", "Branch", "Oper", "PrdSpec");

    @Test
    void everyReferenceTargetHasStableToString() {
        Map<String, String> sources = sources();
        Map<String, String> offenders = new TreeMap<>();
        for (String target : referenceTargets(sources)) {
            if (!TO_STRING.matcher(sources.get(target)).find()) {
                offenders.put(target, "нет своего toString(): наследуется Object.toString(),"
                    + " поэтому сохранённое условие отбора по ссылке на этот тип перестанет"
                    + " разрешаться после перезапуска JVM. Нужен стабильный между запусками"
                    + " toString(), как у остальных сущностей");
            }
        }

        assertThat(offenders).as("сущность-цель ссылки обязана иметь стабильный toString"
            + " (см. javadoc теста)").isEmpty();
    }

    @Test
    void discoveredReferenceTargetsIncludeTheKnownOnes() {
        Set<String> targets = referenceTargets(sources());

        assertThat(targets)
            .as("забор не должен быть вакуумным: цели ссылок выводятся из объявлений полей"
                + " app-модели, и известные четыре цели обязаны в него попадать")
            .isNotEmpty()
            .containsAll(REVIEWED_TARGETS);
    }

    /** Тип является целью ссылки, если им объявлено поле хоть в одной сущности app-модели. */
    private static Set<String> referenceTargets(Map<String, String> sources) {
        Set<String> entities = new TreeSet<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            if (ENTITY.matcher(source.getValue()).find()) {
                entities.add(source.getKey());
            }
        }

        Set<String> targets = new TreeSet<>();
        for (String declaration : sources.values()) {
            for (String entity : entities) {
                Pattern field = Pattern.compile("(?m)^\\s{0,4}(?:private|protected|public)?\\s*"
                    + "(?:final\\s+)?" + entity + "\\s+" + IDENTIFIER + "\\s*[;=]");
                if (field.matcher(declaration).find()) {
                    targets.add(entity);
                }
            }
        }
        return targets;
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MODEL)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                sources.put(name.substring(0, name.length() - ".java".length()),
                    Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }
}
