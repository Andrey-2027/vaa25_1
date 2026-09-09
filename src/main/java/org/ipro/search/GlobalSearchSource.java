package org.ipro.search;

import java.util.List;
import java.util.Objects;

/**
 * Проверенное описание одного источника глобального поиска.
 *
 * @param declarationOrder порядок группы, полученный из порядка деклараций
 * @param entityClass класс сущности
 * @param searchFields прямые строковые поля поиска
 * @param displayFields прямые поля fallback-подписи; пусто означает HasDisplayName
 * @param idFieldName имя поля первичного ключа для детерминированной сортировки
 * @param groupTitle заголовок группы результатов
 */
public record GlobalSearchSource(int declarationOrder,
                                 Class<?> entityClass,
                                 List<String> searchFields,
                                 List<String> displayFields,
                                 String idFieldName,
                                 String groupTitle) {
    public GlobalSearchSource {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(searchFields, "searchFields cannot be null");
        Objects.requireNonNull(displayFields, "displayFields cannot be null");
        Objects.requireNonNull(idFieldName, "idFieldName cannot be null");
        Objects.requireNonNull(groupTitle, "groupTitle cannot be null");
        if (idFieldName.isBlank()) {
            throw new IllegalArgumentException("idFieldName cannot be blank");
        }
        searchFields = List.copyOf(searchFields);
        displayFields = List.copyOf(displayFields);
    }

    /** Использовать getDisplayName() как основной способ формирования подписи. */
    public boolean usesDisplayName() {
        return displayFields.isEmpty();
    }
}
