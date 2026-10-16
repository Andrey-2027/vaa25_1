package org.ipro.reportstudio.query.editor;

import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportQueryEditorTest {

    @Test void inlineParameterRowProvidesFullWidthInputAndUpdatesTypedValueWithoutSelection() {
        var template=new ReportTemplate();template.setJpql("select n.code from Nomenclature n where n.code=:par1");
        var editor=newEditor(template);var param=editor.testParams().getFirst();
        var row=(com.vaadin.flow.component.Component)org.springframework.test.util.ReflectionTestUtils.invokeMethod(editor,"parameterRow",param);
        var value=(com.vaadin.flow.component.orderedlayout.VerticalLayout)row.getChildren().filter(com.vaadin.flow.component.orderedlayout.VerticalLayout.class::isInstance).findFirst().orElseThrow();
        var input=(com.vaadin.flow.component.textfield.TextField)value.getChildren().findFirst().orElseThrow();
        assertThat(input.getWidth()).isEqualTo("100%");input.setValue("Длинное значение параметра");
        assertThat(param.value()).isEqualTo("Длинное значение параметра");
        @SuppressWarnings("unchecked") var type=(com.vaadin.flow.component.combobox.ComboBox<FieldType>)row.getChildren().filter(com.vaadin.flow.component.combobox.ComboBox.class::isInstance).findFirst().orElseThrow();
        type.setValue(FieldType.DECIMAL);
        var number=(com.vaadin.flow.component.textfield.BigDecimalField)value.getChildren().findFirst().orElseThrow();
        number.setValue(new java.math.BigDecimal("123.456"));
        assertThat(number.getWidth()).isEqualTo("100%");assertThat(param.value()).isEqualTo(new java.math.BigDecimal("123.456"));
        var panel=(com.vaadin.flow.component.Component)org.springframework.test.util.ReflectionTestUtils.getField(editor,"parameterPanel");
        var text=(com.vaadin.flow.component.Component)org.springframework.test.util.ReflectionTestUtils.getField(editor,"jpql");
        assertThat(panel.getParent()).isEqualTo(text.getParent());
    }

    @Test void typingParameterNameReplacesPrefixesAndKeepsOtherTestValues() {
        var template=new ReportTemplate();template.setJpql("select a.code from Nomenclature a where a.code=:kept");
        var editor=newEditor(template);editor.testParams().getFirst().setValue("X");
        for(String name:java.util.List.of("p","pa","par","par1"))
            editor.applyJpqlText("select a.code from Nomenclature a where a.code=:kept and a.name=:"+name);
        assertThat(editor.testParams()).extracting(QueryTestParam::name).containsExactly("kept","par1");
        assertThat(editor.testParams().getFirst().value()).isEqualTo("X");
        editor.transferTestParamsToTemplate();
        assertThat(template.getParams()).extracting(org.ipro.reportstudio.dom.ReportParam::getName).containsExactly("kept","par1");
        editor.applyJpqlText("select a.code from Nomenclature a where a.code=:kept");
        assertThat(editor.testParams()).extracting(QueryTestParam::name).containsExactly("kept");
        assertThat(template.getParams()).hasSize(2); // Removing a reference does not delete saved declarations.
    }

    private ReportQueryEditor newEditor(ReportTemplate template) {
        ReportQueryEditor editor = new ReportQueryEditor(
                mock(QueryEditorAnalysisService.class),
                mock(QueryMetadataCatalogService.class),
                mock(ReportPreviewService.class),
                mock(EntityLookup.class),
                mock(SelectionFormAssembler.class),
                mock(ReportQueryAssemblyService.class));
        editor.setTemplate(template);
        return editor;
    }

    @Test void cancelRestoresRemovedParameterAndItsCaption() {
        var template = new ReportTemplate(); template.setJpql("select 1");
        var parameter = new org.ipro.reportstudio.dom.ReportParam(); parameter.setName("date"); parameter.setCaption("Исходная подпись"); template.addParam(parameter);
        var editor = newEditor(template); var restore = editor.captureQuerySnapshot();
        parameter.setCaption("Изменённая подпись"); template.getParams().clear();
        var added = new org.ipro.reportstudio.dom.ReportParam(); added.setName("newParameter"); template.addParam(added);
        restore.run();
        assertThat(template.getParams()).containsExactly(parameter);
        assertThat(parameter.getCaption()).isEqualTo("Исходная подпись");
    }

    @Test void invalidApplicationTextRemainsEditableAndDoesNotCompileOldPackage() {
        var catalog = mock(org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.class);
        var entity = new org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.Entity("Example", Example.class,
            java.util.List.of(new org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.Field("code", "Код", String.class, false)), java.util.List.of());
        org.mockito.Mockito.when(catalog.roots()).thenReturn(java.util.List.of(entity)); org.mockito.Mockito.when(catalog.root("Example")).thenReturn(entity);
        var recipes = new org.ipro.reportstudio.query.register.RegisterSliceReportTemplates(mock(jakarta.persistence.EntityManagerFactory.class),
            mock(org.ipro.register.RegisterRegistry.class), catalog, mock(org.ipro.reportstudio.query.register.RegisterSliceCteGenerator.class));
        var template = new ReportTemplate(); template.setJpql("select Представление(e) as label from Example e");
        var editor = newEditor(template); editor.setQueryConstructorCatalog(catalog); editor.setRegisterSliceRecipes(recipes);
        assertThat(editor.getJpql()).doesNotContain("Представление"); assertThat(template.getQuerySource()).isEqualTo(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        String previousPackage = template.getVisualQueryJson();
        var input = (com.vaadin.flow.component.textfield.TextArea) org.springframework.test.util.ReflectionTestUtils.getField(editor, "sliceDescription");
        input.setValue("select lower(Представление(e)) as label from Example e");
        org.springframework.test.util.ReflectionTestUtils.setField(editor, "shortQueryDirty", true);
        org.assertj.core.api.Assertions.assertThatThrownBy(editor::getJpql).hasMessageContaining("Строка");
        org.assertj.core.api.Assertions.assertThatThrownBy(editor::getJpql).hasMessageContaining("Строка");
        assertThat(input.getValue()).contains("lower(Представление(e))");
        assertThat(template.getVisualQueryJson()).isEqualTo(previousPackage);
    }
    static class Example { }

    @Test void legacyGenericVisualDeclarationKeepsItsExistingEntityParameter() {
        var catalog = mock(org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.class);
        var recipes = new org.ipro.reportstudio.query.register.RegisterSliceReportTemplates(mock(jakarta.persistence.EntityManagerFactory.class),
            mock(org.ipro.register.RegisterRegistry.class), catalog, mock(org.ipro.reportstudio.query.register.RegisterSliceCteGenerator.class));
        var main = new org.ipro.reportstudio.query.VisualQueryDefinition(3, "Example", "e", java.util.List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("e.code", "code")), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.List.of(), null, null,
            java.util.List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("journal", "TEXT", true, null)), java.util.List.of());
        var template = new ReportTemplate(); template.setJpql("select e.code from Example e where e.journal = :journal");
        template.setVisualQueryJson(new org.ipro.reportstudio.query.VisualQueryPackageJsonCodec().write(
            new org.ipro.reportstudio.query.VisualQueryPackage(java.util.List.of(), main)));
        var parameter = new org.ipro.reportstudio.dom.ReportParam(); parameter.setName("journal"); parameter.setKind(ReportParamKind.ENTITY);
        parameter.setEntityClass("org.ip.model.Journal"); template.addParam(parameter);
        var editor = newEditor(template); editor.setQueryConstructorCatalog(catalog); editor.setRegisterSliceRecipes(recipes);
        assertThat(editor.testParams()).singleElement().satisfies(value -> {
            assertThat(value.type()).isEqualTo(FieldType.ENTITY_REFERENCE);
            assertThat(value.className()).isEqualTo("org.ip.model.Journal");
        });
        assertThat(template.getParams()).containsExactly(parameter);
        assertThat(parameter.getKind()).isEqualTo(ReportParamKind.ENTITY);
    }

    @Test
    void transferCreatesMissingScalarParamFromTestValue() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a where a.name = :parName");
        ReportQueryEditor editor = newEditor(template);

        editor.transferTestParamsToTemplate();

        assertThat(template.getParams()).singleElement().satisfies(param -> {
            assertThat(param.getName()).isEqualTo("parName");
            assertThat(param.getKind()).isEqualTo(ReportParamKind.SCALAR);
            assertThat(param.getValueSource()).isEqualTo(ReportParamSource.FORM);
            assertThat(param.isShowOnForm()).isTrue();
            assertThat(param.isRequired()).isFalse();
            assertThat(param.getPosition()).isZero();
        });
    }

    @Test void sliceApplicationDeclaresDateAndReopeningKeepsItsType() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a");
        ReportQueryEditor editor = newEditor(template);
        var main = new org.ipro.reportstudio.query.VisualQueryDefinition(3, "rs1_latest", "s",
            java.util.List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("s.period", "period")),
            java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), null, null,
            java.util.List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.Parameter("asOf", "DATETIME", true, null)), java.util.List.of());
        var query = new ReportTemplate();
        query.setVisualQueryJson(new org.ipro.reportstudio.query.VisualQueryPackageJsonCodec().write(
            new org.ipro.reportstudio.query.VisualQueryPackage(java.util.List.of(), main)));
        query.setJpql("select s.period from NomenclaturePrice s where s.period <= :asOf");
        var parameter = new org.ipro.reportstudio.dom.ReportParam(); parameter.setName("asOf");
        parameter.setRequired(true); parameter.setShowOnForm(true); query.addParam(parameter);
        var moment = java.time.LocalDateTime.of(2026, 10, 7, 12, 0).withNano(123000000);
        editor.applySliceResult(new RegisterSliceQueryDialog.Result(query, moment));
        assertThat(template.getQuerySource()).isEqualTo(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        assertThat(template.getParams()).singleElement().satisfies(param -> assertThat(param.isRequired()).isTrue());
        assertThat(editor.testParams()).singleElement().satisfies(param -> {
            assertThat(param.type()).isEqualTo(FieldType.DATETIME); assertThat(param.value()).isEqualTo(moment);
        });
        var reopened = newEditor(template);
        assertThat(reopened.testParams()).singleElement().satisfies(param -> {
            assertThat(param.type()).isEqualTo(FieldType.DATETIME); assertThat(param.value()).isNull();
        });
    }

    @Test void sliceRejectsIncompatibleExistingParameterBeforeChangingTheQuery() {
        var template = new ReportTemplate(); template.setJpql("original");
        var existing = new org.ipro.reportstudio.dom.ReportParam(); existing.setName("asOf");
        existing.setKind(ReportParamKind.ENTITY); template.addParam(existing);
        var editor = newEditor(template);
        var query = new ReportTemplate(); var date = new org.ipro.reportstudio.dom.ReportParam(); date.setName("asOf"); query.addParam(date);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> editor.applySliceResult(new RegisterSliceQueryDialog.Result(query,
            java.time.LocalDateTime.of(2026, 10, 7, 12, 0)))).hasMessageContaining("другим назначением");
        assertThat(editor.getJpql()).isEqualTo("original");
        assertThat(template.getVisualQueryJson()).isNull();
    }

    @Test void sliceScalarValuesRetainNanosecondsAndNumericTypes() {
        assertThat(RegisterSliceQueryDialog.scalar(java.time.LocalDateTime.class, "2026-10-07 12:00:00.123456789"))
            .isEqualTo(java.time.LocalDateTime.of(2026, 10, 7, 12, 0).withNano(123456789));
        assertThat(RegisterSliceQueryDialog.scalar(Long.class, "2147483648")).isEqualTo(2147483648L);
        assertThat(RegisterSliceQueryDialog.scalar(java.math.BigDecimal.class, "12,34")).isEqualTo(new java.math.BigDecimal("12.34"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegisterSliceQueryDialog.scalar(Integer.class, "2147483648"))
            .hasMessageContaining("Integer");
    }

    @Test
    void transferIsIdempotentAndKeepsExistingDeclarations() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a where a.name = :parName");
        ReportQueryEditor editor = newEditor(template);

        editor.transferTestParamsToTemplate();
        editor.transferTestParamsToTemplate();

        assertThat(template.getParams()).hasSize(1);
    }

    @Test
    void entityTestParamTransfersAsEntityWithClassName() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a where a.journal = :journal");
        ReportQueryEditor editor = newEditor(template);
        QueryTestParam journal = editor.testParams().get(0);
        journal.setType(FieldType.ENTITY_REFERENCE);
        journal.setClassName("org.ip.model.Journal");

        editor.transferTestParamsToTemplate();

        assertThat(template.getParams()).singleElement().satisfies(param -> {
            assertThat(param.getName()).isEqualTo("journal");
            assertThat(param.getKind()).isEqualTo(ReportParamKind.ENTITY);
            assertThat(param.getEntityClass()).isEqualTo("org.ip.model.Journal");
        });
    }

    @Test
    void typingInEditorUpdatesTemplateAndFiresChangeListener() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select 1");
        ReportQueryEditor editor = newEditor(template);
        ReportTemplate[] changed = new ReportTemplate[1];
        editor.setChangeListener(value -> changed[0] = value);

        editor.applyJpqlText("select a.code from Nomenclature a");

        assertThat(template.getJpql()).isEqualTo("select a.code from Nomenclature a");
        assertThat(changed[0]).isSameAs(template);
        assertThat(editor.getJpql()).isEqualTo("select a.code from Nomenclature a");
    }

    @Test
    void constructorResultFormatsTextAndImportsWhereBindingsIntoTestParams() {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select a.code from Nomenclature a");
        ReportQueryEditor editor = newEditor(template);

        var definition = new org.ipro.reportstudio.query.VisualQueryDefinition("Nomenclature", "nomenclature",
                java.util.List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("code", "code")));
        String rawJpql = "select nomenclature.code as code from Nomenclature nomenclature "
                + "where nomenclature.code = :visualFilter_1";
        editor.applyConstructorResult(new org.ipro.reportstudio.query.constructor.JpqlQueryBuilderDialog.Result(
                definition, rawJpql, java.util.Map.of("visualFilter_1", "S-1")));

        // В редактор вставлен форматированный текст (как в окне «Запрос» конструктора)
        assertThat(editor.getJpql()).isNotEqualTo(rawJpql);
        assertThat(editor.getJpql()).contains("nomenclature.code = :visualFilter_1");
        // Bindings конструктора стали тестовыми значениями параметров — иначе
        // «Проверить»/«Выполнить» не сможет забиндить :visualFilter_*.
        assertThat(editor.testParams()).singleElement().satisfies(param -> {
            assertThat(param.name()).isEqualTo("visualFilter_1");
            assertThat(param.value()).isEqualTo("S-1");
        });
        assertThat(template.getQuerySource())
                .isEqualTo(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL);
    }
}
