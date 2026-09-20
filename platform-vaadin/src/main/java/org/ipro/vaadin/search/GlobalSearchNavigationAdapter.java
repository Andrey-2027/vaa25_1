package org.ipro.vaadin.search;

import org.ipro.form.coordinator.FormNavigator;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchResult;
import org.ipro.search.GlobalSearchSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/**
 * Преобразует безопасный DTO результата поиска в навигацию к карточке сущности.
 *
 * <p>Адаптер не открывает entity напрямую и не обходит RLS: фактическое чтение и
 * проверка права на открытие остаются в {@link FormNavigator} и сервисном слое.</p>
 *
 * <p>D3.5.3: зависимость — узкий {@code FormNavigator}, а не concrete singleton
 * {@code FormCoordinator}. Навигатор UI-scoped, а адаптер — singleton: инъекция идёт
 * через {@code ObjectProvider} (у {@code @UIScope} нет scoped-proxy, прямое внедрение
 * UI-бины в синглтон роняло бы старт). Резолв — в момент вызова, когда UI-scope
 * активен, поэтому шапка каждого UI открывает карточку в своём UI.</p>
 */
public final class GlobalSearchNavigationAdapter {

    private final GlobalSearchCatalog catalog;
    private final ObjectProvider<FormNavigator> navigators;

    public GlobalSearchNavigationAdapter(GlobalSearchCatalog catalog,
                                         ObjectProvider<FormNavigator> navigators) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.navigators = Objects.requireNonNull(navigators, "navigators");
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

        formNavigator().openItemForm((Class) source.entityClass(), null, result.entityId(), null, null);
    }

    private FormNavigator formNavigator() {
        FormNavigator navigator = navigators.getObject();
        return Objects.requireNonNull(navigator, "no FormNavigator for the current UI");
    }
}
