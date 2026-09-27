package org.ip.routes;

import org.ip.routes.DeepLinkBaseline.Alias;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * E2.0 (ADR-0009 §3–§5): versioned baseline публичных адресов глубокой ссылки.
 *
 * <p>Что здесь проверяется и почему именно так. Решение E2 — «адрес формы строится из
 * типизированного каталога, а не из строковых литералов». Пока каталога нет (он появляется в
 * E2.1), контракт рискует остаться документом: alias можно переименовать «по мелочи», новый
 * named variant — добавить, не заметив, что он стал публичным адресом. Поэтому baseline
 * {@code src/test/resources/routes/deep-link-baseline.txt} — не отчёт, а вход сборки: тест
 * читает его и <b>исходники</b> и падает на любом расхождении.</p>
 *
 * <p>Шесть свойств, и все они — про расхождение, а не про наличие списка:</p>
 * <ol>
 * <li><b>Полнота публикации.</b> Публикуемый набор выводится из модели
 *     ({@code @EntityMetadata} и не {@code @TableSectionMetadata} в {@code org.ip.model}),
 *     а не из файла: новый корневой тип обязан получить alias, лишняя запись — исчезнуть.
 *     Это правило ADR-0009 §4 «публикация выводится из каталога, курируемого списка нет».</li>
 * <li><b>Ключ выведен, а не выбран.</b> {@code alias} обязан совпадать с kebab-case имени
 *     класса: иначе «внешний ключ» превращается в вторую точку решения на каждый тип, а
 *     переименование класса молча меняет или не меняет адрес — неизвестно, что.</li>
 * <li><b>Грамматика ключа.</b> ASCII lower-kebab-case, уникальность, отсутствие коллизии с
 *     занятым первым сегментом URL: alias попадает в адрес, а не только в тест.</li>
 * <li><b>Варианты формы.</b> Список ITEM/LIST-вариантов в baseline обязан совпадать с
 *     тем, что реально объявлено в конфигах форм приложения: иначе ссылка с
 *     {@code ?variant=} либо не откроется, либо откроется не тем, чем сегодня.</li>
 * <li><b>Обязательный контекст.</b> Сущность с объявленным обязательным контекст-фильтром
 *     (сейчас {@code PrdSpec} → {@code journal}) не может выглядеть в baseline как
 *     бесконтекстная: именно из этого поля E2.1 решает, нужен ли {@code RouteContextCodec}.</li>
 * <li><b>Таблица маршрутов.</b> Существующие {@code @Route}/{@code @RouteAlias} приложения
 *     обязаны совпасть с baseline один-в-один (путь и владелец), а под host-сегментами
 *     ({@code /records}, {@code /lists}) может быть объявлено <b>только</b> то, что объявил сам
 *     host: с E2.3 это его alias-шаблоны, и ничего сверх них.</li>
 * </ol>
 *
 * <p>Известные ограничения замера: варианты, объявленные константой или ключом enum
 * ({@code variants.add(SOME_CONST, …)}), регулярным выражением не видны — их проверит
 * runtime-каталог E2.1 по {@code FormRegistry}. Здесь такие объявления отсутствуют у
 * публикуемых типов (единственный случай — owned row {@code PrdSpecMtr}).</p>
 */
class DeepLinkRouteBaselineTest {

    private static final Path BASELINE = Path.of("src/test/resources/routes/deep-link-baseline.txt");

    /** Модель приложения: источник факта «тип публикуется» (ADR-0009 §4). */
    private static final Path MODEL = Path.of("src/main/java/org/ip/model");

    /** Конфиги форм: источник факта «какие варианты существуют». */
    private static final Path FORM_CONFIGS = Path.of("src/main/java/org/ip/views/forms");

    private static final Path APP_SOURCES = Path.of("src/main/java");

