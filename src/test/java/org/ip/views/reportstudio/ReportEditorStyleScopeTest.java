package org.ip.views.reportstudio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.6.5: стили редактора отчётов живут в одном scope и не доходят до обычных форм.
 *
 * <p><b>Что было.</b> У редактора было два CSS-scope: общий {@code .report-editor} и второй, который
 * висел на вложенном редакторе структуры и на variant-вьюхе. Оба держали один и тот же блок
 * компактных Lumo-переменных, а второй ещё и пять правил под классы, которых не вешает ни один
 * элемент кода (card/pill/drop — проверено обходом {@code src/main}). То есть вид экрана зависел от
 * того, какой из двух классов оказался внутри, а часть правил не работала вообще.</p>
 *
 * <p><b>Что проверяется.</b> Свойство, которое легко потерять при следующей правке, и которое
 * видно только текстом:</p>
 *
 * <ul>
 *   <li>каждое правило, упоминающее редактор, начинается с его scope — значит правило не может
 *       сработать на форме, которая лежит вне редактора;</li>
 *   <li>правила на контролы форм всегда имеют класс-владелец: глобальное правило на
 *       {@code vaadin-text-field} покрасило бы весь продукт, а не редактор;</li>
 *   <li>классы семейства {@code report-editor*} вешает ровно тот код, который записан в книгу:
 *       новый класс — это новое решение (и новый scope), а не побочный эффект;</li>
 *   <li>книга невакуумна: общий scope и его класс реально существуют в css и в коде.</li>
 * </ul>
 *
 * <p>Разбор css — построчный: файл темы состоит из плоских правил и {@code @import}; вложенных
 * {@code @media} в нём нет, и тест это утверждает, чтобы разбор не начал молча врать.</p>
 */
class ReportEditorStyleScopeTest {

    private static final Path STYLES = Path.of("src/main/frontend/themes/default/styles.css");
    private static final Path APP_SOURCES = Path.of("src/main/java");

    /** После D3.6.7 production использует только канонический CSS scope редактора. */
    private static final Set<String> EDITOR_CLASSES = Set.of("report-editor");

    private static final List<String> FORM_CONTROL_TAGS = List.of(
            "vaadin-text-field", "vaadin-text-area", "vaadin-integer-field", "vaadin-combo-box",
            "vaadin-grid", "vaadin-tabs", "vaadin-details", "vaadin-checkbox", "vaadin-button",
            "vaadin-radio-group", "vaadin-split-layout");

    /** Мёртвые правила второго scope: классы не вешаются кодом, поэтому правила удалены. */
    private static final List<String> REMOVED_DEAD_CLASSES = List.of(
            "card-group", "card-selected", "pill-toggle", "drop-line", "drop-overlay");

    @Test
    void editorRulesStayInsideTheEditorScope() {
        for (String selector : selectors()) {
            if (selector.contains("report-editor")) {
                assertThat(selector)
                        .as("правило редактора без общего scope сработало бы и на обычной форме: %s", selector)
                        .startsWith(".report-editor");
            }
        }
    }

    @Test
    void formControlRulesAlwaysHaveAnOwner() {
        for (String selector : selectors()) {
            String head = selector.trim();
            boolean targetsFormControl = FORM_CONTROL_TAGS.stream().anyMatch(head::startsWith);
            if (targetsFormControl) {
                assertThat(head)
                        .as("правило на контрол формы без класса-владельца покрасило бы весь продукт: %s", head)
                        .startsWith(".");
            }
        }
    }

    @Test
    void variantScopesAndDeadRulesAreGone() {
        String css = styles();

        assertThat(css).as("второй scope редактора вернулся").doesNotContain(".report-editor-structured");
        assertThat(css).as("scope compact-вьюхи вернулся").doesNotContain(".report-editor-compact");
        for (String dead : REMOVED_DEAD_CLASSES) {
            assertThat(css)
                    .as("вернулось правило под класс, которого никто не вешает: %s", dead)
                    .doesNotContain(dead);
        }
    }

    @Test
    void productionAddsOnlyTheLedgerClasses() {
        Set<String> added = new TreeSet<>();
        try (Stream<Path> files = Files.walk(APP_SOURCES)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".java")).toList()) {
                Matcher matcher = Pattern.compile("addClassName\\(\"([^\"]+)\"\\)")
                        .matcher(Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (matcher.group(1).startsWith("report-editor")) {
                        added.add(matcher.group(1));
                    }
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }

        assertThat(added)
                .as("классы редактора в коде разошлись с книгой: новый класс — это решение, "
                        + "и его место в книге, а не рядом с ней")
                .isEqualTo(EDITOR_CLASSES);
    }

    /** Не-вакуумность: общий scope есть в css, а его класс — в коде. */
    @Test
    void theScopeIsReal() {
        assertThat(styles()).contains(".report-editor {");
        assertThat(selectors())
                .as("ни одного правила редактора не найдено — забор смотрит не туда")
                .anyMatch(selector -> selector.startsWith(".report-editor"));
    }

    // === разбор темы ===

    private static String styles() {
        try {
            String css = Files.readString(STYLES, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            assertThat(css)
                    .as("разбор темы рассчитан на плоские правила: появились @media — обнови забор")
                    .doesNotContain("@media");
            return css;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Селекторы файла темы: текст перед каждой парой фигурных скобок, разбитый по запятым. */
    private static List<String> selectors() {
        String css = styles().replaceAll("(?s)/\\*.*?\\*/", "");
        List<String> selectors = new ArrayList<>();
        Matcher blocks = Pattern.compile("([^{}]+)\\{").matcher(css);
        while (blocks.find()) {
            // Голова правила целиком: группа селекторов может занимать несколько строк,
            // и проверка только последней строки молча пропускала отвалившиеся первые.
            String head = blocks.group(1).replaceAll("\\s+", " ").trim();
            for (String selector : head.split(",")) {
                String trimmed = selector.trim();
                if (!trimmed.isEmpty()) {
                    selectors.add(trimmed);
                }
            }
        }
        return selectors;
    }
}
