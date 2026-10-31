package org.ipro.query.ui.list;

import org.ipro.query.*;
import org.ipro.query.ui.fixture.DemoDocument;
import org.ipro.query.ui.fixture.DemoDocumentItem;
import org.ipro.query.ui.fixture.DemoProduct;
import org.ipro.query.ui.constructor.QueryConstructorDraft;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListConstructorTest {
    private QueryBuilderMetadataCatalog catalog() {
        var catalog = mock(QueryBuilderMetadataCatalog.class);
        var id = new QueryBuilderMetadataCatalog.Field("id", "Id", Long.class, true);
        var parent = new QueryBuilderMetadataCatalog.Entity("DemoDocument", DemoDocument.class,
                List.of(id), List.of());
        var row = new QueryBuilderMetadataCatalog.Entity("DemoDocumentItem", DemoDocumentItem.class,
                List.of(id), List.of(new QueryBuilderMetadataCatalog.Association("document", "Document", QueryBuilderMetadataCatalog.JoinType.TO_ONE, DemoDocument.class),
                new QueryBuilderMetadataCatalog.Association("nomenclature", "Product", QueryBuilderMetadataCatalog.JoinType.TO_ONE, DemoProduct.class)));
        var product = new QueryBuilderMetadataCatalog.Entity("DemoProduct", DemoProduct.class,
                List.of(id, new QueryBuilderMetadataCatalog.Field("name", "Name", String.class, false)), List.of());
        when(catalog.roots()).thenReturn(List.of(parent, row, product));
        for (var entity : List.of(parent, row, product)) when(catalog.root(entity.entityName())).thenReturn(entity);
        return catalog;
    }
    private ConstructorList source(QueryBuilderMetadataCatalog catalog) {
        return new ListCteGenerator(catalog).generate("DemoDocumentItem", "nomenclature.name", "'; ", false,
                VisualQueryOrder.Direction.DESC, Set.of("list1"));
    }
    private VisualQueryDefinition main() {
        return new VisualQueryDefinition("DemoDocument", "d", List.of(new VisualQueryDefinition.SelectField("d.id", "id")));
    }

    @Test
    void generatesIdentityJoinsUniqueNameAndTypedFields() {
        var catalog = catalog(); var source = source(catalog);
        assertThat(source.name()).isEqualTo("list2");
        var compiled = VisualQueryCompiler.compile(source.stages().getFirst().definition(), catalog);
        assertThat(compiled.jpql()).contains("join DemoDocument p on listRow.document.id = p.id",
                "join DemoProduct listValue1 on listRow.nomenclature.id = listValue1.id",
                "listagg(listValue1.name, '''; ') within group (order by listValue1.name desc)", "group by p.id");
        assertThat(compiled.bindings()).isEmpty();
        assertThat(source.entity().fields().getFirst().technical()).isTrue();
        assertThat(source.entity().fields().getFirst().javaType()).isEqualTo(Long.class);
        assertThat(source.entity().fields().getLast().javaType()).isEqualTo(String.class);
        assertThatThrownBy(() -> new ListCteGenerator(catalog).generateNamed("list1", "DemoDocumentItem", "id", null, true, null))
                .hasMessageContaining("текстовым");
    }

    @Test
    void intentionsRoundTripAndMismatchRemainsOrdinaryStage() {
        var catalog = catalog(); var source = source(catalog);
        var codec = new VisualQueryPackageJsonCodec();
        var pack = new VisualQueryPackage(source.stages(), main());
        String json = ListConstructorSources.attach(codec.write(pack), List.of(source));
        assertThat(ListConstructorSources.read(json, catalog)).containsExactly(source);
        var changed = new ListCteGenerator(catalog).generateNamed(source.name(), source.sectionEntity(), source.valuePath(), ":", true, null);
        assertThat(ListConstructorSources.attach(codec.write(new VisualQueryPackage(changed.stages(), main())), List.of(source)))
                .doesNotContain("constructorLists");
        String tampered = json.replace("\"constructorLists\":[" + source.settingsJson(),
                "\"constructorLists\":[" + source.settingsJson().replace("\"separator\":\"'; \"", "\"separator\":\":\""));
        assertThat(ListConstructorSources.read(tampered, catalog)).isEmpty();
        assertThat(codec.read(tampered).ctes()).hasSize(1);
    }

    @Test
    void draftAddsUpdatesHidesAndPrunesList() {
        var catalog = catalog(); var source = source(catalog);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(main());
        String alias = draft.addConstructorList(source, "d", "names");
        assertThat(draft.visibleStageNames()).doesNotContain(source.name());
        assertThat(draft.availableCteEntities()).contains(source.entity());
        assertThat(draft.compile().ok()).isTrue();
        assertThat(draft.compile().jpql()).contains("left join list2 " + alias + " on " + alias + ".key = d.id", alias + ".value as names");
        var replacement = new ListCteGenerator(catalog).generateNamed(source.name(), source.sectionEntity(), source.valuePath(), ":", true, null);
        draft.updateConstructorList(source.name(), replacement);
        assertThat(draft.compile().jpql()).contains("listagg(distinct listValue1.name, ':')");
        draft.removeTable(alias); draft.pruneUnusedLists();
        assertThat(draft.lists()).isEmpty();
        assertThat(draft.stageNames()).doesNotContain(source.name());
        draft.addConstructorList(source, "d", "names"); draft.clearPackageStages();
        assertThat(draft.lists()).isEmpty();
    }

    @Test
    void draftRenamesPreserveAllAggregateOptions() {
        var catalog = catalog(); var source = source(catalog);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(source.stages().getFirst().definition());
        draft.renameAlias("p", "owner");
        assertThat(draft.definition().aggregates().getFirst().path()).isEqualTo("listValue1.name");
        draft.renameAlias("listValue1", "n");
        var aggregate = draft.definition().aggregates().getFirst();
        assertThat(aggregate.path()).isEqualTo("n.name"); assertThat(aggregate.orderPath()).isEqualTo("n.name");
        draft.renameAggregate(aggregate, "renamed");
        aggregate = draft.definition().aggregates().getFirst();
        draft.replaceAggregateFunction(aggregate, "MAX");
        aggregate = draft.definition().aggregates().getFirst();
        assertThat(aggregate.separator()).isEqualTo(source.separator()); assertThat(aggregate.distinct()).isFalse();
        assertThat(aggregate.direction()).isEqualTo(VisualQueryOrder.Direction.DESC);
        assertThat(aggregate.resultName()).isEqualTo("renamed");
    }

    @Test
    void constructorReopensAndSavesVerifiedIntentions() {
        var catalog = catalog(); var source = source(catalog);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(main());
        draft.addConstructorList(source, "d", "names");
        var pack = draft.packageDefinition();
        String json = ListConstructorSources.attach(new VisualQueryPackageJsonCodec().write(pack), draft.lists());
        var constructor = new org.ipro.query.ui.constructor.JpqlQueryConstructor(catalog, List.of());
        constructor.setPackage(pack); constructor.setListSupport(json);
        assertThat(ListConstructorSources.read(constructor.visualQueryJson(), catalog)).containsExactly(source);
        assertThat(constructor.compileError()).isNull();
    }

    @Test
    void queryFromTableSectionResolvesJoinedAndTraversedTextColumns() {
        var catalog = catalog();
        for (boolean independent : List.of(true, false)) {
            var query = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION,
                    "DemoDocumentItem", "i", List.of(
                    new VisualQueryDefinition.SelectField("i.document.id", "key"),
                    new VisualQueryDefinition.SelectField("n.name", "label"),
                    new VisualQueryDefinition.SelectField("i.nomenclature.name", "traversed")),
                    List.of(independent
                            ? new VisualQueryDefinition.Join(null, "DemoProduct", "n", VisualQueryDefinition.JoinKind.INNER,
                            new JoinCondition.Predicate("i.nomenclature.id", JoinCondition.Operator.EQ, "n.id"))
                            : new VisualQueryDefinition.Join("i", "nomenclature", "n", VisualQueryDefinition.JoinKind.INNER, null)),
                    List.of(), List.of(), null);
            var fields = VisualQueryPackage.VirtualEntity.from("input", query, catalog).fields();
            assertThat(fields).extracting(QueryBuilderMetadataCatalog.Field::javaType)
                    .containsExactly(Long.class, String.class, String.class);
            var list = new ListCteGenerator(catalog).generate(new VisualQueryPackage(List.of(), query),
                    "key", "label", ", ", true, null, Set.of());
            assertThat(list.entity().fields().getLast().javaType()).isEqualTo(String.class);
        }
    }

    @Test
    void rejectsSelfJoinWithoutChangingDraft() {
        var catalog = catalog();
        var source = source(catalog);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(main());
        String alias = draft.addConstructorList(source, "d", "names");
        var before = draft.packageDefinition();
        assertThatThrownBy(() -> draft.updateConstructorList(source.name(), source, alias + ".key"))
                .hasMessageContaining("самим собой");
        assertThat(draft.packageDefinition()).isEqualTo(before);
    }

    @Test
    void removingProjectionKeepsListUsedByAnotherJoinAndExplicitRemovalCascades() {
        var catalog = catalog();
        var first = source(catalog);
        var second = new ListCteGenerator(catalog).generateNamed("list3", first.sectionEntity(), first.valuePath(), ", ", true, null);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(main());
        String alias = draft.addConstructorList(first, "d", "firstNames");
        draft.addConstructorListByField(second, alias + ".key", "secondNames");
        draft.removeSelection(draft.selections().stream().filter(f -> f.resultName().equals("firstNames")).findFirst().orElseThrow());
        draft.pruneUnusedLists();
        assertThat(draft.lists()).hasSize(2);
        assertThat(draft.compile().ok()).isTrue();
        assertThat(draft.compile().jpql()).contains(".key = " + alias + ".key");
        draft.removeTable(alias); draft.pruneUnusedLists();
        assertThat(draft.lists()).isEmpty();
        assertThat(draft.compile().ok()).isTrue();
    }

    @Test
    void arbitraryQueryUsesSelectedStringKeyAndExplicitJoinField() {
        var catalog = catalog();
        var query = new VisualQueryDefinition("DemoProduct", "n", List.of(
                new VisualQueryDefinition.SelectField("n.name", "category"),
                new VisualQueryDefinition.SelectField("n.name", "label")));
        var input = new VisualQueryPackage(List.of(), query);
        var generator = new ListCteGenerator(catalog);
        var source = generator.generate(input, "category", "label", "; ", true, null, Set.of());
        assertThat(source.entity().fields().getFirst().javaType()).isEqualTo(String.class);
        var draft = new QueryConstructorDraft(); draft.setCatalog(catalog); draft.load(query);
        String alias = draft.addConstructorListByField(source, "n.name", "names");
        assertThat(draft.compile().ok()).isTrue();
        assertThat(draft.compile().jpql()).contains(alias + ".key = n.name", "group by listRows.category");
        assertThat(draft.availableCteEntities()).extracting(QueryBuilderMetadataCatalog.Entity::entityName).containsExactly(source.name());
        String json = ListConstructorSources.attach(new VisualQueryPackageJsonCodec().write(draft.packageDefinition()), draft.lists());
        assertThat(ListConstructorSources.read(json, catalog)).containsExactly(source);
        var stagedMain = new VisualQueryDefinition("inputRows", "r", List.of(
                new VisualQueryDefinition.SelectField("r.category", "category"),
                new VisualQueryDefinition.SelectField("r.label", "label")));
        var stagedInput = new VisualQueryPackage(List.of(new VisualQueryPackage.Cte("inputRows", query)), stagedMain);
        var replacement = generator.generateNamed(source.name(), stagedInput, "category", "label", ":", false, null);
        draft.updateConstructorList(source.name(), replacement);
        assertThat(draft.compile().ok()).isTrue();
        assertThat(draft.compile().jpql()).contains("inputRows as (", "listagg(listRows.label, ':')");
        draft.removeTable(alias); draft.pruneUnusedLists();
        assertThat(draft.lists()).isEmpty();
        assertThat(draft.stageNames()).doesNotContain(source.name(), source.name() + "_rows");
        var wrong = new QueryConstructorDraft(); wrong.setCatalog(catalog); wrong.load(main());
        assertThatThrownBy(() -> wrong.addConstructorListByField(source, "d.id", "names")).hasMessageContaining("Несовместимые типы");
        assertThat(wrong.lists()).isEmpty();
    }
}
