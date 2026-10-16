package org.ip.views.unidr;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.textfield.*;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.query.*;
import org.ip.unidr.render.UniDrReportCompiler;
import org.ip.unidr.run.*;
import org.ip.unidr.source.*;
import org.ipro.reportstudio.data.*;
import org.ipro.reportstudio.query.ReportRunQuota;
import org.ipro.reportstudio.render.*;
import org.ipro.reportstudio.run.ReportTaskExecutor;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpSession;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrEditorTest {
    final UniDrSourceCatalog catalog=UniDrSourceCatalog.pilot();
    final UniDrDefinition base=UniDrDefinitions.receivingItemsSummary();
    final UniDrReferenceService references=mock(UniDrReferenceService.class);
    final UniDrDefinitionValidator validator=new UniDrDefinitionValidator(catalog);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),ZoneOffset.UTC);
    // UI.setCurrent stores a weak reference; keep the synthetic UI alive throughout each test.
    private com.vaadin.flow.component.UI testUi;
    @BeforeEach void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice",null,List.of()));
        testUi=new com.vaadin.flow.component.UI();com.vaadin.flow.component.UI.setCurrent(testUi);
        var session=mock(com.vaadin.flow.server.VaadinSession.class);when(session.hasLock()).thenReturn(true);testUi.getInternals().setSession(session);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext();com.vaadin.flow.component.UI.setCurrent(null);testUi=null; }
    static Stream<Component> tree(Component root) { return Stream.concat(Stream.of(root),root.getChildren().flatMap(UniDrEditorTest::tree)); }
    static <T extends Component> List<T> all(Component root,Class<T> type) { return tree(root).filter(type::isInstance).map(type::cast).toList(); }
    static Button button(Component root,String text) { return all(root,Button.class).stream().filter(button->button.getText().equals(text)).findFirst().orElseThrow(); }
    static Button footerButton(com.vaadin.flow.component.dialog.Dialog dialog,String text) {return dialog.getFooter().getElement().getChildren().map(element->element.getComponent().orElse(null)).filter(Button.class::isInstance).map(Button.class::cast).filter(button->button.getText().equals(text)).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked") static <T> ComboBox<T> combo(Component root,String label) { return (ComboBox<T>)all(root,ComboBox.class).stream().filter(input->label.equals(input.getLabel())).findFirst().orElseThrow(); }
    UniDrSourceDefinition.Field field(String id) { return catalog.find(base.sourceId()).orElseThrow().fields().stream().filter(field->field.id().equals(id)).findFirst().orElseThrow(); }
    private UniDrDefinitionEditor attached(UniDrDefinitionEditor editor) {
        com.vaadin.flow.component.UI.getCurrent().add(editor);return editor;
    }
    UniDrConditionsEditor conditions() { return new UniDrConditionsEditor(catalog.find(base.sourceId()).orElseThrow(),base.parameters(),base.filters(),references,()->{}); }
    void add(UniDrConditionsEditor editor,String id,Operator operator,boolean parameter) {
        Component pane=parameter?editor.parameterPane:editor.filterPane;
        UniDrEditorTest.<UniDrSourceDefinition.Field>combo(pane,"Поле").setValue(field(id));UniDrEditorTest.<Operator>combo(pane,"Условие").setValue(operator);
        button(pane,parameter?"Добавить параметр":"Добавить отбор").click();
    }
    UniDrDefinition with(UniDrConditionsEditor conditions) { return new UniDrDefinition(1,base.sourceId(),1,base.title(),base.columns(),base.measures(),base.groups(),conditions.filters(),conditions.parameters(),base.sorts(),base.mode(),true); }
    @Test void initialRecipeRoundTripsThroughActualComponents() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));
        assertThat(editor.definition()).isEqualTo(base);assertThat(validator.validate(editor.definition())).isEmpty();verifyNoInteractions(references);
    }
    @Test void computedDialogValidatesCreatesEditsAndRetainsStylesThroughRemoveAndReadd() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));editor.mode.setValue(Mode.DETAILS);
        var dialog=editor.openComputedEditor(null);
        all(dialog,TextField.class).stream().filter(input->"Название колонки".equals(input.getLabel())).findFirst().orElseThrow().setValue("Стоимость");
        var expression=all(dialog,TextArea.class).getFirst();expression.setValue("{nomenclature} * 10");footerButton(dialog,"Сохранить колонку").click();
        assertThat(dialog.isOpened()).isTrue();assertThat(expression.isInvalid()).isTrue();assertThat(editor.definition().computedColumns()).isEmpty();
        expression.setValue("{quantity} * 10");footerButton(dialog,"Сохранить колонку").click();assertThat(dialog.isOpened()).isFalse();
        var computed=editor.definition().computedColumns().getFirst();assertThat(editor.definition().columns()).extracting(Column::fieldId).contains(computed.id());
        var border=combo(editor,"Граница колонки");border.setValue(border.getListDataView().getItems().filter(value->value.toString().equals("OFF")).findFirst().orElseThrow());
        all(editor,Checkbox.class).stream().filter(input->input.getLabel().equals("Нумеровать строки детализации")).findFirst().orElseThrow().setValue(true);
        var editDialog=editor.openComputedEditor(computed);all(editDialog,TextArea.class).getFirst().setValue("{quantity} * 20");footerButton(editDialog,"Сохранить колонку").click();
        assertThat(editor.definition().computedColumns().getFirst().expression()).isEqualTo("{quantity} * 20");assertThat(editor.definition().fieldStyles().get(computed.id()).border()).isFalse();
        editor.removeField(computed.id());editor.addField(computed.id());
        assertThat(editor.definition().rowNumbers()).isTrue();assertThat(editor.definition().fieldStyles().get(computed.id()).border()).isFalse();assertThat(validator.validate(editor.definition())).isEmpty();
        editor.mode.setValue(Mode.SUMMARY);assertThat(all(editor,Checkbox.class).stream().filter(input->input.getLabel().equals("Нумеровать строки детализации")).findFirst().orElseThrow().isEnabled()).isFalse();
        editor.mode.setValue(Mode.DETAILS);editor.selectField(computed.id());
        all(editor,TextField.class).stream().filter(input->"Формат".equals(input.getLabel())).findFirst().orElseThrow().setValue("0.00");
        var convert=editor.openComputedEditor(editor.definition().computedColumns().getFirst());UniDrEditorTest.<Computation>combo(convert,"Тип вычисления").setValue(Computation.TEXT);
        all(convert,TextArea.class).getFirst().setValue("Количество: {quantity}");footerButton(convert,"Сохранить колонку").click();
        assertThat(editor.definition().fieldStyles().get(computed.id()).format()).isNull();assertThat(validator.validate(editor.definition())).isEmpty();
    }
    @Test void textColumnCreationCancelAndDeletionDoNotLeaveDanglingFields() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));editor.mode.setValue(Mode.DETAILS);
        var cancelled=editor.openComputedEditor(null);footerButton(cancelled,"Отмена").click();assertThat(editor.definition().computedColumns()).isEmpty();
        com.vaadin.flow.component.UI.setCurrent(editor.getUI().orElseThrow());
        var dialog=editor.openComputedEditor(null);all(dialog,TextField.class).stream().filter(input->"Название колонки".equals(input.getLabel())).findFirst().orElseThrow().setValue("Описание");
        UniDrEditorTest.<Computation>combo(dialog,"Тип вычисления").setValue(Computation.TEXT);all(dialog,TextArea.class).getFirst().setValue("{nomenclature} — {quantity}");footerButton(dialog,"Сохранить колонку").click();
        String id=editor.definition().computedColumns().getFirst().id();assertThat(editor.definition().columns()).extracting(Column::fieldId).contains(id);
        button(editor,"Удалить вычисление").click();assertThat(editor.definition().computedColumns()).isEmpty();assertThat(editor.definition().columns()).extracting(Column::fieldId).doesNotContain(id);assertThat(validator.validate(editor.definition())).isEmpty();
    }
    @Test void tableEditsAndPropertySwitchingPreserveTitlesTotalsAndOrder() {
        var changes=new java.util.concurrent.atomic.AtomicInteger();
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,changes::incrementAndGet));
        editor.mode.setValue(Mode.DETAILS);editor.columnTitle("quantity").setValue("Объём");
        var aggregation=editor.aggregation("quantity");aggregation.setValue(Aggregation.AVG);
        editor.mode.setValue(Mode.SUMMARY);editor.columnTitle("quantity").setValue("Средний объём");editor.mode.setValue(Mode.DETAILS);
        editor.selectField("quantity");
        all(editor,IntegerField.class).getFirst().setValue(220);
        UniDrEditorTest.<UniDrDefinition.Alignment>combo(editor,"Выравнивание").setValue(UniDrDefinition.Alignment.CENTER);
        all(editor,TextField.class).stream().filter(input->"Формат".equals(input.getLabel())).findFirst().orElseThrow().setValue("#,##0.000");
        int edits=changes.get();
        editor.selectField("nomenclature");editor.selectField("quantity");
        assertThat(changes.get()).isEqualTo(edits);
        assertThat(editor.columnTitle("quantity").getValue()).isEqualTo("Объём");
        button(editor,"↑").click();
        assertThat(editor.definition().columns()).contains(new Column("quantity","Объём"));
        assertThat(editor.definition().measures()).contains(new Measure("quantity",Aggregation.AVG,"Средний объём"));
        assertThat(editor.definition().fieldStyles()).containsEntry("quantity",new FieldStyle(220,UniDrDefinition.Alignment.CENTER,"#,##0.000"));
        assertThat(editor.definition().columns()).extracting(Column::fieldId).containsExactly("date","document","quantity","nomenclature");
        editor.removeField("quantity");assertThat(editor.definition().columns()).noneMatch(column->column.fieldId().equals("quantity"));assertThat(editor.definition().measures()).noneMatch(measure->measure.fieldId().equals("quantity"));
        editor.addField("quantity");aggregation.setValue(Aggregation.SUM);
        assertThat(editor.definition().columns()).contains(new Column("quantity","Объём"));
        assertThat(editor.definition().measures()).contains(new Measure("quantity",Aggregation.SUM,"Средний объём"));
    }
    @Test void summaryRetainsDetailsAndCountCanBeRemovedAndAddedAsAMeasure() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));
        editor.removeField("rowCount");assertThat(editor.definition().measures()).extracting(Measure::fieldId).containsExactly("quantity");
        editor.addField("rowCount");assertThat(editor.definition().measures()).isEqualTo(base.measures());
        var before=editor.definition();editor.addField("document");assertThat(editor.definition()).isEqualTo(before);
        editor.mode.setValue(Mode.DETAILS);assertThat(editor.definition().columns()).isEqualTo(base.columns());
    }
    @Test void detailMeasureWithoutASelectedColumnEditsItsActualPrintedTitle() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));
        editor.mode.setValue(Mode.DETAILS);editor.removeField("quantity");editor.aggregation("quantity").setValue(Aggregation.AVG);
        editor.columnTitle("quantity").setValue("Средний объём");
        assertThat(editor.definition().columns()).extracting(Column::fieldId).doesNotContain("quantity");
        assertThat(editor.definition().measures()).contains(new Measure("quantity",Aggregation.AVG,"Средний объём"));
    }
    @Test void countPropertiesUseTheNormalPaletteAndStyleResetRetainsTitleAndAggregation() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));
        editor.columnTitle("rowCount").setValue("Число позиций");editor.selectField("rowCount");
        all(editor,IntegerField.class).getFirst().setValue(100);
        assertThat(editor.definition().fieldStyles()).containsKey("rowCount");
        button(editor,"Сбросить оформление").click();
        assertThat(editor.definition().fieldStyles()).doesNotContainKey("rowCount");
        assertThat(editor.definition().measures().getLast()).isEqualTo(new Measure("rowCount",Aggregation.COUNT_ROWS,"Число позиций"));
    }
    @Test void pickerCancelDoesNotMutateAndBatchAdditionPreservesTitlesAndRejectsDuplicates() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));editor.mode.setValue(Mode.DETAILS);
        var before=editor.definition();var cancelled=editor.openFieldChooser(false);
        all(cancelled,Checkbox.class).getFirst().setValue(true);footerButton(cancelled,"Отмена").click();assertThat(editor.definition()).isEqualTo(before);
        var dialog=editor.openFieldChooser(false);
        all(dialog,Checkbox.class).stream().filter(input->List.of("Цех-приёмщик","Единица измерения").contains(input.getLabel())).forEach(input->input.setValue(true));
        footerButton(dialog,"Добавить выбранные").click();editor.addField("unit");
        assertThat(editor.definition().columns()).extracting(Column::fieldId).containsExactly("date","document","nomenclature","quantity","receivingWorkshop","unit");
        editor.removeField("unit");assertThat(editor.definition().columns()).noneMatch(column->column.fieldId().equals("unit"));
        editor.removeField("receivingWorkshop");editor.addField("unit");editor.addField("receivingWorkshop");
        assertThat(editor.definition().columns()).extracting(Column::fieldId).endsWith("unit","receivingWorkshop");
    }
    @Test void movingColumnsAndGroupsKeepsSettingsAndEnforcesGroupLimits() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));editor.mode.setValue(Mode.DETAILS);
        editor.columnTitle("quantity").setValue("Объём");
        editor.moveField("quantity","date");assertThat(editor.definition().columns()).extracting(Column::fieldId).containsExactly("quantity","date","document","nomenclature");
        assertThat(editor.definition().columns().getFirst().title()).isEqualTo("Объём");
        editor.moveGroup("nomenclature","receivingWorkshop");editor.addGroup("unit");editor.addGroup("unit");editor.addGroup("date");
        assertThat(editor.definition().groups()).containsExactly("nomenclature","receivingWorkshop","unit");
    }
    @Test void userAddsNumericParameterEditsCaptionDefaultAndGetsBoundCondition() {
        var editor=conditions();add(editor,"quantity",Operator.GE,true);
        var row=editor.parameterPane.getChildren().flatMap(UniDrEditorTest::tree).filter(component->component instanceof Span span && span.getText().startsWith("Количество ·")).findFirst().orElseThrow().getParent().orElseThrow();
        all(row,TextField.class).getFirst().setValue("Минимальное количество");UniDrEditorTest.<DefaultRule>combo(row,"Начальное значение").setValue(DefaultRule.FIXED);
        all(row,BigDecimalField.class).getFirst().setValue(new BigDecimal("5.25"));
        var definition=validator.requireValid(with(editor));var parameter=definition.parameters().getLast();
        assertThat(parameter.caption()).isEqualTo("Минимальное количество");assertThat(parameter.defaultValue()).isEqualTo(new Literal(ValueType.DECIMAL,List.of("5.25")));
        assertThat(definition.filters()).containsExactly(new Filter("quantity",Operator.GE,new ParameterRef(parameter.id())));
        assertThat(new UniDrRunParameterResolver(validator).resolve(new UniDrRunRequest(definition,Map.of("unit",new Literal(ValueType.REFERENCE,List.of("1"))),ZoneOffset.UTC),clock.instant())).containsEntry(parameter.id(),parameter.defaultValue());
    }
    @Test void parameterTableSwitchesPaletteWithoutChangingDeclarationsOrLosingEntityDefault() {
        var changes=new java.util.concurrent.atomic.AtomicInteger();
        var editor=new UniDrConditionsEditor(catalog.find(base.sourceId()).orElseThrow(),base.parameters(),base.filters(),references,changes::incrementAndGet);
        assertThat(all(editor.parameterPane,Div.class)).anyMatch(div->"table".equals(div.getElement().getAttribute("role")));
        assertThat(all(editor.parameterPane,TextField.class).stream().filter(field->"Подпись параметра".equals(field.getLabel()))).hasSize(1);
        button(editor.parameterPane,"Единица измерения").click();
        UniDrEditorTest.<DefaultRule>combo(editor.parameterPane,"Начальное значение").setValue(DefaultRule.FIXED);
        all(editor.parameterPane,UniDrReferencePicker.class).getFirst().setValue(new UniDrReferenceService.Option(42L,"шт"));
        var before=editor.parameters();int edits=changes.get();
        button(editor.parameterPane,"Период").click();button(editor.parameterPane,"Единица измерения").click();
        assertThat(changes.get()).isEqualTo(edits);assertThat(editor.parameters()).isEqualTo(before);
        assertThat(all(editor.parameterPane,UniDrReferencePicker.class).getFirst().getValue().id()).isEqualTo(42L);
        assertThat(button(editor.parameterPane,"Единица измерения").getElement().getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(button(editor.parameterPane,"Удалить параметр и его отборы").isEnabled()).isFalse();
    }
    @Test void referenceParameterUsesTypedSearchChoiceAndNoIdTextInput() {
        var editor=conditions();add(editor,"nomenclature",Operator.EQ,true);
        Parameter parameter=editor.parameters().getLast();
        var input=new UniDrValueEditor(field("nomenclature"),Operator.EQ,"Номенклатура",references,()->{});
        all(input,UniDrReferencePicker.class).getFirst().setValue(new UniDrReferenceService.Option(42L,"Деталь"));
        assertThat(input.value()).isEqualTo(new Literal(ValueType.REFERENCE,List.of("42")));assertThat(all(input,ComboBox.class)).isEmpty();
        assertThat(all(input,TextField.class).getFirst().getValue()).isEqualTo("Деталь");
        assertThat(parameter.fieldId()).isEqualTo("nomenclature");assertThat(validator.validate(with(editor))).isEmpty();
    }
    @Test void deletingParameterRemovesItsBindingsAndCompatibleExistingParameterCanBeReused() {
        var editor=conditions();add(editor,"transferringWorkshop",Operator.EQ,false);
        var filter=editor.filterPane.getChildren().flatMap(UniDrEditorTest::tree).filter(component->component instanceof Checkbox).map(Checkbox.class::cast).findFirst().orElseThrow();
        filter.setValue(true);UniDrEditorTest.<String>combo(editor.filterPane,"Параметр").setValue("recipient");
        assertThat(validator.validate(with(editor))).isEmpty();
        add(editor,"quantity",Operator.GE,true);String id=editor.parameters().getLast().id();
        var remove=all(editor.parameterPane,Button.class).stream().filter(button->button.getText().startsWith("Удалить параметр") && button.isEnabled()).findFirst().orElseThrow();remove.click();
        assertThat(editor.parameters()).noneMatch(parameter->parameter.id().equals(id));assertThat(editor.filters()).containsExactly(new Filter("transferringWorkshop",Operator.EQ,new ParameterRef("recipient")));
    }
    @Test void entityParameterOpensCanonicalSelectionAndRechecksChosenId() {
        var selection=mock(org.ipro.form.SelectionFormAssembler.class);
        @SuppressWarnings("unchecked") var dialog=(org.ipro.form.SelectionForm<org.ip.model.UnitOfMeasurement>)mock(org.ipro.form.SelectionForm.class);
        var accepted=new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<org.ip.model.UnitOfMeasurement>>();
        when(selection.assemble(eq(org.ip.model.UnitOfMeasurement.class),any())).thenAnswer(call->{accepted.set(call.getArgument(1));return dialog;});
        when(references.selected("unit",42L)).thenReturn(new UniDrReferenceService.Option(42L,"шт"));
        when(references.selected("unit",99L)).thenThrow(new UniDrRunException(UniDrRunException.Code.REFERENCE_UNAVAILABLE,"Недоступно"));
        var changes=new java.util.concurrent.atomic.AtomicInteger();
        var input=new UniDrValueEditor(field("unit"),Operator.EQ,"Единица измерения",references,changes::incrementAndGet,selection);
        all(input,Button.class).getFirst().click();verify(dialog).open();
        var entity=new org.ip.model.UnitOfMeasurement("шт","Штука","PCS");entity.setId(42L);accepted.get().accept(entity);
        assertThat(input.value()).isEqualTo(new Literal(ValueType.REFERENCE,List.of("42")));assertThat(changes.get()).isEqualTo(1);
        entity.setId(99L);accepted.get().accept(entity);
        assertThat(input.value().values()).containsExactly("42");assertThat(all(input,TextField.class).getFirst().isInvalid()).isTrue();assertThat(changes.get()).isEqualTo(1);
    }
    @Test void groupOrderChangesAndPresentationRetainsDetailSelection() {
        var editor=attached(new UniDrDefinitionEditor(catalog.find(base.sourceId()).orElseThrow(),base,references,()->{}));
        Component group=all(editor,Span.class).stream().filter(span->span.getText().equals("2. Номенклатура")).findFirst().orElseThrow().getParent().orElseThrow();button(group,"↑").click();
        assertThat(editor.definition().groups()).containsExactly("nomenclature","receivingWorkshop");
        editor.mode.setValue(Mode.DETAILS);assertThat(editor.definition().columns()).isEqualTo(base.columns());
        all(editor,Checkbox.class).stream().filter(input->input.getLabel().equals("Границы строк")).findFirst().orElseThrow().setValue(false);
        assertThat(editor.definition().appearance().rowBorders()).isFalse();
    }
    @Test void unavailableConfiguredReferenceIsPreservedForValidationRatherThanSilentlyCleared() {
        when(references.selected("nomenclature",99L)).thenThrow(new UniDrRunException(UniDrRunException.Code.REFERENCE_UNAVAILABLE,"Недоступно"));
        var input=new UniDrValueEditor(field("nomenclature"),Operator.EQ,"Номенклатура",references,()->{});input.value(new Literal(ValueType.REFERENCE,List.of("99")));
        assertThat(input.value().values()).containsExactly("99");assertThat(all(input,TextField.class).getFirst().isInvalid()).isTrue();
    }
    @Test void sessionAttributesRemainUsableAfterInitiatingRequestAndAreSessionOnly() {
        var session=new MockHttpSession();var attributes=new UniDrUiSessionAttributes(session);
        attributes.setAttribute("cache","value",org.springframework.web.context.request.RequestAttributes.SCOPE_SESSION);
        assertThat(attributes.getAttribute("cache",1)).isEqualTo("value");assertThat(attributes.getAttribute("cache",0)).isNull();assertThat(attributes.resolveReference("session")).isSameAs(session);
    }
    private record Fixture(UniDrView view,ArrayDeque<Runnable> tasks,UniDrQueryService query) {}
    private Fixture view() {
        when(references.selected(eq("unit"),eq(1L))).thenReturn(new UniDrReferenceService.Option(1L,"шт"));
        var access=new UniDrAccess(()->true,()->SecurityContextHolder.getContext().getAuthentication().getName());
        var query=mock(UniDrQueryService.class);var compiler=mock(UniDrReportCompiler.class);var exporter=mock(ReportCompiler.class);var tasks=new ArrayDeque<Runnable>();
        when(query.read(any(),any(),any())).thenReturn(new UniDrQueryService.Data(new ReportDataset(new QueryField[0],new ReportRow[0]),Map.of()));
        when(compiler.compile(any(),any())).thenReturn(new net.sf.jasperreports.engine.JasperPrint());
        var runs=new UniDrRunService(access,query,new ReportTaskExecutor(tasks::add),new ReportRunQuota(2),clock,compiler,exporter);
        var view=new UniDrView(runs,references,clock);return new Fixture(view,tasks,query);
    }
    private void unit(UniDrView view) { if(view.runDialog()==null)view.start();all(view.runDialog(),UniDrReferencePicker.class).stream().filter(input->input.getLabel().equals("Единица измерения")).findFirst().orElseThrow().setValue(new UniDrReferenceService.Option(1L,"шт")); }
    @Test void separateParameterTabEditsDeclarationsWithoutLosingRunValues() {
        var fixture=view();unit(fixture.view);var before=fixture.view.values();
        var tab=all(fixture.view,com.vaadin.flow.component.tabs.Tab.class).stream().filter(value->value.getElement().getTextRecursively().equals("Параметры отчёта")).findFirst().orElseThrow();
        all(fixture.view,com.vaadin.flow.component.tabs.Tabs.class).getFirst().setSelectedTab(tab);
        var caption=all(fixture.view,TextField.class).stream().filter(value->"Подпись параметра".equals(value.getLabel()) && value.getValue().equals("Период")).findFirst().orElseThrow();
        caption.setValue("Даты накладных");
        assertThat(fixture.view.definition().parameters().getFirst().caption()).isEqualTo("Даты накладных");assertThat(fixture.view.values()).isEqualTo(before);
    }
    @Test void validationPreventsQueueingUntilRequiredUnitIsSelected() {
        var fixture=view();fixture.view.start();fixture.view.runDialog().start();assertThat(fixture.tasks).isEmpty();assertThat(all(fixture.view.runDialog(),Div.class)).anyMatch(div->div.getText().contains("Заполните «Единица измерения»"));
        unit(fixture.view);fixture.view.runDialog().start();assertThat(fixture.tasks).hasSize(1);
    }
    @Test void changedSettingsCancelPendingPublicationAndSuccessfulRunEnablesExports() {
        var fixture=view();unit(fixture.view);fixture.view.runDialog().start();var cancelled=fixture.view.runDialog();fixture.view.markChanged(true);fixture.tasks.removeFirst().run();login();cancelled.poll();
        assertThat(cancelled.preview()).isNull();
        fixture.view.start();fixture.view.runDialog().start();fixture.tasks.removeFirst().run();login();fixture.view.poll();
        var preview=fixture.view.runDialog().preview();assertThat(preview.isOpened()).isTrue();assertThat(fixture.view.runDialog().isOpened()).isFalse();
        assertThat(all(preview,Anchor.class)).hasSize(2);assertThat(all(fixture.view,IFrame.class)).isEmpty();
        fixture.view.markChanged(true);assertThat(preview.isOpened()).isFalse();
    }
    @Test void closedEditorDiscardsLateRunAndResetRestoresInitialDefinition() {
        var fixture=view();unit(fixture.view);fixture.view.runDialog().start();var dialog=fixture.view.runDialog();fixture.view.close();fixture.tasks.removeFirst().run();login();dialog.poll();assertThat(dialog.preview()).isNull();
        var other=view();all(other.view,TextField.class).stream().filter(input->"Название отчёта".equals(input.getLabel())).findFirst().orElseThrow().setValue("Другой отчёт");other.view.reset();assertThat(other.view.definition()).isEqualTo(base);
    }
    @Test void runValuesDoNotDirtyDraftAndDefaultsOnlyResetRuntimeInputs() {
        var fixture=view();var definition=fixture.view.definition();unit(fixture.view);
        assertThat(fixture.view.getElement().hasAttribute("data-dirty")).isFalse();
        fixture.view.runDialog().close();fixture.view.start();
        assertThat(fixture.view.runDialog().values().get("unit").values()).containsExactly("1");
        footerButton(fixture.view.runDialog(),"Значения по умолчанию").click();
        assertThat(fixture.view.runDialog().values().get("unit").values()).isEmpty();
        assertThat(fixture.view.definition()).isEqualTo(definition);
    }
    @Test void disabledRouteShowsExplanationAndHasNoRunControls() {
        var view=new UniDrView(null,null,clock);assertThat(all(view,Button.class)).isEmpty();assertThat(all(view,Paragraph.class)).anyMatch(text->text.getText().equals("Пилот UniDR выключен"));
    }
}
