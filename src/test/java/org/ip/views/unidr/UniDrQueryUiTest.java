package org.ip.views.unidr;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.query.*;
import org.ip.unidr.run.*;
import org.ip.unidr.source.*;
import org.ip.views.components.ReportParamForm;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.*;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.query.editor.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrQueryUiTest {
    private UI ui;
    @BeforeEach void setup(){ui=new UI();UI.setCurrent(ui);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice",null,List.of()));}
    @AfterEach void cleanup(){UI.setCurrent(null);ui=null;org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void parameterChangesNotifyIncludingEntitySelectionAndDynamicListRemoval() {
        var scalar=new ReportParam();scalar.setName("code");
        var entity=new ReportParam();entity.setName("unit");entity.setKind(ReportParamKind.ENTITY);entity.setEntityClass(org.ip.model.UnitOfMeasurement.class.getName());
        var list=new ReportParam();list.setName("units");list.setKind(ReportParamKind.ENTITY_LIST);list.setEntityClass(entity.getEntityClass());
        var lookup=mock(org.ipro.crud.EntityLookup.class);var selection=mock(org.ipro.form.SelectionFormAssembler.class);
        when(selection.resolveColumns(any())).thenReturn(new org.ipro.form.SelectionFormAssembler.ResolvedSelection(List.of(),"Единицы"));
        var unit=new org.ip.model.UnitOfMeasurement("шт","Штука","PCS");
        when(lookup.search(any(),any(),anyString(),anyInt())).thenReturn((List)List.of(unit));
        var form=new ReportParamForm(List.of(scalar,entity,list),ReportContext.empty("alice",Instant.now()),lookup,selection);
        var changes=new java.util.concurrent.atomic.AtomicInteger();form.setChangeListener(changes::incrementAndGet);
        UniDrEditorTest.all(form,com.vaadin.flow.component.textfield.TextField.class).stream().filter(field->"code".equals(field.getLabel())).findFirst().orElseThrow().setValue("X");
        assertThat(changes.get()).isEqualTo(1);
        var first=UniDrEditorTest.all(form,org.ipro.form.EntityField.class).getFirst();
        UniDrEditorTest.all(first,com.vaadin.flow.component.textfield.TextField.class).getFirst().setValue(org.ipro.fetch.instance.InstanceNameBridge.displayName(unit));
        assertThat(changes.get()).isEqualTo(2);assertThat(form.values().get("unit")).isSameAs(unit);
        UniDrEditorTest.button(form,"Добавить").click();assertThat(changes.get()).isEqualTo(3);
        var added=UniDrEditorTest.all(form,org.ipro.form.EntityField.class).getLast();
        UniDrEditorTest.all(added,com.vaadin.flow.component.textfield.TextField.class).getFirst().setValue(org.ipro.fetch.instance.InstanceNameBridge.displayName(unit));
        assertThat(changes.get()).isEqualTo(4);assertThat((List<?>)form.values().get("units")).hasSize(1);
        UniDrEditorTest.all(added.getParent().orElseThrow(),com.vaadin.flow.component.button.Button.class).stream().filter(button->!UniDrEditorTest.all(added,com.vaadin.flow.component.button.Button.class).contains(button)).findFirst().orElseThrow().click();
        assertThat(changes.get()).isEqualTo(5);assertThat((List<?>)form.values().get("units")).isEmpty();
        assertThat(UniDrEditorTest.all(form,org.ipro.form.EntityField.class)).hasSize(2);
    }
    @Test void applyingQueryBuildsFieldsAndEditingOnlyParametersKeepsReportSettings() {
        var fields=List.of(QueryField.scalar("name",String.class),QueryField.scalar("qty",Integer.class));var metadata=UniDrQuerySourceService.metadata(fields);
        var catalog=new UniDrSourceCatalog(List.of(UniDrReceivingItemsSource.definition()),query->metadata);
        var support=mock(UniDrQueryUiSupport.class);when(support.catalog()).thenReturn(catalog);
        var draftChanged=new java.util.concurrent.atomic.AtomicReference<Runnable>();var cancelled=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        when(support.parametersPane(any(),any(),any(),any())).thenAnswer(call->{draftChanged.set(call.getArgument(2));cancelled.set(call.getArgument(3));return new com.vaadin.flow.component.orderedlayout.VerticalLayout();});
        when(support.form(any(),any())).thenAnswer(call->new ReportParamForm(List.of(),ReportContext.empty("alice",Instant.now()),null,null));
        var references=mock(UniDrReferenceService.class);var runs=mock(UniDrRunService.class);
        var view=new UniDrView(runs,references,Clock.systemUTC(),support);
        var query=new UniDrQuerySource(ReportQuerySource.MANUAL,"select p.name as name,p.qty as qty from Product p",null,List.of());view.applyQuery(query);
        assertThat(view.definition().sourceId()).isEqualTo("query");assertThat(view.definition().columns()).extracting(Column::fieldId).containsExactly("name","qty");
        draftChanged.get().run();view.start();verify(runs,never()).newSession();
        assertThat(UniDrEditorTest.all(view,com.vaadin.flow.component.html.Div.class)).anyMatch(div->div.getText().contains("Примените изменения"));
        assertThat(view.definition().query()).isEqualTo(query);cancelled.get().run();
        var row=UniDrEditorTest.fieldRow(view,"qty");
        @SuppressWarnings("unchecked") var aggregation=(ComboBox<Aggregation>)UniDrEditorTest.all(row,ComboBox.class).getFirst();aggregation.setValue(Aggregation.SUM);
        UniDrEditorTest.button(row,"⋯").click();
        UniDrEditorTest.all(view,com.vaadin.flow.component.textfield.IntegerField.class).getFirst().setValue(250);
        var group=UniDrEditorTest.<UniDrSourceDefinition.Field>combo(view,"Добавить группировку");group.setValue(metadata.fields().getFirst());
        var add=UniDrEditorTest.all(group.getParent().orElseThrow(),com.vaadin.flow.component.button.Button.class).getFirst();add.click();
        var definitionEditor=UniDrEditorTest.all(view,UniDrDefinitionEditor.class).getFirst();var computation=definitionEditor.openComputedEditor(null);
        UniDrEditorTest.all(computation,com.vaadin.flow.component.textfield.TextField.class).stream().filter(input->"Название колонки".equals(input.getLabel())).findFirst().orElseThrow().setValue("Стоимость");
        UniDrEditorTest.all(computation,com.vaadin.flow.component.textfield.TextArea.class).getFirst().setValue("{qty} * 10");UniDrEditorTest.footerButton(computation,"Сохранить колонку").click();
        UniDrEditorTest.all(view,Checkbox.class).stream().filter(input->"Номер строки".equals(input.getLabel())).findFirst().orElseThrow().setValue(true);
        var previous=view.definition();var parameter=new ReportParam();parameter.setName("code");parameter.setCaption("Код");
        var draft=query.template();draft.addParam(parameter);view.applyQuery(UniDrQuerySource.from(draft));
        assertThat(view.definition().columns()).isEqualTo(previous.columns());assertThat(view.definition().measures()).isEqualTo(previous.measures());assertThat(view.definition().groups()).isEqualTo(previous.groups());
        assertThat(view.definition().fieldStyles()).isEqualTo(previous.fieldStyles());
        assertThat(view.definition().computedColumns()).isEqualTo(previous.computedColumns());assertThat(view.definition().rowNumbers()).isTrue();
        assertThat(view.definition().query().parameters()).hasSize(1);assertThat(new UniDrDefinitionValidator(catalog).validate(view.definition())).isEmpty();
    }
    @Test void missingQueryParametersBecomeDeclarationsWithoutReplacingExistingPeriodOrEntitySettings() {
        var template=new ReportTemplate();var period=new ReportParam();period.setName("period");period.setKind(ReportParamKind.PERIOD);template.addParam(period);
        var descriptors=List.of(QueryParameterDescriptor.unknown("periodFrom"),QueryParameterDescriptor.unknown("periodTo"),
            new QueryParameterDescriptor("unit",org.ip.model.UnitOfMeasurement.class,org.ip.model.UnitOfMeasurement.class,false,QueryParameterDescriptor.InferenceStatus.INFERRED,""),QueryParameterDescriptor.unknown("minimum"));
        UniDrQueryUiSupport.addMissingParameters(template,new QueryEditorAnalysis("",null,descriptors));
        assertThat(template.getParams()).extracting(ReportParam::getName).containsExactly("period","unit","minimum");
        assertThat(template.getParams().get(1).getKind()).isEqualTo(ReportParamKind.ENTITY);assertThat(template.getParams().get(1).getEntityClass()).isEqualTo(org.ip.model.UnitOfMeasurement.class.getName());
        UniDrQueryUiSupport.addMissingParameters(template,new QueryEditorAnalysis("",null,descriptors));assertThat(template.getParams()).hasSize(3);
    }
    @Test void opensActualUdrEditorAndCancelDoesNotChangeAppliedQuery() {
        var analysis=mock(QueryEditorAnalysisService.class);var metadata=mock(QueryMetadataCatalogService.class);var assembly=mock(ReportQueryAssemblyService.class);
        var constructors=mock(QueryBuilderMetadataCatalog.class);var emf=mock(jakarta.persistence.EntityManagerFactory.class);
        when(emf.getPersistenceUnitUtil()).thenReturn(mock(jakarta.persistence.PersistenceUnitUtil.class));
        @SuppressWarnings("unchecked") var slices=(org.springframework.beans.factory.ObjectProvider<org.ipro.reportstudio.query.register.RegisterSliceReportTemplates>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var service=new UniDrQuerySourceService(assembly);var catalog=new UniDrSourceCatalog(List.of(UniDrReceivingItemsSource.definition()),service::describe);
        var support=new UniDrQueryUiSupport(analysis,metadata,mock(ReportPreviewService.class),mock(org.ipro.crud.EntityLookup.class),mock(org.ipro.form.SelectionFormAssembler.class),assembly,constructors,slices,service,catalog,emf);
        var original=new UniDrQuerySource(ReportQuerySource.MANUAL,"select n.name as name from Nomenclature n",null,List.of());
        var applied=new java.util.concurrent.atomic.AtomicReference<UniDrQuerySource>();var dialog=support.edit(original,applied::set);
        var editor=UniDrEditorTest.all(dialog,ReportQueryEditor.class).getFirst();editor.applyJpqlText("select n.code as code from Nomenclature n");
        assertThat(UniDrEditorTest.all(dialog,com.vaadin.flow.component.button.Button.class)).anyMatch(button->button.getText().equals("Параметры отчёта…"));
        UniDrEditorTest.button(dialog,"Отмена").click();assertThat(applied.get()).isNull();assertThat(original.jpql()).contains("n.name");
    }
}
