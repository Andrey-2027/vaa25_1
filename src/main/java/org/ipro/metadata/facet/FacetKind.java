package org.ipro.metadata.facet;

/**
 * Вид грани метаданных, которую показывает Entity Explorer.
 *
 * <p>Классификация по П5 (docs/platform-forms-rules.md): переопределяемые грани — набор
 * роли 3 (подписи, видимость, членство в подсистемах, участие в поиске); структурные —
 * никогда не переопределяются в рантайме (поля/типы/FK/табличные части привязаны к схеме).</p>
 *
 * <p>{@code overridable() = true} означает: значение грани обязано резолвиться через
 * {@link FacetResolver} (код-дефолт ← переопределение), см. контракт-тест
 * EntityExplorerFacetContractTest. {@code overridable() = false} — грань показывается
 * как есть, пути переопределения у неё нет.</p>
 */
public enum FacetKind {

    // === Переопределяемые (роль 3, явно объявленные грани) ===

    /** Заголовок формы списка (@EntityMetadata.listFormTitle). */
    ENTITY_LIST_TITLE(true),

    /** Заголовок формы элемента (@EntityMetadata.itemFormTitle). */
    ENTITY_ITEM_TITLE(true),

    /** Заголовок формы выбора (@EntityMetadata.selectionFormTitle). */
    ENTITY_SELECTION_TITLE(true),

    /** Подпись поля формы (@FieldMetadata.label). */
    FIELD_LABEL(true),

    /** Заголовок колонки грида (дефолт — подпись поля или заголовок пути через точку). */
    GRID_COLUMN_HEADER(true),

    /** Подпись контекст-фильтра (ContextFilterField.label). */
    CONTEXT_FILTER_LABEL(true),

    /** Членство сущности в подсистеме (@EntityMetadata.subsystem). */
    SUBSYSTEM_MEMBERSHIP(true),

    // === Структурные (никогда не переопределяются в рантайме) ===

    /** Структура поля (имя, java-тип, обязательность, lookup-цель). */
    FIELD_STRUCTURE(false),

    /** Табличная часть документа (@TableSections/@TableSectionMetadata). */
    TABLE_SECTION(false),

    /** Lookup-цель поля (какая сущность открывается выбором). */
    LOOKUP_TARGET(false),

    /** Обратная ссылка «кто ссылается на сущность» (ReferenceIndex). */
    REVERSE_REFERENCE(false),

    /** Декларация нумеруемого поля (@Numbered). */
    NUMBERING_DECL(false);

    private final boolean overridable;

    FacetKind(boolean overridable) {
        this.overridable = overridable;
    }

    public boolean overridable() {
        return overridable;
    }
}
