package org.ipro.form.spi;

import java.util.List;
import java.util.Optional;

/**
 * Платформенный контракт хранилища видов грида (сохранённые наборы колонок).
 *
 * <p>Приложение предоставляет бин-реализацию (над своей сущностью видов);
 * формы работают только с этим интерфейсом и DTO {@link GridView}.</p>
 */
public interface GridViewStore {

    /** Виды, доступные текущему пользователю для formKey (общие + свои личные). */
    List<GridView> findVisibleViews(String formKey);

    Optional<GridView> findById(Long id);

    /** Создать вид от имени текущего пользователя. */
    GridView createView(String formKey, String name, String columns, boolean shared);

    /** Обновить вид (может бросить при отсутствии права на чужой личный вид). */
    GridView updateView(GridView view);

    /** Удалить вид (может бросить при отсутствии права на чужой личный вид). */
    void deleteView(Long id);
}
