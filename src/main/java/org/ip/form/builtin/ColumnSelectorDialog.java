package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.annotation.FieldType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Диалог "Настройка колонок" Формы Списка (1С-стиль "Изменить форму").
 *
 * Показывает дерево доступных полей:
 *   - поля самой сущности (все не-hidden @FieldMetadata);
 *   - для каждого ENTITY_REFERENCE-поля — раскрываемый узел с полями целевой сущности
 *     (один уровень вглубь), которые становятся колонками через точку
 *     ("unitOfMeasurement.shortCode").
 *
 * Отмеченные галочками узлы становятся колонками грида. Порядок: сначала уже активные
 * колонки (в их текущем порядке), затем добавленные — в порядке дерева.
 *
 * Диалог не знает про ListForm — возвращает выбранный состав через onApply, поэтому
 * переиспользуем и для Формы Выбора, когда это понадобится.
 */
public class ColumnSelectorDialog extends Dialog {

    /** Узел дерева: путь колонки (ключ) + подпись. */
    private record ColumnNode(String path, String label) {}

    private final EntityMetadataInfo metadata;
    private final List<ColumnPath> currentColumns;
    private final Consumer<List<ColumnPath>> onApply;
    private final Runnable onReset;
    private final Runnable onSaveAs;
    private final TreeGrid<ColumnNode> tree = new TreeGrid<>();

    /** Все узлы в порядке дерева — для стабильного порядка добавленных колонок. */
    private final Map<String, ColumnNode> nodesByPath = new LinkedHashMap<>();

    /**
     * @param onApply получает выбранный состав колонок при "Применить"
     * @param onReset вызывается при "Стандартные" (возврат к метаданным); null — кнопка
     *                применит состав из метаданных через onApply
     * @param onSaveAs вызывается при "Сохранить как..." — открывает диалог имени/shared
     *                 поверх ТЕКУЩЕГО состава колонок формы (не обязательно применённого
     *                 здесь только что); null — кнопка не показывается
     */
    public ColumnSelectorDialog(EntityMetadataInfo metadata,
                                MetadataResolver metadataResolver,
                                List<ColumnPath> currentColumns,
                                Consumer<List<ColumnPath>> onApply,
                                Runnable onReset,
                                Runnable onSaveAs) {
        this.metadata = metadata;
        this.currentColumns = List.copyOf(currentColumns);
        this.onApply = onApply;
        this.onReset = onReset;
        this.onSaveAs = onSaveAs;

        setHeaderTitle("Настройка колонок: " + metadata.getListFormTitle());
        setWidth("560px");
        setHeight("640px");
        setModal(true);
        setResizable(true);
        setDraggable(true);

        configureTree(metadataResolver);
        add(tree);
        configureButtons();
        preselectCurrent();
    }

    // === Дерево доступных полей ===

    private void configureTree(MetadataResolver metadataResolver) {
        TreeData<ColumnNode> data = new TreeData<>();

        for (FieldMetadataInfo field : metadata.getFormFields()) {
            ColumnNode node = new ColumnNode(field.getName(), field.getLabel());
            nodesByPath.put(node.path(), node);
            data.addItem(null, node);

            if (field.getResolvedType() == FieldType.ENTITY_REFERENCE) {
                addRelatedFields(data, node, field, metadataResolver);
            }
        }

        tree.setDataProvider(new TreeDataProvider<>(data));
        tree.addHierarchyColumn(ColumnNode::label).setHeader("Поле").setFlexGrow(2);
        tree.addColumn(ColumnNode::path).setHeader("Путь").setFlexGrow(1);
        tree.setSelectionMode(Grid.SelectionMode.MULTI);
        tree.setSizeFull();
    }

    /**
     * Дочерние узлы ENTITY_REFERENCE-поля: поля целевой сущности (один уровень).
     * Целевая сущность без @EntityMetadata — узел просто остаётся без детей.
     */
    private void addRelatedFields(TreeData<ColumnNode> data, ColumnNode parent,
                                  FieldMetadataInfo refField, MetadataResolver metadataResolver) {
        EntityMetadataInfo targetMeta;
        try {
            targetMeta = metadataResolver.resolve(refField.getJavaType());
        } catch (IllegalArgumentException notMetadataDriven) {
            return;
        }
        for (FieldMetadataInfo targetField : targetMeta.getFormFields()) {
            ColumnNode child = new ColumnNode(
                parent.path() + "." + targetField.getName(), targetField.getLabel());
            nodesByPath.put(child.path(), child);
            data.addItem(parent, child);
        }
    }

    // === Кнопки ===

    private void configureButtons() {
        Button apply = new Button("Применить", e -> applySelection());
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button reset = new Button("Стандартные", e -> {
            if (onReset != null) {
                onReset.run();
            } else {
                onApply.accept(metadata.getListColumnPaths());
            }
            close();
        });
        reset.setTooltipText("Вернуть состав колонок из метаданных");

        Button cancel = new Button("Отмена", e -> close());

        getFooter().add(reset, cancel, apply);

        if (onSaveAs != null) {
            Button saveAs = new Button("Сохранить как...", e -> {
                onSaveAs.run();
                close();
            });
            saveAs.setTooltipText("Сохранить текущий состав колонок как новый вид");
            getFooter().add(saveAs);
        }
    }

    private void applySelection() {
        List<String> selectedPaths = nodesByPath.keySet().stream()
            .filter(path -> tree.getSelectionModel().getSelectedItems().stream()
                .anyMatch(node -> node.path().equals(path)))
            .toList();

        if (selectedPaths.isEmpty()) {
            Notification.show("Выберите хотя бы одну колонку", 3000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        List<ColumnPath> result = new ArrayList<>(selectedPaths.size());
        // Сначала уже активные колонки в их текущем порядке (переиспользуем готовые ColumnPath)
        for (ColumnPath existing : currentColumns) {
            if (selectedPaths.contains(existing.getKey())) {
                result.add(existing);
            }
        }
        // Затем добавленные — в порядке дерева
        for (String path : selectedPaths) {
            if (currentColumns.stream().noneMatch(c -> c.getKey().equals(path))) {
                result.add(ColumnPath.resolve(metadata.getEntityClass(), path));
            }
        }

        onApply.accept(result);
        close();
    }

    // === Предвыбор текущих колонок ===

    private void preselectCurrent() {
        for (ColumnPath column : currentColumns) {
            ColumnNode node = nodesByPath.get(column.getKey());
            if (node == null) continue;
            tree.select(node);
            int dot = column.getKey().lastIndexOf('.');
            if (dot > 0) {
                ColumnNode parent = nodesByPath.get(column.getKey().substring(0, dot));
                if (parent != null) {
                    tree.expand(parent);
                }
            }
        }
    }
}
