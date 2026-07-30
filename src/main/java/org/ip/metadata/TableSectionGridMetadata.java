package org.ip.metadata;

import java.util.List;

/**
 * Адаптер TableSectionMetadataInfo к GridMetadata — чтобы ItemTable могла использовать
 * тот же ViewSelectorDialog/GridViewEditorDialog, что и обычные @EntityMetadata-сущности
 * в ListForm, без дублирования этих диалогов под табличные части.
 */
public final class TableSectionGridMetadata implements GridMetadata {

    private final TableSectionMetadataInfo sectionMeta;

    public TableSectionGridMetadata(TableSectionMetadataInfo sectionMeta) {
        this.sectionMeta = sectionMeta;
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
        return sectionMeta.getGridFields().stream()
            .map(field -> ColumnPath.resolve(sectionMeta.getRowClass(), field.getName()))
            .toList();
    }

    @Override
    public FieldMetadataInfo getFieldByName(String name) {
        return sectionMeta.getFormFields().stream()
            .filter(f -> f.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
}
