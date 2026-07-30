package org.ip.views.forms;

import com.vaadin.flow.component.Component;
import org.ip.form.FieldFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.form.registry.FormContext;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.RowMetadataInfo;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import java.util.List;

/**
 * Кастомизация Формы Элемента для {@link PrdSpecMtr} — табличной части "Компоненты спецификации".
 *
 * Два варианта:
 *   - "material" (typeMtr=0): поля nomenclature, unit, qt — добавление материала напрямую
 *   - "product" (typeMtr=1): поля prdSpecMtr, unit, qt — добавление существующей спецификации
 *
 * В обоих случаях unit автоматически заполняется из выбранной номенклатуры (прямо или через prdSpecMtr).
 */
@org.springframework.stereotype.Component
public class PrdSpecMtrFormCustomization implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return PrdSpecMtr.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        // Вариант "material": поля nomenclature, unit, qt
        variants.addCustom("material", ctx -> buildMaterialForm(ctx));

        // Вариант "product": поля prdSpecMtr, unit, qt
        variants.addCustom("product", ctx -> buildProductForm(ctx));
    }

    private ItemForm<PrdSpecMtr> buildMaterialForm(FormContext ctx) {
        MetadataResolver resolver = ctx.getParameter("metadataResolver");
        FieldFactory fieldFactory = ctx.getParameter("fieldFactory");

        RowMetadataInfo rowMeta = resolver.resolveRowMetadata(PrdSpecMtr.class);
        List<FieldMetadataInfo> allFields = rowMeta.getFormFields();

        // Фильтруем только нужные поля
        List<FieldMetadataInfo> filteredFields = allFields.stream()
            .filter(f -> List.of("nomenclature", "unit", "qt").contains(f.getName()))
            .toList();

        ItemForm<PrdSpecMtr> form = new ItemForm<>(PrdSpecMtr.class, filteredFields, fieldFactory);

        // Listener: nomenclature → unit
        Component nomenclatureField = form.getField("nomenclature");
        Component unitField = form.getField("unit");

        if (nomenclatureField instanceof com.vaadin.flow.component.HasValue) {
            ((com.vaadin.flow.component.HasValue<?, ?>) nomenclatureField).addValueChangeListener(e -> {
                Object value = e.getValue();
                if (value instanceof Nomenclature) {
                    Nomenclature nomenclature = (Nomenclature) value;
                    UnitOfMeasurement unit = nomenclature.getUnitOfMeasurement();
                    if (unit != null && unitField instanceof com.vaadin.flow.component.HasValue) {
                        @SuppressWarnings("unchecked")
                        com.vaadin.flow.component.HasValue<?, UnitOfMeasurement> unitHasValue =
                            (com.vaadin.flow.component.HasValue<?, UnitOfMeasurement>) unitField;
                        unitHasValue.setValue(unit);
                    }
                }
            });
        }

        return form;
    }

    private ItemForm<PrdSpecMtr> buildProductForm(FormContext ctx) {
        MetadataResolver resolver = ctx.getParameter("metadataResolver");
        FieldFactory fieldFactory = ctx.getParameter("fieldFactory");

        RowMetadataInfo rowMeta = resolver.resolveRowMetadata(PrdSpecMtr.class);
        List<FieldMetadataInfo> allFields = rowMeta.getFormFields();

        // Фильтруем только нужные поля
        List<FieldMetadataInfo> filteredFields = allFields.stream()
            .filter(f -> List.of("prdSpecMtr", "unit", "qt").contains(f.getName()))
            .toList();

        ItemForm<PrdSpecMtr> form = new ItemForm<>(PrdSpecMtr.class, filteredFields, fieldFactory);

        // Listener: prdSpecMtr → nomenclature (read-only) → unit
        Component prdSpecMtrField = form.getField("prdSpecMtr");
        Component unitField = form.getField("unit");

        if (prdSpecMtrField instanceof com.vaadin.flow.component.HasValue) {
            ((com.vaadin.flow.component.HasValue<?, ?>) prdSpecMtrField).addValueChangeListener(e -> {
                Object value = e.getValue();
                if (value instanceof PrdSpec) {
                    PrdSpec prdSpec = (PrdSpec) value;
                    Nomenclature nomenclature = prdSpec.getNomenclature();
                    if (nomenclature != null) {
                        UnitOfMeasurement unit = nomenclature.getUnitOfMeasurement();
                        if (unit != null && unitField instanceof com.vaadin.flow.component.HasValue) {
                            @SuppressWarnings("unchecked")
                            com.vaadin.flow.component.HasValue<?, UnitOfMeasurement> unitHasValue =
                                (com.vaadin.flow.component.HasValue<?, UnitOfMeasurement>) unitField;
                            unitHasValue.setValue(unit);
                        }
                    }
                }
            });
        }

        return form;
    }
}
