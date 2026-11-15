package org.ipro.query.ui.listform;

import org.ipro.metadata.GridViewState;

import java.util.List;
import java.util.Optional;

/**
 * Порт «Видов» списка по запросу (UX-9). Виды хранятся там же, где у ListForm, — в
 * {@code grid_form_view} с ключом формы {@code querylist.<ключ>[.<вариант>]}; указатель вида по
 * умолчанию — в настройках пользователя. Модуль не зависит от форм платформы, поэтому адаптер к
 * существующему хранилищу даёт приложение (FQ-6).
 *
 * <p>Кто что видит и правит, решает адаптер: общий вид видят и правят все, личный — только автор.</p>
 */
public interface QueryListViews {
    /** Виды формы, доступные текущему пользователю: общие и свои. */
    List<View> views(String formKey);

    /** Идентификатор вида по умолчанию для текущего пользователя. */
    Optional<String> defaultViewId(String formKey);

    /** Задаёт вид по умолчанию; {@code null} снимает указатель. */
    void setDefaultView(String formKey, String viewId);

    View create(String formKey, String name, boolean shared, GridViewState state);

    /** Меняет название, признак «Общий» и состояние вида. */
    View update(String formKey, String viewId, String name, boolean shared, GridViewState state);

    void delete(String formKey, String viewId);

    /**
     * @param editable текущий пользователь может изменить и удалить вид
     */
    record View(String id, String name, boolean shared, boolean editable, GridViewState state) { }
}
