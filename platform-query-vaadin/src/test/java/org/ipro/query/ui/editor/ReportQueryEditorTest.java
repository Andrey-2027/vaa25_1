package org.ipro.query.ui.editor;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.ui.fixture.EmptyEntitySelection;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.crud.EntityLookup;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QuerySource;
import org.ipro.query.model.QueryValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportQueryEditorTest {

    /** M1.3: имя сущности берётся из модульной фикстуры, а не из прикладной модели. */
    private static final String ENTITY_CLASS = org.ipro.query.ui.fixture.DemoDocument.class.getName();

    private static QueryDraft draft(String jpql) {
        return new QueryDraft(jpql, QuerySource.MANUAL, null, null, List.of());
    }

    @Test void inlineParameterRowProvidesFullWidthInputAndUpdatesTypedValueWithoutSelection() {
        var editor=newEditor(draft("select n.code from Nomenclature n where n.code=:par1"));var param=editor.testParams().getFirst();
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
        var editor=newEditor(draft("select a.code from Nomenclature a where a.code=:kept"));editor.testParams().getFirst().setValue("X");
        for(String name:List.of("p","pa","par","par1"))
            editor.applyJpqlText("select a.code from Nomenclature a where a.code=:kept and a.name=:"+name);
        assertThat(editor.testParams()).extracting(QueryTestParam::name).containsExactly("kept","par1");
        assertThat(editor.testParams().getFirst().value()).isEqualTo("X");
        editor.transferTestParams();
        assertThat(editor.draft().parameters()).extracting(QueryParameter::name).containsExactly("kept","par1");
        editor.applyJpqlText("select a.code from Nomenclature a where a.code=:kept");
        assertThat(editor.testParams()).extracting(QueryTestParam::name).containsExactly("kept");
        assertThat(editor.draft().parameters()).hasSize(2); // Removing a reference does not delete saved declarations.
    }

    private ReportQueryEditor newEditor(QueryDraft draft) {
        ReportQueryEditor editor = new ReportQueryEditor(
                mock(QueryEditorAnalysisService.class),
                mock(QueryMetadataCatalogService.class),
                mock(ReportPreviewService.class),
                mock(EntityLookup.class),
                new EmptyEntitySelection(),
                mock(ReportQueryAssemblyService.class));
        editor.open(draft);
        return editor;
    }

    @Test void invalidApplicationTextRemainsEditableAndDoesNotCompileOldPackage() {
        var catalog = mock(org.ipro.query.QueryBuilderMetadataCatalog.class);
        var entity = new org.ipro.query.QueryBuilderMetadataCatalog.Entity("Example", Example.class,
            List.of(new org.ipro.query.QueryBuilderMetadataCatalog.Field("code", "Код", String.class, false)), List.of());
        org.mockito.Mockito.when(catalog.roots()).thenReturn(List.of(entity)); org.mockito.Mockito.when(catalog.root("Example")).thenReturn(entity);
        var recipes = new org.ipro.query.register.RegisterSliceReportTemplates(mock(jakarta.persistence.EntityManagerFactory.class),
            mock(org.ipro.register.RegisterRegistry.class), catalog, mock(org.ipro.query.register.RegisterSliceCteGenerator.class));
        var editor = newEditor(draft("select Представление(e) as label from Example e"));
        editor.setQueryConstructorCatalog(catalog); editor.setRegisterSliceRecipes(recipes);
        assertThat(editor.getJpql()).doesNotContain("Представление"); assertThat(editor.draft().source()).isEqualTo(QuerySource.VISUAL);
        String previousPackage = editor.draft().visualQueryJson();
        var input = (com.vaadin.flow.component.textfield.TextArea) org.springframework.test.util.ReflectionTestUtils.getField(editor, "sliceDescription");
        input.setValue("select lower(Представление(e)) as label from Example e");
        org.springframework.test.util.ReflectionTestUtils.setField(editor, "shortQueryDirty", true);
        org.assertj.core.api.Assertions.assertThatThrownBy(editor::getJpql).hasMessageContaining("Строка");
        org.assertj.core.api.Assertions.assertThatThrownBy(editor::getJpql).hasMessageContaining("Строка");
        assertThat(input.getValue()).contains("lower(Представление(e))");
        assertThat(editor.draft().visualQueryJson()).isEqualTo(previousPackage);
    }
    static class Example { }

    @Test void legacyGenericVisualDeclarationKeepsItsExistingEntityParameter() {
        var catalog = mock(org.ipro.query.QueryBuilderMetadataCatalog.class);
        var recipes = new org.ipro.query.register.RegisterSliceReportTemplates(mock(jakarta.persistence.EntityManagerFactory.class),
            mock(org.ipro.register.RegisterRegistry.class), catalog, mock(org.ipro.query.register.RegisterSliceCteGenerator.class));
        var main = new org.ipro.query.VisualQueryDefinition(3, "Example", "e", List.of(new org.ipro.query.VisualQueryDefinition.SelectField("e.code", "code")), List.of(),
            List.of(), List.of(), List.of(), null, null,
            List.of(new org.ipro.query.VisualQueryDefinition.Parameter("journal", "TEXT", true, null)), List.of());
        String visualJson = new org.ipro.query.VisualQueryPackageJsonCodec().write(
            new org.ipro.query.VisualQueryPackage(List.of(), main));
        var journal = new QueryParameter("journal", "journal", "journal", QueryParameterKind.ENTITY, QueryScalarType.UNRESOLVED,
            ENTITY_CLASS, QueryValueSource.FORM, false, true, null, QueryComputedValue.NONE, null, 0);
        var editor = newEditor(new QueryDraft("select e.code from Example e where e.journal = :journal",
            QuerySource.MANUAL, visualJson, null, List.of(journal)));
        editor.setQueryConstructorCatalog(catalog); editor.setRegisterSliceRecipes(recipes);
        assertThat(editor.testParams()).singleElement().satisfies(value -> {
            assertThat(value.type()).isEqualTo(FieldType.ENTITY_REFERENCE);
            assertThat(value.className()).isEqualTo(ENTITY_CLASS);
        });
        assertThat(editor.draft().parameter("journal")).get().satisfies(param -> {
            assertThat(param.kind()).isEqualTo(QueryParameterKind.ENTITY);
            assertThat(param.entityClass()).isEqualTo(ENTITY_CLASS);
            assertThat(param.required()).isTrue();
            assertThat(param.showOnForm()).isTrue();
        });
    }

    @Test
    void transferCreatesMissingScalarParamFromTestValue() {
        ReportQueryEditor editor = newEditor(draft("select a.code from Nomenclature a where a.name = :parName"));

        editor.transferTestParams();

        assertThat(editor.draft().parameters()).singleElement().satisfies(param -> {
            assertThat(param.name()).isEqualTo("parName");
            assertThat(param.kind()).isEqualTo(QueryParameterKind.SCALAR);
            assertThat(param.valueSource()).isEqualTo(QueryValueSource.FORM);
            assertThat(param.showOnForm()).isTrue();
            assertThat(param.required()).isFalse();
            assertThat(param.position()).isZero();
        });
    }

    @Test void sliceApplicationDeclaresDateAndReopeningKeepsItsType() {
        ReportQueryEditor editor = newEditor(draft("select a.code from Nomenclature a"));
        var main = new org.ipro.query.VisualQueryDefinition(3, "rs1_latest", "s",
            List.of(new org.ipro.query.VisualQueryDefinition.SelectField("s.period", "period")),
            List.of(), List.of(), List.of(), List.of(), null, null,
            List.of(new org.ipro.query.VisualQueryDefinition.Parameter("asOf", "DATETIME", true, null)), List.of());
        var query = new QueryDefinition("select s.period from NomenclaturePrice s where s.period <= :asOf",
            QuerySource.VISUAL,
            new org.ipro.query.VisualQueryPackageJsonCodec().write(
                new org.ipro.query.VisualQueryPackage(List.of(), main)),
            null, List.of(QueryParameter.required("asOf")));
        var moment = java.time.LocalDateTime.of(2026, 10, 7, 12, 0).withNano(123000000);
        editor.applySliceResult(new RegisterSliceQueryDialog.Result(query, moment));
        assertThat(editor.draft().source()).isEqualTo(QuerySource.VISUAL);
        assertThat(editor.draft().parameters()).singleElement().satisfies(param -> assertThat(param.required()).isTrue());
        assertThat(editor.testParams()).singleElement().satisfies(param -> {
            assertThat(param.type()).isEqualTo(FieldType.DATETIME); assertThat(param.value()).isEqualTo(moment);
        });
        var reopened = newEditor(editor.draft());
        assertThat(reopened.testParams()).singleElement().satisfies(param -> {
            assertThat(param.type()).isEqualTo(FieldType.DATETIME); assertThat(param.value()).isNull();
        });
    }

    @Test void sliceRejectsIncompatibleExistingParameterBeforeChangingTheQuery() {
        var existing = new QueryParameter("asOf", "asOf", "asOf", QueryParameterKind.ENTITY, QueryScalarType.UNRESOLVED,
            ENTITY_CLASS, QueryValueSource.FORM, false, true, null, QueryComputedValue.NONE, null, 0);
        var editor = newEditor(new QueryDraft("original", QuerySource.MANUAL, null, null, List.of(existing)));
        var query = new QueryDefinition("select 1", QuerySource.MANUAL, null, null,
            List.of(new QueryParameter("asOf")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> editor.applySliceResult(new RegisterSliceQueryDialog.Result(query,
            java.time.LocalDateTime.of(2026, 10, 7, 12, 0)))).hasMessageContaining("другим назначением");
        assertThat(editor.getJpql()).isEqualTo("original");
        assertThat(editor.draft().visualQueryJson()).isNull();
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
        ReportQueryEditor editor = newEditor(draft("select a.code from Nomenclature a where a.name = :parName"));

        editor.transferTestParams();
        editor.transferTestParams();

        assertThat(editor.draft().parameters()).hasSize(1);
    }

    @Test
    void entityTestParamTransfersAsEntityWithClassName() {
        ReportQueryEditor editor = newEditor(draft("select a.code from Nomenclature a where a.journal = :journal"));
        QueryTestParam journal = editor.testParams().get(0);
        journal.setType(FieldType.ENTITY_REFERENCE);
        journal.setClassName(ENTITY_CLASS);

        editor.transferTestParams();

        assertThat(editor.draft().parameters()).singleElement().satisfies(param -> {
            assertThat(param.name()).isEqualTo("journal");
            assertThat(param.kind()).isEqualTo(QueryParameterKind.ENTITY);
            assertThat(param.entityClass()).isEqualTo(ENTITY_CLASS);
        });
    }

    @Test
    void typingInEditorUpdatesDraftAndFiresChangeListener() {
        ReportQueryEditor editor = newEditor(draft("select 1"));
        QueryDraft[] changed = new QueryDraft[1];
        editor.setChangeListener(value -> changed[0] = value);

        editor.applyJpqlText("select a.code from Nomenclature a");

        assertThat(editor.draft().jpql()).isEqualTo("select a.code from Nomenclature a");
        assertThat(changed[0]).isEqualTo(editor.draft());
        assertThat(editor.getJpql()).isEqualTo("select a.code from Nomenclature a");
    }

    @Test
    void constructorResultFormatsTextAndImportsWhereBindingsIntoTestParams() {
        ReportQueryEditor editor = newEditor(draft("select a.code from Nomenclature a"));

        var definition = new org.ipro.query.VisualQueryDefinition("Nomenclature", "nomenclature",
                List.of(new org.ipro.query.VisualQueryDefinition.SelectField("code", "code")));
        String rawJpql = "select nomenclature.code as code from Nomenclature nomenclature "
                + "where nomenclature.code = :visualFilter_1";
        editor.applyConstructorResult(new org.ipro.query.ui.constructor.JpqlQueryBuilderDialog.Result(
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
        assertThat(editor.draft().source()).isEqualTo(QuerySource.MANUAL);
    }
}
