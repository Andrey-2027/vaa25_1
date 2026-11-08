package org.ip.form.coordinator;

import org.ip.config.DataInitializer;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.registry.FormResolver;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import org.ip.views.forms.PrdSpecItemForm;
import org.ip.views.forms.PrdSpecMaterialsOnlyForm;
import org.ip.views.forms.PrdSpecFullForm;
import org.ipro.form.builder.FormParameters;
import org.ipro.form.registry.FormType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Состав и режим секций формы Спецификации (PR-1.5, решение №7, кейс PrdSpec):
 * - вариант «materials-only» — форма с одной секцией {@code PrdSpecMtr} (без вкладок);
 * - default (без варианта) — обе секции, как раньше;
 * - вариант «full» — обе секции явно;
 * - драйвер «по роли»: параметр {@code readOnlySections} — секция операций read-only
 *   (кнопки скрыты), материалы редактируются.
 */
@SpringBootTest
class PrdSpecSectionCompositionTest {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FormResolver formResolver;

    @Test
    void materialsOnlyVariantAttachesOnlyMtrSection() {
        ItemForm<PrdSpec> form = formResolver.resolveItemForm(PrdSpec.class, "materials-only", null, null);

        assertThat(sectionRowClasses(form)).containsExactly(PrdSpecMtr.class);
        // 1 секция → без вкладок (ItemForm: 1 секция — под полями шапки, TabSheet не создаётся)
        assertThat(form.getTableSections()).hasSize(1);
    }

    @Test
    void defaultResolvesBothSections() {
        ItemForm<PrdSpec> form = formResolver.resolveItemForm(PrdSpec.class, null, null, null);

        assertThat(sectionRowClasses(form)).containsExactly(PrdSpecMtr.class, PrdSpecOper.class);
    }

    @Test
    void fullVariantResolvesBothSections() {
        ItemForm<PrdSpec> form = formResolver.resolveItemForm(PrdSpec.class, "full", null, null);

        assertThat(sectionRowClasses(form)).containsExactly(PrdSpecMtr.class, PrdSpecOper.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void roleParametersMakeOnlyOperationsReadOnly() {
        ItemForm<PrdSpec> form = formResolver.resolveItemForm(PrdSpec.class, "full", null,
            FormParameters.values(PrdSpecItemForm.READ_ONLY_SECTIONS.with(List.of(PrdSpecOper.class))));

        ItemTable<PrdSpecOper, PrdSpec> operations = form.tableSection(PrdSpecOper.class);
        ItemTable<PrdSpecMtr, PrdSpec> materials = form.tableSection(PrdSpecMtr.class);

        assertThat(operations.isReadOnly()).isTrue();
        assertThat(materials.isReadOnly()).isFalse();
    }

    @Test void annotatedVariantsAndOpeningStateAreIndependent() {
        var first = formResolver.resolveItemForm(PrdSpec.class, "materials-only", null,
            FormParameters.values(PrdSpecItemForm.READ_ONLY_SECTIONS.with(List.of(PrdSpecMtr.class))));
        var second = formResolver.resolveItemForm(PrdSpec.class, null, null, null);
        var full = formResolver.resolveItemForm(PrdSpec.class, "full", null, null);
        assertThat(first).isInstanceOf(PrdSpecMaterialsOnlyForm.class);
        assertThat(second).isExactlyInstanceOf(PrdSpecItemForm.class);
        assertThat(full).isInstanceOf(PrdSpecFullForm.class);
        assertThat(first.tableSection(PrdSpecMtr.class).isReadOnly()).isTrue();
        assertThat(second.tableSection(PrdSpecMtr.class).isReadOnly()).isFalse();
        assertThat(second.tableSection(PrdSpecOper.class).isReadOnly()).isFalse();
        assertThat(second.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName())
            .containsExactly("journal", "nomenclature", "codeSpec", "draft", "comment");
    }

    @Test void legacyMapIsCheckedAtOpeningAndUnknownParametersFail() {
        var compatible = formResolver.resolveItemForm(PrdSpec.class, null, null,
            Map.of("readOnlySections", List.of(PrdSpecOper.class)));
        assertThat(compatible.tableSection(PrdSpecOper.class).isReadOnly()).isTrue();
        for (var supplied : List.of(Map.of("readOnlySections", "operations"),
                Map.of("readOnlySections", List.of("operations")), Map.of("readOnlySections", List.of(PrdSpec.class)),
                Map.of("readOnlySection", List.of(PrdSpecOper.class)))) {
            assertThatThrownBy(() -> formResolver.resolveItemForm(PrdSpec.class, null, null, (Map) supplied))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parameter");
        }
    }

    @Test void contextualListIsNoLongerRegistered() {
        assertThat(formResolver.getFormRegistry().has(PrdSpec.class, FormType.LIST, "contextual")).isFalse();
        assertThatThrownBy(() -> formResolver.resolveListForm(PrdSpec.class, "contextual", null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("contextual");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Class<?>> sectionRowClasses(ItemForm<?> form) {
        return ((ItemForm) form).getTableSections().stream()
            .map(table -> ((ItemTable) table).getRowClass())
            .toList();
    }
}
