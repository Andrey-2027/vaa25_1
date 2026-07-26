package org.ip.form.builder.layout;

import com.vaadin.flow.component.Component;

/**
 * Произвольный, заранее построенный Vaadin-компонент — вставляется в layout как есть.
 *
 * Не регистрируется в FormBindingRegistry: синхронизацию с сущностью (если она вообще нужна —
 * например, кнопка с собственным обработчиком) вызывающий код делает сам.
 */
public record CustomNode(Component component) implements LayoutNode {
}
