package org.ipro.form.coordinator;

import org.ipro.form.builtin.ListForm;
import org.ipro.identity.IdentifiableEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Узкий навигационный контракт форм для прикладного кода (D3.5.1, будет APP_API
 * {@code platform-vaadin}).
 *
 * <p>Реализация — {@link FormCoordinator} (INTERNAL). Сам координатор публичным не делать:
 * он несет {@code ApplicationContext}, {@code ServiceLocator}, mutable {@code WorkspaceGateway}
 * и режим открытия — состояние конкретного UI в singleton. Здесь только то, что нужно
 * прикладному view: создать список и открыть карточку.</p>
 *
 * <p>Заменяет строковый параметр {@code "coordinator"} в {@code FormContext.getParameters()}:
 * опечатка в ключе ловилась только рантаймом, а cast — {@code ClassCastException} у
 * пользователя. Типизированное поле ловится компилятором; цикл
 * {@code FormResolver → FormContext → FormCoordinator} не возникает, потому что резолвер
 * о координаторе не знает — навигацию подставляет сам координатор.</p>
 */
public interface FormNavigator {

    /**
     * Создать ListForm указанного варианта с параметрами открытия для встраивания в View.
     */
    <T extends IdentifiableEntity, ID> ListForm<T, ID> createListForm(
        Class<T> entityClass, String variant, Map<String, Object> parameters);

    /**
     * Открыть форму элемента с вариантом и параметрами открытия.
     *
     * @param parameters бизнес-параметры открытия (могут быть null); инфраструктура
     *                   через карту не передается
     */
    <T extends IdentifiableEntity, ID> void openItemForm(
        Class<T> entityClass, String variant, ID id, Consumer<T> onSaved,
        Map<String, Object> parameters);
}
