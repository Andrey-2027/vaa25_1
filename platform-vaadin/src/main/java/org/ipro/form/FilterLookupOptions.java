package org.ipro.form;

import org.ipro.crud.EntityLookup;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.util.EntityOptionLabel;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.GridMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Значения справочника для визуального фильтра FilterGrid (D3.5.2b, INTERNAL будущего
 * {@code platform-vaadin}).
 *
 * <h2>Почему это отдельный шов</h2>
 *
 * <p>{@code FilterFieldResolver.valueOptions(field)} — точка, куда FilterGrid просит список
 * значений ссылочного поля. Раньше она отдавала {@code LookupService.findAll(entity)}: полную
 * выгрузку справочника. Мало того что чтение не ограничено, оно ещё и <b>повторяется на каждый
 * запрос</b>: {@code JpaFilterConditionCompiler.compile(FilterNode, resolver)} возвращает
 * отложенную {@code Specification}, и разрешение значений живёт внутри {@code toPredicate} —
 * то есть выгрузка справочника выполнялась при каждом обновлении грида, а не один раз при
 * применении вида.</p>
 *
 * <h2>Что именно нужно библиотеке (проверено по байткоду filtergrid-core/-jpa 1.0-SNAPSHOT)</h2>
 *
 * <p>{@code resolveEntityRef(stored, field, resolver)} берёт {@code valueOptions(field)} и ищет
 * в списке элемент, у которого <b>строка</b> совпадает с сохранённым значением, в двух проходах:
 * сначала {@code EntityOptionLabel.canonical(option)} ({@code getDisplayName()}, а без него
 * {@code toString()}), затем {@code String.valueOf(option)} ({@code toString()}). Если совпадения
 * нет — {@code IllegalArgumentException} с подписью поля. Отсюда два следствия, которые
 * определяют реализацию:</p>
 *
 * <ul>
 *   <li>сопоставление идёт <b>по строке, а не по идентичности</b>: вернуть «правильную» сущность,
 *       загруженную по id, недостаточно — её display-строка должна ещё и совпасть с сохранённой.
 *       Поэтому id-путь применим только как быстрый способ получить сущность, которую потом всё
 *       равно проверяет {@link #exactMatch};</li>
 *   <li>сохранённое значение — это всегда <b>display-строка</b> (редактор FilterGrid пишет
 *       {@code String.valueOf(entity)}), поэтому ограниченное разрешение — это поиск по тексту,
 *       а не чтение по id.</li>
 * </ul>
 *
 * <h2>Режимы</h2>
 *
 * <ul>
 *   <li>{@link #forTree(FilterNode)} — режим компилятора: отдаёт <b>только</b> значения, на которые
 *       ссылается компилируемое дерево. Ноль запросов при пустом дереве, по одному ограниченному
 *       чтению на ссылку, с мемоизацией (content- и count-запросы идут через один resolver).</li>
 *   <li>{@link #forPicker(Supplier)} — режим выбора значения в UI: ссылки текущего дерева (чтобы
 *       сохранённое условие отображалось) плюс ограниченная первая страница справочника как
 *       подсказка. Полный выбор по большому справочнику — задача выбора значения
 *       ({@code FilterEntitySelector}), а не выгрузки таблицы в комбобокс.</li>
 * </ul>
 *
 * <h2>Честная граница</h2>
 *
 * <p>Разрешение display-строки идёт ограниченным {@link EntityLookup#search}: сначала по всей
 * сохранённой строке, затем по её длинным токенам — составные display-имена («КОД - Наименование»,
 * «Наименование (шт)») не находятся подстрочным поиском по одному полю. Кандидаты затем проходят
 * строгую проверку {@link #exactMatch}, поэтому «похожая» сущность в результат не попадёт.
 * Нерезрешимое значение даёт пустой список, и библиотека падает громко, с подписью поля — это
 * осознанный выбор в пользу отказа вместо тихо неверного фильтра. Тот же класс хрупкости есть и
 * у прежнего полного сканирования: у сущностей без стабильного {@code toString()} (например
 * {@code Journal}, у которого его нет) редактор сохраняет {@code org.ip.model.Journal@1a2b3c},
 * и такое значение не разрешается уже после перезапуска JVM.</p>
 */
public final class FilterLookupOptions {

    /** Страница подсказок в комбобоксе выбора значения (см. {@link LookupComboHelper#DICTIONARY_LIMIT}). */
    public static final int PICKER_PAGE_LIMIT = LookupComboHelper.DICTIONARY_LIMIT;

    /** Сколько кандидатов запрашивается на одну сохранённую display-строку. */
    public static final int RESOLVE_LIMIT = LookupComboHelper.DICTIONARY_LIMIT;

    /** Сколько токенов сохранённой строки участвуют в поиске (длинные display-имена). */
    private static final int MAX_TOKENS = 3;

    private final EntityLookup lookup;
    private final GridMetadata metadata;
    private final Map<String, List<Object>> resolved = new ConcurrentHashMap<>();

    public FilterLookupOptions(EntityLookup lookup, GridMetadata metadata) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    /**
     * Режим компилятора: значения, на которые ссылается дерево.
     *
     * @param tree компилируемое дерево (обычно собранное из fixed/context/user)
     */
    public Function<ResolvedFilterField, List<?>> forTree(FilterNode tree) {
        FilterNode captured = tree;
        return field -> referencedOptions(field, captured);
    }

    /**
     * Режим выбора значения: ссылки текущего дерева плюс ограниченная страница справочника.
     *
     * @param tree текущее дерево фильтра (может быть пустым или null)
     */
    public Function<ResolvedFilterField, List<?>> forPicker(Supplier<FilterNode> tree) {
        return field -> {
            List<Object> options = new ArrayList<>(referencedOptions(field, tree == null ? null : tree.get()));
            for (Object candidate : pageOptions(field)) {
                if (!options.contains(candidate)) {
                    options.add(candidate);
                }
            }
            return options;
        };
    }

    private List<Object> referencedOptions(ResolvedFilterField field, FilterNode tree) {
        Class<?> entityType = lookupEntityType(field);
        if (entityType == null || tree == null) {
            return List.of();
        }
        List<Object> options = new ArrayList<>();
        for (String stored : storedValues(tree, field.path())) {
            Optional<Object> resolvedOption = resolve(entityType, stored);
            if (resolvedOption.isPresent() && !options.contains(resolvedOption.get())) {
                options.add(resolvedOption.get());
            }
        }
        return options;
    }

    private List<Object> pageOptions(ResolvedFilterField field) {
        Class<?> entityType = lookupEntityType(field);
        return entityType == null ? List.of() : search(entityType, "", PICKER_PAGE_LIMIT);
    }

    private Class<?> lookupEntityType(ResolvedFilterField field) {
        if (field == null) {
            return null;
        }
        FieldMetadataInfo info = metadata.getFieldByName(field.path());
        return info != null && info.hasLookup() ? info.getLookupEntity() : null;
    }

    private Optional<Object> resolve(Class<?> entityType, String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        String cacheKey = entityType.getName() + '\u0000' + stored;
        List<Object> cached = resolved.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? Optional.empty() : Optional.of(cached.get(0));
        }
        Object match = byIdentifier(entityType, stored);
        if (match == null) {
            for (String term : searchTerms(stored)) {
                match = exactMatch(search(entityType, term, RESOLVE_LIMIT), stored);
                if (match != null) {
                    break;
                }
            }
        }
        resolved.put(cacheKey, match == null ? List.of() : List.of(match));
        return Optional.ofNullable(match);
    }

    /**
     * Быстрый путь для значения-идентификатора: одно чтение по PK. Результат всё равно проходит
     * {@link #exactMatch} — иначе «правильная» сущность с несовпадающей display-строкой всё равно
     * не подошла бы компилятору и лишь замаскировала бы несовпадение.
     */
    private Object byIdentifier(Class<?> entityType, String stored) {
        Long id;
        try {
            id = Long.valueOf(stored.trim());
        } catch (NumberFormatException notAnIdentifier) {
            return null;
        }
        return lookup.findSelectedById(entityType, id)
            .filter(candidate -> exactMatch(List.of(candidate), stored) != null)
            .orElse(null);
    }

    /**
     * Точное соответствие контракту библиотеки: проход по display-имени (canonical), затем по
     * {@code toString}. Порядок и способ сравнения — как в {@code resolveEntityRef}, включая
     * использование самого {@link EntityOptionLabel}, а не его копии.
     */
    private static Object exactMatch(List<Object> candidates, String stored) {
        for (Object candidate : candidates) {
            if (stored.equals(EntityOptionLabel.canonical(candidate))) {
                return candidate;
            }
        }
        for (Object candidate : candidates) {
            if (candidate != null && stored.equals(String.valueOf(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Термы поиска: сначала вся сохранённая строка (display-имя из одного поля находится сразу),
     * затем её длинные токены — для составных имён, которые подстрочным поиском не находятся.
     */
    private static List<String> searchTerms(String stored) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(stored);
        storedCodePoints(stored).stream()
            .filter(token -> !token.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .limit(MAX_TOKENS)
            .forEach(terms::add);
        return List.copyOf(terms);
    }

    private static List<String> storedCodePoints(String stored) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < stored.length(); index++) {
            char character = stored.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                token.append(character);
            } else if (token.length() > 0) {
                tokens.add(token.toString());
                token.setLength(0);
            }
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    /**
     * Сохранённые display-строки дерева для одного пути. Значения, уже пришедшие объектами
     * ({@code FilterCondition.values()} — вариант {@code inList}), разрешать не нужно: компилятор
     * использует их как есть.
     */
    static List<String> storedValues(FilterNode tree, String path) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collect(tree, path, values);
        return List.copyOf(values);
    }

    private static void collect(FilterNode node, String path, LinkedHashSet<String> out) {
        if (node instanceof FilterGroup group) {
            for (FilterNode child : group.children()) {
                collect(child, path, out);
            }
            return;
        }
        if (!(node instanceof FilterConditionNode holder)) {
            return;
        }
        FilterCondition condition = holder.condition();
        if (condition == null || !path.equals(condition.path())
            || condition.dataType() != FilterDataType.ENTITY_REFERENCE) {
            return;
        }
        if (condition.values() != null) {
            return;
        }
        if (condition.operator() == FilterOperator.IN) {
            for (Object value : condition.inValues()) {
                addIfText(value, out);
            }
            return;
        }
        addIfText(condition.value(), out);
        addIfText(condition.valueTo(), out);
    }

    private static void addIfText(Object value, LinkedHashSet<String> out) {
        if (value instanceof String text && !text.isBlank()) {
            out.add(text);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> search(Class<?> entityType, String term, int limit) {
        return (List<Object>) lookup.search((Class) entityType, List.of(), term, limit);
    }

    /** Резолвер для {@code LookupFilterFieldResolver} без привязки к дереву (все значения — подсказки). */
    Function<ResolvedFilterField, List<?>> asResolver() {
        return forPicker(null);
    }
}
