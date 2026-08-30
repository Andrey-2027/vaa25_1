package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisualQueryFilterResolverTest {
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
