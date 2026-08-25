package org.ip.views.reports;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import jakarta.servlet.http.HttpSession;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.run.JrxmlExecutionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Диалог запуска JR-отчёта (.jrxml): форма скалярных параметров из шаблона +
 * кнопка «Сформировать».
 *
 * <p>Выполнение — в фоновом потоке по паттерну {@code ReportRunDialog}:
 * SecurityContext и HttpSession захватываются до старта потока (критично для
 * session-scoped RlsReadableIdsCache), результат открывается в
 * {@link JrxmlPreviewDialog}. Формирование выполняет {@link JrxmlExecutionService}
 * — JPQL-источник проходит guard+RLS под учёткой пользователя.</p>
 */
public class JrxmlRunDialog extends Dialog {

    private final JrxmlTemplate template;
    private final JrxmlExecutionService executionService;
    /** nullable: запуск из каталога — контекста реестра нет. */
    private final org.ipro.reportstudio.param.ReportContext context;
    private final List<JrxmlExecutionService.JrxmlParamSpec> specs;
    private final Map<String, TextField> valueFields = new HashMap<>();
    private final Map<String, DatePicker> dateFields = new HashMap<>();

    public JrxmlRunDialog(JrxmlTemplate template, JrxmlExecutionService executionService) {
        this(template, executionService, null);
    }

    /**
     * Запуск из кнопки «Печать» реестра: служебные параметры
     * {@code parEntityId}/{@code parEntityIds} (если объявлены в jrxml)
     * заполняются автоматически из контекста — id текущей записи и
     * идентификаторы выделенных строк (та же конвенция, что у UDR).
     */
    public JrxmlRunDialog(JrxmlTemplate template, JrxmlExecutionService executionService,
                          org.ipro.reportstudio.param.ReportContext context) {
        this.template = template;
        this.executionService = executionService;
        this.context = context;
        this.specs = executionService.parameterSpecs(template);

        setHeaderTitle("Запуск отчёта: " + template.getName());
        setWidth("min(720px, 95vw)");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        VerticalLayout form = new VerticalLayout();
        form.setPadding(false);
        form.setSpacing(true);
        for (JrxmlExecutionService.JrxmlParamSpec spec : specs) {
            addParameterField(form, spec);
        }

        Button run = new Button("Сформировать", event -> runReport());
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Закрыть", event -> close());
        HorizontalLayout actions = new HorizontalLayout(run, cancel);
        actions.setSpacing(true);

        add(form, actions);
    }

    private void addParameterField(VerticalLayout form,
                                   JrxmlExecutionService.JrxmlParamSpec spec) {
        // служебные параметры контекста в форму не выводятся — автозаполнение
        if (org.ipro.reportstudio.query.ServiceParams.NAMES.contains(spec.name())) {
            return;
        }
        if ("java.util.Date".equals(spec.valueClassName())
                || "java.sql.Date".equals(spec.valueClassName())) {
            DatePicker picker = new DatePicker(spec.name());
            if (spec.defaultValueLiteral() != null && !spec.defaultValueLiteral().isBlank()) {
                try {
                    picker.setValue(LocalDate.parse(spec.defaultValueLiteral()));
                } catch (RuntimeException notIsoDate) {
                    // непарсируемый литерал — оставляем поле пустым
                }
            }
            dateFields.put(spec.name(), picker);
            form.add(picker);
            return;
        }
        TextField field = new TextField(spec.name());
        field.setWidthFull();
        if (spec.defaultValueLiteral() != null) {
            field.setValue(spec.defaultValueLiteral());
        }
        valueFields.put(spec.name(), field);
        form.add(field);
    }

    private Map<String, Object> collectParameters() {
        Map<String, Object> parameters = new HashMap<>();
        valueFields.forEach((name, field) -> {
            String raw = field.getValue();
            if (raw != null && !raw.isBlank()) {
                parameters.put(name, convert(name, raw));
            }
        });
        dateFields.forEach((name, picker) -> {
            LocalDate value = picker.getValue();
            if (value != null) {
                parameters.put(name, java.sql.Date.valueOf(value));
            }
        });
        // служебные параметры контекста реестра (та же конвенция, что у UDR)
        if (context != null) {
            for (JrxmlExecutionService.JrxmlParamSpec spec : specs) {
                if (org.ipro.reportstudio.query.ServiceParams.ENTITY_ID.equals(spec.name())
                        && context.entityId() != null
                        && isLongCompatible(spec.valueClassName())) {
                    parameters.put(spec.name(), context.entityId());
                }
                if (org.ipro.reportstudio.query.ServiceParams.ENTITY_IDS.equals(spec.name())
                        && context.selectedIds() != null && !context.selectedIds().isEmpty()) {
                    parameters.put(spec.name(),
                            List.copyOf(context.selectedIds()));
                }
            }
        }
        return parameters;
    }

