package org.ipro.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilterTreeTextTest {

    @Test
    void rendersConditionsAndNestedOrGroup() {
        FilterFieldResolver resolver = new FilterFieldResolver() {
            @Override
            public List<ResolvedFilterField> fields() {
                return List.of(
                        new ResolvedFilterField("name", "Наименование", String.class, FilterDataType.TEXT, true),
                        new ResolvedFilterField("typeNom", "Тип", String.class, FilterDataType.TEXT, true));
            }

            @Override
            public ResolvedFilterField resolve(String path) {
                return fields().stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
            }
        };
        FilterNode tree = FilterGroup.and(
                FilterConditionNode.of(new FilterCondition("name", FilterOperator.CONTAINS, "олт", null, FilterDataType.TEXT)),
                FilterGroup.or(
                        FilterConditionNode.of(new FilterCondition("typeNom", FilterOperator.EQ, "Узел", null, FilterDataType.TEXT)),
                        FilterConditionNode.of(new FilterCondition("typeNom", FilterOperator.EQ, "Материал", null, FilterDataType.TEXT))));

        assertThat(FilterTreeText.render(tree, resolver))
                .isEqualTo("Наименование Содержит 'олт' И (Тип Равно 'Узел' ИЛИ Тип Равно 'Материал')");
    }

    @Test
    void rendersNullAsEmptyAndInListAndBetween() {
        assertThat(FilterTreeText.render(null, null)).isEmpty();

        FilterNode inList = FilterConditionNode.of(new FilterCondition(
                "typeNom", FilterOperator.IN, "Узел,Материал", null, FilterDataType.TEXT));
        assertThat(FilterTreeText.render(inList, null))
                .isEqualTo("typeNom В списке ('Узел', 'Материал')");

        FilterNode between = FilterConditionNode.of(new FilterCondition(
                "amount", FilterOperator.BETWEEN, "1", "5", FilterDataType.NUMBER));
        assertThat(FilterTreeText.render(between, null))
                .isEqualTo("amount Между '1' и '5'");

        FilterNode empty = FilterConditionNode.of(new FilterCondition(
                "archived", FilterOperator.IS_NULL, null, null, FilterDataType.BOOLEAN));
        assertThat(FilterTreeText.render(empty, null)).isEqualTo("archived Пусто");
    }
}
