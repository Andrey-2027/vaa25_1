package org.ipro.form;

import com.vaadin.flow.component.combobox.ComboBox;
import org.ipro.crud.EntityLookup;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Общий lazy lookup для комбобоксов фильтров (D3.5.2, INTERNAL будущего
 * {@code platform-vaadin}).
 *
 * <p>Проблема: фильтры грузили справочник целиком ({@code LookupService.findAll})
 * ради выпадающего списка. Для больших справочников это скрытая выгрузка всей
 * таблицы; простая замена на bounded list тихо скрыла бы значения. Поэтому все
 * комбобоксы-подсказки идут через один autocomplete-провайдер поверх стабильного
 * {@link EntityLookup#search} (страница {@value #SUGGESTION_LIMIT}), а сохранённое
 * значение подгружается точечно через {@link EntityLookup#findSelectedById}.</p>
 *
 * <p>Что НЕ покрыто (осознанно, см. D3.5.2): {@code lookupFilterOptions} в
 * {@code ListForm}/{@code GridViewEditorDialog} питает компилятор сохранённых видов
 * библиотеки FilterGrid, который резолвит сохранённые значения сканированием списка.
 * Там решение принимается отдельно после characterization-теста round-trip —
 * менять источник под компилятором вслепую значит ломать сохранённые виды.</p>
 */
public final class LookupComboHelper {

    /** Страница автокомплита: ни один запрос подсказок не читает больше. */
    public static final int SUGGESTION_LIMIT = 20;

    /**
     * Явный лимит маленького закрытого справочника (роли, единицы, типы атрибутов):
     * десятки записей, полный состав виден сразу. Для больших справочников не применять —
     * там только autocomplete ({@link #SUGGESTION_LIMIT}) или SelectionForm.
     */
    public static final int DICTIONARY_LIMIT = 200;

    private LookupComboHelper() {
    }

    /**
     * Текстовые select-колонки сущности — поля поиска автокомплита (как в
     * {@code FieldFactory}). Пусто, если сущность неизвестна метаданным: тогда backend
     * сам выводит поля из {@code @SearchFields}/instanceName/selectColumns, пустой
     * список для него — не ошибка, а «по умолчанию».
     */
    public static List<String> textSearchFields(Class<?> entityType, MetadataResolver resolver) {
        if (entityType == null || resolver == null) {
            return List.of();
        }
        try {
            return resolver.resolve(entityType).getSelectColumnPaths().stream()
                .filter(path -> path.getResolvedType() == FieldType.TEXT)
                .map(ColumnPath::getKey)
                .toList();
        } catch (RuntimeException unresolvable) {
            return List.of();
        }
    }

    /**
     * Предложения для введённого текста: bounded {@code search} поверх LOOKUP-сценария
     * с обязательным RLS gate (внутри {@code EntityLookup}).
     */
    public static List<Object> suggest(Class<?> entityType, EntityLookup lookup,
                                       MetadataResolver resolver, String term) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(lookup, "lookup must not be null");
        return lookup.search(entityType, textSearchFields(entityType, resolver),
                term, SUGGESTION_LIMIT).stream()
            .map(item -> (Object) item)
            .toList();
    }

    /**
     * Вешает на комбобокс lazy autocomplete вместо полной загрузки справочника.
     * Формально та же перегрузка {@code setItems}, что уже использовалась с
     * {@code findAll}, — меняется только источник данных (bounded search).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> void installSuggestItems(ComboBox<T> box, Class<? extends T> entityType,
                                               EntityLookup lookup, MetadataResolver resolver) {
        Objects.requireNonNull(box, "box must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(lookup, "lookup must not be null");
        box.setItems(query -> suggest(entityType, lookup, resolver, filterText(query.getFilter()))
            .stream().map(item -> (T) item));
    }

    /**
     * Текст фильтра запроса: ComboBox передаёт {@code Optional<String>}, прямой вызов
     * провайдера — строку как есть. Пусто и null — одно и то же: начало списка.
     */
    private static String filterText(Object filter) {
        if (filter instanceof Optional<?> present) {
            return present.map(value -> value == null ? "" : value.toString()).orElse("");
        }
        return filter == null ? "" : filter.toString();
    }

    /**
     * Восстановить ранее сохранённое значение (id строкой) без загрузки справочника.
     * Недоступное/удалённое/нечисловое значение не выдумывается и фильтр не трётся:
     * комбобокс остаётся пустым с честным helper-текстом.
     *
     * <p>Предусловие: у комбобокса уже стоит провайдер (обычно {@link #installSuggestItems}
     * прямо перед этим вызовом) — пустой ComboBox отклоняет любое значение исключением
     * самого Vaadin. Порядок install → restore обязателен.</p>
     */
    public static <T> void restoreSavedSelection(ComboBox<T> box, Class<T> entityType,
                                                 String savedId, EntityLookup lookup) {
        if (box == null || entityType == null || savedId == null || savedId.isBlank()
            || lookup == null) {
            return;
        }
        final Long id;
        try {
            id = Long.valueOf(savedId.trim());
        } catch (NumberFormatException notAnIdentifier) {
            box.setHelperText("Сохранённое значение «" + savedId + "» не является идентификатором");
            return;
        }
        lookup.findSelectedById(entityType, id).ifPresentOrElse(
            box::setValue,
            () -> box.setHelperText("Сохранённое значение ID " + savedId
                + " недоступно (удалено или нет доступа)"));
    }
}
