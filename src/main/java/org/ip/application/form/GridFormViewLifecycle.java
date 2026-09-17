package org.ip.application.form;

import org.ip.model.GridFormView;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityDeleteContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityUpdateContext;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Ownership-правило сохранённого вида формы списка: общий ({@code shared}) вид
 * редактирует/удаляет кто угодно, личный — только автор ({@code BaseEntity.createdBy}).
 *
 * <p>C4.6 волна F: правило переехало из {@code GridFormViewService.checkEditable} в
 * canonical-executed lifecycle. Раньше оно жило только внутри типизированного сервиса, и
 * это ограничивало canonical handle типа до {@code CREATE} — иначе прямой
 * {@code EntityDataAccess.update/delete} обходил бы проверку. Теперь правило исполняет тот
 * же write pipeline, что и остальные lifecycle-запреты (после capability-границы и раннего
 * RLS, до persistence), а canonical handle типа больше не нужно сужать.</p>
 *
 * <p>{@link #beforeUpdate} смотрит на <b>исходное</b> (сохранённое) состояние, а не на
 * payload: решение «можно ли менять эту строку» принимается по строке как она есть. Проверка
 * только payload позволила бы поменять чужой личный вид, передав его с {@code shared = true}.</p>
 */
@Component
public class GridFormViewLifecycle implements EntityLifecycle<GridFormView> {

    private final RlsCurrentUser currentUser;

    public GridFormViewLifecycle(RlsCurrentUser currentUser) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    @Override
    public Class<GridFormView> entityType() {
        return GridFormView.class;
    }

    @Override
    public void beforeUpdate(EntityUpdateContext<GridFormView> context) {
        requireEditableByCurrentUser(context.original());
    }

    @Override
    public void beforeDelete(EntityDeleteContext<GridFormView> context) {
        requireEditableByCurrentUser(context.entity());
    }

    /** shared = true — кто угодно; shared = false — только автор. */
    private void requireEditableByCurrentUser(GridFormView view) {
        if (view.isShared()) {
            return;
        }
        String username = currentUser.username();
        if (!username.equals(view.getCreatedBy())) {
            throw new ValidationException(
                "Этот вид личный (не общий) — изменять или удалять его может только автор: " +
                view.getCreatedBy());
        }
    }
}
