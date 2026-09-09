package org.ipro.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Декларативная конфигурация участия сущностей в глобальном поиске.
 *
 * <p>Добавление сущности в эту конфигурацию означает её участие в поиске — отдельного
 * флага {@code searchable} нет. Порядок вызовов {@link #add(Class, String...)} задаёт
 * порядок групп результатов. Конфигурация собирается при старте приложения, а запросы
 * работают с неизменяемым {@link GlobalSearchCatalog}.</p>
 *
 * <p>Этот класс содержит только факты о действии глобального поиска. Поля
 * {@code @Lookup.searchFields} для автокомплита ссылок остаются отдельной настройкой.</p>
 */
public final class GlobalSearchConfig {

    private final List<SourceBuilder> declarations = new ArrayList<>();

    /**
     * Добавить сущность в глобальный поиск.
     *
     * @param entityClass класс JPA-сущности
     * @param searchFields прямые строковые поля, по которым выполняется поиск
     * @return декларация, которую можно дополнить настройками представления
     */
    public synchronized SourceBuilder add(Class<?> entityClass, String... searchFields) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(searchFields, "searchFields cannot be null");
        SourceBuilder declaration = new SourceBuilder(entityClass, List.copyOf(Arrays.asList(searchFields)));
        declarations.add(declaration);
        return declaration;
    }

    /**
     * Вернуть снимок деклараций в порядке регистрации.
     * Каталог дополнительно копирует и проверяет эти данные.
     */
    public synchronized List<Declaration> declarations() {
        return declarations.stream()
            .map(SourceBuilder::toDeclaration)
            .toList();
    }

    /** Декларация одного источника до её проверки и разрешения в каталоге. */
    public record Declaration(Class<?> entityClass,
                              List<String> searchFields,
                              List<String> displayFields) {
        public Declaration {
            Objects.requireNonNull(entityClass, "entityClass cannot be null");
            Objects.requireNonNull(searchFields, "searchFields cannot be null");
            Objects.requireNonNull(displayFields, "displayFields cannot be null");
            searchFields = List.copyOf(searchFields);
            displayFields = List.copyOf(displayFields);
        }
    }

    /**
     * Изменяемая только на этапе объявления часть конфигурации одного источника.
     * После построения {@link GlobalSearchCatalog} runtime-каталог от неё не зависит.
     */
    public static final class SourceBuilder {
        private final Class<?> entityClass;
        private final List<String> searchFields;
        private List<String> displayFields = List.of();

        private SourceBuilder(Class<?> entityClass, List<String> searchFields) {
            this.entityClass = entityClass;
            this.searchFields = searchFields;
        }

        /**
         * Явно задать поля для fallback-подписи сущности без {@code HasDisplayName}.
         * Поддерживаются только прямые простые поля; произвольные шаблоны не вводятся.
         */
        public SourceBuilder displayFields(String... fields) {
            Objects.requireNonNull(fields, "displayFields cannot be null");
            this.displayFields = List.copyOf(Arrays.asList(fields));
            return this;
        }

        private Declaration toDeclaration() {
            return new Declaration(entityClass, searchFields, displayFields);
        }
    }
}
