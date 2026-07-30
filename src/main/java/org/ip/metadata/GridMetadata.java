package org.ip.metadata;

import java.util.List;

/**
 * Минимальный набор метаданных, нужный ViewSelectorDialog/GridViewEditorDialog для
 * построения диалога видов грида — независимо от того, обычная это @EntityMetadata-
 * сущность (EntityMetadataInfo) или строка табличной части (TableSectionMetadataInfo,
 * через адаптер {@link TableSectionGridMetadata}).
 *
 * EntityMetadataInfo жёстко привязан к реальному экземпляру аннотации @EntityMetadata
 * (title/order/icon и т.д. читаются прямо из неё) — у табличной части такой аннотации
 * нет, только @TableSectionMetadata. Подделать аннотацию через прокси было бы хрупко и
 * избыточно, поэтому оба вида метаданных просто реализуют этот общий интерфейс.
 */
public interface GridMetadata {

    Class<?> getEntityClass();

    /** Заголовок для диалогов "Виды"/"Редактор вида". */
    String getListFormTitle();

    /** Поля для "доступных полей" при выборе колонок/условий отбора. */
    List<FieldMetadataInfo> getFormFields();

    /** Стандартный (из метаданных, без сохранённого вида) состав колонок. */
    List<ColumnPath> getListColumnPaths();

    /** Найти поле по имени — для восстановления условия отбора по сохранённому FilterSpec.path(). Null, если не найдено. */
    FieldMetadataInfo getFieldByName(String name);
}
