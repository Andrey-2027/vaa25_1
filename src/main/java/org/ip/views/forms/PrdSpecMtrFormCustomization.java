package org.ip.views.forms;

import org.ip.form.FieldFactory;
import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.form.builtin.ItemForm;
import org.ip.metadata.FetchGraphs;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.RowMetadataInfo;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import org.ip.service.LookupService;
import org.ip.views.components.EntityField;
import org.springframework.stereotype.Component;

import java.util.List;

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
 * Выбранная сущность приходит из lookup-поиска с ленивыми прокси (сессия закрыта), поэтому
 * перед чтением вложенных связей сущность перечитывается по ID через LookupService.findById
 * с fetch-графом (см. unitOfSelectedNomenclature/specWithNomenclature).
 */
@Component
public class PrdSpecMtrFormCustomization implements ItemFormCustomization {

    /**
     * Глубина BFS по ссылкам при гидратации выбранной из lookup-поиска сущности
     * (см. FetchGraphs.associationPaths): прямые ссылки + их ссылки.
     */
    private static final int LOOKUP_FETCH_DEPTH = 2;

    @Override
    public Class<?> entityClass() {
        return PrdSpecMtr.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.add("material", ctx -> buildForm(ctx, List.of("nomenclature", "unit", "qt"), false));
        variants.add("product", ctx -> buildForm(ctx, List.of("prdSpecMtr", "unit", "qt"), true));
    }

    private ItemForm<PrdSpecMtr> buildForm(org.ip.form.registry.FormContext ctx,
                                           List<String> fieldNames, boolean viaSpec) {
        MetadataResolver resolver = ctx.metadataResolver();
        FieldFactory fieldFactory = ctx.fieldFactory();
        LookupService lookupService = ctx.lookupService();

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
            // укоренённые в nomenclature, показывали данные уже до сохранения. Сущности
            // из lookup-поиска приходят с ленивыми прокси (сессия закрыта) — перечитываем
            // по ID с fetch-графом, иначе getNomenclature()/getUnitOfMeasurement() бросит
            // LazyInitializationException в UI-потоке.
            EntityField<PrdSpec> specField = form.entityField("prdSpecMtr");
            specField.addValueChangeListener(spec -> {
                PrdSpec full = spec == null ? null : specWithNomenclature(lookupService, resolver, spec);
                Nomenclature nom = full != null ? full.getNomenclature() : null;
                form.getEntity().setNomenclature(nom);
                unitField.setValue(nom != null ? nom.getUnitOfMeasurement() : null);
            });
        } else {
            EntityField<Nomenclature> nomenclatureField = form.entityField("nomenclature");
            nomenclatureField.addValueChangeListener(nom -> unitField.setValue(
                nom == null ? null : unitOfSelectedNomenclature(lookupService, resolver, nom)));
        }

        return form;
    }

    /**
     * Пути fetch-графа для гидратации выбранной сущности — выводятся автоматически из
     * структуры её ассоциаций (FetchGraphs.associationPaths), без ручного перечисления.
     */
    private UnitOfMeasurement unitOfSelectedNomenclature(LookupService lookupService,
                                                         MetadataResolver metadataResolver,
                                                         Nomenclature nom) {
        Nomenclature full = lookupService.findById(Nomenclature.class, nom.getId(),
            FetchGraphs.associationPaths(Nomenclature.class, metadataResolver, LOOKUP_FETCH_DEPTH))
            .orElse(nom);
        return full.getUnitOfMeasurement();
    }

    private PrdSpec specWithNomenclature(LookupService lookupService,
                                          MetadataResolver metadataResolver,
                                          PrdSpec spec) {
        return lookupService.findById(PrdSpec.class, spec.getId(),
            FetchGraphs.associationPaths(PrdSpec.class, metadataResolver, LOOKUP_FETCH_DEPTH))
            .orElse(spec);
    }
}
