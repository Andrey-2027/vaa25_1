package org.ipro.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisualQueryFilterResolverTest {
    @Test
    void technicalIdIsAvailableOnlyOnRealSourcesAndAllowedAliases() {
        var real = new QueryBuilderMetadataCatalog.Entity("Item", String.class, List.of(), List.of());
        var virtual = new QueryBuilderMetadataCatalog.Entity("latest", Object.class,
            List.of(new QueryBuilderMetadataCatalog.Field("itemId", "Item", Long.class, false)), List.of());
        var resolver = new VisualQueryFilterResolver(List.of(
            new VisualQueryFilterResolver.TableAlias("i", real), new VisualQueryFilterResolver.TableAlias("l", virtual)));
        assertThat(resolver.resolve("i.id").javaType()).isEqualTo(Long.class);
        assertThatThrownBy(() -> resolver.resolve("other.id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("l.id")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesRootAndJoinedFields() {
        var root = new QueryBuilderMetadataCatalog.Entity("Product", Object.class,
                List.of(new QueryBuilderMetadataCatalog.Field("code", "Код", String.class, false)),
                List.of(new QueryBuilderMetadataCatalog.Association("journal", "Журнал", QueryBuilderMetadataCatalog.JoinType.TO_ONE, String.class)));
        var target = new QueryBuilderMetadataCatalog.Entity("Journal", String.class,
                List.of(new QueryBuilderMetadataCatalog.Field("name", "Название", String.class, false)), List.of());
        var catalog = mock(QueryBuilderMetadataCatalog.class);
        when(catalog.root("Product")).thenReturn(root);
        when(catalog.roots()).thenReturn(List.of(root, target));
        var definition = new VisualQueryDefinition(1, "Product", "q",
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of(new VisualQueryDefinition.Join("q.journal", "j", VisualQueryDefinition.JoinKind.LEFT)),
                List.of(), List.of(), null);
        var resolver = new VisualQueryFilterResolver(definition, catalog);
        assertThat(resolver.fields()).extracting("path").contains("q.code", "j.name");
        assertThatThrownBy(() -> resolver.resolve("x.bad")).isInstanceOf(IllegalArgumentException.class);
    }
}
