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
