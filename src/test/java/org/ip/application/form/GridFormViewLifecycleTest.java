package org.ip.application.form;

import org.ip.model.GridFormView;
import org.ipro.crud.ValidationException;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityDeleteContext;
import org.ipro.lifecycle.EntityUpdateContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ownership-правило сохранённого вида: общий вид меняет кто угодно, личный — только автор.
 *
 * <p>C4.6 волна F: правило переехало из типизированного сервиса в canonical-executed
 * lifecycle ({@link GridFormViewLifecycle}). Именно поэтому оно больше не требует сужения
 * capability типа: pipeline вызывает handler в том же порядке, что и остальные lifecycle
 * запреты — после capability и раннего RLS, до persistence.</p>
 */
class GridFormViewLifecycleTest {

    private final GridFormViewLifecycle lifecycle =
        new GridFormViewLifecycle(() -> SecurityContextHolder.getContext()
            .getAuthentication() == null ? "system"
            : SecurityContextHolder.getContext().getAuthentication().getName());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sharedViewIsEditableByAnyone() {
        authenticateAs("someone-else");
        GridFormView shared = view("view-owner", true);

        assertThatCode(() -> lifecycle.beforeUpdate(updating(shared, shared)))
            .doesNotThrowAnyException();
        assertThatCode(() -> lifecycle.beforeDelete(deleting(shared)))
            .doesNotThrowAnyException();
    }

    @Test
    void personalViewIsEditableOnlyByItsAuthor() {
        GridFormView personal = view("view-owner", false);

        authenticateAs("view-owner");
        assertThatCode(() -> lifecycle.beforeUpdate(updating(personal, personal)))
            .doesNotThrowAnyException();
        assertThatCode(() -> lifecycle.beforeDelete(deleting(personal)))
            .doesNotThrowAnyException();

        authenticateAs("other-user");
        assertThatThrownBy(() -> lifecycle.beforeUpdate(updating(personal, personal)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("личный")
            .hasMessageContaining("view-owner");
        assertThatThrownBy(() -> lifecycle.beforeDelete(deleting(personal)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("личный");
    }

    /**
     * Решение принимается по сохранённому состоянию, а не по payload: иначе чужой личный вид
     * можно было бы изменить, передав его копию с {@code shared = true}.
     */
    @Test
    void updateDecidesOnTheStoredStateNotOnThePayload() {
        authenticateAs("other-user");
        GridFormView stored = view("view-owner", false);
        GridFormView payload = view("view-owner", true);
        payload.setId(stored.getId());

        assertThatThrownBy(() -> lifecycle.beforeUpdate(updating(stored, payload)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("личный");
    }

    private static GridFormView view(String createdBy, boolean shared) {
        GridFormView view = new GridFormView("ownIt.formKey", "Вид", "[]", shared);
        view.setId(42L);
        view.setCreatedBy(createdBy);
        return view;
    }

    private static EntityUpdateContext<GridFormView> updating(GridFormView original,
                                                              GridFormView updated) {
        return new EntityUpdateContext<>(original, updated, context("update:GridFormView"));
    }

    private static EntityDeleteContext<GridFormView> deleting(GridFormView entity) {
        return new EntityDeleteContext<>(entity, context("delete:GridFormView"));
    }

    private static EventContext context(String operation) {
        return EventContext.forEntity(GridFormView.class, 42L, EventSource.SYSTEM, operation);
    }

    private static void authenticateAs(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
    }
}
