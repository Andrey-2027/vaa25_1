package org.ip.form.builder.layout;

/**
 * Узел дерева произвольного layout'а Формы Элемента — см. {@link ItemFormLayout}.
 *
 * Строится через методы {@code addField}/{@code addPanel}/{@code addTabSheet}/{@code addCustom}
 * на {@code org.ip.form.builder.ItemFormBuilder} — для случаев, когда нужна нестандартная
 * компоновка (группировка полей в панели, вкладки, вставка произвольных компонентов), но не
 * хочется вручную собирать весь layout мимо {@code FieldFactory}/{@code FormBindingRegistry}.
 */
public sealed interface LayoutNode permits FieldNode, PanelNode, TabSheetNode, CustomNode {
}
