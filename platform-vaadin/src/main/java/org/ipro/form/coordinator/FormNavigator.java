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
 * он несет {@code ApplicationContext}, {@code ServiceLocator} и режим открытия — состояние
 * конкретного UI в singleton. Здесь только то, что нужно прикладному view: создать список,
 * открыть список в рабочей области и открыть карточку.</p>
 *
 * <p>Рабочая область не передаётся через этот контракт: она — UI-scoped бин приложения
 * ({@code WorkspaceGateway}), и координатор берёт её сам. Поэтому прикладной view не знает ни
 * про вкладки вообще, ни про то, какой именно UI их держит.</p>
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
     * Default-вариант без параметров открытия — самый частый случай у прикладных view.
     *
     * <p>Контракт обязан покрывать то, что приложение реально вызывает: иначе view пришлось бы
     * снова зависеть от класса реализации, а это ровно то, что разделяет этот интерфейс.</p>
     */
    default <T extends IdentifiableEntity, ID> ListForm<T, ID> createListForm(Class<T> entityClass) {
        return createListForm(entityClass, null, null);
    }

    /**
     * Открыть форму элемента с вариантом и параметрами открытия.
     *
     * @param parameters бизнес-параметры открытия (могут быть null); инфраструктура
     *                   через карту не передается
     */
    <T extends IdentifiableEntity, ID> void openItemForm(
        Class<T> entityClass, String variant, ID id, Consumer<T> onSaved,
        Map<String, Object> parameters);

    /**
     * Открыть форму списка в рабочей области текущего UI (вкладка 1С-стиля).
     *
     * <p>Работает там, где приложение предоставило UI-scoped {@code WorkspaceGateway}; без него —
     * отказ с причиной, а не тихо созданная никому не показанная форма. Вызывающему, которому форма
     * нужна внутри своего view, нужен не этот метод, а {@link #createListForm}.</p>
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param parameters бизнес-параметры открытия (могут быть null)
     */
    <T extends IdentifiableEntity, ID> void openListForm(Class<T> entityClass, String variant,
                                                         Map<String, Object> parameters);
}
