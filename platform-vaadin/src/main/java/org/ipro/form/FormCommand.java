package org.ipro.form;

import java.util.Set;

import org.ipro.form.action.ActionId;

/**
 * Дескриптор команды командной панели диалоговой формы ({@link DialogForm}) и карточки элемента
 * ({@code ItemForm}). Состояние меняется только здесь — тем самым кнопка панели и пункт меню «Ещё»
 * остаются согласованными; внутренний рендерер панели наружу не выходит.
 */
public final class FormCommand {

    private final CommandBar.Command delegate;
    private final ActionId id;
    private final Set<ActionId> iconOnlyIds;
    private final Runnable onViewChanged;

    FormCommand(CommandBar.Command delegate, ActionId id, Set<ActionId> iconOnlyIds,
                Runnable onViewChanged) {
        this.delegate = delegate;
        this.id = id;
        this.iconOnlyIds = iconOnlyIds;
        this.onViewChanged = onViewChanged;
    }

    public FormCommand setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
        return this;
    }

    public FormCommand setVisible(boolean visible) {
        delegate.setVisible(visible);
        return this;
    }

    public FormCommand setTooltip(String tooltip) {
        delegate.setTooltip(tooltip);
        return this;
    }

    /** На панели показывать только иконку (название остаётся в подсказке и в «Ещё»). */
    public FormCommand iconOnly() {
        if (iconOnlyIds.add(id)) {
            onViewChanged.run();
        }
        return this;
    }
}
