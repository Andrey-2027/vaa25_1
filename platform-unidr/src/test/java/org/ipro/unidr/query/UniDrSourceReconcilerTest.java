package org.ipro.unidr.query;

import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.source.UniDrSourceDefinition;
import org.ipro.unidr.source.UniDrSourceDefinition.Field;
import org.ipro.filtergrid.filter.*;
import org.ipro.query.document.ReportQuerySource;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Pure reconciliation cases prepared for the next authorized test run. */
class UniDrSourceReconcilerTest {
    @Test void totalsSelectionsMapFieldsAndCounterAndKeepCustomWhenEmpty() {
        var before=plan("qty","oldCount",null);var after=plan("amount","newCount",null);
        var original=definition(before).edit(d->{d.groups=List.of("qty");d.totalsSettings=new TotalsSettings(new MeasureSelection(SelectionMode.CUSTOM,List.of("oldCount")),
            Map.of("qty",new MeasureSelection(SelectionMode.CUSTOM,List.of("qty"))));});
        var analysis=UniDrSourceReconciler.analyze(original,before,after,List.of());var choices=analysis.automatic();choices.put("qty","amount");
        var mapped=analysis.preview(choices).definition();
        assertThat(mapped.totalsSettings().report().fieldIds()).containsExactly("newCount");
        assertThat(mapped.totalsSettings().group("amount").fieldIds()).containsExactly("amount");
        choices.put("oldCount",null);
        assertThat(analysis.preview(choices).definition().totalsSettings().report()).isEqualTo(new MeasureSelection(SelectionMode.CUSTOM,List.of()));
    }
    @Test void sourceReplacementMapsGroupPageSettingsAndRemovalClearsThem() {
        var before=plan("qty","oldCount",null);var after=plan("amount","newCount",null);
        var original=definition(before).edit(d->{d.groups=List.of("qty");d.appearance=d.appearance.withGroups(Map.of("qty",new GroupSettings(true)));});
        var analysis=UniDrSourceReconciler.analyze(original,before,after,List.of());var choices=analysis.automatic();choices.put("qty","amount");
        assertThat(analysis.preview(choices).definition().appearance().groupSettings()).containsExactlyEntriesOf(Map.of("amount",new GroupSettings(true)));
        choices.put("qty",null);
        assertThat(analysis.preview(choices).definition().appearance().groupSettings()).isEmpty();
    }
    private UniDrSourcePlan plan(String numeric,String counter,Class<?> reference) {
        var fields=List.of(new Field("name","Название",ValueType.TEXT,"name","name",List.of("name"),null,null,true,Set.of(),EnumSet.allOf(Operator.class)),
            new Field(numeric,"Количество",ValueType.DECIMAL,numeric,numeric,List.of(numeric),null,null,true,Set.of(Aggregation.SUM,Aggregation.AVG),EnumSet.allOf(Operator.class)),
            new Field(counter,"Записей",ValueType.COUNT,null,null,List.of(),null,null,false,Set.of(Aggregation.COUNT_ROWS),Set.of()));
        var query=new UniDrQuerySource(ReportQuerySource.MANUAL,"select r.name as name,r."+numeric+" as "+numeric+" from Example r",null,List.of());
        return new UniDrSourcePlan(query,new UniDrSourceDefinition("query",1,"Пример","",fields,List.of(),3),Map.of(
            "name",new UniDrSourcePlan.Projection("name","r.name",reference==null?String.class:reference,reference==null?null:reference.getName(),"name",true),
            numeric,new UniDrSourcePlan.Projection(numeric,"r."+numeric,BigDecimal.class,null,numeric,true)),List.of());
    }
    private UniDrDefinition definition(UniDrSourcePlan plan) {
        return new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Проверка",List.of(new Column("name","Детали"),new Column("qty","Объём")),
            List.of(new Measure("qty",Aggregation.SUM,"Сумма"),new Measure("oldCount",Aggregation.COUNT_ROWS,"Всего строк")),List.of("name"),List.of(),List.of(),List.of(new Sort("name",Direction.DESC)),Mode.DETAILS,true)
            .withQuery(plan.original()).withFieldStyles(Map.of("qty",new FieldStyle(200,Alignment.RIGHT,"0.00",false),"oldCount",new FieldStyle(50,Alignment.CENTER,"0",true)))
            .withComputedColumns(List.of(new ComputedColumn("calc_total","Стоимость",Computation.FORMULA,"{qty} * 10")));
    }
    @Test void replacementRewritesFormulaAndKeepsOrderCaptionsStylesAndCounterRole() {
        var before=plan("qty","oldCount",null);var after=plan("amount","newCount",null);var original=definition(before);
        var analysis=UniDrSourceReconciler.analyze(original,before,after,List.of());var choices=analysis.automatic();choices.put("qty","amount");
        var result=analysis.preview(choices);
        assertThat(result.blocking()).isEmpty();assertThat(result.losses()).isEmpty();
        assertThat(result.definition().columns()).containsExactly(new Column("name","Детали"),new Column("amount","Объём"));
        assertThat(result.definition().measures()).containsExactly(new Measure("amount",Aggregation.SUM,"Сумма"),new Measure("newCount",Aggregation.COUNT_ROWS,"Всего строк"));
        assertThat(result.definition().fieldStyles()).containsEntry("amount",original.fieldStyles().get("qty")).containsEntry("newCount",original.fieldStyles().get("oldCount"));
        assertThat(result.definition().computedColumns().getFirst().expression()).isEqualTo("{amount} * 10");
        assertThat(original.computedColumns().getFirst().expression()).isEqualTo("{qty} * 10");
    }
    @Test void removingAnOrBranchKeepsItsParameterDeclarationAndRemovesEmptyGroups() {
        var before=plan("qty","oldCount",null);var after=plan("amount","newCount",null);
        var p=new UniDrParameter("p_min","minimum","Минимум",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.DECIMAL,null,false,true,
            UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
        var qty=new FilterConditionNode(new FilterCondition("qty",FilterOperator.GE,":minimum",null,FilterDataType.NUMBER));
        var name=new FilterConditionNode(new FilterCondition("name",FilterOperator.EQ,"X",null,FilterDataType.TEXT));
        var original=definition(before).withParameters(List.of(p)).withFilterTree(FilterGroup.or(FilterGroup.and(qty),name));
        var result=UniDrSourceReconciler.analyze(original,before,after,List.of(p)).preview(Map.of("name","name","oldCount","newCount"));
        assertThat(result.definition().parameterDeclarations()).containsExactly(p);
        assertThat(((FilterGroup)result.definition().filterTree()).children()).containsExactly(name);
        assertThat(result.definition().computedColumns()).isEmpty();assertThat(result.losses()).isNotEmpty();
        assertThat(((FilterGroup)original.filterTree()).children()).hasSize(2);
    }
    @Test void sameAliasWithAnotherReferenceDomainCannotKeepTheField() {
        var before=plan("qty","oldCount",String.class);var after=plan("qty","newCount",Long.class);
        var analysis=UniDrSourceReconciler.analyze(definition(before),before,after,List.of());
        assertThat(analysis.automatic().get("name")).isNull();
        assertThat(analysis.changes().stream().filter(row->row.field().id().equals("name")).findFirst().orElseThrow().candidates()).isEmpty();
    }
    @Test void onlyCompleteBraceReferencesAreRenamed() {
        var column=new ComputedColumn("calc_text","Текст",Computation.TEXT,"qty: {qty}; qtyExtra: { qtyExtra }");
        assertThat(UniDrComputedColumns.rewriteReferences(column,Map.of("qty","amount","qtyExtra","other"))).isEqualTo("qty: {amount}; qtyExtra: {other}");
    }
    @Test void changingParameterTypeExplicitlyResetsItsOldDefault() {
        var before=plan("qty","oldCount",null);var after=plan("qty","newCount",null);
        var old=new UniDrParameter("p_value","value","Значение",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.DECIMAL,null,false,false,
            UniDrParameter.ValueSource.FIXED,UniDrParameter.InitialRule.VALUE,List.of("5"),UniDrParameter.Computed.NONE,null,null);
        var next=new UniDrParameter(old.id(),old.name(),old.caption(),old.kind(),UniDrParameter.ScalarType.DATE,null,false,true,old.valueSource(),old.initialRule(),old.initialValues(),old.computed(),null,null);
        var analysis=UniDrSourceReconciler.analyze(definition(before).withParameters(List.of(old)),before,after,List.of(next));var result=analysis.preview(analysis.automatic());
        assertThat(result.losses()).anyMatch(message->message.contains("Параметр"));
        assertThat(result.definition().parameterDeclarations()).singleElement().satisfies(p->{assertThat(p.initialValues()).isEmpty();assertThat(p.valueSource()).isEqualTo(UniDrParameter.ValueSource.FORM);assertThat(p.showOnRun()).isTrue();assertThat(p.contextKey()).isNull();});
        assertThat(analysis.keepsValue(old,result.definition().parameterDeclarations().getFirst())).isFalse();
    }
}