    private static final Pattern ENTITY_METADATA = Pattern.compile("(?m)^\\s*@EntityMetadata\\b");
    private static final Pattern TABLE_SECTION = Pattern.compile("(?m)^\\s*@TableSectionMetadata\\b");
    private static final Pattern KEBAB = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
    private static final Pattern CONTEXT_PATH = Pattern.compile("^[a-z][A-Za-z0-9]*(?::[a-z][A-Za-z0-9]*)?$");
    private static final Pattern ROUTE = Pattern.compile("@Route(?:Alias)?\\(\"([^\"]*)\"\\)");
    private static final Pattern ENTITY_CLASS = Pattern.compile(
        "entityClass\\(\\)\\s*\\{\\s*return\\s+([A-Za-z0-9_]+)\\.class");
    private static final Pattern IMPLEMENTS = Pattern.compile("implements\\s+([^{]+)\\{");
    private static final Pattern ITEM_VARIANT = Pattern.compile("variants\\.add\\(\"([^\"]+)\"");
    private static final Pattern LIST_VARIANT = Pattern.compile("variants\\.(?:add|addView)\\(\"([^\"]+)\"");
    private static final Pattern REQUIRED_CONTEXT = Pattern.compile(
        "required(?:Auto|Lookup|Select)\\(\"([^\"]+)\"");

    /** Пути, занятые хостом глубокой ссылки: под ними не может быть статического route. */
    private static final Set<String> DEEP_LINK_HOSTS = Set.of("records", "lists");

    @Test
    void everyPublishedRootHasExactlyOneAliasInTheBaseline() {
        Set<String> derived = publishedRootClasses();
        Map<String, String> baseline = new TreeMap<>();
        for (Alias alias : baseline().aliases()) {
            baseline.put(alias.entityClass(), alias.alias());
        }

        Set<String> unpublished = new TreeSet<>(derived);
        unpublished.removeAll(baseline.keySet());
        Set<String> stale = new TreeSet<>(baseline.keySet());
        stale.removeAll(derived);

        assertThat(unpublished)
            .as("корневой тип каталога обязан иметь публичный alias (ADR-0009 §4): публикация"
                + " выводится из @EntityMetadata/ManagedEntityCatalog, а не решается поштучно")
            .isEmpty();
        assertThat(stale)
            .as("запись baseline обязана соответствовать типу каталога: иначе адрес обещан"
                + " для сущности, которой в модели нет, или строка секции снова стала"
                + " самостоятельным маршрутом (OWNED_ROW не публикуется)")
            .isEmpty();
        assertThat(baseline)
            .as("число публикуемых корней — измеренное значение E2.0; изменение допустимо,"
                + " но обязано быть решением, а не следствием рефакторинга модели")
            .hasSize(16);
    }

    @Test
    void aliasIsTheKebabCaseOfTheEntityClassName() {
        for (Alias alias : baseline().aliases()) {
            String simpleName = alias.simpleName();
            assertThat(alias.alias())
                .as("alias '%s' обязан быть выводом kebab-case из имени класса: внешний ключ"
                    + " не выбирается вручную, иначе переименование класса молча меняет адрес"
                    + " с неизвестным статусом опубликованности (ADR-0009 §4)", alias.alias())
                .isEqualTo(kebabCase(simpleName));
        }
    }

