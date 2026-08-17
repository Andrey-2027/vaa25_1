package org.ip.form.coordinator;

import org.ip.config.DataInitializer;
import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ItemTable;
import org.ip.form.registry.FormResolver;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
            Map.of("readOnlySections", List.of(PrdSpecOper.class)));

        ItemTable<PrdSpecOper, PrdSpec> operations = form.tableSection(PrdSpecOper.class);
        ItemTable<PrdSpecMtr, PrdSpec> materials = form.tableSection(PrdSpecMtr.class);

        assertThat(operations.isReadOnly()).isTrue();
        assertThat(materials.isReadOnly()).isFalse();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Class<?>> sectionRowClasses(ItemForm<?> form) {
        return ((ItemForm) form).getTableSections().stream()
            .map(table -> ((ItemTable) table).getRowClass())
            .toList();
    }
}
