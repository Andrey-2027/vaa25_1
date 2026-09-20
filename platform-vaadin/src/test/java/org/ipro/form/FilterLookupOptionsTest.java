package org.ipro.form;

import org.ipro.crud.EntityLookup;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.util.EntityOptionLabel;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.GridMetadata;
import org.ipro.metadata.HasDisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3.5.2b: characterization-контракта {@code FilterFieldResolver.valueOptions}
 * (см. {@link FilterLookupOptions}).
 *
 * <p>Тест фиксирует не «как нам удобно», а то, что реально требует компилятор FilterGrid:
 * сохранённое значение условия-ссылки — это <b>display-строка</b>, и она должна быть
 * сопоставима с одним из отданных вариантов по {@code EntityOptionLabel.canonical()} либо по
 * {@code String.valueOf()} (оба прохода библиотечного {@code resolveEntityRef}). Сопоставление
 * проверяется настоящим {@link EntityOptionLabel}, а не копией его логики, поэтому тест упадёт,
 * если мы начнём отдавать «правильную по id, но не по строке» сущность.</p>
 *
 * <p>Второе, что здесь зафиксировано: чтения ограничены. Раньше эта точка отдавала всю таблицу
 * ({@code LookupService.findAll}) на каждый вызов, а вызывается она из {@code toPredicate} —
 * то есть на каждый запрос грида.</p>
 */
class FilterLookupOptionsTest {

    private static final String PATH = "category";

    @Test
    void resolvesOnlyValuesReferencedByTheCompiledTree() {
        DictionaryItem other = new DictionaryItem(2L, "B", "Пила");
        RecordingLookup lookup = new RecordingLookup().on("B - Пила", other);

        List<Object> options = options(optionsForTree(lookup, condition("B - Пила")), field());

        assertThat(options).containsExactly(other);
        assertThat(lookup.reads).hasSize(1);
        assertThat(lookup.reads.get(0)).contains("term={B - Пила}");
    }

    @Test
    void resolvedOptionSatisfiesTheLibrarysTwoPassMatch() {
        DictionaryItem hammer = new DictionaryItem(1L, "A", "Молоток");
        // сохранённое редактором FilterGrid значение — String.valueOf(entity), то есть toString;
        // display-имя отличается от него, поэтому попадает второй проход resolveEntityRef
        String stored = "A - Молоток";
        RecordingLookup lookup = new RecordingLookup().on(stored, hammer);

        List<Object> options = options(optionsForTree(lookup, condition(stored)), field());

        assertThat(options).containsExactly(hammer);
        assertThat(EntityOptionLabel.canonical(hammer)).isNotEqualTo(stored);
        assertThat(String.valueOf(options.get(0))).isEqualTo(stored);
    }

    @Test
    void composedDisplayStringIsFoundByItsLongestToken() {
        DictionaryItem hammer = new DictionaryItem(1L, "A", "Молоток");
        // Составное display-имя («A - Молоток») подстрочным поиском по одному полю не находится,
        // поэтому второй терм — самый длинный токен; найденное всё равно проходит строгую проверку.
        RecordingLookup lookup = new RecordingLookup().on("Молоток", hammer);

        List<Object> options = options(optionsForTree(lookup, condition("A - Молоток")), field());

        assertThat(options).containsExactly(hammer);
        assertThat(lookup.reads).hasSize(2);
        assertThat(lookup.reads.get(1)).contains("term={Молоток}");
    }

    @Test
    void similarDictionaryEntryIsNotAccepted() {
        RecordingLookup lookup = new RecordingLookup()
            .on("Молоток", new DictionaryItem(1L, "A", "Молоток большой"));

        List<Object> options = options(optionsForTree(lookup, condition("A - Молоток")), field());

        assertThat(options).isEmpty();
    }

    @Test
    void unresolvableValueYieldsEmptyOptionsInsteadOfAGuess() {
        RecordingLookup lookup = new RecordingLookup().on("что-то другое", new DictionaryItem(9L, "Z", "Другое"));

        List<Object> options = options(optionsForTree(lookup, condition("Удалённый элемент")), field());

        assertThat(options).isEmpty();
    }

    @Test
    void treeWithoutReferenceConditionsCostsNothing() {
        RecordingLookup lookup = new RecordingLookup();

        FilterNode textOnly = FilterConditionNode.of(new FilterCondition(
            PATH, FilterOperator.EQ, "A - Молоток", null, FilterDataType.TEXT));

        assertThat(options(optionsForTree(lookup, null), field())).isEmpty();
        assertThat(options(optionsForTree(lookup, textOnly), field())).isEmpty();
        assertThat(lookup.reads).isEmpty();
    }

