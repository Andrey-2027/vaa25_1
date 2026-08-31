package org.ip.views.forms;

import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.form.builtin.ItemForm;
import org.ip.form.registry.FormContext;
import org.ipro.form.EntityField;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.EntityMetadataInfo;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Состав и режим секций формы Спецификации (PR-1.5, решение №7).
 *
 * <p>Варианты формы документа:</p>
 * <ul>
 *   <li>{@code materials-only} — только секция материалов ({@code PrdSpecMtr});</li>
 *   <li>{@code full} — обе секции;</li>
 *   <li>default — обе секции (как generic), но с тип-ограничением выбора Номенклатуры Шапки.</li>
 * </ul>
 *
 * <p>Тип-ограничения (Фаза 4): Спецификация — сборочная единица, поэтому выбираемая в шапке
 * Номенклатура не может быть «Материал». Реализовано через {@link SeedFilter} на SelectionForm
 * (разрешённые типы — все, кроме «Материал»): основная точка выбора диалогом, автокомплит не трогаем.</p>
 */
@Component
public class PrdSpecFormConfig implements ItemFormCustomization {

    /** Типы номенклатуры, допустимые для Спецификации как сборочной единицы (не «Материал»). */
    private static final List<String> NON_MATERIAL_TYPES =
        List.of("Узел", "ДСЕ", "Нормали", "ПКИ", "Прочее");

    private final SelectionFormAssembler selectionFormAssembler;

    public PrdSpecFormConfig(SelectionFormAssembler selectionFormAssembler) {
        this.selectionFormAssembler = selectionFormAssembler;
    }

    @Override
    public Class<?> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> prdSpecForm(ctx, null));
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
        restrictHeaderNomenclature(form);
        return form;
    }

    /** Диалог «Выбрать номенклатуру» в шапке — только не-материальные типы. */
    private void restrictHeaderNomenclature(ItemForm<PrdSpec> form) {
        try {
            EntityField<Nomenclature> field = form.entityField("nomenclature");
            field.setSelectionFilter(Map.of("typeNom", NON_MATERIAL_TYPES),
                (onSelect, filters) -> selectionFormAssembler.assemble(
                    Nomenclature.class, onSelect, filters));
        } catch (IllegalStateException ignored) {
            // поле отсутствует/не EntityField — ограничение не применяем
        }
    }
}