package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.numbering.NumberingMetadataRegistry.NumberedFieldInfo;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.numbering.NumberingRule;
import org.ipro.numbering.NumberingRuleService;
import org.ipro.numbering.NumberingService;
import org.springframework.context.annotation.Scope;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Вкладка «Нумерация» админ-раздела: каталог {@code @Numbered}-полей приложения
 * ({@link NumberingMetadataRegistry}), сгруппированный по подсистемам сущностей. Для каждой
 * серии — редактор runtime-правила (period/prefix/pattern/manualInput/initialValue) через
 * {@link NumberingRuleService}; для GLOBAL-серий — правка текущего значения через
 * {@link NumberingService#setCurrentValue}. Доступ — ROLE_ADMIN (вкладка внутри AdminView).
 */
@SpringComponent
@Scope("prototype")
public class NumberingAdminTab extends VerticalLayout {

    private final NumberingMetadataRegistry numberingRegistry;
    private final NumberingRuleService ruleService;
    private final NumberingService numberingService;
    private final MetadataResolver metadataResolver;
    private final SubsystemRegistry subsystemRegistry;

    private final Grid<Row> grid = new Grid<>(Row.class, false);

    record Row(Class<?> entityClass, String fieldName, String entityTitle, String subsystemTitle,
               NumberingRule rule, long currentValue, boolean globalScope) {
    }

    public NumberingAdminTab(NumberingMetadataRegistry numberingRegistry,
                             NumberingRuleService ruleService,
                             NumberingService numberingService,
                             MetadataResolver metadataResolver,
                             SubsystemRegistry subsystemRegistry) {
        this.numberingRegistry = numberingRegistry;
        this.ruleService = ruleService;
        this.numberingService = numberingService;
        this.metadataResolver = metadataResolver;
        this.subsystemRegistry = subsystemRegistry;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Нумерация — правила по сущностям"));
        configureGrid();
        add(grid);
        grid.setSizeFull();
        setFlexGrow(1, grid);
    }

    /** Перечитать правила и значения (вызывается при выборе вкладки). */
    public void refresh() {
        grid.setItems(rows());
    }

    private void configureGrid() {
        grid.addColumn(Row::subsystemTitle).setHeader("Подсистема").setWidth("170px");
        grid.addColumn(Row::entityTitle).setHeader("Сущность").setFlexGrow(1);
        grid.addColumn(Row::fieldName).setHeader("Поле").setWidth("110px");
        grid.addColumn(r -> r.rule().getPeriod().name()).setHeader("Период").setWidth("100px");
        grid.addColumn(r -> r.rule().getPrefix()).setHeader("Префикс").setWidth("90px");
        grid.addColumn(r -> r.rule().getPattern()).setHeader("Шаблон").setFlexGrow(1);
        grid.addColumn(r -> r.rule().isManualInput() ? "да" : "всегда авто")
                .setHeader("Ручной ввод").setWidth("105px");
        grid.addColumn(r -> r.rule().getInitialValue() == null ? ""
                : String.valueOf(r.rule().getInitialValue())).setHeader("Начало").setWidth("70px");
        grid.addColumn(r -> r.globalScope() ? String.valueOf(r.currentValue()) : "по scope")
                .setHeader("Текущее").setWidth("80px");
        grid.addComponentColumn(this::editButton).setHeader("").setWidth("110px");
    }

    private List<Row> rows() {
        return numberingRegistry.all().stream().map(info -> {
            Class<?> clazz = info.entityClass();
            EntityMetadataInfo meta = metadataResolver.resolve(clazz);
            String subsystemTitle = subsystemRegistry.findByMarker(meta.getAnnotation().subsystem())
                    .map(SubsystemNode::getTitle).orElse("—");
            NumberingRule effective = ruleService.effectiveRule(
                    clazz.getSimpleName(), info.fieldName(), info.annotation());
            boolean global = info.annotation().scope().length == 0;
            long current = 0;
            if (global) {
                current = numberingService.currentValue(instantiate(clazz), fieldOf(clazz, info.fieldName()));
            }
            return new Row(clazz, info.fieldName(), meta.getListFormTitle(), subsystemTitle,
                    effective, current, global);
        }).toList();
    }

    private Component editButton(Row row) {
        return new Button("Изменить", new Icon(VaadinIcon.EDIT), e -> openDialog(row));
    }

    private void openDialog(Row row) {
        NumberingRule rule = row.rule();
        boolean persisted = rule.getId() != null;

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(row.entityTitle() + " · " + row.fieldName());
        dialog.setWidth("540px");

        ComboBox<NumberingPeriod> period = new ComboBox<>("Периодичность");
        period.setItems(NumberingPeriod.values());
        period.setValue(rule.getPeriod());

        TextField prefix = new TextField("Префикс");
        prefix.setValue(rule.getPrefix());

        TextField pattern = new TextField("Шаблон");
        pattern.setValue(rule.getPattern());

        Checkbox manualInput = new Checkbox("Разрешить ручной ввод");
        manualInput.setValue(rule.isManualInput());

        TextField initialValue = new TextField("Начальное значение новой серии");
        initialValue.setValue(rule.getInitialValue() == null ? "" : String.valueOf(rule.getInitialValue()));
        initialValue.setPlaceholder("пусто = с 1");

        VerticalLayout form = new VerticalLayout(period, prefix, pattern, manualInput, initialValue);
        form.setSpacing(true);

        HorizontalLayout actions = new HorizontalLayout();
        Button save = new Button("Сохранить правило", new Icon(VaadinIcon.CHECK), e -> {
            try {
                NumberingRule target = persisted ? rule : newRule(row);
                target.setPeriod(period.getValue());
                target.setPrefix(prefix.getValue());
                target.setPattern(pattern.getValue());
                target.setManualInput(manualInput.getValue());
                target.setInitialValue(parseInitial(initialValue.getValue()));
                ruleService.save(target);
                dialog.close();
                refresh();
                Notification.show("Правило сохранено", 2000, Notification.Position.BOTTOM_END);
            } catch (NumberFormatException ex) {
                Notification.show("Начальное значение — целое число", 3000, Notification.Position.MIDDLE);
            }
        });
        actions.add(save);

        if (persisted) {
            Button remove = new Button("Удалить правило", new Icon(VaadinIcon.TRASH), e -> {
                ruleService.delete(rule);
                dialog.close();
                refresh();
                Notification.show("Правило удалено — действуют дефолты аннотации",
                        2500, Notification.Position.BOTTOM_END);
            });
            actions.add(remove);
        }

        dialog.add(form, actions);

        if (row.globalScope()) {
            dialog.add(currentValueEditor(row));
        }

        dialog.open();
    }

    private Component currentValueEditor(Row row) {
        TextField current = new TextField("Текущее значение (последний выданный номер)");
        current.setValue(String.valueOf(row.currentValue()));
        Button apply = new Button("Установить", new Icon(VaadinIcon.PENCIL), e -> {
            try {
                numberingService.setCurrentValue(instantiate(row.entityClass()),
                        fieldOf(row.entityClass(), row.fieldName()), Long.parseLong(current.getValue().trim()));
                refresh();
                Notification.show("Текущее значение обновлено", 2000, Notification.Position.BOTTOM_END);
            } catch (NumberFormatException ex) {
                Notification.show("Укажите целое число", 3000, Notification.Position.MIDDLE);
            }
        });
        HorizontalLayout rowLayout = new HorizontalLayout(current, apply);
        rowLayout.setAlignItems(Alignment.BASELINE);
        return rowLayout;
    }

    private static NumberingRule newRule(Row row) {
        NumberingRule rule = new NumberingRule();
        rule.setEntityClass(row.entityClass().getSimpleName());
        rule.setFieldName(row.fieldName());
        return rule;
    }

    private static Long parseInitial(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value.trim());
    }

    private static Object instantiate(Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось создать экземпляр " + clazz.getName()
                    + " для работы со счётчиком", e);
        }
    }

    private static Field fieldOf(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
