package org.ip.views.reportstudio;

import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReportParamEditorTest {

    @Test
    void addsValidFormScalarDeclarationToTemplate() {
        ReportParamEditor editor = new ReportParamEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        AtomicInteger changes = new AtomicInteger();
        editor.setChangeListener(changes::incrementAndGet);

        editor.addParam();

        assertThat(editor.getTemplate()).isSameAs(template);
        assertThat(template.getParams()).hasSize(1);
        assertThat(changes).hasValue(1);
        assertThat(template.getParams().getFirst())
                .satisfies(param -> {
                    assertThat(param.getName()).isEqualTo("param1");
                    assertThat(param.getKind()).isEqualTo(ReportParamKind.SCALAR);
                    assertThat(param.getValueSource()).isEqualTo(ReportParamSource.FORM);
                    assertThat(param.isShowOnForm()).isTrue();
                });
    }

    @Test
    void addingParamShowsPaletteWithItsValues() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        ReportParam param = template().getParams().getFirst();
        assertThat(editor.selectedParam()).isSameAs(param);
        assertThat(editor.paletteVisible()).isTrue();
        assertThat(editor.nameField().getValue()).isEqualTo("param1");
        assertThat(editor.captionField().getValue()).isEqualTo("Параметр 1");
        assertThat(editor.kindField().getValue()).isEqualTo(ReportParamKind.SCALAR);
        assertThat(editor.valueSourceField().getValue()).isEqualTo(ReportParamSource.FORM);
        assertThat(editor.requiredField().getValue()).isFalse();
        assertThat(editor.showOnFormField().getValue()).isTrue();
    }

    @Test
    void paletteAppliesPropertiesInstantlyToSelectedParam() {
        ReportParamEditor editor = newEditor();
        editor.addParam();
        ReportParam param = template().getParams().getFirst();

        editor.nameField().setValue("reportDate");
        editor.captionField().setValue("Дата отчёта");
        editor.requiredField().setValue(true);
        editor.showOnFormField().setValue(false);

        assertThat(param.getName()).isEqualTo("reportDate");
        assertThat(param.getCaption()).isEqualTo("Дата отчёта");
        assertThat(param.isRequired()).isTrue();
        assertThat(param.isShowOnForm()).isFalse();
        assertThat(editor.nameField().getValue()).isEqualTo("reportDate");
    }

    @Test
    void entityKindEnablesEntityClassAndClearsItWhenSwitchingBack() {
        ReportParamEditor editor = newEditor();
        editor.setEntityOptions(List.of(
                new QueryMetadataCatalogService.EntityOption("Item", "org.ipro.test.Item", "Товары")));
        editor.addParam();
        ReportParam param = template().getParams().getFirst();

        editor.kindField().setValue(ReportParamKind.ENTITY);
        assertThat(editor.entityClassField().isEnabled()).isTrue();

        editor.kindField().setValue(ReportParamKind.SCALAR);
        assertThat(editor.entityClassField().isEnabled()).isFalse();
        assertThat(editor.entityClassField().getValue()).isNull();
        assertThat(param.getEntityClass()).isNull();
    }

    @Test
    void entityKindAppliesSelectedEntityClassToModel() {
        ReportParamEditor editor = newEditor();
        editor.setEntityOptions(List.of(
                new QueryMetadataCatalogService.EntityOption("Item", "org.ipro.test.Item", "Товары")));
        editor.addParam();
        ReportParam param = template().getParams().getFirst();

        editor.kindField().setValue(ReportParamKind.ENTITY_LIST);
        editor.entityClassField().setValue(
                new QueryMetadataCatalogService.EntityOption("Item", "org.ipro.test.Item", "Товары"));

        assertThat(param.getKind()).isEqualTo(ReportParamKind.ENTITY_LIST);
        assertThat(param.getEntityClass()).isEqualTo("org.ipro.test.Item");
    }

    @Test
    void sourceDependenciesEnableOnlyRelevantControls() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.valueSourceField().setValue(ReportParamSource.DEFAULT);
        assertThat(editor.defaultValueField().isEnabled()).isTrue();
        assertThat(editor.computedField().isEnabled()).isFalse();
        assertThat(editor.requiredField().isEnabled()).isFalse();

        editor.valueSourceField().setValue(ReportParamSource.COMPUTED);
        assertThat(editor.computedField().isEnabled()).isTrue();
        assertThat(editor.defaultValueField().isEnabled()).isFalse();
        assertThat(editor.requiredField().isEnabled()).isFalse();

        editor.valueSourceField().setValue(ReportParamSource.FORM);
        assertThat(editor.requiredField().isEnabled()).isTrue();
        assertThat(editor.defaultValueField().isEnabled()).isFalse();
        assertThat(editor.computedField().isEnabled()).isFalse();
    }

    @Test
    void defaultAndComputedValuesApplyToModelAndClearOnSourceSwitch() {
        ReportParamEditor editor = newEditor();
        editor.addParam();
        ReportParam param = template().getParams().getFirst();

        editor.valueSourceField().setValue(ReportParamSource.DEFAULT);
        editor.defaultValueField().setValue("{\"period\":\"MONTH\"}");
        assertThat(param.getDefaultValue()).isEqualTo("{\"period\":\"MONTH\"}");

        editor.valueSourceField().setValue(ReportParamSource.COMPUTED);
        editor.computedField().setValue(ReportComputedValue.NOW);
        assertThat(param.getComputed()).isEqualTo(ReportComputedValue.NOW);
        assertThat(param.getDefaultValue()).isNull();

        editor.valueSourceField().setValue(ReportParamSource.FORM);
        assertThat(param.getComputed()).isEqualTo(ReportComputedValue.NONE);
    }

    @Test
    void removingParameterHidesPaletteAndClearsSelection() {
        ReportParamEditor editor = newEditor();
        editor.addParam();

        editor.removeParam();

        assertThat(template().getParams()).isEmpty();
        assertThat(editor.selectedParam()).isNull();
        assertThat(editor.paletteVisible()).isFalse();
        assertThat(editor.nameField().getValue()).isEmpty();
    }

    private static ReportTemplate template;

    private static ReportTemplate template() {
        return template;
    }

    private static ReportParamEditor newEditor() {
        template = new ReportTemplate();
        ReportParamEditor editor = new ReportParamEditor();
        editor.setTemplate(template);
        return editor;
    }
}
