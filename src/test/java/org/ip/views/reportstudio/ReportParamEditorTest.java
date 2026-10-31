package org.ipro.report.ui.params;

import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QuerySource;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReportParamEditorTest {

    @Test
    void addsValidFormScalarDeclarationToDefinition() {
        ReportParamEditor editor = new ReportParamEditor();
        QueryDefinition definition = definition();
        editor.setDefinition(definition);
        AtomicInteger changes = new AtomicInteger();
        editor.setChangeListener(changes::incrementAndGet);

        editor.addParam();

        assertThat(editor.getDefinition().parameters()).hasSize(1);
        assertThat(changes).hasValue(1);
        assertThat(editor.getDefinition().parameters().getFirst())
                .satisfies(param -> {
                    assertThat(param.name()).isEqualTo("param1");
                    assertThat(param.kind()).isEqualTo(QueryParameterKind.SCALAR);
                    assertThat(param.valueSource()).isEqualTo(QueryValueSource.FORM);
                    assertThat(param.showOnForm()).isTrue();
                });
        assertThat(definition.parameters()).as("исходное описание не мутируется").isEmpty();
    }

    @Test
    void addingParamShowsPaletteWithItsValues() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        QueryParameter param = editor.getDefinition().parameters().getFirst();
        assertThat(editor.selectedParam()).isEqualTo(param);
        assertThat(editor.paletteVisible()).isTrue();
        assertThat(editor.nameField().getValue()).isEqualTo("param1");
        assertThat(editor.captionField().getValue()).isEqualTo("Параметр 1");
        assertThat(editor.kindField().getValue()).isEqualTo(QueryParameterKind.SCALAR);
        assertThat(editor.valueSourceField().getValue()).isEqualTo(QueryValueSource.FORM);
        assertThat(editor.requiredField().getValue()).isFalse();
        assertThat(editor.showOnFormField().getValue()).isTrue();
    }

    @Test
    void paletteAppliesPropertiesInstantlyToSelectedParam() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.nameField().setValue("reportDate");
        editor.captionField().setValue("Дата отчёта");
        editor.requiredField().setValue(true);
        editor.showOnFormField().setValue(false);

        assertThat(editor.getDefinition().parameters().getFirst()).satisfies(param -> {
            assertThat(param.name()).isEqualTo("reportDate");
            assertThat(param.caption()).isEqualTo("Дата отчёта");
            assertThat(param.required()).isTrue();
            assertThat(param.showOnForm()).isFalse();
        });
        assertThat(editor.nameField().getValue()).isEqualTo("reportDate");
    }

    @Test
    void invalidBindingNameIsRejectedWithoutLosingTheDeclaration() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.nameField().setValue("report date");

        assertThat(editor.nameField().isInvalid()).isTrue();
        assertThat(editor.getDefinition().parameters().getFirst().name()).isEqualTo("param1");

        editor.nameField().setValue("reportDate");

        assertThat(editor.nameField().isInvalid()).isFalse();
        assertThat(editor.getDefinition().parameters().getFirst().name()).isEqualTo("reportDate");
    }

    @Test
    void entityKindEnablesEntityClassAndClearsItWhenSwitchingBack() {
        ReportParamEditor editor = newEditor();
        editor.setEntityOptions(List.of(
                new QueryMetadataCatalogService.EntityOption("Item", "org.ipro.test.Item", "Товары")));
        editor.addParam();

        editor.kindField().setValue(QueryParameterKind.ENTITY);
        assertThat(editor.entityClassField().isEnabled()).isTrue();

        editor.kindField().setValue(QueryParameterKind.SCALAR);
        assertThat(editor.entityClassField().isEnabled()).isFalse();
        assertThat(editor.entityClassField().getValue()).isNull();
        assertThat(editor.getDefinition().parameters().getFirst().entityClass()).isNull();
    }

    @Test
    void entityKindAppliesSelectedEntityClassToModel() {
        ReportParamEditor editor = newEditor();
        editor.setEntityOptions(List.of(
                new QueryMetadataCatalogService.EntityOption("Item", "org.ipro.test.Item", "Товары")));
        editor.addParam();

        editor.kindField().setValue(QueryParameterKind.ENTITY_LIST);
        editor.entityClassField().setValue(
                new QueryMetadataCatalogService.EntityOption("Item", "org.ipro.test.Item", "Товары"));

        assertThat(editor.getDefinition().parameters().getFirst()).satisfies(param -> {
            assertThat(param.kind()).isEqualTo(QueryParameterKind.ENTITY_LIST);
            assertThat(param.entityClass()).isEqualTo("org.ipro.test.Item");
        });
    }

    @Test
    void sourceDependenciesEnableOnlyRelevantControls() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.valueSourceField().setValue(QueryValueSource.FIXED);
        assertThat(editor.defaultValueField().isEnabled()).isTrue();
        assertThat(editor.computedField().isEnabled()).isFalse();
        assertThat(editor.requiredField().isEnabled()).isFalse();

        editor.valueSourceField().setValue(QueryValueSource.COMPUTED);
        assertThat(editor.computedField().isEnabled()).isTrue();
        assertThat(editor.defaultValueField().isEnabled()).isFalse();
        assertThat(editor.requiredField().isEnabled()).isFalse();

        editor.valueSourceField().setValue(QueryValueSource.FORM);
        assertThat(editor.requiredField().isEnabled()).isTrue();
        assertThat(editor.defaultValueField().isEnabled()).isFalse();
        assertThat(editor.computedField().isEnabled()).isFalse();
    }

    @Test
    void defaultAndComputedValuesApplyToModelAndClearOnSourceSwitch() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.valueSourceField().setValue(QueryValueSource.FIXED);
        editor.defaultValueField().setValue("{\"period\":\"MONTH\"}");
        assertThat(editor.getDefinition().parameters().getFirst().defaultValue())
                .isEqualTo("{\"period\":\"MONTH\"}");

        editor.valueSourceField().setValue(QueryValueSource.COMPUTED);
        editor.computedField().setValue(QueryComputedValue.NOW);
        assertThat(editor.getDefinition().parameters().getFirst()).satisfies(param -> {
            assertThat(param.computed()).isEqualTo(QueryComputedValue.NOW);
            assertThat(param.defaultValue()).isNull();
        });

        editor.valueSourceField().setValue(QueryValueSource.FORM);
        assertThat(editor.getDefinition().parameters().getFirst().computed())
                .isEqualTo(QueryComputedValue.NONE);
    }

    @Test
    void removingParameterHidesPaletteAndClearsSelection() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.removeParam();

        assertThat(editor.getDefinition().parameters()).isEmpty();
        assertThat(editor.selectedParam()).isNull();
        assertThat(editor.paletteVisible()).isFalse();
        assertThat(editor.nameField().getValue()).isEmpty();
    }

    private static QueryDefinition definition() {
        return new QueryDefinition("", QuerySource.MANUAL, null, null, List.of());
    }

    private static ReportParamEditor newEditor() {
        ReportParamEditor editor = new ReportParamEditor();
        editor.setDefinition(definition());
        return editor;
    }
}
