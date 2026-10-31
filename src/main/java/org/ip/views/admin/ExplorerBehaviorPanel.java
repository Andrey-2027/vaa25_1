package org.ip.views.admin;

import com.vaadin.flow.component.ClickNotifier;
import com.vaadin.flow.component.HtmlContainer;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import org.ipro.vaadin.explorer.EntitySummary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Presents ready facts; only an explicit W9 action constructs an isolated preview. */
final class ExplorerBehaviorPanel extends Div {
    private final EntitySummary summary;
    private final ExplorerUiState state;
    private final EntityExplorerPilotPlace.Tab tab;
    private final ExplorerSourceReader sources;
    private final Consumer<String> breadcrumb;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, Runnable> selections = new LinkedHashMap<>();
    private final Div detail = new Div();
    private final ExplorerSourceView source = new ExplorerSourceView();
    private final Div sourcePage = new Div();
    private final SplitLayout split = new SplitLayout();

    ExplorerBehaviorPanel(EntitySummary summary, ExplorerUiState state, EntityExplorerPilotPlace.Tab tab,
                          ExplorerSourceReader sources, Consumer<String> breadcrumb) {
        this.summary = summary;
        this.state = state;
        this.tab = tab;
        this.sources = sources;
        this.breadcrumb = breadcrumb;
        addClassName("explorer-behavior-panel");
        Div list = new Div();
        list.addClassName("explorer-behavior-list");
        detail.addClassName("explorer-behavior-detail");
        split.addClassName("explorer-behavior-split");
        split.addToPrimary(list);
        split.addToSecondary(detail);
        ExplorerSequentialLayout.install(split, list, detail);
        list.getStyle().set("flex", "0 0 42%");
        detail.getStyle().set("flex", "1 1 0px");
        if (tab == EntityExplorerPilotPlace.Tab.FORMS) buildForms(list);
        else buildHooks(list);
        Button back = new Button("Назад к формам", e -> {
            sourcePage.setVisible(false);
            split.setVisible(true);
        });
        sourcePage.addClassName("explorer-form-source-page");
        sourcePage.add(back, source);
        sourcePage.setVisible(false);
        add(split, sourcePage);
        String saved = state.behaviorSelection(summary.entityClass(), tab);
        if (saved != null && selections.containsKey(saved)) choose(saved, false);
        else if (!selections.isEmpty()) choose(tab == EntityExplorerPilotPlace.Tab.FORMS && selections.containsKey("ITEM/default")
            ? "ITEM/default" : selections.keySet().iterator().next(), false);
        split.getElement().removeAttribute("data-detail");
    }

    private void buildForms(Div list) {
        list.add(new H4("Формы"));
        Div columns = new Div(new Span("Вид"), new Span("Вариант"), new Span("Класс"));
        columns.addClassNames("explorer-behavior-columns", "explorer-form-choice");
        list.add(columns);
        for (var form : summary.forms()) {
            String variant = form.variant() == null ? "default" : form.variant();
            String key = form.formType().name() + "/" + variant;
            Row row = row(key, () -> showForm(form));
            row.addClassName("explorer-form-choice");
            row.add(cell(form.formType().name()), cell(variant), cell(form.formClass() == null ? "Не объявлен" : form.formClass().getSimpleName()));
            list.add(row);
        }
        if (!summary.structure().sections().isEmpty()) list.add(new H4("Формы строк табчастей"));
        for (var section : summary.structure().sections()) {
            Row row = row("ROW:" + section.identity().id(), () -> showRowForm(section));
            row.add(cell(section.value().value() + " · " + section.identity().rowType().getSimpleName()));
            list.add(row);
        }
    }

