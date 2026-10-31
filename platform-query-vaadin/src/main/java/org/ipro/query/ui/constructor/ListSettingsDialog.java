package org.ipro.query.ui.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.query.*;
import org.ipro.query.ui.list.*;
import java.util.*;

/** Edits a query-backed list without mutating its owner until Apply. */
final class ListSettingsDialog extends Dialog {
    private final QueryConstructorDraft draft;
    private final ConstructorList editing;
    private final Runnable changed;
    private VisualQueryPackage source;
    private final ComboBox<String> key = new ComboBox<>("Ключ источника");
    private final ComboBox<String> value = new ComboBox<>("Поле значения");
    private final ComboBox<String> target = new ComboBox<>("Поле связи основного запроса");
    private final TextField separator = new TextField("Разделитель");
    private final Checkbox distinct = new Checkbox("Без повторов", true);
    private final ComboBox<VisualQueryOrder.Direction> direction = new ComboBox<>("Порядок по тексту значения");
    private final TextField alias = new TextField("Имя колонки");
    private final Span status = new Span();
    private final Span preview = new Span();
    private final Span sourceInfo = new Span("Источник не выбран");
    private final Button apply;

    ListSettingsDialog(QueryConstructorDraft draft, ConstructorList editing, Runnable changed) {
        this.draft = draft; this.editing = editing; this.changed = changed;
        setHeaderTitle("Объединить значения в строку"); setWidth("640px"); setMaxWidth("96vw");
        addClassNames("form-1c", "query-constructor");
        var sourceButton = new Button("Выбрать / изменить запрос источника…", e -> openSource());
        separator.setValue(editing == null ? ", " : editing.separator());
        separator.setHelperText("Пробелы сохраняются. Пустой разделитель допустим.");
        distinct.setValue(editing == null || editing.distinct());
        direction.setItems(VisualQueryOrder.Direction.values());
        direction.setItemLabelGenerator(d -> d == VisualQueryOrder.Direction.ASC ? "По возрастанию" : "По убыванию");
        direction.setValue(editing == null ? VisualQueryOrder.Direction.ASC : editing.direction());
        var targets = new LinkedHashSet<String>();
        draft.tables().forEach(t -> {
            if (editing != null && t.entity().entityName().equals(editing.name())) return;
            t.entity().fields().stream().filter(f -> f.javaType() != Object.class).forEach(f -> targets.add(t.alias() + "." + f.name()));
            if (t.entity().javaType() != Object.class) targets.add(t.alias() + ".id");
        });
        target.setItems(targets);
        String suggested = "listValues";
        var occupied = new HashSet<String>();
        draft.selections().forEach(f -> occupied.add(f.resultName())); draft.aggregates().forEach(f -> occupied.add(f.resultName()));
        draft.expressions().forEach(f -> occupied.add(f.resultName()));
        for (int n = 2; occupied.contains(suggested); n++) suggested = "listValues" + n;
        alias.setValue(suggested);
        if (editing != null) {
            source = editing.sourceQuery();
            String tableAlias = draft.cteFieldAlias(editing.name());
            draft.selections().stream().filter(f -> f.path().equals(tableAlias + ".value")).findFirst().ifPresent(f -> alias.setValue(f.resultName()));
            draft.joins().stream().filter(j -> j.alias().equals(tableAlias)).findFirst().ifPresent(j -> {
                if (j.on() instanceof JoinCondition.Predicate p) target.setValue(p.leftPath().equals(tableAlias + ".key") ? p.rightPath() : p.leftPath());
            });
            if (source == null) {
                var old = editing.stages().getLast().definition();
                source = new VisualQueryPackage(List.of(), new VisualQueryDefinition(old.version(), old.entityName(), old.entityAlias(),
                        List.of(new VisualQueryDefinition.SelectField("p.id", "key"),
                                new VisualQueryDefinition.SelectField(old.aggregates().getFirst().path(), "value")), old.joins(), List.of(), List.of(), null));
            }
            refreshColumns(); key.setValue(editing.keyColumn() == null ? "key" : editing.keyColumn());
            value.setValue(editing.valueColumn() == null ? "value" : editing.valueColumn());
        }
        apply = new Button(editing == null ? "Добавить" : "Применить", e -> apply());
        apply.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        status.getStyle().set("color", "var(--lumo-error-text-color)");
        var content = new VerticalLayout(sourceButton, sourceInfo, key, value, target, separator, distinct,
                new Span("Без повторов — одинаковый текст выводится один раз, даже из разных строк."),
                direction, alias, preview, new Span("Нет видимых значений — пустая колонка. XLSX: до 32 767 символов в ячейке."), status);
        content.setPadding(false); content.setWidthFull();
        for (var field : List.of(key, value, target, direction)) field.setWidthFull();
        separator.setWidthFull(); alias.setWidthFull(); add(content);
        getFooter().add(new Button("Отмена", e -> close()), apply);
        separator.addValueChangeListener(e -> updatePreview()); distinct.addValueChangeListener(e -> updatePreview());
        direction.addValueChangeListener(e -> updatePreview()); updatePreview();
        alias.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.EAGER);
        alias.addValueChangeListener(e -> validateInputs()); key.addValueChangeListener(e -> validateInputs());
        value.addValueChangeListener(e -> validateInputs()); target.addValueChangeListener(e -> validateInputs());
        validateInputs();
    }

    private void openSource() {
        var dialog = new JpqlQueryBuilderDialog(draft.catalog(), draft.parameterNames(), source == null ? null : source.main(),
                source, List.of(), result -> { source = result.queryPackage() == null ? new VisualQueryPackage(List.of(), result.definition()) : result.queryPackage(); refreshColumns(); });
        dialog.open();
    }

    void setSource(VisualQueryPackage source) { this.source = source; refreshColumns(); }

    private void refreshColumns() {
        if (source == null) return;
        var virtual = VisualQueryPackage.VirtualCatalog.empty();
        for (var cte : source.ctes()) virtual = virtual.add(cte.name(), cte.definition(), draft.catalog());
        var fields = VisualQueryPackage.VirtualEntity.from("listInput", source.main(), draft.catalog(), virtual).asCatalogEntity().fields();
        String oldKey = key.getValue(), oldValue = value.getValue();
        key.setItems(fields.stream().filter(f -> f.javaType() != Object.class).map(QueryBuilderMetadataCatalog.Field::name).toList());
        value.setItems(fields.stream().filter(f -> f.javaType() == String.class).map(QueryBuilderMetadataCatalog.Field::name).toList());
        if (fields.stream().anyMatch(f -> f.name().equals(oldKey))) key.setValue(oldKey);
        if (fields.stream().anyMatch(f -> f.name().equals(oldValue) && f.javaType() == String.class)) value.setValue(oldValue);
        sourceInfo.setText("Источник: " + source.main().entityName() + "; колонок: " + fields.size());
        if (apply != null) validateInputs();
    }

    private void validateInputs() {
        boolean validName = alias.getValue().matches("[A-Za-z_][A-Za-z0-9_]*");
        String existingAlias = editing == null ? null : draft.cteFieldAlias(editing.name());
        boolean taken = draft.selections().stream().anyMatch(f -> f.resultName().equals(alias.getValue()) && !f.path().equals(existingAlias + ".value"))
                || draft.aggregates().stream().anyMatch(a -> a.resultName().equals(alias.getValue()))
                || draft.expressions().stream().anyMatch(a -> a.resultName().equals(alias.getValue()));
        alias.setInvalid(!validName || taken);
        alias.setErrorMessage(taken ? "Имя колонки уже занято" : "Используйте латинские буквы, цифры и подчёркивание");
        apply.setEnabled(source != null && !key.isEmpty() && !value.isEmpty() && !target.isEmpty() && validName && !taken);
    }

    ComboBox<String> keyField() { return key; }
    ComboBox<String> valueField() { return value; }
    ComboBox<String> targetField() { return target; }
    TextField separatorField() { return separator; }
    TextField aliasField() { return alias; }

    private void updatePreview() {
        var samples = new ArrayList<>(List.of("Деталь А", "Деталь Б", "Деталь А"));
        if (distinct.getValue()) samples = new ArrayList<>(new LinkedHashSet<>(samples));
        samples.sort(direction.getValue() == VisualQueryOrder.Direction.DESC ? Comparator.reverseOrder() : Comparator.naturalOrder());
        preview.setText("Пример: " + String.join(separator.getValue(), samples));
    }

    boolean apply() {
        try {
            if (source == null || key.isEmpty() || value.isEmpty() || target.isEmpty()) throw new IllegalArgumentException("Выберите источник, ключ, значение и поле связи");
            if (!alias.getValue().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Некорректное имя колонки");
            String existingAlias = editing == null ? null : draft.cteFieldAlias(editing.name());
            boolean taken = draft.selections().stream().anyMatch(f -> f.resultName().equals(alias.getValue()) && !f.path().equals(existingAlias + ".value"))
                    || draft.aggregates().stream().anyMatch(a -> a.resultName().equals(alias.getValue()))
                    || draft.expressions().stream().anyMatch(a -> a.resultName().equals(alias.getValue()));
            if (taken) throw new IllegalArgumentException("Имя колонки уже занято");
            var generator = new ListCteGenerator(draft.catalog());
            var list = editing == null ? generator.generate(source, key.getValue(), value.getValue(), separator.getValue(), distinct.getValue(), direction.getValue(), draft.occupiedNames())
                    : generator.generateNamed(editing.name(), source, key.getValue(), value.getValue(), separator.getValue(), distinct.getValue(), direction.getValue());
            if (editing == null) draft.addConstructorListByField(list, target.getValue(), alias.getValue());
            else {
                draft.updateConstructorList(editing.name(), list, target.getValue());
                var selection = draft.selections().stream().filter(f -> f.path().equals(existingAlias + ".value")).findFirst().orElse(null);
                if (selection != null) draft.renameResultName(selection, alias.getValue());
            }
            changed.run(); close(); return true;
        } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); return false; }
    }
}
