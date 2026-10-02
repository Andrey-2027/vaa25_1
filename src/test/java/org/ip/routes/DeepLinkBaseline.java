package org.ip.routes;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Читатель versioned baseline публичных адресов глубокой ссылки (ADR-0009 §4/§5).
 *
 * <p>Отдельный от тестов класс, потому что baseline читают два разных замера: забор по
 * <b>исходникам</b> ({@code DeepLinkRouteBaselineTest} — литералы {@code @Route}, вывод ключа из
 * имени класса) и забор по <b>рантайму</b> ({@code DeepLinkCatalogBaselineIT} — каталог,
 * построенный поверх настоящих дескрипторов и реестра форм). Формат файла должен читаться
 * ровно одинаково, иначе две проверки разойдутся и перестанут подтверждать одну поверхность.</p>
 *
 * <p>Публичный он потому, что тем же единственным чтением пользуется забор словаря якоря
 * ({@code org.ip.views.admin.CardAnchorTest}, записи {@code view=}): у формата файла одно место,
 * а не по читателю на раздел.</p>
 */
public final class DeepLinkBaseline {

    /** Ровно те поля, которые обязана нести запись alias: опечатка в имени поля — ошибка, а не пропуск. */
    private static final Set<String> ALIAS_FIELDS =
        Set.of("alias", "class", "item", "list", "context", "legacy");

    private static final Pattern FIELD = Pattern.compile("([a-z]+)=([^\\s]+)");

    private final List<Alias> aliases;
    private final Set<String> reserved;
    private final Map<String, String> routes;
    private final Set<String> planned;
    private final List<String> views;

    private DeepLinkBaseline(List<Alias> aliases, Set<String> reserved,
                             Map<String, String> routes, Set<String> planned,
                             Set<String> views) {
        this.aliases = List.copyOf(aliases);
        this.reserved = Set.copyOf(reserved);
        this.routes = Map.copyOf(routes);
        this.planned = Set.copyOf(planned);
        this.views = List.copyOf(views);
    }

    public static DeepLinkBaseline read(Path file) {
        List<Alias> aliases = new ArrayList<>();
        Set<String> reserved = new LinkedHashSet<>();
        Map<String, String> routes = new TreeMap<>();
        Set<String> planned = new LinkedHashSet<>();
        Set<String> views = new LinkedHashSet<>();
        for (String raw : lines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("reserved ")) {
                assertThat(reserved.add(line.substring("reserved ".length()).trim()))
                    .as("reserved-сегмент '%s' объявлен дважды", line)
                    .isTrue();
            } else if (line.startsWith("route ")) {
                String[] parts = line.split("\\s+");
                assertThat(parts)
                    .as("строка route обязана иметь вид 'route <path> <owner>': %s", line)
                    .hasSize(3);
                assertThat(routes.put(parts[1], parts[2]))
                    .as("маршрут '%s' объявлен дважды в baseline", parts[1])
                    .isNull();
            } else if (line.startsWith("planned ")) {
                assertThat(planned.add(line.substring("planned ".length()).trim()))
                    .as("planned-адрес '%s' объявлен дважды", line)
                    .isTrue();
            } else if (line.startsWith("view=")) {
                assertThat(views.add(line.substring("view=".length()).trim()))
                    .as("якорь '%s' объявлен дважды: у одного места карточки один адрес", line)
                    .isTrue();
            } else {
                aliases.add(alias(line));
            }
        }
        return new DeepLinkBaseline(aliases, reserved, routes, planned, views);
    }

    private static Alias alias(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(line);
        while (matcher.find()) {
            assertThat(fields.put(matcher.group(1), matcher.group(2)))
                .as("поле '%s' объявлено дважды в строке: %s", matcher.group(1), line)
                .isNull();
        }
        assertThat(fields.keySet())
            .as("запись alias обязана нести ровно поля %s (иначе опечатка в имени поля"
                + " молча выпадает из проверки): %s", ALIAS_FIELDS, line)
            .isEqualTo(ALIAS_FIELDS);
        return new Alias(fields.get("alias"), fields.get("class"),
            set(fields.get("item")), set(fields.get("list")),
            set(fields.get("context")), set(fields.get("legacy")));
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> set(String value) {
        return new LinkedHashSet<>(List.of(value.split(",")));
    }

    /** Публикуемые типы, их варианты и объявленный контекст. */
    List<Alias> aliases() {
        return aliases;
    }

    /** Занятые первые сегменты URL. */
    Set<String> reserved() {
        return reserved;
    }

    /** Существующие статические маршруты: путь → владелец. */
    Map<String, String> routes() {
        return routes;
    }

    /** Объявленные, но ещё не реализованные адреса хоста ссылки. */
    Set<String> planned() {
        return planned;
    }

    /**
     * Публичные якоря карточки типа ({@code ?view=}): вкладки и разделы в порядке словаря. Порядок
     * хранится потому, что этот список — публикация словаря, и её читают глазами.
     */
    public List<String> views() {
        return views;
    }

    /** Запись alias: ключ, класс, варианты по видам формы, контекст и прежние ключи. */
    record Alias(String alias, String entityClass, Set<String> item, Set<String> list,
                 Set<String> context, Set<String> legacy) {

        /** Класс без пакета — для сверки с простым именем типа. */
        String simpleName() {
            return entityClass.substring(entityClass.lastIndexOf('.') + 1);
        }

        /** Пути обязательного контекста списка; {@code -} означает «обязательных нет». */
        Set<String> requiredContext() {
            return context.contains("-") ? Set.of() : context;
        }
    }
}