    private void showForm(EntitySummary.FormRow form) {
        detail.removeAll();
        ExplorerPropertyList properties = new ExplorerPropertyList();
        properties.property("form-kind", "Вид", switch (form.formType()) {
            case ITEM -> "Форма элемента"; case LIST -> "Форма списка"; case SELECTION -> "Форма выбора";
        });
        properties.property("form-variant", "Вариант", form.variant() == null ? "default" : form.variant());
        properties.property("form-class", "Класс", form.formClass() == null ? new Span("Класс результата фабрики не объявлен")
            : namedType(form.formClass().getName()));
        properties.property("class-evidence", "Подтверждение", form.formClass() == null ? "Фабрика не вызывается для инспекции"
            : form.classDeclared() ? "Объявлен регистрацией; результат фабрики не проверялся" : "Стандартная платформа");
        properties.property("form-base", "База", form.baseClass().isBlank() ? "Не подтверждена" : form.baseClass());
        properties.property("registration", "Регистрация", form.registrationKind());
        Div configs = new Div();
        if (form.registrationClasses().isEmpty()) configs.add(new Span("Прикладной конфиг не указан"));
        else for (String config : form.registrationClasses()) configs.add(new Div(namedType(config)));
        properties.property("configuration", "Конфиг", configs);
        properties.property("recipe", "Рецепт / шаблон", "Генератор не подключён в пилоте");
        properties.property("result-file", "Результирующий файл", "Генерация не подключена");
        properties.property("form-composition", "Состав", "По метаданным; состав custom-формы не подтверждён");
        detail.add(new H4("Свойства выбранной регистрации"), properties);
        Div actions = new Div();
        actions.addClassName("explorer-source-actions");
        String previewReason = ExplorerFormPreview.unavailableReason(summary.entityClass(), form);
        Button preview = new Button("Предпросмотр", e -> new ExplorerFormPreviewDialog(summary.entityClass(), form).open());
        preview.setEnabled(previewReason == null);
        actions.add(preview);
        if (previewReason != null) actions.add(new Span(previewReason));
        if (form.classDeclared() && form.formClass() != null && !isStandardFormClass(form.formClass()))
            actions.add(new Button("Код класса", e -> openFormSource(new ExplorerSourceReader.Target(form.formClass().getName(), null))));
        for (String config : form.registrationClasses()) actions.add(new Button("Код регистрации", e -> openFormSource(new ExplorerSourceReader.Target(config, null))));
        detail.add(actions);
        breadcrumb.accept(summary.simpleName() + " / Формы / " + form.formType() + " / " + (form.variant() == null ? "default" : form.variant()));
    }

    private void showRowForm(EntitySummary.OwnedSectionRow section) {
        detail.removeAll();
        ExplorerPropertyList properties = new ExplorerPropertyList();
        properties.property("section", "Табчасть", section.value().value());
        properties.property("owner", "Владелец", section.identity().ownerType().getSimpleName());
        properties.property("row", "Класс строки", section.identity().rowType().getName());
        properties.property("standard-path", "Стандартный путь", "ItemTable.buildRowForm → ItemForm; поля строки по метаданным");
        properties.property("custom-row-form", "Custom-форма", "Декларация реализации формы строки не предоставлена");
        properties.property("recipe", "Рецепт / шаблон", "Генератор не подключён в пилоте");
        detail.add(new H4("Форма строки табчасти"), properties);
        breadcrumb.accept(summary.simpleName() + " / Формы / Строка / " + section.value().value());
    }

    private void openFormSource(ExplorerSourceReader.Target target) {
        split.setVisible(false);
        sourcePage.setVisible(true);
        source.show(sources.read(summary, target));
    }

    private static boolean isStandardFormClass(Class<?> type) {
        return java.util.Set.of("org.ipro.form.builtin.ItemForm", "org.ipro.form.builtin.ListForm",
            "org.ipro.form.SelectionForm").contains(type.getName());
    }

    private void buildHooks(Div list) {
        list.add(new H4("Процедуры"));
        var handler = summary.lifecycle().stream().filter(r -> r.hook().isBlank()).findFirst();
        if (handler.isPresent()) {
            Span label = new Span(handler.get().value().value());
            label.addClassName("explorer-property-note");
            list.add(label);
        }
        for (var hook : summary.lifecycle()) {
            if (hook.hook().isBlank()) continue;
            Row row = row(hook.hook(), () -> showHook(hook));
            row.addClassName("explorer-hook-choice");
            row.add(cell(hook.hook()), cell(hook.declared() ? "Есть" : "default"));
            list.add(row);
        }
        if (selections.isEmpty()) detail.add(new Span("Состав хуков недоступен: подтверждённые сведения о handler отсутствуют"));
    }

