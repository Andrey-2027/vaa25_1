package org.ipro.query.ui.constructor;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.ipro.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.query.ui.constructor.QueryConstructorDraftTest.catalog;
import static org.ipro.query.ui.constructor.QueryConstructorDraftTest.category;
import static org.ipro.query.ui.constructor.QueryConstructorDraftTest.product;

/** Тесты вкладки «Таблицы и поля»: авто-JOIN через ассоциации, дубликаты, каскадное удаление. */
class TablesAndFieldsTabTest {

    private static List<ConstructorTreeNode> sourceEntities(TablesAndFieldsTab tab) {
        return tab.databaseRoots().stream().flatMap(group -> group.children().stream()).toList();
    }

    @Test
    void associationNodeAddsEntityReferenceFieldWithoutJoin() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        AtomicInteger changes = new AtomicInteger();
        var tab = new TablesAndFieldsTab(draft, changes::incrementAndGet);
        tab.refreshFromDraft();

        List<ConstructorTreeNode> roots = sourceEntities(tab);
        assertThat(roots).hasSize(2);

        var productRoot = roots.stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        tab.addNode(productRoot); // Product
        assertThat(draft.hasRoot()).isTrue();
        assertThat(draft.tables()).hasSize(1);

        var associationNode = productRoot.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.ASSOCIATION)
                .findFirst().orElseThrow();
        tab.addNode(associationNode);

        // Двойной клик по ассоциации берёт сущностное поле-ссылку (как в 1С),
        // JOIN по нему не создаётся.
        assertThat(draft.selections()).singleElement()
                .satisfies(field -> assertThat(field.path()).isEqualTo("product.category"));
        assertThat(draft.joins()).isEmpty();
        assertThat(changes.get()).isGreaterThanOrEqualTo(2);
    }

    @Test void addsEntityAndAssociationPresentationsWithoutCreatingJoins() {
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog(product(), category()));
        var tab = new TablesAndFieldsTab(draft, () -> { }); tab.refreshFromDraft();
        var root = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals("Product")).findFirst().orElseThrow();
        tab.addPresentation(root);
        tab.addPresentation(root.children().stream().filter(node -> node.kind() == ConstructorTreeNode.Kind.ASSOCIATION).findFirst().orElseThrow());
        assertThat(draft.expressions()).hasSize(2);
        assertThat(draft.joins()).isEmpty();
        assertThat(draft.compile().ok()).as(draft.compile().error()).isTrue();
        assertThat(draft.compile().fields()).allSatisfy(field -> assertThat(field.javaType()).isEqualTo(String.class));
    }

    @Test
    void fieldNodeThroughAssociationGetsPathWithJoin() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        var associationNode = productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.ASSOCIATION)
                .findFirst().orElseThrow();
        var fieldNode = associationNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .filter(child -> child.field().name().equals("name"))
                .findFirst().orElseThrow();

        tab.addNode(fieldNode);

        assertThat(draft.joins()).singleElement()
                .satisfies(join -> assertThat(join.sourcePath()).isEqualTo("category"));
        assertThat(draft.selections()).singleElement()
                .satisfies(field -> assertThat(field.path()).isEqualTo("category.name"));
    }

    @Test
    void duplicateFieldSelectionGetsAutoSuffix() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        var codeNode = productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .filter(child -> child.field().name().equals("code"))
                .findFirst().orElseThrow();

        tab.addNode(codeNode);
        tab.addNode(codeNode);

        assertThat(draft.selections()).extracting("resultName").containsExactly("code", "code2");
    }

    @Test
    void addAllFieldsOfEntityAddsEveryDirectField() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        tab.addAllFields(productNode);

        assertThat(draft.selections()).hasSize(product().fields().size());
    }

    @Test
    void selectedTableIsRemovedWithCascade() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        var associationNode = productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.ASSOCIATION)
                .findFirst().orElseThrow();
        var fieldNode = associationNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .findFirst().orElseThrow();
        tab.addNode(fieldNode); // root + join + поле category.*
        tab.refreshFromDraft();

        var tableNodes = tab.tableRoots();
        assertThat(tableNodes).hasSize(2);
        tab.tablesTree().asSingleSelect().setValue(
                tableNodes.stream().filter(node -> node.tableAlias() != null
                        && node.tableAlias().equals("category")).findFirst().orElseThrow());
        tab.removeSelectedTable();

        assertThat(draft.joins()).isEmpty();
        assertThat(draft.selections()).isEmpty();
        assertThat(draft.hasRoot()).isTrue();
    }

    @Test
    void fieldsGridShowsSelections() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();
        var productNode = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        tab.addNode(productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .findFirst().orElseThrow());
        tab.refreshFromDraft();

        assertThat(tab.fieldsGrid().getListDataView().getItems().toList())
                .singleElement()
                .satisfies(field -> assertThat(field.path()).isEqualTo("product.code"));
    }

    @Test
    void recordIdNodeSelectableInTree() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow();
        var idNode = productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .filter(child -> child.field().name().equals("id"))
                .findFirst().orElseThrow();
        assertThat(idNode.caption()).isEqualTo("Идентификатор записи (id)");

        tab.addNode(idNode);
        assertThat(draft.selections()).singleElement()
                .satisfies(field -> assertThat(field.path()).isEqualTo("product.id"));

        // и id сущности через ассоциацию (id связанной записи)
        var associationNode = productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.ASSOCIATION)
                .findFirst().orElseThrow();
        var assocIdNode = associationNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .filter(child -> child.field().name().equals("id"))
                .findFirst().orElseThrow();
        tab.addNode(assocIdNode);
        assertThat(draft.selections()).extracting("path").contains("category.id");
        assertThat(draft.joins()).singleElement()
                .satisfies(join -> assertThat(join.sourcePath()).isEqualTo("category"));
    }

    @Test
    void computedFieldEditorAddsToDraftAndGrid() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        AtomicInteger changes = new AtomicInteger();
        var tab = new TablesAndFieldsTab(draft, changes::incrementAndGet);
        tab.refreshFromDraft();
        tab.addNode(sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow()); // корень Product

        var dialog = tab.computedFieldEditorFor(null);
        dialog.expressionField().setValue("product.amount * 2");
        assertThat(dialog.save()).isTrue();

        assertThat(draft.expressions()).singleElement()
                .satisfies(expr -> assertThat(expr.resultName()).isEqualTo("field"));
        assertThat(tab.expressionsGrid().getListDataView().getItems().toList()).hasSize(1);
        assertThat(changes.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void clearAllTablesEmptiesDraft() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();
        tab.addNode(sourceEntities(tab).stream().filter(node -> node.entity().entityName().equals(product().entityName())).findFirst().orElseThrow());

        tab.clearAllTables();

        assertThat(draft.hasRoot()).isFalse();
        assertThat(draft.definition()).isNull();
    }
}
