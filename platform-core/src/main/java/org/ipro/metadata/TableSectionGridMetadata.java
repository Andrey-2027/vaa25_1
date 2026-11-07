package org.ipro.metadata;

import java.util.List;

/**
 * Адаптер TableSectionMetadataInfo к GridMetadata — чтобы ItemTable могла использовать
 * тот же ViewSelectorDialog/GridViewEditorDialog, что и обычные @EntityMetadata-сущности
 * в ListForm, без дублирования этих диалогов под табличные части.
 */
public final class TableSectionGridMetadata implements GridMetadata {

    private final TableSectionMetadataInfo sectionMeta;
    private final List<ColumnPath> columns;

    public TableSectionGridMetadata(TableSectionMetadataInfo sectionMeta) {
        this(sectionMeta, sectionMeta.getGridFields().stream()
            .map(field -> ColumnPath.resolve(sectionMeta.getRowClass(), field.getName())).toList());
    }

    /** Final form defaults; section metadata and other openings remain unchanged. */
    public TableSectionGridMetadata(TableSectionMetadataInfo sectionMeta, List<ColumnPath> columns) {
        this.sectionMeta = sectionMeta;
        this.columns = List.copyOf(columns);
    }

    @Override
    public Class<?> getEntityClass() {
        return sectionMeta.getRowClass();
    }

    @Override
    public String getListFormTitle() {
        return sectionMeta.getTitle();
    }

    @Override
    public List<FieldMetadataInfo> getFormFields() {
        return sectionMeta.getFormFields();
    }

    @Override
    public List<ColumnPath> getListColumnPaths() {
        return columns;
    }

    @Override
    public FieldMetadataInfo getFieldByName(String name) {
        return sectionMeta.getFormFields().stream()
            .filter(f -> f.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
}
