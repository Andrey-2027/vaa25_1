package org.ipro.unidr.ui;
import org.ip.unidr.source.UniDrReceivingItemsSource;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.model.UniDrDefinitions;

import org.ipro.query.document.*;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.query.*;
import org.ipro.unidr.run.*;
import org.ipro.unidr.source.*;
import org.ipro.report.ui.params.ReportParamForm;
import org.ipro.report.data.QueryField;
import org.ipro.query.ReportContext;
import org.ipro.query.*;
import org.ipro.query.model.*;
import org.ipro.query.ui.editor.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrQueryUiTest {
    @Test void queryParameterWindowUsesCanonicalDeclarationsAndCancelKeepsThemUnchanged() {
        var assembly=mock(ReportQueryAssemblyService.class);var constructors=mock(QueryBuilderMetadataCatalog.class);
        var emf=mock(jakarta.persistence.EntityManagerFactory.class);
        @SuppressWarnings("unchecked") var slices=(org.springframework.beans.factory.ObjectProvider<org.ipro.query.register.RegisterSliceReportTemplates>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var sources=new UniDrQuerySourceService(assembly);
        var support=new UniDrQueryUiSupport(mock(QueryEditorAnalysisService.class),mock(QueryMetadataCatalogService.class),mock(ReportPreviewService.class),mock(org.ipro.crud.EntityLookup.class),mock(org.ipro.form.SelectionFormAssembler.class),mock(org.ipro.query.ui.selection.EntitySelection.class),UniDrDefinitions.referenceTypes(),assembly,constructors,slices,sources,UniDrDefinitions.catalog(),emf);
        var p=new UniDrParameter("p_saved","saved","Сохранённый параметр",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.INTEGER,null,false,true,UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.VALUE,List.of("42"),UniDrParameter.Computed.NONE,null,null);
        var query=new UniDrQuerySource(ReportQuerySource.MANUAL,"select n.code as code from Nomenclature n",null,List.of());
        var definition=UniDrDefinitions.receivingItemsSummary().withQuery(query).withParameters(List.of(p));
        var described=UniDrQueryDefinitions.of(query);
        var editor=new ReportQueryEditor(mock(QueryEditorAnalysisService.class),mock(QueryMetadataCatalogService.class),mock(ReportPreviewService.class),mock(org.ipro.crud.EntityLookup.class),new org.ipro.reportstudio.query.adapter.EmptyEntitySelection(),assembly);editor.open(QueryDraft.of(described));
        var parameters=new java.util.concurrent.atomic.AtomicReference<>(definition.parameterDeclarations());
        var dialog=support.openCanonicalParameters(definition,described,editor,parameters,UniDrDefinitions.referenceTypes(),ignored->{ });
        var parameterEditor=UniDrEditorTest.all(dialog,UniDrParameterEditor.class).getFirst();
        assertThat(parameterEditor.value()).containsExactly(p);
        parameterGrid(parameterEditor).select(p);
        UniDrEditorTest.all(parameterEditor,TextField.class).stream().filter(field->"Подпись".equals(field.getLabel())).findFirst().orElseThrow().setValue("Изменено");
        UniDrEditorTest.footerButton(dialog,"Отмена").click();assertThat(parameters.get()).containsExactly(p);assertThat(described.parameters()).isEmpty();
        var applied=support.openCanonicalParameters(definition,described,editor,parameters,UniDrDefinitions.referenceTypes(),ignored->{ });
        var edits=UniDrEditorTest.all(applied,UniDrParameterEditor.class).getFirst();parameterGrid(edits).select(p);
        UniDrEditorTest.all(edits,TextField.class).stream().filter(field->"Подпись".equals(field.getLabel())).findFirst().orElseThrow().setValue("Новая подпись");
        UniDrEditorTest.footerButton(applied,"Применить").click();
        assertThat(parameters.get()).singleElement().satisfies(next->{assertThat(next.caption()).isEqualTo("Новая подпись");assertThat(next.id()).isEqualTo(p.id());assertThat(next.scalarType()).isEqualTo(p.scalarType());assertThat(next.initialValues()).containsExactly("42");});
    }
    public enum Labelled { FIRST; @Override public String toString(){return "Первый";} }
    private UI ui;
    @BeforeEach void setup(){ui=new UI();UI.setCurrent(ui);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice",null,List.of()));}
    @AfterEach void cleanup(){UI.setCurrent(null);ui=null;org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void parameterChangesNotifyIncludingEntitySelectionAndDynamicListRemoval() {
        var scalar=queryParameter("code",QueryParameterKind.SCALAR,null,0);
        var entity=queryParameter("unit",QueryParameterKind.ENTITY,org.ip.model.UnitOfMeasurement.class.getName(),1);
        var list=queryParameter("units",QueryParameterKind.ENTITY_LIST,entity.entityClass(),2);
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
        var restored=new org.ip.model.UnitOfMeasurement("кг","Килограмм","KG");
        form.setValues(Map.of("unit",restored));assertThat(form.values().get("unit")).isSameAs(restored);assertThat(changes.get()).isEqualTo(5);
        var cleared=new LinkedHashMap<String,Object>();cleared.put("unit",null);form.setValues(cleared);
        assertThat(form.values()).doesNotContainKey("unit");assertThat(form.allValues()).containsEntry("unit",null);assertThat(changes.get()).isEqualTo(5);
    }
    @Test void typedDecimalAndIntegerInputsNeverRoundThroughDouble() {
        var amount=queryParameter("amount",QueryParameterKind.SCALAR,null,0);var identity=queryParameter("identity",QueryParameterKind.SCALAR,null,1);
        var form=new ReportParamForm(List.of(amount,identity),ReportContext.empty("alice",Instant.now()),null,null,
            Map.of("amount","java.math.BigDecimal","identity","java.lang.Long"));
        var fields=UniDrEditorTest.all(form,com.vaadin.flow.component.textfield.BigDecimalField.class);
        fields.getFirst().setValue(new java.math.BigDecimal("1234567890123.123456789012"));
        fields.getLast().setValue(new java.math.BigDecimal("9007199254740993"));
        assertThat(form.values()).containsEntry("amount",new java.math.BigDecimal("1234567890123.123456789012")).containsEntry("identity",9007199254740993L);
        fields.getLast().setValue(new java.math.BigDecimal("1.5"));assertThatThrownBy(form::values).isInstanceOf(ArithmeticException.class);
    }
    @Test void enumDefaultsRestoreTheConstantFromItsSavedName() {
        var p=queryParameter("choice",QueryParameterKind.SCALAR,null,0);var form=new ReportParamForm(List.of(p),ReportContext.empty("alice",Instant.now()),null,null,Map.of("choice",Labelled.class.getName()));
        form.setValues(Map.of("choice","FIRST"));assertThat(form.values()).containsEntry("choice",Labelled.FIRST);
    }
    @Test void applyingQueryBuildsFieldsAndEditingOnlyParametersKeepsReportSettings() {
        var fields=List.of(QueryField.scalar("name",String.class),QueryField.scalar("qty",Integer.class));var metadata=UniDrQuerySourceService.metadata(fields);
        var catalog=new UniDrSourceCatalog(List.of(UniDrReceivingItemsSource.definition()),query->metadata);
        catalog.usePlans(query->new UniDrSourcePlan(query,metadata,Map.of(
            "name",new UniDrSourcePlan.Projection("name","p.name",String.class,null,"name",true),
            "qty",new UniDrSourcePlan.Projection("qty","p.qty",Integer.class,null,"qty",true)),List.of()));
        var support=mock(UniDrQueryUiSupport.class);when(support.catalog()).thenReturn(catalog);
        var references=UniDrDefinitions.referenceTypes();var runs=mock(UniDrRunService.class);
        var view=new UniDrView(runs,references,Clock.systemUTC(),support);
        var query=new UniDrQuerySource(ReportQuerySource.MANUAL,"select p.name as name,p.qty as qty from Product p",null,List.of());view.applyQuery(query);
        assertThat(view.definition().sourceId()).isEqualTo("query");assertThat(view.definition().columns()).extracting(Column::fieldId).containsExactly("name","qty");
        assertThat(UniDrEditorTest.all(view,UniDrParameterEditor.class)).hasSize(1);
        assertThat(view.definition().query()).isEqualTo(query);
        var definitionEditor=UniDrEditorTest.all(view,UniDrDefinitionEditor.class).getFirst();
        definitionEditor.aggregation("qty").setValue(Aggregation.SUM);definitionEditor.selectField("qty");
        UniDrEditorTest.all(view,com.vaadin.flow.component.textfield.IntegerField.class).getFirst().setValue(250);
        definitionEditor.addGroup(metadata.fields().getFirst().id());
        var computation=definitionEditor.openComputedEditor(null);
        UniDrEditorTest.all(computation,com.vaadin.flow.component.textfield.TextField.class).stream().filter(input->"Название колонки".equals(input.getLabel())).findFirst().orElseThrow().setValue("Стоимость");
        UniDrEditorTest.all(computation,com.vaadin.flow.component.textfield.TextArea.class).getFirst().setValue("{qty} * 10");UniDrEditorTest.footerButton(computation,"Сохранить колонку").click();
        UniDrEditorTest.all(view,Checkbox.class).stream().filter(input->"Нумеровать строки детализации".equals(input.getLabel())).findFirst().orElseThrow().setValue(true);
        var previous=view.definition();var parameter=new QueryParameter("code","code","Код",QueryParameterKind.SCALAR,QueryScalarType.UNRESOLVED,null,QueryValueSource.FORM,false,true,null,QueryComputedValue.NONE,null,0);
        var draft=UniDrQueryDefinitions.of(query).withParameters(List.of(parameter));view.applyQuery(UniDrQueryDefinitions.source(draft,null));
        assertThat(view.definition().columns()).isEqualTo(previous.columns());assertThat(view.definition().measures()).isEqualTo(previous.measures());assertThat(view.definition().groups()).isEqualTo(previous.groups());
        assertThat(view.definition().fieldStyles()).isEqualTo(previous.fieldStyles());
        assertThat(view.definition().computedColumns()).isEqualTo(previous.computedColumns());assertThat(view.definition().rowNumbers()).isTrue();
        assertThat(view.definition().parameterDeclarations()).singleElement().satisfies(p->assertThat(p.name()).isEqualTo("code"));
        assertThat(view.definition().query().parameters()).isEmpty(); // Usage is computed from references, not a second saved list.
        var parameterEditor=UniDrEditorTest.all(view,UniDrParameterEditor.class).getFirst();
        @SuppressWarnings("unchecked") var grid=(com.vaadin.flow.component.grid.Grid<UniDrParameter>)(com.vaadin.flow.component.grid.Grid<?>)UniDrEditorTest.all(parameterEditor,com.vaadin.flow.component.grid.Grid.class).getFirst();
        var beforeSelection=view.definition();grid.select(parameterEditor.value().getFirst());assertThat(view.definition()).isEqualTo(beforeSelection);
        UniDrEditorTest.all(parameterEditor,com.vaadin.flow.component.textfield.TextField.class).stream().filter(input->"Подпись".equals(input.getLabel())).findFirst().orElseThrow().setValue("Код для запуска");
        assertThat(view.definition().parameterDeclarations()).singleElement().satisfies(p->assertThat(p.caption()).isEqualTo("Код для запуска"));
        assertThat(view.definition().fieldStyles()).isEqualTo(previous.fieldStyles());
        assertThat(new UniDrDefinitionValidator(catalog).validate(view.definition())).isEmpty();
    }
    @Test void missingQueryParametersBecomeDeclarationsWithoutReplacingExistingPeriodOrEntitySettings() {
        var period=new QueryParameter("period","period","Период",QueryParameterKind.RANGE,QueryScalarType.UNRESOLVED,null,QueryValueSource.FORM,false,true,null,QueryComputedValue.NONE,null,0);
        var described=new org.ipro.query.model.QueryDefinition("",org.ipro.query.model.QuerySource.MANUAL,null,null,List.of(period));
        var descriptors=List.of(QueryParameterDescriptor.unknown("periodFrom"),QueryParameterDescriptor.unknown("periodTo"),
            new QueryParameterDescriptor("unit",org.ip.model.UnitOfMeasurement.class,org.ip.model.UnitOfMeasurement.class,false,QueryParameterDescriptor.InferenceStatus.INFERRED,""),QueryParameterDescriptor.unknown("minimum"));
        var enriched=UniDrQueryUiSupport.addMissingParameters(described,new QueryEditorAnalysis("",null,descriptors));
        assertThat(enriched.parameters()).extracting(QueryParameter::name).containsExactly("period","unit","minimum");
        assertThat(enriched.parameters().get(1).kind()).isEqualTo(QueryParameterKind.ENTITY);assertThat(enriched.parameters().get(1).entityClass()).isEqualTo(org.ip.model.UnitOfMeasurement.class.getName());
        assertThat(UniDrQueryUiSupport.addMissingParameters(enriched,new QueryEditorAnalysis("",null,descriptors)).parameters()).hasSize(3);
    }
    @Test void opensActualUdrEditorAndCancelDoesNotChangeAppliedQuery() {
        var analysis=mock(QueryEditorAnalysisService.class);var metadata=mock(QueryMetadataCatalogService.class);var assembly=mock(ReportQueryAssemblyService.class);
        var constructors=mock(QueryBuilderMetadataCatalog.class);var emf=mock(jakarta.persistence.EntityManagerFactory.class);
        when(emf.getPersistenceUnitUtil()).thenReturn(mock(jakarta.persistence.PersistenceUnitUtil.class));
        @SuppressWarnings("unchecked") var slices=(org.springframework.beans.factory.ObjectProvider<org.ipro.query.register.RegisterSliceReportTemplates>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var service=new UniDrQuerySourceService(assembly);var catalog=new UniDrSourceCatalog(List.of(UniDrReceivingItemsSource.definition()),service::describe);
        var support=new UniDrQueryUiSupport(analysis,metadata,mock(ReportPreviewService.class),mock(org.ipro.crud.EntityLookup.class),mock(org.ipro.form.SelectionFormAssembler.class),mock(org.ipro.query.ui.selection.EntitySelection.class),UniDrDefinitions.referenceTypes(),assembly,constructors,slices,service,catalog,emf);
        var original=new UniDrQuerySource(ReportQuerySource.MANUAL,"select n.name as name from Nomenclature n",null,List.of());
        var applied=new java.util.concurrent.atomic.AtomicReference<UniDrQuerySource>();var dialog=support.edit(original,applied::set);
        var editor=UniDrEditorTest.all(dialog,ReportQueryEditor.class).getFirst();editor.applyJpqlText("select n.code as code from Nomenclature n");
        assertThat(UniDrEditorTest.all(dialog,com.vaadin.flow.component.button.Button.class)).anyMatch(button->button.getText().equals("Параметры отчёта…"));
        UniDrEditorTest.button(dialog,"Отмена").click();assertThat(applied.get()).isNull();assertThat(original.jpql()).contains("n.name");
    }
    @Test void conditionUsageRefreshLocksTheParameterNameWithoutRemovingItsDeclaration() {
        var p=new UniDrParameter("p_search","search","Поиск",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.TEXT,null,false,true,
            UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
        var state=new java.util.concurrent.atomic.AtomicReference<>(UniDrDefinitions.receivingItemsSummary().withParameters(List.of(p)));
        var editor=new UniDrParameterEditor(List.of(p),mock(UniDrQueryUiSupport.class),UniDrDefinitions.referenceTypes(),state::get,()->{});
        @SuppressWarnings("unchecked") var grid=(com.vaadin.flow.component.grid.Grid<UniDrParameter>)(com.vaadin.flow.component.grid.Grid<?>)UniDrEditorTest.all(editor,com.vaadin.flow.component.grid.Grid.class).getFirst();grid.select(p);
        var name=UniDrEditorTest.all(editor,com.vaadin.flow.component.textfield.TextField.class).stream().filter(field->field.getLabel().equals("Имя в запросе и условиях")).findFirst().orElseThrow();
        assertThat(name.isReadOnly()).isFalse();
        state.set(state.get().withFilterTree(new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("name",org.ipro.filtergrid.filter.FilterOperator.EQ,":search",null,org.ipro.filtergrid.filter.FilterDataType.TEXT))));
        editor.refreshUsage();assertThat(name.isReadOnly()).isTrue();
        state.set(state.get().withFilterTree(null));editor.refreshUsage();assertThat(name.isReadOnly()).isFalse();assertThat(editor.value()).containsExactly(p);
    }
    @Test void entityTypeChooserFiltersSectionsAndCancelOrReselectionPreservesParameterDefaults() {
        var unit=entity("UnitOfMeasurement",org.ip.model.UnitOfMeasurement.class,"Единицы измерения",null);
        var item=entity("ReceivingDocumentItem",org.ip.model.ReceivingDocumentItem.class,"Позиции накладных",org.ip.model.ReceivingDocument.class);
        var support=mock(UniDrQueryUiSupport.class);when(support.tables()).thenReturn(List.of(unit,item));
        when(support.formForParameters(anyList(),any(),any())).thenAnswer(call->new ReportParamForm(List.of(),ReportContext.empty("alice"),null,null));
        var p=entityParameter(UniDrParameter.Kind.ENTITY);
        var changes=new java.util.concurrent.atomic.AtomicInteger();
        var editor=new UniDrParameterEditor(List.of(p),support,UniDrDefinitions.referenceTypes(),()->UniDrDefinitions.receivingItemsSummary().withParameters(List.of(p)),changes::incrementAndGet);
        parameterGrid(editor).select(p);
        assertThat(UniDrEditorTest.all(editor,ComboBox.class)).noneMatch(input->"Тип сущности".equals(input.getLabel()));
        var field=UniDrEditorTest.all(editor,TextField.class).stream().filter(input->"Тип сущности".equals(input.getLabel())).findFirst().orElseThrow();
        assertThat(field.isReadOnly()).isTrue();assertThat(field.getValue()).isEqualTo("Единицы измерения");
        var cancelled=editor.openEntityTypeDialog();var choices=entityGrid(cancelled);
        assertThat(choices.getListDataView().getItems().toList()).containsExactly(unit);
        assertThat(choices.asSingleSelect().getValue()).isEqualTo(unit);
        UniDrEditorTest.footerButton(cancelled,"Отмена").click();
        assertThat(editor.value()).containsExactly(p);assertThat(changes.get()).isZero();
        var reselected=editor.openEntityTypeDialog();UniDrEditorTest.footerButton(reselected,"Выбрать").click();
        assertThat(reselected.isOpened()).isFalse();assertThat(editor.value()).containsExactly(p);assertThat(changes.get()).isZero();
    }
    @Test void entityTypeChooserSearchAndApplyUpdateSingleAndListTypesAndResetIncompatibleDefaults() {
        var unit=entity("UnitOfMeasurement",org.ip.model.UnitOfMeasurement.class,"Единицы измерения",null);
        var nomenclature=entity("Nomenclature",org.ip.model.Nomenclature.class,"Номенклатура",null);
        var support=mock(UniDrQueryUiSupport.class);when(support.tables()).thenReturn(List.of(unit,nomenclature));
        when(support.formForParameters(anyList(),any(),any())).thenAnswer(call->new ReportParamForm(List.of(),ReportContext.empty("alice"),null,null));
        for(var kind:List.of(UniDrParameter.Kind.ENTITY,UniDrParameter.Kind.ENTITY_LIST)) {
            var p=entityParameter(kind);var changes=new java.util.concurrent.atomic.AtomicInteger();
            var editor=new UniDrParameterEditor(List.of(p),support,UniDrDefinitions.referenceTypes(),()->UniDrDefinitions.receivingItemsSummary().withParameters(List.of(p)),changes::incrementAndGet);
            parameterGrid(editor).select(p);var dialog=editor.openEntityTypeDialog();var choices=entityGrid(dialog);
            UniDrEditorTest.all(dialog,TextField.class).getFirst().setValue("  НОМЕНКЛ  ");
            assertThat(choices.getListDataView().getItems().toList()).containsExactly(nomenclature);
            var apply=UniDrEditorTest.footerButton(dialog,"Выбрать");assertThat(apply.isEnabled()).isFalse();
            choices.select(nomenclature);assertThat(apply.isEnabled()).isTrue();apply.click();
            assertThat(dialog.isOpened()).isFalse();assertThat(changes.get()).isEqualTo(1);
            assertThat(editor.value()).singleElement().satisfies(next->{
                assertThat(next.entityType()).isEqualTo(org.ip.model.Nomenclature.class.getName());
                assertThat(next.kind()).isEqualTo(kind);assertThat(next.id()).isEqualTo(p.id());
                assertThat(next.initialRule()).isEqualTo(UniDrParameter.InitialRule.NONE);assertThat(next.initialValues()).isEmpty();
                assertThat(next.required()).isTrue();assertThat(next.showOnRun()).isTrue();
            });
        }
    }
    @Test void metadataChooserDoubleClickUsesEventItemEvenWhenGridSelectionIsEmpty() {
        var unit=entity("UnitOfMeasurement",org.ip.model.UnitOfMeasurement.class,"Единицы измерения",null);
        var selected=new java.util.concurrent.atomic.AtomicReference<QueryBuilderMetadataCatalog.Entity>();
        var dialog=new UniDrEntityTypeDialog("Выбор таблицы",List.of(unit),null,selected::set);dialog.open();
        var choices=entityGrid(dialog);String key=choices.getDataCommunicator().getKeyMapper().key(unit);
        assertThat(choices.asSingleSelect().getValue()).isNull();
        ComponentUtil.fireEvent(choices,new ItemDoubleClickEvent<>(choices,true,key,null,0,0,0,0,2,0,false,false,false,false));
        assertThat(selected.get()).isEqualTo(unit);assertThat(dialog.isOpened()).isFalse();
    }
    private static QueryParameter queryParameter(String name,QueryParameterKind kind,String entityClass,int position) {
        return new QueryParameter(name,name,"",kind,QueryScalarType.UNRESOLVED,entityClass,QueryValueSource.FORM,false,true,null,QueryComputedValue.NONE,null,position);
    }
    private static QueryBuilderMetadataCatalog.Entity entity(String name,Class<?> type,String caption,Class<?> owner) {
        return new QueryBuilderMetadataCatalog.Entity(name,type,List.of(),List.of(),caption,"Справочник",owner);
    }
    private static UniDrParameter entityParameter(UniDrParameter.Kind kind) {
        return new UniDrParameter("p_entity","entity","Объект",kind,UniDrParameter.ScalarType.TEXT,org.ip.model.UnitOfMeasurement.class.getName(),true,true,
            UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.VALUE,List.of("42"),UniDrParameter.Computed.NONE,null,null);
    }
    @SuppressWarnings("unchecked") private static Grid<UniDrParameter> parameterGrid(UniDrParameterEditor editor) {
        return (Grid<UniDrParameter>)(Grid<?>)UniDrEditorTest.all(editor,Grid.class).getFirst();
    }
    @SuppressWarnings("unchecked") private static Grid<QueryBuilderMetadataCatalog.Entity> entityGrid(com.vaadin.flow.component.dialog.Dialog dialog) {
        return (Grid<QueryBuilderMetadataCatalog.Entity>)(Grid<?>)UniDrEditorTest.all(dialog,Grid.class).getFirst();
    }
}
