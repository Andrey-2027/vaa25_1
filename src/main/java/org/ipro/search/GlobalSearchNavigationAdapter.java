package org.ipro.search;

import org.ipro.form.coordinator.FormCoordinator;

import java.util.Objects;

/**
 * Преобразует безопасный DTO результата поиска в навигацию к карточке сущности.
 *
 * <p>Адаптер не открывает entity напрямую и не обходит RLS: фактическое чтение и
 * проверка права на открытие остаются в {@link FormCoordinator} и сервисном слое.</p>
 */
public final class GlobalSearchNavigationAdapter {

    private final GlobalSearchCatalog catalog;
    private final FormCoordinator formCoordinator;

    public GlobalSearchNavigationAdapter(GlobalSearchCatalog catalog,
                                         FormCoordinator formCoordinator) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.formCoordinator = Objects.requireNonNull(formCoordinator, "formCoordinator");
    }

    /**
     * Открыть карточку записи из результата поиска.
     *
     * @throws IllegalArgumentException если результат не соответствует каталогу
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void open(GlobalSearchResult result) {
        Objects.requireNonNull(result, "result");
        GlobalSearchSource source = catalog.sourceOf(result.entityClass())
            .orElseThrow(() -> new IllegalArgumentException(
                "Результат поиска содержит незарегистрированную сущность: "
                    + result.entityClass().getName()));

        if (source.declarationOrder() != result.sourceOrder()
                || !source.groupTitle().equals(result.groupTitle())) {
            throw new IllegalArgumentException(
                "Результат поиска не соответствует текущему каталогу: "
                    + result.entityClass().getName());
        }

        formCoordinator.openItemForm((Class) source.entityClass(), result.entityId(), null);
    }
}
