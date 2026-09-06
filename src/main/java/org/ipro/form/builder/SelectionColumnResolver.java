package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.SelectionColumnsDef;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Единая точка приоритета колонок Формы Выбора. Используется и {@code FieldFactory}
 * (поле ссылается на сущность), и {@code FormResolver} (программное открытие выбора) —
 * чтобы оба пути резолвили один и тот же набор и не расходились.
 *
 * Приоритет (по нарастанию специфичности):
 * <ol>
 *   <li>per-field {@code @Lookup.columns()} — переопределение одного места использования;</li>
 *   <li>именованный вариант из реестра ({@code SelectionFormCustomization}) —
 *       выбирается через {@code @Lookup.variant()} на поле либо параметром открытия;</li>
 *   <li>{@code @EntityMetadata.selectColumns()} целевой сущности;</li>
 *   <li>{@code listColumns} / грид сущности (fallback метаданных).</li>
 * </ol>
 *
 * Возвращает null ТОЛЬКО когда запрошен непустой вариант, не зарегистрированный в реестре —
 * вызывающая сторона обязана бросить strict-ошибку конфигурации со своим контекстом
 * (поле vs программное открытие), а не fallback.
 */
public final class SelectionColumnResolver {

    private SelectionColumnResolver() {
    }

    public static SelectionFormAssembler.ResolvedSelection resolve(
            MetadataResolver metadataResolver,
            FormRegistry registry,
            Class<?> entityClass,
            String variant,
            String[] fieldColumnsOverride) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);

        if (fieldColumnsOverride != null && fieldColumnsOverride.length > 0) {
            return new SelectionFormAssembler.ResolvedSelection(
                resolveExplicit(entityClass, List.of(fieldColumnsOverride)), defaultTitle(meta));
        }

        if (variant != null && !variant.isBlank()) {
            SelectionColumnsDef def = registry.getSelectionColumns(entityClass, variant);
            if (def == null) return null;
            List<ColumnPath> columns = def.columns().isEmpty()
                ? meta.getSelectColumnPaths()
                : resolveExplicit(entityClass, def.columns());
            String title = def.title() != null && !def.title().isBlank()
                ? def.title() : defaultTitle(meta);
            return new SelectionFormAssembler.ResolvedSelection(columns, title);
        }

        return new SelectionFormAssembler.ResolvedSelection(
            meta.getSelectColumnPaths(), defaultTitle(meta));
    }

    private static List<ColumnPath> resolveExplicit(Class<?> entityClass, List<String> paths) {
        List<ColumnPath> result = new ArrayList<>(paths.size());
        for (String path : paths) {
            result.add(ColumnPath.resolve(entityClass, path));
        }
        return List.copyOf(result);
    }

    private static String defaultTitle(EntityMetadataInfo meta) {
        return !meta.getSelectionFormTitle().isBlank()
            ? meta.getSelectionFormTitle() : meta.getListFormTitle();
    }
}
