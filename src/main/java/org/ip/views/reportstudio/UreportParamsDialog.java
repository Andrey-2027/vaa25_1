package org.ip.views.reportstudio;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.ureport.params.ParamUiType;
import org.ipro.ureport.params.UreportParamSpec;

/**
 * Диалог запуска отчёта UReport3 (UnionReport1.md, Ф2): поля по
 * UreportParamSpec из XML-шаблона. Результат открывается в новой вкладке
 * (/ureport/preview), экспорт - /ureport/pdf, /ureport/excel (Р4).
 *
 * Параметры передаются query-параметрами; пустые не биндятся - шаблон
 * обрабатывает отсутствие паттерном ":p is null OR ...".
 */
public class UreportParamsDialog extends Dialog {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String fileName;
    private final Map<String, com.vaadin.flow.component.HasValue<?, ?>> fields = new HashMap<>();

    public UreportParamsDialog(String reportName, String fileName, List<UreportParamSpec> specs) {
        this.fileName = fileName;
        setHeaderTitle("Параметры отчёта: " + reportName);
        setWidth("min(560px, 92vw)");

        VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(true);
        if (specs.isEmpty()) {
            body.add(new com.vaadin.flow.component.html.Span(
                    "У отчёта нет параметров - будет выполнен как есть."));
        }
        for (UreportParamSpec spec : specs) {
            body.add(buildField(spec));
        }

        Button preview = new Button("Просмотр", event -> open("/ureport/preview"));
        preview.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button pdf = new Button("PDF", event -> open("/ureport/pdf"));
        Button excel = new Button("Excel", event -> open("/ureport/excel"));
        Button cancel = new Button("Отмена", event -> close());

        HorizontalLayout buttons = new HorizontalLayout(preview, pdf, excel, cancel);
        buttons.setWrap(true);
        add(body, buttons);
    }

    private com.vaadin.flow.component.Component buildField(UreportParamSpec spec) {
        com.vaadin.flow.component.Component field;
        switch (spec.uiType()) {
            case INTEGER -> {
                NumberField number = new NumberField(spec.caption());
                number.setStep(1d);
                number.setWidthFull();
                if (spec.defaultValue() != null && !spec.defaultValue().isBlank()) {
                    try {
                        number.setValue(Double.parseDouble(spec.defaultValue().trim()));
                    } catch (NumberFormatException ignored) {
                        // некорректный default - оставляем пустым
                    }
                }
                field = number;
            }
            case FLOAT -> {
                NumberField number = new NumberField(spec.caption());
                number.setWidthFull();
                if (spec.defaultValue() != null && !spec.defaultValue().isBlank()) {
                    try {
                        number.setValue(Double.parseDouble(spec.defaultValue().trim()));
                    } catch (NumberFormatException ignored) {
                        // некорректный default - оставляем пустым
                    }
                }
                field = number;
            }
            case BOOLEAN -> {
                Checkbox checkbox = new Checkbox(spec.caption());
                if (spec.defaultValue() != null) {
                    checkbox.setValue(Boolean.parseBoolean(spec.defaultValue().trim()));
                }
                field = checkbox;
            }
            case DATE -> {
                DatePicker picker = new DatePicker(spec.caption());
                picker.setWidthFull();
                if (spec.defaultValue() != null && !spec.defaultValue().isBlank()) {
                    try {
                        picker.setValue(LocalDate.parse(spec.defaultValue().trim(), DATE_FORMAT));
                    } catch (Exception ignored) {
                        // некорректный default - оставляем пустым
                    }
                }
                field = picker;
            }
            default -> {
                TextField text = new TextField(spec.caption());
                text.setWidthFull();
                text.setValue(spec.defaultValue() == null ? "" : spec.defaultValue());
                field = text;
            }
        }
        fields.put(spec.name(), (com.vaadin.flow.component.HasValue<?, ?>) field);
        return field;
    }

    private void open(String endpoint) {
        StringBuilder url = new StringBuilder(endpoint)
                .append("?_u=file:").append(urlEncode(fileName));
        for (Map.Entry<String, com.vaadin.flow.component.HasValue<?, ?>> entry : fields.entrySet()) {
            String value = stringValue(entry.getValue());
            if (value == null || value.isBlank()) {
                continue; // пустые не биндятся
            }
            url.append('&').append(urlEncode(entry.getKey())).append('=').append(urlEncode(value));
        }
        getUI().ifPresentOrElse(
                ui -> ui.getPage().open(url.toString(), "_blank"),
                () -> showError("Нет UI-контекста для открытия отчёта"));
    }

    private String stringValue(com.vaadin.flow.component.HasValue<?, ?> field) {
        Object value = field.getValue();
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return DATE_FORMAT.format(date);
        }
        if (value instanceof Double number) {
            // NumberField: целые без ".0"
            return number == Math.floor(number) && !number.isInfinite()
                    ? String.valueOf(number.longValue())
                    : String.valueOf(number);
        }
        return String.valueOf(value);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 5_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