    private void showHook(EntitySummary.LifecycleRow hook) {
        detail.removeAll();
        ExplorerPropertyList properties = new ExplorerPropertyList();
        properties.property("hook-status", "Статус", hook.declared() ? "Переопределён handler'ом" : "Default контракта; не переопределён handler'ом");
        properties.property("hook-signature", "Сигнатура", hook.signature().isBlank() ? "Точная сигнатура не предоставлена" : hook.signature());
        properties.property("hook-phase", "Область / фаза", switch (hook.hook()) {
            case "beforeSave" -> "Проверка перед сохранением entity; внутри транзакции";
            case "beforeUpdate" -> "Проверка изменения существующей entity; внутри транзакции";
            case "beforeAggregateSave" -> "Проверка root и подключённых секций; внутри транзакции";
            case "beforeDelete" -> "Проверка перед удалением; внутри транзакции";
            case "onSave" -> "После repository.save; внутри транзакции";
            case "afterCommit" -> "После успешного commit; завершённую операцию отменить нельзя";
            default -> "Фаза не подтверждена";
        });
        Span note = new Span("Хуки не образуют одну обязательную последовательность. Default не означает отсутствие всех прикладных правил.");
        note.addClassName("explorer-property-note");
        String symbol = hook.value().symbol();
        int separator = symbol.indexOf('#');
        ExplorerSourceReader.Target target = separator > 0 ? new ExplorerSourceReader.Target(symbol.substring(0, separator), symbol.substring(separator + 1)) : null;
        ExplorerSourceView code = new ExplorerSourceView();
        code.show(sources.read(summary, target));
        detail.add(properties, note, code);
        breadcrumb.accept(summary.simpleName() + " / Обработчики / " + hook.hook());
    }

    private Row row(String key, Runnable selection) {
        Row row = new Row();
        row.addClassName("explorer-behavior-row");
        row.getElement().setAttribute("type", "button");
        row.getElement().setAttribute("data-behavior-choice", key);
        row.addClickListener(e -> choose(key, false));
        row.getElement().addEventListener("keydown", e -> {
            var keys = rows.keySet().stream().toList();
            int index = keys.indexOf(key);
            String pressed = e.getEventData().get("event.key").asText();
            int next = switch (pressed) {
                case "ArrowDown" -> Math.min(keys.size() - 1, index + 1);
                case "ArrowUp" -> Math.max(0, index - 1);
                case "Home" -> 0;
                default -> keys.size() - 1;
            };
            choose(keys.get(next), true);
        }).addEventData("event.key").setFilter("['ArrowDown','ArrowUp','Home','End'].includes(event.key) && (event.preventDefault(), true)");
        rows.put(key, row);
        selections.put(key, selection);
        return row;
    }

    private void choose(String key, boolean focus) {
        for (var entry : rows.entrySet()) {
            boolean selected = entry.getKey().equals(key);
            entry.getValue().getElement().setAttribute("aria-pressed", String.valueOf(selected));
            entry.getValue().getElement().setAttribute("tabindex", selected ? "0" : "-1");
            if (selected && focus) entry.getValue().getElement().executeJs("this.scrollIntoView({block:'nearest'});this.focus({preventScroll:true});");
        }
        state.rememberBehaviorSelection(summary.entityClass(), tab, key);
        selections.get(key).run();
        ExplorerSequentialLayout.showDetail(split);
    }

    private static Span cell(String value) {
        Span cell = new Span(value);
        cell.getElement().setAttribute("title", value);
        return cell;
    }

    private static Span namedType(String name) {
        Span value = new Span(name.substring(name.lastIndexOf('.') + 1));
        value.getElement().setAttribute("title", name);
        return value;
    }

    @Tag("button") private static final class Row extends HtmlContainer implements ClickNotifier<Row> { }
}
