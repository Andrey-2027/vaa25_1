package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.catalog;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.category;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.product;

/** Тесты вкладки «Таблицы и поля»: авто-JOIN через ассоциации, дубликаты, каскадное удаление. */
class TablesAndFieldsTabTest {

    @Test
    void associationNodeAddsEntityReferenceFieldWithoutJoin() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        AtomicInteger changes = new AtomicInteger();
        var tab = new TablesAndFieldsTab(draft, changes::incrementAndGet);
        tab.refreshFromDraft();

        List<ConstructorTreeNode> roots = tab.databaseRoots();
        assertThat(roots).hasSize(2);

        tab.addNode(roots.get(0)); // Product
        assertThat(draft.hasRoot()).isTrue();
        assertThat(draft.tables()).hasSize(1);

        var associationNode = roots.get(0).children().stream()
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

    @Test
    void fieldNodeThroughAssociationGetsPathWithJoin() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = tab.databaseRoots().get(0);
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

        var productNode = tab.databaseRoots().get(0);
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

        var productNode = tab.databaseRoots().get(0);
        tab.addAllFields(productNode);

        assertThat(draft.selections()).hasSize(product().fields().size());
    }

    @Test
    void selectedTableIsRemovedWithCascade() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = tab.databaseRoots().get(0);
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
        var productNode = tab.databaseRoots().get(0);
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

        var productNode = tab.databaseRoots().get(0);
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
        tab.addNode(tab.databaseRoots().get(0)); // корень Product

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
        tab.addNode(tab.databaseRoots().get(0));

        tab.clearAllTables();

        assertThat(draft.hasRoot()).isFalse();
        assertThat(draft.definition()).isNull();
    }
}
