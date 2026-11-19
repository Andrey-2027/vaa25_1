package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.shared.Registration;

/** Keyboard confirmation for form containers. */
public final class FormKeyboard {
    // Vaadin relays a native input change to Flow. Dispatch before the action RPC,
    // including when the field stays focused after validation fails.
    static final String COMMIT_INPUT = """
        key => {
            const input = key.composedPath()[0];
            if (input?.matches?.('textarea, input:not([type="checkbox"]):not([type="radio"])')) {
                input.dispatchEvent(new Event('change', {bubbles: true}));
            }
        }
        """;

    private FormKeyboard() {
    }

    public static Registration confirm(Component scope, boolean control, Runnable action) {
        String target = control ? "" : """
            && !event.composedPath().some(node => node.matches?.(
                'button, a[href], [role="button"], [role="menuitem"], [role="menuitemcheckbox"], textarea, [contenteditable="true"]'))
            """;
        return scope.getElement().addEventListener("keydown", event -> action.run())
            .setFilter("""
                event.key === 'Enter' && event.ctrlKey === %s
                && !event.altKey && !event.shiftKey && !event.metaKey
                && !event.defaultPrevented && !event.isComposing && !event.repeat
                %s
                && ((%s)(event), true)
                """.formatted(control, target, COMMIT_INPUT))
            .preventDefault().stopPropagation();
    }
}