    @Test
    void aliasesAreUniqueWellFormedAndDoNotShadowReservedSegments() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> reserved = baseline().reserved();
        for (Alias alias : baseline().aliases()) {
            assertThat(alias.alias())
                .as("alias обязан быть ASCII lower-kebab-case: он попадает в адрес как есть")
                .matches(KEBAB);
            assertThat(alias.alias())
                .as("alias не может совпасть с занятым первым сегментом URL (%s)", reserved)
                .isNotIn(reserved);
            assertThat(seen.add(alias.alias()))
                .as("alias '%s' объявлен дважды: два типа не могут делить один адрес",
                    alias.alias())
                .isTrue();

            assertThat(alias.item())
                .as("ITEM-варианты обязаны включать default: default-ветка резолвится всегда", alias.alias())
                .contains("default");
            assertThat(alias.list())
                .as("LIST-варианты обязаны включать default: default-ветка резолвится всегда", alias.alias())
                .contains("default");
            for (String variant : concat(alias.item(), alias.list())) {
                assertThat(variant)
                    .as("ключ варианта '%s' — это ровно ключ FormRegistry (strict variants)", variant)
                    .matches(KEBAB);
            }
            assertThat(alias.context())
                .as("контекст сущности '%s' объявляется путём поля либо '<variant>:<path>'",
                    alias.alias())
                .allMatch(value -> "-".equals(value) || CONTEXT_PATH.matcher(value).matches());
            assertThat(alias.legacy())
                .as("legacy-ключ '%s' имеет ту же грамматику, что canonical", alias.alias())
                .allMatch(value -> "-".equals(value) || KEBAB.matcher(value).matches());
        }
    }

    @Test
    void declaredFormVariantsAndRequiredContextMatchTheBaseline() {
        for (Alias alias : baseline().aliases()) {
            List<Path> configs = formConfigsFor(alias.simpleName());
            Set<String> declaredItem = variantsIn(configs, "ItemFormCustomization", ITEM_VARIANT);
            Set<String> declaredList = variantsIn(configs, "ListFormCustomization", LIST_VARIANT);
            Set<String> declaredContext = requiredContextIn(configs);

            assertThat(concat(declaredItem, Set.of("default")))
                .as("ITEM-варианты '%s': baseline фиксирует не только наличие default, но и"
                    + " каждый named variant — новый вариант становится публичным адресом"
                    + " (?variant=…), и это решение, а не побочный эффект конфига", alias.alias())
                .containsExactlyInAnyOrderElementsOf(alias.item());
            assertThat(concat(declaredList, Set.of("default")))
                .as("LIST-варианты '%s': тот же контракт, что для ITEM", alias.alias())
                .containsExactlyInAnyOrderElementsOf(alias.list());
            assertThat(declaredContext)
                .as("обязательный контекст списка '%s': сущность с requiredSelect/requiredLookup/"
                    + "requiredAuto не может выглядеть бесконтекстной — список такого типа не"
                    + " linkable до объявленного RouteContextCodec (ADR-0009 §5)", alias.alias())
                .containsExactlyInAnyOrderElementsOf(alias.requiredContext());
        }
    }

    @Test
    void staticRoutesInTheBaselineAreExactlyTheOnesDeclaredInSources() {
        Map<String, String> declared = declaredStaticRoutes();
        Map<String, String> baselineRoutes = new TreeMap<>();
        for (Map.Entry<String, String> entry : baseline().routes().entrySet()) {
            baselineRoutes.put(entry.getKey(), entry.getValue());
        }

        assertThat(declared)
            .as("таблица маршрутов — часть контракта E2: новый @Route/@RouteAlias приложения"
                + " обязан появиться в baseline тем же коммитом, а исчезнувший — исчезнуть из"
                + " неё; расхождение означает, что адрес меняется без решения")
            .isEqualTo(baselineRoutes);
    }

    @Test
    void deepLinkHostSegmentsAreOwnedByTheHostAndNothingElse() {
        Map<String, String> declared = declaredStaticRoutes();
        Set<String> declaredUnderHost = new TreeSet<>();
        for (String route : declared.keySet()) {
            if (DEEP_LINK_HOSTS.contains(firstSegment(route))) {
                declaredUnderHost.add(route);
            }
        }

        assertThat(declaredUnderHost)
            .as("под /records и /lists объявлено не то, что объявил host: эти сегменты принадлежат"
                + " хосту глубокой ссылки (ADR-0009 §1/§3), а его форма адреса — решение E2.0"
                + " (alias-шаблоны на MainLayout), а не свободное место для чужих @Route")
            .containsExactlyInAnyOrder("/records/:entityKey/:id", "/lists/:entityKey");
        assertThat(declared)
            .as("шаблоны адреса объявлены самим host'ом, а не третьим классом")
            .containsEntry("/records/:entityKey/:id", "org.ip.views.MainLayout")
            .containsEntry("/lists/:entityKey", "org.ip.views.MainLayout");

        assertThat(baseline().planned())
            .as("реализованный маршрут обязан уйти из planned в static-section тем же коммитом:"
                + " иначе baseline обещает как будущее то, что уже объявлено, и следующий срез"
                + " выбирал бы адрес заново")
            .doesNotContain("/records/:entityKey/:id", "/lists/:entityKey")
            .contains("/link-state");
    }

    private static String firstSegment(String route) {
        return route.replaceFirst("^/", "").split("/", 2)[0];
    }

    // === baseline ===

    /** Формат файла читает {@link DeepLinkBaseline}: тот же ридер использует рантайм-забор. */
    private static DeepLinkBaseline baseline() {
        return DeepLinkBaseline.read(BASELINE);
    }

    // === вывод из исходников ===

    /**
     * Публикуемые корневые типы: ровно то правило, которым пользуется
     * {@code EntityDescriptorCatalog} — {@code @EntityMetadata} даёт {@code STANDARD_ROOT},
     * {@code @TableSectionMetadata} отбирает строку секции раньше ({@code OWNED_ROW}).
     */
    private static Set<String> publishedRootClasses() {
        Set<String> published = new TreeSet<>();
        try (Stream<Path> files = Files.list(MODEL)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = source(file);
                if (ENTITY_METADATA.matcher(source).find() && !TABLE_SECTION.matcher(source).find()) {
                    String simpleName = file.getFileName().toString().replace(".java", "");
                    assertThat(source)
                        .as("файл %s объявлен публикуемым, но не объявляет тип '%s'",
                            file, simpleName)
                        .containsPattern("(class|record|enum)\\s+" + simpleName + "\\b");
                    published.add("org.ip.model." + simpleName);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return published;
    }

    /** Конфиги форм, объявляющие указанный класс сущности. */
    private static List<Path> formConfigsFor(String className) {
        Pattern owner = Pattern.compile("entityClass\\(\\)\\s*\\{\\s*return\\s+"
            + Pattern.quote(className) + "\\.class");
        try (Stream<Path> files = Files.list(FORM_CONFIGS)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> owner.matcher(source(path)).find())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Named-варианты указанного вида формы, объявленные литералами в конфигах. */
    private static Set<String> variantsIn(List<Path> configs, String customization, Pattern variant) {
        Set<String> variants = new TreeSet<>();
        for (Path config : configs) {
            String source = source(config);
            Matcher implementsClause = IMPLEMENTS.matcher(source);
            if (!implementsClause.find() || !implementsClause.group(1).contains(customization)) {
                continue;
            }
            Matcher matcher = variant.matcher(source);
            while (matcher.find()) {
                variants.add(matcher.group(1));
            }
        }
        return variants;
    }

    /** Обязательные контекст-фильтры, объявленные в конфигах форм сущности. */
    private static Set<String> requiredContextIn(List<Path> configs) {
        Set<String> required = new TreeSet<>();
        for (Path config : configs) {
            Matcher matcher = REQUIRED_CONTEXT.matcher(source(config));
            while (matcher.find()) {
                required.add(matcher.group(1));
            }
        }
        return required;
    }

    /** Путь → владелец по факту объявления {@code @Route}/{@code @RouteAlias} в исходниках. */
    private static Map<String, String> declaredStaticRoutes() {
        Map<String, String> routes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(APP_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ROUTE.matcher(source(file));
                while (matcher.find()) {
                    String value = matcher.group(1);
                    String path = value.isEmpty() ? "/" : value.startsWith("/") ? value : "/" + value;
                    String owner = APP_SOURCES.relativize(file).toString()
                        .replace(".java", "").replace('\\', '.');
                    assertThat(routes.put(path, owner))
                        .as("маршрут '%s' объявлен дважды: %s и %s", path, routes.get(path), owner)
                        .isNull();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return routes;
    }

    // === helpers ===

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String source(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> set(String value) {
        return new LinkedHashSet<>(List.of(value.split(",")));
    }

    private static Set<String> concat(Set<String> first, Set<String> second) {
        Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }

    /** ASCII lower-kebab-case имени класса: граница — перед заглавной после строчной/цифры. */
    private static String kebabCase(String simpleName) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char current = simpleName.charAt(i);
            if (Character.isUpperCase(current)) {
                char previous = i == 0 ? 0 : simpleName.charAt(i - 1);
                boolean boundary = i > 0 && !Character.isUpperCase(previous)
                    || i > 0 && Character.isUpperCase(previous) && i + 1 < simpleName.length()
                        && Character.isLowerCase(simpleName.charAt(i + 1));
                if (boundary) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
