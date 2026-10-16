package org.ip.unidr.run;

import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrParameter.*;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.param.ReportContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UniDrCanonicalValuesTest {
    final ReportContext context=ReportContext.empty("alice",Instant.parse("2026-10-12T22:30:00Z"));
    final ZoneId zone=ZoneId.of("Europe/Moscow");
    UniDrParameter p(String name,Kind kind,ScalarType type,boolean required,InitialRule rule,List<String> initial,ValueSource source,Computed computed) {
        return new UniDrParameter("p_"+name,name,name,kind,type,null,required,true,source,rule,initial,computed,null,null);
    }
    UniDrDefinition definition(UniDrParameter... parameters) {
        return new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Тест",List.of(new UniDrDefinition.Column("value",null)),List.of(),List.of(),List.of(),List.of(),List.of(),UniDrDefinition.Mode.DETAILS,true)
            .withQuery(new UniDrQuerySource(ReportQuerySource.MANUAL,"select n.id as value from Nomenclature n where n.id>:min and n.name=:search and n.id between :periodFrom and :periodTo and n.code=:user and n.name=:day",null,List.of()))
            .withParameters(List.of(parameters));
    }
    @Test void formDefaultsAreEditableAndExplicitEmptyClearsOptionalInitialValue() {
        var p=p("search",Kind.SCALAR,ScalarType.TEXT,false,InitialRule.VALUE,List.of("default"),ValueSource.FORM,Computed.NONE);
        var definition=definition(p);
        assertThat(UniDrCanonicalValues.resolve(definition,Map.of(),Map.of(),context,zone)).containsEntry("search","default");
        var cleared=new HashMap<String,Object>();cleared.put("search",null);
        assertThat(UniDrCanonicalValues.resolve(definition,new UniDrRunRequest(definition,Map.of(),zone,cleared).queryValues(),Map.of(),context,zone)).isEmpty();
        assertThat(UniDrCanonicalValues.resolve(definition,Map.of("search","edited"),Map.of(),context,zone)).containsEntry("search","edited");
    }
    @Test void decimalAndNumericRangeKeepPrecisionAndRequiredBoundariesAreChecked() {
        var decimal=p("min",Kind.SCALAR,ScalarType.DECIMAL,true,InitialRule.NONE,List.of(),ValueSource.FORM,Computed.NONE);
        var period=p("period",Kind.RANGE,ScalarType.INTEGER,true,InitialRule.NONE,List.of(),ValueSource.FORM,Computed.NONE);
        var definition=definition(decimal,period);var entered=Map.<String,Object>of("min",new BigDecimal("9007199254740993.123456789"),"periodFrom",9007199254740993L,"periodTo",9007199254740994L);
        assertThat(UniDrCanonicalValues.resolve(definition,entered,Map.of(),context,zone)).containsEntry("min",new BigDecimal("9007199254740993.123456789")).containsEntry("periodFrom",9007199254740993L);
        assertThatThrownBy(()->UniDrCanonicalValues.resolve(definition,Map.of("min",1,"periodFrom",3),Map.of(),context,zone)).hasMessageContaining("обе границы");
    }
    @Test void fixedComputedAndTodayUseTheTrustedRunContextInsteadOfEnteredValues() {
        var fixed=p("min",Kind.SCALAR,ScalarType.DECIMAL,true,InitialRule.VALUE,List.of("1.234"),ValueSource.FIXED,Computed.NONE);
        var user=p("user",Kind.SCALAR,ScalarType.TEXT,true,InitialRule.NONE,List.of(),ValueSource.COMPUTED,Computed.CURRENT_USER);
        var today=p("day",Kind.SCALAR,ScalarType.DATE,true,InitialRule.TODAY,List.of(),ValueSource.FORM,Computed.NONE);
        var values=UniDrCanonicalValues.resolve(definition(fixed,user,today),Map.of("min",999,"user","mallory"),Map.of(),context,zone);
        assertThat(values).containsEntry("min",new BigDecimal("1.234")).containsEntry("user","alice").containsEntry("day",LocalDate.of(2026,10,13));
        assertThatThrownBy(()->UniDrCanonicalValues.resolve(definition(p("user",Kind.ENTITY,ScalarType.UNRESOLVED,true,InitialRule.NONE,List.of(),ValueSource.COMPUTED,Computed.CURRENT_USER)),Map.of(),Map.of(),context,zone)).hasMessageContaining("текстовое имя");
    }
    @Test void organisationComesOnlyFromServerGrantsAndRequiresACompatibleType() {
        var p=p("min",Kind.SCALAR,ScalarType.INTEGER,true,InitialRule.NONE,List.of(),ValueSource.COMPUTED,Computed.RLS_ORG);
        var org=new UniDrOrgContext.Value(org.ip.model.Branch.class,42L);
        assertThat(UniDrCanonicalValues.resolve(definition(p),Map.of("min",999L),Map.of(),context,zone,org)).containsEntry("min",42L);
        assertThatThrownBy(()->UniDrCanonicalValues.resolve(definition(p),Map.of(),Map.of(),context,zone)).hasMessageContaining("единственной");
        assertThatThrownBy(()->UniDrCanonicalValues.resolve(definition(p("min",Kind.SCALAR,ScalarType.TEXT,true,InitialRule.NONE,List.of(),ValueSource.COMPUTED,Computed.RLS_ORG)),Map.of(),Map.of(),context,zone,org)).hasMessageContaining("тип параметра");
    }
    @Test void contextEntityIdsAreDetachedAndCannotOverrideTheTrustedUser() {
        var source=ReportContext.of(org.ip.model.Branch.class,42L,List.of(42L,43L),"view","mallory",context.now());
        var request=new UniDrRunRequest(definition(),Map.of(),zone,Map.of(),source);
        assertThat(request.context().user()).isNull();assertThat(request.context().entityId()).isEqualTo(42L);assertThat(request.context().selectedIds()).isEqualTo(List.of(42L,43L));
        var bad=ReportContext.of(org.ip.model.Branch.class,new org.ip.model.Branch(),List.of(),null,"mallory",context.now());
        assertThatThrownBy(()->new UniDrRunRequest(definition(),Map.of(),zone,Map.of(),bad)).hasMessageContaining("без живых сущностей");
    }
}
