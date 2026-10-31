package org.ipro.unidr.query;
import org.ip.unidr.model.UniDrDefinitions;

import org.ipro.query.document.*;

import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.run.UniDrRunRequest;
import org.ipro.unidr.source.*;
import org.ipro.report.data.*;
import org.ipro.query.param.*;
import org.ipro.query.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrQuerySourceTest {
    final List<QueryField> fields=List.of(QueryField.scalar("name",String.class),QueryField.scalar("qty",Integer.class));
    final UniDrQuerySource query=new UniDrQuerySource(ReportQuerySource.MANUAL,"select p.name as name,p.qty as qty from Product p",null,List.of());
    UniDrDefinition definition() {return new UniDrDefinition(1,"query",1,"Произвольный отчёт",List.of(new Column("name",null),new Column("qty",null)),
        List.of(new Measure("qty",Aggregation.SUM,null)),List.of("name"),List.of(),List.of(),List.of(new Sort("qty",Direction.DESC)),Mode.SUMMARY,true).withQuery(query);}
    @Test void querySourceCopiesParametersAndJsonRoundTripsWithoutMutableSharing() {
        var mutable=new ArrayList<UniDrQuerySource.Param>();
        mutable.add(new UniDrQuerySource.Param("minimum","minimum",ReportParamKind.SCALAR,null,ReportParamSource.FORM,false,true,"3",ReportComputedValue.NONE,0));
        var saved=new UniDrQuerySource(ReportQuerySource.MANUAL,query.jpql(),null,mutable);
        mutable.clear();
        assertThat(saved.parameters()).singleElement().satisfies(parameter->assertThat(parameter.defaultValue()).isEqualTo("3"));
        var restored=UniDrQueryDefinitions.source(UniDrQueryDefinitions.of(saved),null);
        assertThat(restored.jpql()).isEqualTo(query.jpql());assertThat(restored.parameters().getFirst().defaultValue()).isEqualTo("3");assertThat(restored.tableEntity()).isNull();
        var catalog=new UniDrSourceCatalog(List.of(),source->UniDrQuerySourceService.metadata(fields));var codec=new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(catalog));
        assertThat(codec.decode(codec.encode(definition().withQuery(restored)))).isEqualTo(definition().withQuery(restored));
        assertThat(new UniDrDefinitionValidator(UniDrDefinitions.catalog()).validate(definition())).isNotEmpty();
    }
    @Test void completeResultFiltersSortsAndNormalizesNumbersBeforeNativeGroups() {
        var service=new UniDrQuerySourceService(mock(ReportQueryAssemblyService.class));
        var slots=fields.toArray(QueryField[]::new);var data=new ReportDataset(slots,new ReportRow[]{new ReportRow(slots,new Object[]{"B",4}),new ReportRow(slots,new Object[]{"A",2}),new ReportRow(slots,new Object[]{"A",7})});
        var initial=definition();var filtered=new UniDrDefinition(1,"query",1,initial.title(),initial.columns(),initial.measures(),initial.groups(),List.of(new Filter("qty",Operator.GE,new Literal(ValueType.DECIMAL,List.of("3")))),List.of(),initial.sorts(),initial.mode(),true).withQuery(query);
        var result=service.prepare(filtered,new ReportQueryAssembler(query.jpql(),Map.of(),fields,List.of()),data,Map.of());
        assertThat(result.rows()).extracting(row->row.value("name")).containsExactly("A","B");assertThat(result.rows()).extracting(row->row.value("qty")).containsExactly(new BigDecimal("7"),new BigDecimal("4"));
        var catalog=new UniDrSourceCatalog(List.of(),source->UniDrQuerySourceService.metadata(fields));
        assertThat(new org.ipro.unidr.render.UniDrReportCompiler(catalog,new UniDrDefinitionValidator(catalog)).compile(filtered,result)).isNotNull();
    }
    @Test void computedPriceUsesTwoQueryFieldsAfterNormalizationIncludingHiddenDependencies() {
        var schema=List.of(QueryField.scalar("qty",Integer.class),QueryField.scalar("price",Double.class));
        var slots=schema.toArray(QueryField[]::new);var raw=new ReportDataset(slots,new ReportRow[]{new ReportRow(slots,new Object[]{3,2.5}),new ReportRow(slots,new Object[]{2,4.0})});
        var recipe=new UniDrDefinition(1,"query",1,"Стоимость",List.of(new Column("calc_amount",null)),List.of(new Measure("calc_amount",Aggregation.SUM,null)),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true)
            .withQuery(query).withComputedColumns(List.of(new ComputedColumn("calc_amount","Стоимость",Computation.FORMULA,"{qty} * {price}")));
        var source=UniDrQuerySourceService.metadata(schema);
        var normalized=new UniDrQuerySourceService(mock(ReportQueryAssemblyService.class)).prepare(recipe,new ReportQueryAssembler(query.jpql(),Map.of(),schema,List.of()),raw,Map.of());
        var result=UniDrComputedColumns.evaluate(recipe,source,normalized);
        assertThat(result.rows()).extracting(row->row.value("calc_amount")).containsExactly(new BigDecimal("7.5"),new BigDecimal("8.0"));
        var catalog=new UniDrSourceCatalog(List.of(),ignored->source);
        assertThat(new org.ipro.unidr.render.UniDrReportCompiler(catalog,new UniDrDefinitionValidator(catalog)).compile(recipe,normalized)).isNotNull();
    }
    @Test void entityGroupsKeepSeparateIdsWithEqualCaptionsAndNullValues() {
        var schema=List.of(QueryField.scalar("workshop",org.ip.model.Workshop.class),QueryField.scalar("qty",Long.class));
        var slots=schema.toArray(QueryField[]::new);var data=new ReportDataset(slots,new ReportRow[]{new ReportRow(slots,new Object[]{new EntityRef(2L,"Одинаково"),3L}),new ReportRow(slots,new Object[]{new EntityRef(1L,"Одинаково"),4L}),new ReportRow(slots,new Object[]{null,null})});
        var initial=definition();var recipe=new UniDrDefinition(1,"query",1,"Ссылки",List.of(new Column("workshop",null)),initial.measures(),List.of("workshop"),List.of(),List.of(),List.of(),Mode.SUMMARY,true).withQuery(query);
        var service=new UniDrQuerySourceService(mock(ReportQueryAssemblyService.class));var source=UniDrQuerySourceService.metadata(schema);
        var result=service.prepare(recipe,new ReportQueryAssembler(query.jpql(),Map.of(),schema,List.of()),data,Map.of());
        assertThat(result.rows()).extracting(row->row.value("__unidr_key_workshop")).containsExactly(null,"1","2");
        var catalog=new UniDrSourceCatalog(List.of(),ignored->source);assertThat(new org.ipro.unidr.render.UniDrReportCompiler(catalog,new UniDrDefinitionValidator(catalog)).compile(recipe,result)).isNotNull();
    }
    @Test void describeUsesUdrGuardButDoesNotResolveRuntimeParameters() {
        var guard=mock(ReportQueryGuard.class);var resolver=mock(ReportParamResolver.class);var refresher=mock(EntityParamRefresher.class);
        when(guard.guard(anyString(),anySet(),anyMap())).thenReturn(GuardResult.allowed(new Analysis(List.of(),List.of(),fields,Set.of())));
        var service=new UniDrQuerySourceService(new ReportQueryAssemblyService(guard,resolver,refresher));
        assertThat(service.describe(query).fields()).extracting(UniDrSourceDefinition.Field::id).containsExactly("name","qty",UniDrQuerySourceService.COUNT);
        verify(guard).guard(eq(query.jpql()),eq(Set.of()),eq(Map.of()));verifyNoInteractions(resolver,refresher);
        when(guard.guard(anyString(),anySet(),anyMap())).thenReturn(GuardResult.denied(List.of("Нет чтения"),List.of(),new Analysis(List.of(),List.of(),fields,Set.of())));
        assertThatThrownBy(()->service.describe(query)).isInstanceOf(org.ipro.unidr.run.UniDrRunException.class);
    }
    @Test void describeVisualQueryCompilesTheConstructorDefinitionInsteadOfStaleText() {
        var visual=new VisualQueryDefinition(1,"Product","p",List.of(new VisualQueryDefinition.SelectField("p.name","name")),List.of(),List.of(),List.of(),null);
        var spec=new UniDrQuerySource(ReportQuerySource.VISUAL,"ignored",new VisualQueryDefinitionJsonCodec().write(visual),List.of());
        var guard=mock(ReportQueryGuard.class);when(guard.guard(anyString(),anySet(),anyMap())).thenReturn(GuardResult.allowed(new Analysis(List.of(),List.of(),List.of(fields.getFirst()),Set.of())));
        var resolver=mock(ReportParamResolver.class);var assembly=new ReportQueryAssemblyService(guard,resolver,mock(EntityParamRefresher.class));
        var description=assembly.describe(UniDrQueryDefinitions.of(spec));assertThat(description.jpql()).contains("p.name as name").doesNotContain("ignored");
        assertThat(new UniDrQuerySourceService(assembly).describe(spec).fields()).extracting(UniDrSourceDefinition.Field::id).contains("name");verifyNoInteractions(resolver);
    }
    @Test void runtimeValuesAreImmutableIdsNotEntitiesAndReservedAliasesAreRejected() {
        var ids=new ArrayList<>(List.of(1L,2L));var request=new UniDrRunRequest(definition(),Map.of(),ZoneOffset.UTC,Map.of("ids",ids));ids.clear();
        assertThat(request.queryValues().get("ids")).isEqualTo(List.of(1L,2L));
        assertThatThrownBy(()->new UniDrRunRequest(definition(),Map.of(),ZoneOffset.UTC,Map.of("entity",new org.ip.model.Workshop()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->UniDrQuerySourceService.metadata(List.of(QueryField.scalar("__unidr_count",Long.class)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->UniDrQuerySourceService.metadata(List.of(fields.getFirst(),fields.getFirst()))).isInstanceOf(IllegalArgumentException.class);
    }
}
