package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.settings.SettingsRegistry;
import org.ipro.settings.SettingsService;
import org.ipro.settings.SettingsRegistry.FieldDescriptor;
import org.ipro.settings.SettingsRegistry.GroupInfo;
import org.springframework.context.annotation.Scope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Вкладка «Настройки» админ-раздела: разделы констант ({@code SettingsRegistry}) группируются
 * по подсистемам, поле строится лёгким построителем по {@code FieldType} (НЕ FieldFactory —
 * он жёстко завязан на {@code FieldMetadataInfo}). Доступ на изменение — по CHECK_ONLY-измерению
 * {@code SETTINGS:<Subsystem>} (canUpdate): без права раздел read-only. Секретные {@code @Setting}
 * не редактируются — показываются только дефолты из кода.
 */
@SpringComponent
@Scope("prototype")
public class SettingsAdminTab extends VerticalLayout {

    private static final String LABEL_WIDTH = "280px";

    private final SettingsRegistry registry;
    private final SettingsService settingsService;
    private final SubsystemRegistry subsystemRegistry;
    private final AccessService accessService;
    private final RlsCurrentUser currentUser;

    public SettingsAdminTab(SettingsRegistry registry,
                            SettingsService settingsService,
                            SubsystemRegistry subsystemRegistry,
                            AccessService accessService,
                            RlsCurrentUser currentUser) {
        this.registry = registry;
        this.settingsService = settingsService;
        this.subsystemRegistry = subsystemRegistry;
        this.accessService = accessService;
        this.currentUser = currentUser;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    /** Пересобрать раздел под текущего пользователя (вызывается при выборе вкладки). */
    public void refresh() {
        removeAll();
        List<GroupInfo> groups = registry.groups();
        if (groups.isEmpty()) {
            add(new Span("Разделы настроек не зарегистрированы (org.ip.settings пуст)."));
            return;
        }
        String username = currentUser.username();
        for (GroupInfo group : groups) {
            add(buildGroup(group, username));
        }
    }

    private Component buildGroup(GroupInfo group, String username) {
        String subsystemTitle = subsystemRegistry.findByMarker(group.subsystemMarker())
                .map(SubsystemNode::getTitle)
                .orElse(group.subsystemMarker().getSimpleName());
        boolean editable = accessService.canUpdate(group.rlsDimension(), null, username);

        VerticalLayout card = new VerticalLayout();
        card.setPadding(false);
        card.setSpacing(true);
        card.add(new H4(subsystemTitle + " — " + group.title()));

        if (!editable) {
            Span hint = new Span("Нет прав на изменение раздела (измерение "
                    + group.rlsDimension() + ").");
            hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
            card.add(hint);
        }

        for (FieldDescriptor field : group.fields()) {
            card.add(buildFieldRow(group.groupClass(), field, editable));
        }
        return card;
    }

    private Component buildFieldRow(Class<?> groupClass, FieldDescriptor field, boolean editable) {
        if (field.secret()) {
            HorizontalLayout row = new HorizontalLayout();
            row.setWidthFull();
            row.add(label(field.label()));
            Span value = new Span(String.valueOf(settingsService.get(groupClass, field.name())));
            value.getStyle().set("color", "var(--lumo-secondary-text-color)");
            Span note = new Span("(задаётся только в коде)");
            note.getStyle().set("color", "var(--lumo-tertiary-text-color)");
            row.add(value, note);
            row.setAlignItems(Alignment.CENTER);
            return row;
        }

        Component control = buildControl(field);
        loadValue(control, field, settingsService.get(groupClass, field.name()));
        ((HasEnabled) control).setEnabled(editable);

        Button save = new Button("Сохранить", new Icon(VaadinIcon.CHECK),
                e -> saveField(groupClass, field, control));
        Button reset = new Button("Сброс", new Icon(VaadinIcon.REFRESH),
                e -> resetField(groupClass, field, control));
        save.setEnabled(editable);
        reset.setEnabled(editable);

        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.add(label(field.label()), control, save, reset);
        row.setAlignItems(Alignment.CENTER);
        row.setVerticalComponentAlignment(Alignment.CENTER, control, save, reset);
        return row;
    }

    private static Span label(String text) {
        Span span = new Span(text);
        span.setWidth(LABEL_WIDTH);
        return span;
    }

    // ------------------------------------------------------------------ построитель поля

    private static Component buildControl(FieldDescriptor field) {
        return switch (field.type()) {
            case TEXT -> new TextField();
            case TEXT_AREA -> new TextArea();
            case EMAIL -> new EmailField();
            case PASSWORD -> new PasswordField();
            case INTEGER -> new IntegerField();
            case DECIMAL -> new NumberField();
            case BOOLEAN -> new Checkbox();
            case DATE -> new DatePicker();
            case DATETIME -> new DateTimePicker();
            case ENUM -> {
                ComboBox<Object> combo = new ComboBox<>();
                combo.setItems((Object[]) field.field().getType().getEnumConstants());
                yield combo;
            }
            case ENTITY_REFERENCE -> new TextField();
            case AUTO -> new TextField();
        };
    }

    private static void loadValue(Component control, FieldDescriptor field, Object value) {
        switch (field.type()) {
            case TEXT, TEXT_AREA, EMAIL, PASSWORD, AUTO ->
                ((TextField) control).setValue(value == null ? "" : String.valueOf(value));
            case INTEGER -> ((IntegerField) control).setValue(
                value instanceof Number n ? n.intValue() : null);
            case DECIMAL -> ((NumberField) control).setValue(
                value instanceof Number n ? n.doubleValue() : null);
            case BOOLEAN -> ((Checkbox) control).setValue(Boolean.TRUE.equals(value));
            case DATE -> ((DatePicker) control).setValue((LocalDate) value);
            case DATETIME -> ((DateTimePicker) control).setValue((LocalDateTime) value);
            case ENUM -> ((ComboBox<Object>) control).setValue(value);
            case ENTITY_REFERENCE -> ((TextField) control).setValue(
                value instanceof Number n ? String.valueOf(n.longValue()) : "");
        }
    }

    private void saveField(Class<?> groupClass, FieldDescriptor field, Component control) {
        try {
            Object value = toDomainValue(control, field);
            if (value == null) {
                settingsService.resetToDefault(groupClass, field.name());
            } else {
                settingsService.set(groupClass, field.name(), value);
            }
            loadValue(control, field, settingsService.get(groupClass, field.name()));
            Notification.show("Сохранено: " + field.label(), 2000, Notification.Position.BOTTOM_END);
        } catch (NumberFormatException e) {
            Notification.show("Числовое значение указано неверно: " + field.label(),
                    3000, Notification.Position.MIDDLE);
        }
    }

    private void resetField(Class<?> groupClass, FieldDescriptor field, Component control) {
        settingsService.resetToDefault(groupClass, field.name());
        loadValue(control, field, settingsService.get(groupClass, field.name()));
        Notification.show("Сброшено к значению из кода: " + field.label(),
                2000, Notification.Position.BOTTOM_END);
    }

    private static Object toDomainValue(Component control, FieldDescriptor field) {
        switch (field.type()) {
            case TEXT, TEXT_AREA, EMAIL, PASSWORD, AUTO -> {
                String value = ((TextField) control).getValue();
                return value == null || value.isBlank() ? null : value;
            }
            case INTEGER -> {
                Integer value = ((IntegerField) control).getValue();
                return value == null ? null : value.longValue();
            }
            case DECIMAL -> {
                Double value = ((NumberField) control).getValue();
                return value == null ? null : BigDecimal.valueOf(value);
            }
            case BOOLEAN -> {
                return ((Checkbox) control).getValue();
            }
            case DATE -> {
                return ((DatePicker) control).getValue();
            }
            case DATETIME -> {
                return ((DateTimePicker) control).getValue();
            }
            case ENUM -> {
                return ((ComboBox<Object>) control).getValue();
            }
            case ENTITY_REFERENCE -> {
                String value = ((TextField) control).getValue();
                return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
            }
        }
        return null;
    }
}
