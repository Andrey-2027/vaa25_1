package org.ipro.filter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaFilterConditionCompilerTest {
    private static final FilterFieldResolver RESOLVER = path -> {
        if (!"name".equals(path)) throw new IllegalArgumentException("unknown");
        return new FilterFieldResolver.ResolvedFilterField(path, "Имя", String.class,
                FilterDataType.TEXT, true);
    };

    @Test
    void emptyDefinitionCompilesToNoSpecification() {
        assertThat(JpaFilterConditionCompiler.compile(FilterDefinition.empty(), RESOLVER)).isNull();
    }

    @Test
    void resolverRejectsUnknownPathBeforeBuildingSpecification() {
        var condition = new FilterCondition("secret", FilterOperator.EQ, "x", null, FilterDataType.TEXT);
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(
                new FilterDefinition(null, List.of(condition)), RESOLVER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankValueForValueOperator() {
        var condition = new FilterCondition("name", FilterOperator.EQ, "", null, FilterDataType.TEXT);
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(
                new FilterDefinition(null, List.of(condition)), RESOLVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Укажите значение фильтра");
    }

    @Test
    void resolvesEntityReferenceValueViaResolverOptions() {
        Object unit = new Object() { @Override public String toString() { return "м2"; } };
        FilterFieldResolver resolver = entityResolver(unit);
        FilterNode tree = FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "unitOfMeasurement", FilterOperator.EQ, "м2", null, FilterDataType.ENTITY_REFERENCE)));
        assertThat(JpaFilterConditionCompiler.compile(tree, resolver)).isNotNull();

        Root<Object> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(root.get("unitOfMeasurement")).thenReturn(path);
        JpaFilterConditionCompiler.compile(tree, resolver).toPredicate(root, null, cb);
        verify(cb).equal(path, unit);
    }

    @Test
    void entityReferenceValueNotFoundIsRejected() {
        Object unit = new Object() { @Override public String toString() { return "м2"; } };
        FilterFieldResolver resolver = entityResolver(unit);
        FilterNode tree = FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "unitOfMeasurement", FilterOperator.EQ, "кг", null, FilterDataType.ENTITY_REFERENCE)));

        Root<Object> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        when(root.get("unitOfMeasurement")).thenReturn(path);
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(tree, resolver)
                .toPredicate(root, null, mock(CriteriaBuilder.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдено");
    }

    private static FilterFieldResolver entityResolver(Object... options) {
        return new FilterFieldResolver() {
            @Override
            public List<ResolvedFilterField> fields() {
                return List.of(new ResolvedFilterField("unitOfMeasurement", "Единица измерения",
                        Object.class, FilterDataType.ENTITY_REFERENCE, true));
            }

            @Override
            public ResolvedFilterField resolve(String path) {
                return fields().get(0);
            }

            @Override
            public List<?> valueOptions(ResolvedFilterField field) {
                return List.of(options);
            }
        };
    }

    @Test
    void rejectsTextOperatorForNonTextField() {
        FilterFieldResolver resolver = path -> new FilterFieldResolver.ResolvedFilterField(
                path, "Сумма", Integer.class, FilterDataType.NUMBER, true);
        var condition = new FilterCondition("amount", FilterOperator.CONTAINS, "1", null, FilterDataType.TEXT);
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(
                new FilterDefinition(null, List.of(condition)), resolver))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