    @Test
    void emptyTreeCostsNothingInPickerMode() {
        RecordingLookup lookup = new RecordingLookup().on("", new DictionaryItem(1L, "A", "Молоток"));

        Function<ResolvedFilterField, List<?>> picker = new FilterLookupOptions(lookup, metadata())
            .forPicker(() -> null);

        assertThat(options(picker, field())).hasSize(1);
        assertThat(lookup.reads).containsExactly(
            "search DictionaryItem term={} limit=" + FilterLookupOptions.PICKER_PAGE_LIMIT + " fields=[]");
    }

    @Test
    void pickerKeepsReferencedValueFirstAndAddsBoundedPage() {
        DictionaryItem referenced = new DictionaryItem(7L, "Z", "Удалённый из страницы");
        DictionaryItem firstPage = new DictionaryItem(1L, "A", "Молоток");
        RecordingLookup lookup = new RecordingLookup()
            .on("", firstPage)
            .on("Z - Удалённый из страницы", referenced);

        Function<ResolvedFilterField, List<?>> picker = new FilterLookupOptions(lookup, metadata())
            .forPicker(() -> condition("Z - Удалённый из страницы"));

        assertThat(options(picker, field())).containsExactly(referenced, firstPage);
        assertThat(lookup.reads.get(0)).contains("term={Z - Удалённый из страницы}");
        assertThat(lookup.reads.get(1))
            .contains("term={} limit=" + FilterLookupOptions.PICKER_PAGE_LIMIT);
    }

    @Test
    void everyReadStaysWithinTheApiLimit() {
        DictionaryItem item = new DictionaryItem(1L, "A", "Молоток");
        RecordingLookup lookup = new RecordingLookup().on("A - Молоток", item);

        options(optionsForTree(lookup, condition("A - Молоток")), field());

        assertThat(lookup.reads).isNotEmpty();
        assertThat(FilterLookupOptions.RESOLVE_LIMIT).isLessThanOrEqualTo(EntityLookup.MAX_LIMIT);
        assertThat(FilterLookupOptions.PICKER_PAGE_LIMIT).isLessThanOrEqualTo(EntityLookup.MAX_LIMIT);
    }

    @Test
    void valueResolvedOncePerResolvedString() {
        DictionaryItem item = new DictionaryItem(1L, "A", "Молоток");
        RecordingLookup lookup = new RecordingLookup().on("A - Молоток", item);
        Function<ResolvedFilterField, List<?>> optionsFn = optionsForTree(lookup,
            FilterGroup.or(condition("A - Молоток"), condition("A - Молоток")));

        List<Object> twice = new ArrayList<>();
        twice.addAll(options(optionsFn, field()));
        twice.addAll(options(optionsFn, field()));

        assertThat(twice).containsExactly(item, item);
        // вторая ссылка и второй вызов valueOptions (content/count-запросы) берут результат из кэша
        assertThat(lookup.reads).hasSize(1);
    }

    @Test
    void valuesAlreadyCarriedAsObjectsNeedNoRead() {
        RecordingLookup lookup = new RecordingLookup();
        FilterNode tree = FilterConditionNode.of(FilterCondition.inList(
            PATH, FilterDataType.ENTITY_REFERENCE, List.of(new DictionaryItem(3L, "C", "Ключ"))));

        assertThat(options(optionsForTree(lookup, tree), field())).isEmpty();
        assertThat(lookup.reads).isEmpty();
    }

    @Test
    void inListStoredAsTextResolvesEveryElement() {
        DictionaryItem hammer = new DictionaryItem(1L, "A", "Молоток");
        DictionaryItem saw = new DictionaryItem(2L, "B", "Пила");
        RecordingLookup lookup = new RecordingLookup()
            .on("A - Молоток", hammer)
            .on("B - Пила", saw);
        FilterNode tree = FilterConditionNode.of(new FilterCondition(
            PATH, FilterOperator.IN, "A - Молоток, B - Пила", null, FilterDataType.ENTITY_REFERENCE));

        assertThat(options(optionsForTree(lookup, tree), field())).containsExactly(hammer, saw);
    }

    @Test
    void nonReferenceConditionOnTheSamePathIsIgnored() {
        RecordingLookup lookup = new RecordingLookup();
        FilterNode tree = FilterConditionNode.of(new FilterCondition(
            PATH, FilterOperator.EQ, "A - Молоток", null, FilterDataType.TEXT));

        assertThat(options(optionsForTree(lookup, tree), field())).isEmpty();
        assertThat(lookup.reads).isEmpty();
    }

    @Test
    void nonLookupFieldIsIgnored() {
        RecordingLookup lookup = new RecordingLookup();
        Function<ResolvedFilterField, List<?>> options = new FilterLookupOptions(lookup, metadata(false))
            .forTree(condition("A - Молоток"));

        assertThat(options(options, field())).isEmpty();
        assertThat(lookup.reads).isEmpty();
    }

