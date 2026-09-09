package org.ipro.metadata.explorer;

import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;

import java.util.List;

/**
 * Сводка о сущности для Entity Explorer — immutable, замкнутый словарь (П1): только
 * готовые резолвнутые факты, никаких {@code Object payload}, Vaadin-типов, callback'ов
 * и строкового SQL внутри.
 *
 * <p>Собирается {@link EntitySummaryAssembler} из уже резолвнутых реестров
 * (MetadataResolver, FormRegistry, ReferenceIndex, NumberingMetadataRegistry,
 * SubsystemRegistry, FacetResolver) — сам сводку нигде не резолвит и не хранит.</p>
 *
 * <p>Каждая показываемая строка несёт {@link FacetKey} (вид грани + стабильный адрес)
 * и {@link ResolvedValue} (значение + источник «код» на срезе 1; «переопределение»
 * появится со store-слоем роли 3). Ключ осмыслен для переопределяемых граней
 * ({@link FacetKind#overridable()}); структурные строки несут ключ для единообразия
 * поверхности чтения.</p>
 */
public record EntitySummary(
        Class<?> entityClass,
        String simpleName,
        ResolvedValue displayName,
        List<OverviewRow> overview,
        List<FieldRow> fieldsForm,
        List<FieldRow> fieldsGrid,
        List<ColumnRow> listColumns,
        List<ColumnRow> selectColumns,
        List<SectionRow> tableSections,
        List<FormRow> forms,
        List<FilterRow> contextFilters,
        List<SelectionRow> selections,
        List<ReferenceRow> references,
        List<NumberingRow> numbering) {

    public EntitySummary {
        overview = List.copyOf(overview);
        fieldsForm = List.copyOf(fieldsForm);
        fieldsGrid = List.copyOf(fieldsGrid);
        listColumns = List.copyOf(listColumns);
        selectColumns = List.copyOf(selectColumns);
        tableSections = List.copyOf(tableSections);
        forms = List.copyOf(forms);
        contextFilters = List.copyOf(contextFilters);
        selections = List.copyOf(selections);
        references = List.copyOf(references);
        numbering = List.copyOf(numbering);
    }

    /** Общая поверхность любой показываемой строки: вид грани + ключ + эффективное значение. */
    public interface FacetRow {
        FacetKind kind();

        FacetKey key();

        ResolvedValue value();
    }

    /** Строка «Обзора»: подсистема, заголовки форм. caption — стабильная подпись строки. */
    public record OverviewRow(String caption, FacetKey key, ResolvedValue value) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Поле сущности в проекции формы ({@code formFields}) или грида ({@code fieldsGrid}).
     * {@code value} — эффективная подпись (для формы — поле {@code FIELD_LABEL},
     * для грида — {@code GRID_COLUMN_HEADER}); код-дефолт: {@code @FieldMetadata.label()},
     * при отсутствии — имя поля.
     */
    public record FieldRow(
            FacetKey key,
            String name,
            String typeLabel,
            ResolvedValue value,
            boolean required,
            boolean readOnly,
            String lookupTarget,
            Class<?> lookupEntityClass) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Колонка списка/выбора ({@code ColumnPath}): путь (возможно, через точку) +
     * эффективный заголовок (код-дефолт — заголовок пути из метаданных последнего сегмента).
     */
    public record ColumnRow(
            FacetKey key,
            String path,
            ResolvedValue value,
            String typeLabel,
            boolean nested) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /** Табличная часть документа (@TableSectionMetadata). */
    public record SectionRow(
            FacetKey key,
            ResolvedValue value,
            String rowClass,
            int order,
            int minRows,
            int formFieldCount,
            int gridFieldCount) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Зарегистрированная форма/вариант (custom-фабрика из реестра или платформенный дефолт).
     * {@code source} — пути к .java-файлам классов-декларантов («где явно»): конфиг, View или
     * набор колонок, например {@code org/ip/views/forms/ReceivingDocumentFormConfig.java};
     * пусто для платформенных дефолтов и регистраций без указанного источника.
     */
    public record FormRow(
            org.ipro.form.registry.FormType formType,
            String variant,
            String registrationKind,
            boolean platformDefault,
            String source) {
    }

    /**
     * Декларированный контекст-фильтр: ряд списка/выбора или конкретного варианта.
     * {@code source} — путь к .java-файлу конфига-декларанта (например
     * {@code org/ip/views/forms/PrdSpecListFormConfig.java}); пусто — не указан.
     */
    public record FilterRow(
            FacetKey key,
            String path,
            ResolvedValue value,
            String control,
            boolean required,
            boolean allListVariants,
            String scope,
            String source) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /** Именованный набор колонок Формы Выбора (data-вариант) или метаданные selectColumns. */
    public record SelectionRow(
            String variant,
            ResolvedValue value,
            List<String> columns,
            boolean registered) {
    }

    /** Обратная ссылка: {@code referencingClass} (поле {@code fieldName}) ссылается на сущность. */
    public record ReferenceRow(
            FacetKey key,
            Class<?> referencingClass,
            String fieldName,
            boolean columnRef,
            ResolvedValue value) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /** Декларация нумеруемого поля (@Numbered). */
    public record NumberingRow(
            FacetKey key,
            String fieldName,
            ResolvedValue value,
            String scope,
            String period,
            boolean manualAllowed) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }
}
