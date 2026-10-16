package org.ip.unidr.query;

import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ipro.reportstudio.query.*;
import org.ipro.filtergrid.filter.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrTableSourcesTest {
    final QueryBuilderMetadataCatalog metadata=mock(QueryBuilderMetadataCatalog.class);
    final UniDrTableSources sources=new UniDrTableSources(metadata);
    UniDrTableSourcesTest() {
        var unit=new QueryBuilderMetadataCatalog.Entity("UnitOfMeasurement",org.ip.model.UnitOfMeasurement.class,
            List.of(new QueryBuilderMetadataCatalog.Field("shortCode","Единица",String.class,false)),List.of());
        var item=new QueryBuilderMetadataCatalog.Entity("Nomenclature",org.ip.model.Nomenclature.class,
            List.of(new QueryBuilderMetadataCatalog.Field("code","Код",String.class,false),new QueryBuilderMetadataCatalog.Field("name","Наименование",String.class,false)),
            List.of(new QueryBuilderMetadataCatalog.Association("unit","Единица",QueryBuilderMetadataCatalog.JoinType.TO_ONE,org.ip.model.UnitOfMeasurement.class)));
        when(metadata.roots()).thenReturn(List.of(item,unit));when(metadata.root(anyString())).thenAnswer(call->metadata.roots().stream().filter(e->e.entityName().equals(call.getArgument(0))).findFirst().orElseThrow());
    }
    UniDrDefinition definition(List<Column> columns) {
        return new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Таблица",columns,List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(sources.select("Nomenclature"));
    }
    @Test void columnOrderDoesNotRenameProjectionAliasesAndOnlyNeededFieldsAreSelected() {
        var first=sources.project(definition(List.of(new Column("name",null),new Column("code",null))),false);
        var reversed=sources.project(definition(List.of(new Column("code",null),new Column("name",null))),false);
        assertThat(first.jpql()).isEqualTo(reversed.jpql()).contains(" as code"," as name").doesNotContain(" as id");
        assertThat(first.tableEntity()).isEqualTo("Nomenclature");assertThat(sources.plan(first).schema().fields()).extracting(org.ip.unidr.source.UniDrSourceDefinition.Field::id).contains("unit","unit_shortCode");
    }
    @Test void optionalLinksAreLeftJoinedAndFilterOnlyFieldIsNotProjected() {
        var tree=new FilterConditionNode(new FilterCondition("unit_shortCode",FilterOperator.EQ,"шт",null,FilterDataType.TEXT));
        var projected=sources.project(definition(List.of(new Column("name",null))).withFilterTree(tree),false);
        assertThat(projected.jpql()).contains("left join UnitOfMeasurement").contains("r0.unit.id").contains(" as name").doesNotContain(" as unit_shortCode");
        var queryForEditor=sources.project(definition(List.of(new Column("name",null))).withFilterTree(tree),true);
        assertThat(queryForEditor.jpql()).contains(" as unit_shortCode");
    }
    @Test void countOnlySourceStillSelectsOneRowIdentityAndDoesNotAddSqlGrouping() {
        var definition=definition(List.of()).edit(draft->{draft.mode=Mode.SUMMARY;draft.measures=List.of(new Measure(UniDrQuerySourceService.COUNT,Aggregation.COUNT_ROWS,null));});
        assertThat(sources.project(definition,false).jpql()).contains("r0.id as id").doesNotContain("group by");
    }
}
