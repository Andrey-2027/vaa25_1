package org.ip.views.forms;

import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.form.builtin.ItemForm;
import org.ip.form.registry.FormContext;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Состав и режим секций формы Спецификации (PR-1.5, решение №7).
 *
 * Варианты формы документа:
 * <ul>
 *   <li>{@code materials-only} — только секция материалов ({@code PrdSpecMtr});
 *       секция операций не attach-ится вообще: не участвует в save/validate/rows,
 *       вкладка не создаётся;</li>
 *   <li>{@code full} — обе секции, как и в default (generic) варианте;</li>
 * </ul>
 *
 * Драйвер «по роли»: точка открытия (coordinator/view) передаёт параметр
 * {@code readOnlySections} (List&lt;Class&lt;?&gt;&gt;) — секции с этими row-классами
 * открываются в режиме «только просмотр» (кнопки скрыты), остальные редактируются.
 *
 * Открытие без варианта (null) не регистрируется здесь — оно остаётся generic-путём
 * со всеми секциями, поведение не меняется.
 */
@Component
public class PrdSpecFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.add("materials-only", ctx -> prdSpecForm(ctx, List.of(PrdSpecMtr.class)));
        variants.add("full", ctx -> prdSpecForm(ctx, List.of(PrdSpecMtr.class, PrdSpecOper.class)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ItemForm<PrdSpec> prdSpecForm(FormContext ctx, List<Class<?>> sectionFilter) {
        EntityMetadataInfo meta = ctx.metadataResolver().resolve(PrdSpec.class);
        ItemForm<PrdSpec> form = new ItemForm<>(meta, ctx.fieldFactory(), (List<String>) null);
        form.setSectionFilter(sectionFilter);
        List<Class<?>> readOnlySections = ctx.getParameter("readOnlySections");
        if (readOnlySections != null && !readOnlySections.isEmpty()) {
            form.setReadOnlySections(readOnlySections);
        }
        return form;
    }
}
