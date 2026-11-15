package org.ipro.query.ui.admin;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.query.list.QueryListDefinition.Command;
import org.ipro.query.list.QueryListDefinition.CommandObject;
import org.ipro.query.list.QueryListRegistry;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class QueryListCommandsTab extends VerticalLayout {
    private final QueryListAdminEnvironment env;
    private final Runnable changed;
    private final ComboBox<String> entity = new ComboBox<>("Основная сущность");
    private final ComboBox<String> column = new ComboBox<>("Колонка объекта команды");
    private final EnumMap<Command, Checkbox> choices = new EnumMap<>(Command.class);
    private final Span status = new Span();
    private List<CommandObject> checkedColumns = List.of();
    private boolean updating;

    QueryListCommandsTab(QueryListAdminEnvironment env, boolean readOnly, Runnable changed) {
        this.env = env;
        this.changed = changed;
        setWidthFull();
        setMaxWidth("48rem");
        entity.setWidthFull();
        entity.setItemLabelGenerator(env.entityTitle()::apply);
        entity.setClearButtonVisible(true);
        entity.setReadOnly(readOnly);
        column.setWidthFull();
        column.setClearButtonVisible(true);
        column.setReadOnly(readOnly);
        column.setHelperText("Нужна для действий над выбранной записью. Для создания достаточно сущности.");
        HorizontalLayout fields = new HorizontalLayout(entity, column);
        fields.setWidthFull();
        fields.setFlexGrow(1, entity, column);
        fields.getStyle().set("flex-wrap", "wrap");
        entity.setMinWidth("14rem");
        column.setMinWidth("14rem");
        HorizontalLayout commands = new HorizontalLayout();
        commands.getStyle().set("flex-wrap", "wrap");
        choices.put(Command.CREATE, new Checkbox("Создать"));
        choices.put(Command.OPEN, new Checkbox("Открыть"));
        choices.put(Command.EDIT, new Checkbox("Изменить"));
        choices.put(Command.DELETE, new Checkbox("Удалить"));
        choices.values().forEach(choice -> {
            choice.setReadOnly(readOnly);
            choice.addValueChangeListener(event -> {
                if (!updating) changed.run();
            });
            commands.add(choice);
        });
        status.getStyle().set("color", "var(--lumo-secondary-text-color)");
        status.setVisible(false);
        add(fields, commands, status);
        entity.addValueChangeListener(event -> {
            if (updating) return;
            updateColumns(null);
            updateChoices(true);
            changed.run();
        });
        column.addValueChangeListener(event -> {
            if (updating) return;
            updateChoices(true);
            changed.run();
        });
    }

    void load(CommandObject object, Set<Command> commands) {
        checkedColumns = List.of();
        setFields(object);
        updating = true;
        try {
            choices.forEach((command, choice) -> choice.setValue(commands.contains(command)));
        } finally {
            updating = false;
        }
        status.setVisible(false);
        updateChoices(false);
    }

    void show(QueryListRegistry.Check check) {
        checkedColumns = check.commandColumns();
        setFields(object());
        updateChoices(false);
        String message = check.diagnostic();
        if (message == null && checkedColumns.isEmpty()) {
            message = "В запросе нет проверенных колонок с идентификаторами сущностей.";
        }
        status.setText(message == null ? "" : message);
        status.setVisible(message != null);
    }

    CommandObject object() {
        return entity.getValue() == null ? null : new CommandObject(entity.getValue(), column.getValue());
    }

    Set<Command> commands() {
        return choices.entrySet().stream().filter(entry -> entry.getValue().getValue())
                .map(java.util.Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private void setFields(CommandObject object) {
        updating = true;
        try {
            var entities = new LinkedHashSet<>(env.commandEntities());
            for (CommandObject candidate : checkedColumns) entities.add(candidate.entityClass());
            if (object != null) entities.add(object.entityClass());
            entity.setItems(entities.stream()
                    .sorted(Comparator.comparing(env.entityTitle()).thenComparing(Comparator.naturalOrder())).toList());
            entity.setValue(object == null ? null : object.entityClass());
            setColumns(object == null ? null : object.column());
        } finally {
            updating = false;
        }
    }

    private void updateColumns(String selected) {
        updating = true;
        try {
            setColumns(selected);
        } finally {
            updating = false;
        }
    }

    private void setColumns(String selected) {
        var columns = new LinkedHashSet<String>();
        checkedColumns.stream().filter(candidate -> candidate.entityClass().equals(entity.getValue()))
                .map(CommandObject::column).forEach(columns::add);
        if (selected != null) columns.add(selected);
        column.setItems(columns);
        column.setValue(selected);
        column.setEnabled(entity.getValue() != null);
    }

    private void updateChoices(boolean clearUnavailable) {
        choices.forEach((command, choice) -> {
            boolean available = entity.getValue() != null
                    && (command == Command.CREATE || column.getValue() != null);
            choice.setEnabled(available || !clearUnavailable && choice.getValue());
            if (!available && clearUnavailable) choice.setValue(false);
        });
    }
}
