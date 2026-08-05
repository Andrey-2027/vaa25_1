package org.ip.form.builder.layout;

/**
 * Узел дерева произвольного layout'а Формы Элемента — см. {@link ItemFormLayout}.
 *
 * Конструируется напрямую (обычные record'ы, никакого builder'а не нужно):
 * <pre>
 * new ItemFormLayout(List.of(
 *     new FieldNode("code"),
 *     new PanelNode(List.of(new FieldNode("date"), new FieldNode("number"))),
 *     new CustomNode(myOwnComponent)
 * ))
 * </pre>
 * — для случаев, когда нужна нестандартная компоновка (группировка полей в панели, вкладки,
 * вставка произвольных компонентов), но не хочется вручную собирать весь layout мимо
 * {@code FieldFactory}/{@code FormBindingRegistry}.
 */
public sealed interface LayoutNode permits FieldNode, PanelNode, TabSheetNode, CustomNode {
}
