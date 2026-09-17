package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты per-class инвалидации (Этап 5.2): сброс одного класса не трогает остальные,
 * секции чистятся и по родителю, и по строке.
 */
class MetadataInvalidateTest {

    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void invalidateSingleClassKeepsOthers() {
        EntityMetadataInfo firstA = resolver.resolve(A.class);
        EntityMetadataInfo firstB = resolver.resolve(B.class);

        resolver.invalidate(A.class);

        assertThat(resolver.resolve(A.class)).isNotSameAs(firstA);
        assertThat(resolver.resolve(B.class)).isSameAs(firstB);
    }

    @Test
    void invalidateRowDropsParentSections() {
        var first = resolver.resolveTableSections(Doc.class);
        assertThat(first).hasSize(1);

        resolver.invalidate(Row.class);

        var second = resolver.resolveTableSections(Doc.class);
        assertThat(second).isNotSameAs(first);
        assertThat(second).hasSize(1);
    }

    @Test
    void invalidateParentDropsSections() {
        resolver.resolveTableSections(Doc.class);

        resolver.invalidate(Doc.class);

        var second = resolver.resolveTableSections(Doc.class);
        assertThat(second).hasSize(1);
    }

    @EntityMetadata(listFormTitle = "А")
    static class A {
        @FieldMetadata(label = "Поле", grid = @GridColumn(order = 1))
        String field;
    }

    @EntityMetadata(listFormTitle = "Б")
    static class B {
        @FieldMetadata(label = "Поле", grid = @GridColumn(order = 1))
        String field;
    }

    @TableSections({Row.class})
    static class Doc {
    }

    @TableSectionMetadata(parentEntity = Doc.class, parentField = "parent")
    static class Row {
        Doc parent;
        @FieldMetadata(label = "Данные", grid = @GridColumn(order = 1))
        String data;
    }
}
