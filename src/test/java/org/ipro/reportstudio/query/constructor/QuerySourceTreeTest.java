package org.ipro.reportstudio.query.constructor;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.metadata.MetadataResolver;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.query.editor.QueryMetadataNode;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.*;

class QuerySourceTreeTest {
    static class Lines { }
    static class RegisterRows { }
    @Test void editorAndConstructorUseTheSameGroupsSearchSectionsAndReferences() {
        var products = new QueryBuilderMetadataCatalog.Entity("Product", Product.class, product().fields(), product().associations(), "Товары", "Справочник", null);
        var categories = new QueryBuilderMetadataCatalog.Entity("Category", Category.class, category().fields(), List.of(), "Категории", "Справочник", null);
        var sections = new QueryBuilderMetadataCatalog.Entity("Lines", Lines.class, List.of(new QueryBuilderMetadataCatalog.Field("quantity", "Количество", Integer.class, false)), List.of(), "Строки", "Прочее", Product.class);
        var rows = new QueryBuilderMetadataCatalog.Entity("RegisterRows", RegisterRows.class, List.of(field("period", java.time.LocalDateTime.class)), List.of(), "Регистр", "Регистр", null);
        var catalog = catalog(products, categories, sections, rows);
        var editor = new QueryMetadataCatalogService(mock(EntityManagerFactory.class), new MetadataResolver(), mock(RlsReadGate.class), () -> "tester");
        editor.useSharedCatalog(catalog, null);
        for (String search : List.of("", "Товары", "Product", "Количество", "quantity", "Категория", "missing")) {
            assertThat(editor.roots(search).stream().map(QuerySourceTreeTest::editorSignature).toList())
                .isEqualTo(CatalogTreeModel.sourceRoots(catalog.roots(), null, search).stream().map(QuerySourceTreeTest::constructorSignature).toList());
        }
        var root = CatalogTreeModel.sourceRoots(catalog.roots(), null, "quantity");
        assertThat(root).singleElement().satisfies(group -> {
            assertThat(group.caption()).isEqualTo("Справочники");
            assertThat(group.children()).singleElement().satisfies(owner -> {
                assertThat(owner.entity().javaType()).isEqualTo(Product.class);
                assertThat(owner.children()).singleElement().satisfies(section -> assertThat(section.entity().javaType()).isEqualTo(Lines.class));
            });
        });
        assertThat(CatalogTreeModel.sourceRoots(catalog.roots(), null, "RegisterRows").getFirst().children().getFirst().children())
            .singleElement().satisfies(node -> assertThat(node.caption()).isEqualTo("Записи регистра"));
    }
    private static String constructorSignature(ConstructorTreeNode node) {
        return CatalogTreeModel.label(node) + "|" + CatalogTreeModel.name(node) + "|" + CatalogTreeModel.category(node)
            + "|" + CatalogTreeModel.dataType(node) + node.children().stream().map(QuerySourceTreeTest::constructorSignature).toList();
    }
    private static String editorSignature(QueryMetadataNode node) {
        return node.caption() + "|" + node.token() + "|" + node.category() + "|" + node.javaType()
            + node.children().stream().map(QuerySourceTreeTest::editorSignature).toList();
    }
}
