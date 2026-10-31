package org.ipro.unidr.ui;
import org.ip.unidr.source.UniDrReceivingItemsSource;
import org.ip.unidr.query.UniDrQueryAdapter;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.model.UniDrDefinitions;

import org.ipro.unidr.model.*;
import org.ipro.unidr.query.*;
import org.ipro.unidr.source.*;
import org.ipro.filtergrid.filter.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrNativeFilterEditorTest {
    private com.vaadin.flow.component.UI testUi;
    @BeforeEach void ui(){testUi=new com.vaadin.flow.component.UI();com.vaadin.flow.component.UI.setCurrent(testUi);var session=mock(com.vaadin.flow.server.VaadinSession.class);when(session.hasLock()).thenReturn(true);testUi.getInternals().setSession(session);}
    @AfterEach void clear(){com.vaadin.flow.component.UI.setCurrent(null);com.vaadin.flow.server.VaadinSession.setCurrent(null);}
    @Test void dateAndEntityParameterReferencesRenderWithoutParsingThemAsLiteralValues() {
        var base=UniDrDefinitions.receivingItemsSummary();
        var date=new FilterConditionNode(new FilterCondition("date",FilterOperator.EQ,":periodFrom",null,FilterDataType.DATE));
        var entity=new FilterConditionNode(new FilterCondition("unit",FilterOperator.EQ,":unit",null,FilterDataType.ENTITY_REFERENCE));
        var tree=FilterGroup.and(date,entity);
        var editor=new UniDrNativeFilterEditor(UniDrQueryAdapter.plan(UniDrReceivingItemsSource.definition(),UniDrDefinitions.referenceTypes()),tree,base.parameterDeclarations(),mock(UniDrReferenceService.class),null,ignored->{});
        assertThat(editor.value()).isEqualTo(tree);
        var inputs=UniDrEditorTest.all(editor,com.vaadin.flow.component.combobox.ComboBox.class);
        assertThat(inputs).anyMatch(input->Objects.equals(input.getValue(),":periodFrom"));
        assertThat(inputs).anyMatch(input->Objects.equals(input.getValue(),":unit"));
        editor.parameters(base.parameterDeclarations());assertThat(editor.value()).isEqualTo(tree);
    }
    @Test void enumAndBooleanReferencesStayVisibleAsParameters() {
        var schema=UniDrQuerySourceService.metadata(List.of(org.ipro.report.data.QueryField.scalar("day",java.time.DayOfWeek.class),org.ipro.report.data.QueryField.scalar("flag",Boolean.class)));
        var plan=new UniDrSourcePlan(null,schema,Map.of("day",new UniDrSourcePlan.Projection("day","n.day",java.time.DayOfWeek.class,null,"day",true),"flag",new UniDrSourcePlan.Projection("flag","n.flag",Boolean.class,null,"flag",true)),List.of());
        var day=new UniDrParameter("p_day","day","День",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.ENUM,null,false,true,UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
        var flag=new UniDrParameter("p_flag","flag","Признак",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.BOOLEAN,null,false,true,UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
        var tree=FilterGroup.and(new FilterConditionNode(new FilterCondition("day",FilterOperator.EQ,":day",null,FilterDataType.ENUM)),new FilterConditionNode(new FilterCondition("flag",FilterOperator.EQ,":flag",null,FilterDataType.BOOLEAN)));
        var editor=new UniDrNativeFilterEditor(plan,tree,List.of(day,flag),mock(UniDrReferenceService.class),null,ignored->{});
        assertThat(UniDrEditorTest.all(editor,com.vaadin.flow.component.combobox.ComboBox.class)).anyMatch(input->Objects.equals(input.getValue(),":day")).anyMatch(input->Objects.equals(input.getValue(),":flag"));
        assertThat(editor.value()).isEqualTo(tree);
    }
}
