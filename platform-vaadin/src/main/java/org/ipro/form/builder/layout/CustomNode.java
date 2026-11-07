package org.ipro.form.builder.layout;

import com.vaadin.flow.component.Component;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Произвольный Vaadin-компонент: фабрика на каждое открытие или готовый компонент
 * через прежний конструктор совместимости.
 *
 * Не регистрируется в FormBindingRegistry: синхронизацию с сущностью (если она вообще нужна —
 * например, кнопка с собственным обработчиком) вызывающий код делает сам.
 */
public record CustomNode(Component component, Supplier<? extends Component> factory) implements LayoutNode {
    public CustomNode {
        if ((component == null) == (factory == null))
            throw new IllegalArgumentException("Specify either a component or a component factory");
    }

    /** Compatibility entry point for an already constructed component. */
    public CustomNode(Component component) {
        this(component, null);
    }

    /** Invoked once per form construction, so a declaration can be reused across openings. */
    public CustomNode(Supplier<? extends Component> factory) {
        this(null, factory);
    }

    public Component createComponent() {
        return component != null ? component : Objects.requireNonNull(factory.get(), "CustomNode factory result");
    }
}
