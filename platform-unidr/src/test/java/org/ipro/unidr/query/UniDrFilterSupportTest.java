package org.ipro.unidr.query;

import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrParameter.*;
import org.ipro.filtergrid.filter.*;
import org.ipro.query.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UniDrFilterSupportTest {
    private enum Labelled { FIRST; @Override public String toString(){return "Первый";} }
    final UniDrSourcePlan plan=new UniDrSourcePlan(null,UniDrQuerySourceService.metadata(List.of(
        org.ipro.report.data.QueryField.scalar("name",String.class),org.ipro.report.data.QueryField.scalar("qty",BigDecimal.class))),
        Map.of("name",new UniDrSourcePlan.Projection("name","n.name",String.class,null,"name",true),
            "qty",new UniDrSourcePlan.Projection("qty","n.qty",BigDecimal.class,null,"qty",true)),List.of());
    UniDrParameter param(String id,ScalarType type,boolean required){return new UniDrParameter("p_"+id,id,id,Kind.SCALAR,type,null,required,true,ValueSource.FORM,InitialRule.NONE,List.of(),Computed.NONE,null,null);}
    FilterNode condition(String path,FilterOperator op,String value,FilterDataType type){return new FilterConditionNode(new FilterCondition(path,op,value,null,type));}
    @Test void parameterIsResolvedBeforeSearchMaskAndBindNamesNeverCollide() {
        var tree=FilterGroup.and(condition("name",FilterOperator.CONTAINS,":search",FilterDataType.TEXT),condition("qty",FilterOperator.GE,":min",FilterDataType.NUMBER));
        var result=UniDrFilterSupport.compile(tree,plan,List.of(param("search",ScalarType.TEXT,true),param("min",ScalarType.DECIMAL,true)),
            Map.of("search","ДЕТАЛЬ","min",new BigDecimal("1.234567890123456789")),Set.of("unidrFilter_1"),(type,id)->{throw new AssertionError();});
        assertThat(result.bindings()).doesNotContainKey("unidrFilter_1");
        assertThat(result.bindings().values()).containsExactlyInAnyOrder("%деталь%",new BigDecimal("1.234567890123456789"));
        assertThat(result.predicate()).contains("lower(n.name)","n.qty >=");
    }
    @Test void enumBindingUsesIdentityEvenWhenItsDisplayLabelIsLocalized() {
        var source=UniDrQuerySourceService.metadata(List.of(org.ipro.report.data.QueryField.scalar("value",Labelled.class)));
        var enumPlan=new UniDrSourcePlan(null,source,Map.of("value",new UniDrSourcePlan.Projection("value","n.value",Labelled.class,null,"value",true)),List.of());
        var result=UniDrFilterSupport.compile(condition("value",FilterOperator.EQ,":choice",FilterDataType.ENUM),enumPlan,List.of(param("choice",ScalarType.ENUM,true)),Map.of("choice",Labelled.FIRST),Set.of(),(type,id)->{});
        assertThat(result.bindings().values()).containsExactly(Labelled.FIRST);
    }
    @Test void clearingAnOptionalParameterPrunesItsConditionButKeepsOtherBranches() {
        var tree=FilterGroup.or(condition("name",FilterOperator.EQ,":search",FilterDataType.TEXT),condition("qty",FilterOperator.GT,"3",FilterDataType.NUMBER));
        var result=UniDrFilterSupport.compile(tree,plan,List.of(param("search",ScalarType.TEXT,false)),Map.of(),Set.of(),(type,id)->{});
        assertThat(result.predicate()).doesNotContain("n.name").contains("n.qty >");
        assertThat(result.bindings().values()).containsExactly(new BigDecimal("3"));
    }
    @Test void unknownExpressionsWrongTypesAndAggregatePredicatesAreRejected() {
        assertThatThrownBy(()->UniDrFilterSupport.validate(condition("n.name or 1=1",FilterOperator.EQ,"x",FilterDataType.TEXT),plan,List.of())).hasMessageContaining("недоступно");
        assertThatThrownBy(()->UniDrFilterSupport.validate(condition("qty",FilterOperator.GE,":search",FilterDataType.NUMBER),plan,List.of(param("search",ScalarType.TEXT,true)))).hasMessageContaining("Тип параметра");
        var aggregate=new UniDrSourcePlan(null,plan.schema(),Map.of("qty",new UniDrSourcePlan.Projection("qty","sum(n.qty)",BigDecimal.class,null,"qty",false)),List.of());
        assertThatThrownBy(()->UniDrFilterSupport.validate(condition("qty",FilterOperator.GE,"1",FilterDataType.NUMBER),aggregate,List.of())).hasMessageContaining("HAVING");
    }
    @Test void entityIdentityCannotBeBoundToAnUnrelatedScalarEvenWithNumericScalarMetadata() {
        var entity=new UniDrParameter("p_entity","entity","Ссылка",Kind.ENTITY,ScalarType.DECIMAL,"app.Nomenclature",true,true,ValueSource.FORM,InitialRule.NONE,List.of(),Computed.NONE,null,null);
        assertThatThrownBy(()->UniDrFilterSupport.validate(condition("qty",FilterOperator.EQ,":entity",FilterDataType.NUMBER),plan,List.of(entity))).hasMessageContaining("Сущностный параметр");
    }
    @Test void whereKeepsOriginalOrGroupedAndIgnoresClausesInsideStringsCommentsAndSubqueries() {
        String source="select n.name from Nomenclature n where n.code='order by' or n.id in (select m.id from Nomenclature m where m.name='x') /* group by */ order by n.name";
        assertThat(WhereClauseApplier.apply(source,"n.id > :min")).isEqualTo("select n.name from Nomenclature n where (n.code='order by' or n.id in (select m.id from Nomenclature m where m.name='x') /* group by */) and (n.id > :min) order by n.name");
        assertThat(JpqlParameters.rename("n.code=':old' and n.id=:old /* :old */",Map.of("old","new"))).isEqualTo("n.code=':old' and n.id=:new /* :old */");
    }
}
