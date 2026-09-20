package org.ipro.form;

import org.ipro.crud.EntityLookup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.2: граница lookup-чтений в form-слое — безусловной выгрузки справочника здесь нет ни одной.
 *
 * <p>История забора: D3.5.2 закрыл интерактивные пути ({@code LookupComboHelper},
 * {@code ContextFilterPanel}, {@code createFilterForPath}), а два вызова оставил сознательно —
 * их результат питал компилятор сохранённых видов FilterGrid, который резолвит сохранённые
 * значения сканированием списка ({@code resolveEntityRef}). Тогда существовал
 * burn-down-список исключений с причиной и следующим шагом: characterization-тест round-trip,
 * потом замена источника. D3.5.2b это выполнил ({@link FilterLookupOptions}), поэтому список
 * исключений пуст, а запрет — безусловный.</p>
 *
 * <p>Почему запрет стоит держать и после закрытия долга: {@code LookupService.findAll(Class)} —
 * public-метод {@code MODULE_API}-класса. Он переживёт физический вынос {@code platform-vaadin},
 * и «ещё одно исключение» никто не увидит. Тест двусторонний по смыслу: новый неограниченный
 * вызов падает, а отдать его обратно в reviewed-список нельзя без явного изменения причины
 * в этом файле.</p>
 *
 * <p>Забор ищет именно безусловную выгрузку, а не имя метода: {@code findAll(spec, pageable)}
 * у {@code BaseService} — это list-read с Pageable, и он в шаблон не попадает.</p>
 */
class LookupReadBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /**
     * Неограниченное lookup-чтение: у {@code LookupService} нет другого {@code findAll}, кроме
     * безусловного (pageable-варианты живут на {@code BaseService}, не здесь), поэтому достаточно
     * получателя с «lookup» в имени. Второй шаблон ловит ту же выгрузку, записанную через
     * {@code applicationContext.getBean(LookupService.class).findAll(...)}.
     */
    private static final List<Pattern> UNBOUNDED_LOOKUP_READ = List.of(
        Pattern.compile("\\b\\w*[Ll]ookup\\w*\\s*\\.\\s*findAll\\s*\\("),
        Pattern.compile("LookupService\\s*\\.\\s*class\\s*\\)\\s*\\.\\s*findAll\\s*\\("));

    /** Блочные комментарии вырезаются первыми: внутри них может встретиться «//». */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void lookupContractItselfOffersNoUnboundedRead() {
        assertThat(EntityLookup.class.getDeclaredMethods())
            .as("у APP_API-контракта lookup нет и не должно быть findAll: безусловная выгрузка"
                + " таблицы не может быть частью публичного чтения — для полной выборки есть"
                + " list-read (LIST/Pageable). Нужен handle с записью — это EntityServiceResolver")
            .noneMatch(method -> method.getName().equals("findAll"));
    }

    @Test
    void scannerMeasuresCodeNotProse() {
        // Забор не должен быть вакуумным: настоящий вызов находится,
        // а упоминание того же вызова в комментарии — нет.
        assertThat(unboundedReadsIn("class X { void m() { lookupService.findAll(Role.class); } }"))
            .isEqualTo(1);
        assertThat(unboundedReadsIn("class X { Object o() { return ctx.getBean(LookupService.class)"
            + ".findAll(Role.class); } }"))
            .isEqualTo(1);
        assertThat(unboundedReadsIn("class X { /* был lookupService.findAll(Role.class) */ }"))
            .isZero();
        assertThat(unboundedReadsIn("class X { // был lookupService.findAll(Role.class)\n }"))
            .isZero();
        assertThat(unboundedReadsIn("class X { void m() { service.findAll(spec, pageable); } }"))
            .as("list-read с Pageable не является выгрузкой таблицы")
            .isZero();
    }

    @Test
    void noCallSiteReadsTheWholeDictionary() {
        Map<String, Integer> found = scanForUnboundedLookupReads();

        assertThat(found)
            .as("неограниченное lookup-чтение в form-слое. Значения для визуального фильтра берутся"
                + " из ссылок дерева ограниченным search (FilterLookupOptions), подсказки —"
                + " LookupComboHelper. Если полная выгрузка здесь действительно нужна, это не"
                + " lookup-чтение, а list-read (LIST/Pageable) или отдельный use case с явным"
                + " намерением. Найдено: " + found)
            .isEmpty();
    }

    private static Map<String, Integer> scanForUnboundedLookupReads() {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Path source : javaSources(MAIN_SOURCES)) {
            int hits = unboundedReadsIn(read(source));
            if (hits > 0) {
                found.put(source.toString().replace('\\', '/'), hits);
            }
        }
        return found;
    }

    static int unboundedReadsIn(String source) {
        String code = stripComments(source);
        int hits = 0;
        for (Pattern pattern : UNBOUNDED_LOOKUP_READ) {
            Matcher matcher = pattern.matcher(code);
            while (matcher.find()) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * Комментарии вырезаются до поиска: забор меряет код, а не прозу. Иначе объяснение
     * «здесь больше нет выгрузки» в javadoc-е само выглядело бы как выгрузка.
     */
    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            TreeSet<Path> sorted = new TreeSet<>();
            files.filter(path -> path.toString().endsWith(".java")).forEach(sorted::add);
            return List.copyOf(sorted);
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
