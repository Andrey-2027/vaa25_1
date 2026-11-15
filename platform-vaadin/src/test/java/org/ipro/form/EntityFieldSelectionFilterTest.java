package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.textfield.TextField;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.ipro.data.SelectionFilters;
import org.ipro.metadata.HasDisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Б1: отбор, заданный через {@code setSelectionFilter}, действует и в автокомплите, и в
 * диалоге выбора — правило одно, ограничение не обходится вводом с клавиатуры.
 */
class EntityFieldSelectionFilterTest {

    private record Item(String name) implements HasDisplayName {
        @Override
        public String getDisplayName() {
            return name;
        }
    }

    private static TextField textFieldOf(Component root) {
        return findText(root).orElseThrow();
    }

    private static Optional<TextField> findText(Component component) {
        if (component instanceof TextField textField) {
            return Optional.of(textField);
        }
        return component.getChildren().map(EntityFieldSelectionFilterTest::findText)
            .flatMap(Optional::stream).findFirst();
    }

    @Test
    void autocompleteReceivesTheFilterSetForTheField() {
        List<Map<String, Object>> seenByAutocomplete = new ArrayList<>();
        FilterableSearchFunction<Item> search = (term, filters) -> {
            seenByAutocomplete.add(filters);
            return List.of(new Item("Болт"));
        };
        EntityField<Item> field = new EntityField<>("Номенклатура", search);
        Map<String, Object> filters = Map.of("typeNom", "Материал");
        field.setSelectionFilter(filters, (onSelect, applied) -> null);

        textFieldOf(field).setValue("бол");

        assertThat(seenByAutocomplete).containsExactly(filters);
    }

    @Test
    void nothingFoundIsSaidInWords() {
        EntityField<Item> field = new EntityField<>("Единица", term -> List.of());

        textFieldOf(field).setValue("zzz");

        assertThat(field.isNotFoundHintShown()).isTrue();
        assertThat(field.notFoundHintText()).isEqualTo("Ничего не найдено");
    }

    @Test
    void nothingFoundUnderAFilterSaysSearchIsLimited() {
        EntityField<Item> field = new EntityField<>("Номенклатура",
            (FilterableSearchFunction<Item>) (term, filters) -> List.of());
        field.setSelectionFilter(Map.of("typeNom", "Материал"), (onSelect, applied) -> null);

        textFieldOf(field).setValue("zzz");

        assertThat(field.isNotFoundHintShown()).isTrue();
        assertThat(field.notFoundHintText())
            .isEqualTo("Ничего не найдено Поиск ограничен отбором формы");
    }

    @Test
    void hintGoesAwayWhenSomethingIsFoundOrFieldIsCleared() {
        List<Item> result = new ArrayList<>();
        EntityField<Item> field = new EntityField<>("Единица", term -> result);
        TextField text = textFieldOf(field);

        text.setValue("zzz");
        assertThat(field.isNotFoundHintShown()).isTrue();

        result.add(new Item("Килограмм"));
        text.setValue("кило");
        assertThat(field.isNotFoundHintShown()).isFalse();

        result.clear();
        text.setValue("zzzz");
        assertThat(field.isNotFoundHintShown()).isTrue();
        field.clear();
        assertThat(field.isNotFoundHintShown()).isFalse();
    }

    @Test
    void fieldWithoutFilterSearchesTheOldWay() {
        List<String> terms = new ArrayList<>();
        EntityField<Item> field = new EntityField<>("Единица", term -> {
            terms.add(term);
            return List.of();
        });

        textFieldOf(field).setValue("кг");

        assertThat(terms).containsExactly("кг");
    }

    @Test
    void filterableFunctionWithoutFilterGetsEmptyMap() {
        List<Map<String, Object>> seen = new ArrayList<>();
        EntityField<Item> field = new EntityField<>("Единица",
            (FilterableSearchFunction<Item>) (term, filters) -> {
                seen.add(filters);
                return List.of();
            });

        textFieldOf(field).setValue("кг");

        assertThat(seen).containsExactly(Map.of());
    }

    @Test
    void searchFunctionThatCannotApplyTheFilterIsRejectedNotIgnored() {
        EntityField<Item> field = new EntityField<>("Номенклатура", term -> List.of());

        assertThatThrownBy(() -> field.setSelectionFilter(Map.of("typeNom", "Материал"),
            (onSelect, applied) -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("typeNom");
    }

    @Test
    void emptyFilterDoesNotRequireAFilterableFunction() {
        EntityField<Item> field = new EntityField<>("Номенклатура", term -> List.of());

        field.setSelectionFilter(Map.of(), (onSelect, applied) -> null);
        Map<String, Object> noise = new LinkedHashMap<>();
        noise.put("", "x");
        noise.put("typeNom", null);
        field.setSelectionFilter(noise, (onSelect, applied) -> null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dialogAndAutocompleteBuildTheFilterWithOneRule() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("typeNom", "Материал");
        filters.put("kind", List.of("A", "B"));

        Specification<Object> dialog = SelectionFormAssembler.specificationFor(filters);
        Specification<Object> core = SelectionFilters.specificationFor(filters);

        for (Specification<Object> specification : List.of(dialog, core)) {
            Root<Object> root = mock(Root.class);
            CriteriaQuery<?> query = mock(CriteriaQuery.class);
            CriteriaBuilder cb = mock(CriteriaBuilder.class);
            Path<Object> typePath = mock(Path.class);
            Path<Object> kindPath = mock(Path.class);
            CriteriaBuilder.In<Object> in = mock(CriteriaBuilder.In.class);
            when(root.get("typeNom")).thenReturn(typePath);
            when(root.get("kind")).thenReturn(kindPath);
            when(cb.in(kindPath)).thenReturn(in);
            when(in.value(any(java.util.Collection.class))).thenReturn(in);

            specification.toPredicate(root, query, cb);

            verify(cb).equal(typePath, "Материал");
            verify(cb).in(kindPath);
            verify(in).value(List.of("A", "B"));
        }
        assertThat(SelectionFormAssembler.<Object>specificationFor(Map.of())).isNull();
        assertThat(SelectionFilters.<Object>specificationFor(null)).isNull();
    }
}
