package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.ipro.crud.LookupService;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.MetadataResolver;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Подключает ряд контекст-фильтров к диалогу выбора. Лестница (первое непустое):
 * ряд варианта выбора → собственный ряд диалога → поля с {@code allListVariants()}
 * (где бы ни объявлены: уровень сущности, блоки вариантов списка).
 * Семантика везде замена, не слияние.
 *
 * Вызывается при каждом открытии диалога (диалог свежий, панель пустая —
 * семантика «пусто = все записи», как в списке). Фиксированные фильтры открытия
 * задаются отдельно и с панелью не смешиваются.
 */
public final class SelectionContextFilters {

    private SelectionContextFilters() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void attach(SelectionForm<?> form,
                              Class<?> entityClass,
                              String variant,
                              Map<String, Object> fixedFilters,
                              FormRegistry registry,
                              MetadataResolver metadataResolver,
                              LookupService lookupService,
                              SelectionFormAssembler assembler) {
        form.setFixedFilters(fixedFilters);
        List<ContextFilterField> fields = resolveRow(registry, entityClass, variant);
        if (fields.isEmpty()) return;
        ContextFilterPanel panel = new ContextFilterPanel(entityClass, fields,
            metadataResolver, lookupService,
            (source, onSelect) -> assembler.assemble(
                (Class) source, (Consumer) onSelect),
            (path, value) -> {
                if (value == null) {
                    form.clearContextFilter(path);
                } else {
                    form.setContextFilter(path, value);
                }
            });
        form.setHeaderRow(panel);
    }

    /**
     * Лестница ряда диалога. Возвращает первое непустое (замена, не слияние):
     * ряд варианта выбора → собственный ряд диалога → {@code allListVariants()}.
     */
    static List<ContextFilterField> resolveRow(FormRegistry registry,
                                              Class<?> entityClass, String variant) {
        List<ContextFilterField> variantRow =
            registry.getVariantContextFilters(entityClass,
                org.ipro.form.registry.FormType.SELECTION, variant);
        if (variantRow != null && !variantRow.isEmpty()) return variantRow;

        List<ContextFilterField> own = registry.getSelectionContextFilters(entityClass);
        if (own != null && !own.isEmpty()) return own;

        return markedEverywhere(registry, entityClass);
    }

    /**
     * Поля с {@code allListVariants()}: сначала уровень сущности, затем блоки
     * вариантов списка по имени (детерминированный порядок), дубли по пути —
     * первое вхождение побеждает.
     */
    static List<ContextFilterField> markedEverywhere(FormRegistry registry, Class<?> entityClass) {
        Map<String, ContextFilterField> merged = new java.util.LinkedHashMap<>();
        List<ContextFilterField> shared = registry.getContextFilters(entityClass);
        if (shared != null) {
            for (ContextFilterField field : shared) {
                if (field.allLists()) merged.putIfAbsent(field.path(), field);
            }
        }
        Map<String, List<ContextFilterField>> rows = registry.getVariantContextFilters(
            entityClass, org.ipro.form.registry.FormType.LIST);
        if (rows != null) {
            for (var entry : rows.entrySet()) {
                for (ContextFilterField field : entry.getValue()) {
                    if (field.allLists()) merged.putIfAbsent(field.path(), field);
                }
            }
        }
        return List.copyOf(merged.values());
    }
}
