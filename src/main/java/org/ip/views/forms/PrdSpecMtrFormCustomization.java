package org.ip.views.forms;

import org.ipro.form.FieldFactory;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.RowMetadataInfo;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import org.ipro.form.EntityField;
import org.ipro.form.SelectionFormAssembler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Кастомизация Формы Элемента для {@link PrdSpecMtr} — табличной части "Компоненты спецификации".
 *
 * Два варианта, оба — обычный Java-код, использующий FieldFactory/ItemForm напрямую (без
 * промежуточного layout-DSL — см. обсуждение упрощения builder-слоя):
 *   - "material" (typeMtr=0): поля nomenclature, unit, qt — добавление материала напрямую
 *   - "product" (typeMtr=1): поля prdSpecMtr, unit, qt — добавление существующей спецификации
 *
 * В обоих случаях unit автоматически заполняется из выбранной номенклатуры (прямо или через
 * prdSpecMtr) — через ItemForm.entityField(name): EntityField — это кастомный компонент (Div),
 * НЕ реализующий Vaadin HasValue, поэтому для него нельзя использовать
 * ItemForm.getEntityField(String, Class).
 *
 * Зависимости выбора (номенклатура спецификации, её единица измерения) объявлены в metadata
 * самих полей через {@code @Lookup(fetch = ...)} и загружаются сценарием LOOKUP единого
 * FetchPlan. Форма не знает ни об EntityGraph, ни о глубине загрузки, ни о перечитывании
 * сущности по ID — см. ADX-07 и ADR-0006.
 */
@Component
public class PrdSpecMtrFormCustomization implements ItemFormCustomization {

    private final SelectionFormAssembler selectionFormAssembler;

    public PrdSpecMtrFormCustomization(SelectionFormAssembler selectionFormAssembler) {
        this.selectionFormAssembler = selectionFormAssembler;
    }

    @Override
    public Class<?> entityClass() {
        return PrdSpecMtr.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.add(PrdSpecMtrVariant.MATERIAL.key(),
            ctx -> buildForm(ctx, List.of("nomenclature", "unit", "qt"), false));
        variants.add(PrdSpecMtrVariant.PRODUCT.key(),
            ctx -> buildForm(ctx, List.of("prdSpecMtr", "unit", "qt"), true));
    }

    private ItemForm<PrdSpecMtr> buildForm(org.ipro.form.registry.FormContext ctx,
                                           List<String> fieldNames, boolean viaSpec) {
        MetadataResolver resolver = ctx.metadataResolver();
        FieldFactory fieldFactory = ctx.fieldFactory();

        RowMetadataInfo rowMeta = resolver.resolveRowMetadata(PrdSpecMtr.class);
        List<FieldMetadataInfo> fields = rowMeta.getFormFields().stream()
            .filter(f -> fieldNames.contains(f.getName()))
            .toList();

        ItemForm<PrdSpecMtr> form = new ItemForm<>(PrdSpecMtr.class, fields, fieldFactory);

        EntityField<UnitOfMeasurement> unitField = form.entityField("unit");

        if (viaSpec) {
            // prdSpecMtr → nomenclature строки + unit. Поля nomenclature на форме нет
            // вообще (см. fieldNames выше) — при выборе спецификации её номенклатура
            // проставляется напрямую в строку (row.nomenclature), чтобы колонки вида,
            // укоренённые в nomenclature, показывали данные уже до сохранения.
            EntityField<PrdSpec> specField = form.entityField("prdSpecMtr");
            specField.addValueChangeListener(spec -> {
                Nomenclature nom = spec != null ? spec.getNomenclature() : null;
                form.getEntity().setNomenclature(nom);
                unitField.setValue(nom != null ? nom.getUnitOfMeasurement() : null);
            });
        } else {
            EntityField<Nomenclature> nomenclatureField = form.entityField("nomenclature");
            // Тип-ограничение (Фаза 4): компонент-«Материал» нельзя выбрать Узел/Сборку —
            // диалог выбора открывается предотфильтрованным по typeNom = «Материал».
            nomenclatureField.setSelectionFilter(Map.of("typeNom", "Материал"),
                (onSelect, filters) -> selectionFormAssembler.assemble(
                    Nomenclature.class, onSelect, filters));
            nomenclatureField.addValueChangeListener(nom -> unitField.setValue(
                nom == null ? null : nom.getUnitOfMeasurement()));
        }

        return form;
    }
}