    @Test
    void numericStoredValueIsReadByIdentifierButStillVerifiedByDisplayString() {
        // Значение-идентификатор: одно чтение по PK, но принять его можно только если строка
        // совпадает так же, как её сравнивает библиотека.
        DictionaryItem plain = new DictionaryItem(5L, "A", "Молоток");
        IdShapedItem idShaped = new IdShapedItem(5L);
        RecordingLookup lookup = new RecordingLookup().onId(5L, idShaped);

        List<Object> options = options(optionsForTree(lookup, condition("5")), field());

        assertThat(options).containsExactly(idShaped);
        assertThat(lookup.reads).containsExactly("byId DictionaryItem id=5");

        RecordingLookup mismatching = new RecordingLookup().onId(5L, plain);
        assertThat(options(optionsForTree(mismatching, condition("5")), field())).isEmpty();
    }

    @Test
    void storedValuesOfTreeAreScopedToTheRequestedPath() {
        FilterNode tree = FilterGroup.and(
            condition("A - Молоток"),
            FilterConditionNode.of(new FilterCondition("other", FilterOperator.EQ, "B - Пила", null,
                FilterDataType.ENTITY_REFERENCE)));

        assertThat(FilterLookupOptions.storedValues(tree, PATH)).containsExactly("A - Молоток");
    }

    // === fixtures ===

    private static FilterNode condition(String stored) {
        return FilterConditionNode.of(new FilterCondition(
            PATH, FilterOperator.EQ, stored, null, FilterDataType.ENTITY_REFERENCE));
    }

    private static ResolvedFilterField field() {
        return new ResolvedFilterField(PATH, "Категория", DictionaryItem.class,
            FilterDataType.ENTITY_REFERENCE, true);
    }

    private static Function<ResolvedFilterField, List<?>> optionsForTree(RecordingLookup lookup, FilterNode tree) {
        return new FilterLookupOptions(lookup, metadata()).forTree(tree);
    }

    /** Вызов провайдера: wildcard-результат приводится к {@code List<Object>} для assert'ов. */
    private static List<Object> options(Function<ResolvedFilterField, List<?>> provider, ResolvedFilterField field) {
        List<Object> result = new ArrayList<>();
        result.addAll(provider.apply(field));
        return result;
    }

    private static GridMetadata metadata() {
        return metadata(true);
    }

    private static GridMetadata metadata(boolean lookup) {
        FieldMetadataInfo info = mock(FieldMetadataInfo.class);
        when(info.hasLookup()).thenReturn(lookup);
        doReturn(DictionaryItem.class).when(info).getLookupEntity();
        GridMetadata metadata = mock(GridMetadata.class);
        when(metadata.getFieldByName(PATH)).thenReturn(info);
        return metadata;
    }

    /**
     * Словарная запись: стабильные display-имя и toString, как у сущностей справочников.
     * Класс обязан быть {@code public}: {@code EntityOptionLabel.canonical} вызывает
     * {@code getDisplayName()} рефлексией из другого пакета, и на непубличном классе
     * {@code Method.invoke} падает — библиотека тихо уходит в {@code toString()}.
     */
    public static class DictionaryItem implements HasDisplayName {
        final Long id;
        private final String code;
        private final String name;

        DictionaryItem(Long id, String code, String name) {
            this.id = id;
            this.code = code;
            this.name = name;
        }

        @Override
        public String getDisplayName() {
            return code + " " + name;
        }

        @Override
        public String toString() {
            return code + " - " + name;
        }
    }

    /** Запись, у которой сохранённая строка совпадает с идентификатором. */
    public static final class IdShapedItem extends DictionaryItem {
        IdShapedItem(Long id) {
            super(id, String.valueOf(id), "");
        }

        @Override
        public String getDisplayName() {
            return String.valueOf(id);
        }

        @Override
        public String toString() {
            return String.valueOf(id);
        }
    }

    /** Lookup-заглушка: записывает все чтения и отдаёт заранее заданные ответы. */
    static final class RecordingLookup implements EntityLookup {
        private final List<String> reads = new ArrayList<>();
        private final Map<String, List<Object>> byTerm = new LinkedHashMap<>();
        private final Map<Long, Object> byId = new LinkedHashMap<>();

        RecordingLookup on(String term, Object... found) {
            byTerm.put(term, List.of(found));
            return this;
        }

        RecordingLookup onId(long id, Object found) {
            byId.put(id, found);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> search(Class<T> entityClass, Collection<String> searchFields, String term, int limit) {
            reads.add("search " + entityClass.getSimpleName() + " term={" + term + "} limit=" + limit
                + " fields=" + searchFields);
            return (List<T>) byTerm.getOrDefault(term, List.of());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> findSelectedById(Class<T> entityClass, Object id) {
            reads.add("byId " + entityClass.getSimpleName() + " id=" + id);
            return Optional.ofNullable((T) byId.get(((Number) id).longValue()));
        }
    }
}
