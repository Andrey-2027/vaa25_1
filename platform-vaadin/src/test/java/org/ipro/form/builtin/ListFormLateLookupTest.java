package org.ipro.form.builtin;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityLookup;
import org.ipro.crud.LookupService;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Регрессия ревью D3.5 (P1): resolver визуального фильтра создаётся в конструкторе
 * {@link ListForm}, когда {@code lookupService} ещё не установлен — координатор подключает
 * его вызовом {@link ListForm#setLookupService} позже.
 *
 * <p>Что было: обе опционные функции вычислялись в конструкторе один раз. При null-сервисе
 * в {@code FilterGrid} навсегда уходила функция {@code field -> List.of()}, и последующая
 * установка сервиса ничего не меняла — выбор значений ссылочного поля в «+ Условия» оставался
 * пустым при живом lookup.</p>
 *
 * <p>Фикс: обе функции ({@code optionsForTree} и {@code optionsForPicker}) обращаются к
 * {@code filterLookupOptions()} в момент вызова. {@code LookupFilterFieldResolver} хранит
 * функцию и вызывает её на каждое {@code valueOptions(field)}, поэтому поздняя установка
 * доходит до потребителей. Оба резолвера (picker в гриде и tree в компиляторе адаптера)
 * построены на одном ленивом механизме — здесь доказывается picker-путь, как единственный
 * наблюдаемый без драйва JPA-компиляции.</p>
 *
 * <p>Фикстура повторяет продовый порядок — <b>constructor → setLookupService</b>: именно тот,
 * при котором старый код фиксировал пустой список. Резолвер перехватывается переопределением
 * {@code enableVisualFilter} на анонимном {@code FilterGrid} (публичного getter'а у грида нет)
 * и вызывается так же, как это делает библиотека.</p>
 */
class ListFormLateLookupTest {

    @Test
    void beforeTheServiceIsSetPickerOptionsAreEmptyWithoutFailing() {
        AtomicReference<org.ipro.filtergrid.filter.FilterFieldResolver> captured = new AtomicReference<>();
        ListForm<TestEntity, Long> form = newForm(captured);

        assertThat(captured.get()).as("конструктор обязан зарегистрировать resolver в гриде").isNotNull();
        assertThat(options(captured.get()))
            .as("без lookup-сервиса picker обязан отступать к пустому списку, а не падать")
            .isEmpty();
    }

    /** Продовый порядок: constructor → setLookupService. Подсказки обязаны появиться. */
    @Test
    void lateLookupServiceReachesThePicker() {
        AtomicReference<org.ipro.filtergrid.filter.FilterFieldResolver> captured = new AtomicReference<>();
        ListForm<TestEntity, Long> form = newForm(captured);
        DictionaryItem hammer = new DictionaryItem(1L);

        form.setLookupService(lookupReturning(hammer));

        assertThat(options(captured.get()))
            .as("поздняя установка lookup-сервиса обязана дойти до picker'а: функция, зажатая"
                + " в конструкторе, навсегда фиксировала пустой список")
            .containsExactly(hammer);
    }

    /** После установки сервиса вызов живой: новый ответ lookup виден на следующем запросе. */
    @Test
    void pickerAsksTheServiceOnEveryCall() {
        AtomicReference<org.ipro.filtergrid.filter.FilterFieldResolver> captured = new AtomicReference<>();
        ListForm<TestEntity, Long> form = newForm(captured);
        DictionaryItem hammer = new DictionaryItem(1L);

        LookupService lookup = mock(LookupService.class);
        when(lookup.search(eq(DictionaryItem.class), anyCollection(), eq(""), anyInt()))
            .thenReturn(List.of());
        form.setLookupService(lookup);
        assertThat(options(captured.get())).isEmpty();

        when(lookup.search(eq(DictionaryItem.class), anyCollection(), eq(""), anyInt()))
            .thenReturn(List.of(hammer));
        assertThat(options(captured.get()))
            .as("функция обязана обращаться к сервису в момент вызова, а не к снимку конструктора")
            .containsExactly(hammer);
    }

    // === Фикстура ===

    private static ListForm<TestEntity, Long> newForm(
            AtomicReference<org.ipro.filtergrid.filter.FilterFieldResolver> captured) {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestEntity.class).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(TestEntity.class, "id")));
        FieldMetadataInfo info = mock(FieldMetadataInfo.class);
        when(info.hasLookup()).thenReturn(true);
        doReturn(DictionaryItem.class).when(info).getLookupEntity();
        when(metadata.getFieldByName("item")).thenReturn(info);

        return new ListForm<>(metadata,
            new org.ipro.filtergrid.FilterGrid<TestEntity>(TestEntity.class) {
                private final com.vaadin.flow.component.grid.Grid<TestEntity> grid =
                    new com.vaadin.flow.component.grid.Grid<>(TestEntity.class, false);
                {
                    add(grid);
                }

                @Override
                protected org.ipro.filtergrid.FilterSpecification<TestEntity> buildSpecification() {
                    return (root, query, cb) -> cb.conjunction();
                }

                @Override
                public com.vaadin.flow.component.grid.Grid<TestEntity> getGrid() {
                    return grid;
                }

                @Override
                public org.ipro.filtergrid.FilterGrid<TestEntity> enableVisualFilter(
                        org.ipro.filtergrid.filter.FilterFieldResolver resolver) {
                    captured.set(resolver);
                    return super.enableVisualFilter(resolver);
                }
            });
    }

    private static org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField field() {
        return new org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField(
            "item", "Товар", DictionaryItem.class,
            org.ipro.filtergrid.filter.FilterDataType.ENTITY_REFERENCE, true);
    }

    /** Вызов valueOptions как это делает библиотека: wildcard приводится к List<Object>. */
    private static List<Object> options(org.ipro.filtergrid.filter.FilterFieldResolver resolver) {
        List<Object> result = new java.util.ArrayList<>();
        result.addAll(resolver.valueOptions(field()));
        return result;
    }

    private static LookupService lookupReturning(DictionaryItem item) {
        LookupService lookup = mock(LookupService.class);
        when(lookup.search(eq(DictionaryItem.class), anyCollection(), eq(""), anyInt()))
            .thenReturn(List.of(item));
        return lookup;
    }

    /** Запись-справочник: публичный класс со стабильными display-именем и toString. */
    public static class DictionaryItem {
        private final Long id;

        DictionaryItem(Long id) {
            this.id = id;
        }

        public String getDisplayName() {
            return "Товар " + id;
        }

        @Override
        public String toString() {
            return "A - Товар " + id;
        }
    }

    static class TestEntity extends BaseEntity {
    }

    /** Резервная заглушка EntityLookup (не используется в матчерах — см. lookupReturning). */
    @SuppressWarnings("unused")
    private static final class StubLookup implements EntityLookup {
        private final Map<String, List<Object>> byTerm = new LinkedHashMap<>();

        StubLookup on(String term, Object... found) {
            byTerm.put(term, List.of(found));
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> search(Class<T> entityClass, Collection<String> searchFields, String term,
                                  int limit) {
            return (List<T>) byTerm.getOrDefault(term, List.of());
        }

        @Override
        public <T> Optional<T> findSelectedById(Class<T> entityClass, Object id) {
            return Optional.empty();
        }
    }
}