    private static boolean isLongCompatible(String className) {
        return "java.lang.Long".equals(className)
                || className == null || "java.lang.Object".equals(className);
    }

    private Object convert(String name, String raw) {
        String className = parameterClass(name);
        try {
            return switch (className == null ? "java.lang.String" : className) {
                case "java.lang.Integer" -> Integer.valueOf(raw.trim());
                case "java.lang.Long" -> Long.valueOf(raw.trim());
                case "java.lang.Short" -> Short.valueOf(raw.trim());
                case "java.lang.Double" -> Double.valueOf(raw.trim());
                case "java.lang.Float" -> Float.valueOf(raw.trim());
                case "java.math.BigDecimal" -> new BigDecimal(raw.trim());
                case "java.lang.Boolean" -> Boolean.valueOf(raw.trim());
                default -> raw;
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Параметр «" + name + "»: значение «" + raw + "» не приводится к "
                            + className);
        }
    }

    private String parameterClass(String name) {
        return specs.stream()
                .filter(spec -> spec.name().equals(name))
                .map(JrxmlExecutionService.JrxmlParamSpec::valueClassName)
                .findFirst().orElse(null);
    }

    private void runReport() {
        Map<String, Object> parameters = collectParameters();

        // Паттерн ReportRunDialog: контекст до старта фонового потока.
        String localeTag = getLocale().toLanguageTag();
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();

        HttpSession httpSession = null;
        VaadinSession vaadinSession = VaadinSession.getCurrent();
        if (vaadinSession != null && vaadinSession.getSession() instanceof WrappedHttpSession wrapped) {
            httpSession = wrapped.getHttpSession();
        }
        final HttpSession backgroundSession = httpSession;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        UI currentUI = UI.getCurrent();
        currentUI.setPollInterval(250);
        new Thread(() -> {
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(securityContext);
            if (backgroundSession != null) {
                RequestContextHolder.setRequestAttributes(
                        new ReportRunRequestAttributes(backgroundSession));
            }
            try {
                var print = executionService.run(template, parameters);
                currentUI.access(() -> {
                    try {
                        close();
                        new JrxmlPreviewDialog(template.getName(), print,
                                localeTag, zone, now).open();
                    } finally {
                        currentUI.setPollInterval(-1);
                    }
                });
            } catch (RuntimeException executionError) {
                currentUI.access(() -> {
                    try {
                        Notification error = Notification.show(
                                "Не удалось сформировать отчёт: "
                                        + executionError.getMessage(),
                                8_000, Notification.Position.MIDDLE);
                        error.addThemeVariants(NotificationVariant.LUMO_ERROR);
                    } finally {
                        currentUI.setPollInterval(-1);
                    }
                });
            } finally {
                if (backgroundSession != null) {
                    RequestContextHolder.resetRequestAttributes();
                }
                SecurityContextHolder.clearContext();
            }
        }).start();
    }

    /**
     * Минимальный RequestAttributes поверх HttpSession — как в ReportRunDialog:
     * session-scoped бины Spring читают атрибуты через этот интерфейс.
     */
    private static final class ReportRunRequestAttributes implements RequestAttributes {

        private final HttpSession session;

        ReportRunRequestAttributes(HttpSession session) {
            this.session = session;
        }

        @Override
        public Object getAttribute(String name, int scope) {
            return scope == SCOPE_SESSION ? session.getAttribute(name) : null;
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            if (scope == SCOPE_SESSION) {
                session.setAttribute(name, value);
            }
        }

        @Override
        public void removeAttribute(String name, int scope) {
            if (scope == SCOPE_SESSION) {
                session.removeAttribute(name);
            }
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return scope == SCOPE_SESSION
                    ? java.util.Collections.list(session.getAttributeNames()).toArray(new String[0])
                    : new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
            // деструкция session-бинов выполняется контейнером
        }

        @Override
        public Object resolveReference(String key) {
            return null;
        }

        @Override
        public String getSessionId() {
            return session.getId();
        }

        @Override
        public Object getSessionMutex() {
            return session;
        }
    }
}
