package org.ipro.form.coordinator;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextArea;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.ipro.telemetry.core.MdcKeys;
import org.ipro.telemetry.core.TelemetryBridge;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Internal UI adapter. Never interprets exception text as permission data. */
public final class AccessDenialPresentation {
    private static final java.util.Map<Throwable, String> REFERENCES = new WeakHashMap<>();
    private static final String COPY_MESSAGE_JS = """
        if (!window.isSecureContext || !navigator.clipboard || !navigator.clipboard.writeText) {
          return false;
        }
        try {
          await navigator.clipboard.writeText($0);
          return true;
        } catch (refused) {
          return false;
        }
        """;

    private AccessDenialPresentation() { }

    /** Safe text and server-only administrative details (empty for other subjects). */
    public record Report(String message, String administrativeDetails) { }

    public static AccessDeniedException find(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException denied) {
                return denied;
            }
        }
        return null;
    }

    public static boolean allowsDetails() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    public static Report describe(Throwable error, String action, MetadataResolver metadataResolver) {
        var denied = find(error);
        if (denied == null) {
            throw new IllegalArgumentException("Expected an access denial");
        }
        String reason = "ACCESS_DENIED";
        String operation = "";
        String key = "";
        Class<?> entityType = null;
        String explanation = "Недостаточно прав для этого действия.";
        if (denied instanceof C5AccessDeniedException c5 && c5.decision() != null) {
            var decision = c5.decision();
            reason = decision.reason().name();
            operation = decision.operation().name();
            key = decision.key();
            entityType = c5.entityType();
            explanation = switch (decision.operation()) {
                case READ -> "Нет права чтения";
                case CREATE -> "Нет права создания";
                case UPDATE -> "Нет права изменения";
                case DELETE -> "Нет права удаления";
                case EXECUTE -> "Нет права выполнения действия";
            };
            String label = label(entityType, metadataResolver);
            explanation += label.isEmpty() ? "." : ": «" + label + "».";
            if (decision.reason() == org.ipro.rls.c5.C5DecisionReason.UNAUTHENTICATED) {
                explanation = "Для этого действия необходимо войти в систему.";
            }
        } else if (denied instanceof RlsAccessDeniedException rls) {
            reason = "RLS_DENIED";
            operation = rls.operation() == null ? "" : rls.operation();
            key = rls.dimension() == null ? "" : "RLS:" + rls.dimension();
            entityType = rls.entityType();
            explanation = "Действие запрещено ограничениями доступа к данным.";
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String user = authentication == null ? "не определён" : authentication.getName();
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        String reference;
        boolean first;
        synchronized (REFERENCES) {
            reference = REFERENCES.get(denied);
            first = reference == null;
            if (first) {
                reference = UUID.randomUUID().toString();
                REFERENCES.put(denied, reference);
            }
        }
        String details = "Код диагностики: " + reference + "\nДействие: " + action
            + "\nПричина: " + reason + "\nОперация: " + operation + "\nКлюч проверки: " + key
            + "\nСущность: " + (entityType == null ? "не определена" : entityType.getName())
            + "\nПользователь: " + user
            + "\nTrace ID: " + (traceId == null ? "не создан" : traceId);
        if (first) {
            LoggerFactory.getLogger(AccessDenialPresentation.class).warn("Отказ доступа\n{}", details);
            var sink = TelemetryBridge.getSink();
            if (sink != null) {
                try {
                    sink.acceptDurable(new TelemetryEvent(EventType.SECURITY, "WARN", Instant.now(),
                        null, traceId, user, null, "ui:access-denied", null, null,
                        null, null, false, details, null));
                } catch (RuntimeException unavailable) {
                    // The ordinary application log retains the reference even without telemetry.
                    LoggerFactory.getLogger(AccessDenialPresentation.class)
                        .warn("Не удалось записать диагностику отказа {} в телеметрию", reference);
                }
            }
        }
        return new Report(action + ". " + explanation
            + " Обратитесь к администратору.",
            allowsDetails() ? details : "");
    }

    private static String label(Class<?> type, MetadataResolver resolver) {
        if (type == null || resolver == null) {
            return "";
        }
        try {
            // Only declared metadata is used; never query a denied entity for its name.
            return resolver.resolve(type).getListFormTitle();
        } catch (RuntimeException unknownMetadata) {
            LoggerFactory.getLogger(AccessDenialPresentation.class)
                .warn("Cannot resolve metadata label for denied type " + type.getName(), unknownMetadata);
            return "";
        }
    }

    public static Details details(String administrativeDetails) {
        if (!allowsDetails() || administrativeDetails == null || administrativeDetails.isEmpty()) {
            return null;
        }
        Pre text = new Pre();
        text.setText(administrativeDetails);
        text.getStyle().set("white-space", "pre-wrap");
        return new Details("Подробнее (для администратора)", text);
    }

    public static void show(Report report) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Доступ запрещён");
        dialog.add(new Paragraph(report.message()));
        var details = details(report.administrativeDetails());
        if (details != null) {
            dialog.add(details);
        }
        dialog.getFooter().add(copyMessageButton(report.message()),
            new Button("Закрыть", event -> dialog.close()));
        dialog.setMaxWidth("700px");
        dialog.open();
    }

    /** Copies exactly the visible user message, without administrative details or identifiers. */
    public static Button copyMessageButton(String message) {
        java.util.Objects.requireNonNull(message, "message");
        return new Button("Скопировать сообщение", event -> {
            UI ui = event.getSource().getUI().orElseThrow();
            ui.getPage().executeJs(COPY_MESSAGE_JS, message).then(Boolean.class, copied -> {
                if (Boolean.TRUE.equals(copied)) {
                    Notification.show("Сообщение скопировано", 2000, Notification.Position.BOTTOM_START);
                } else {
                    showMessageForManualCopy(message);
                }
            });
        });
    }

    private static void showMessageForManualCopy(String message) {
        TextArea field = new TextArea("Сообщение");
        field.setValue(message);
        field.setReadOnly(true);
        field.setWidthFull();
        field.setAutoselect(true);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Скопировать сообщение");
        dialog.add(new Paragraph("Браузер не разрешил автоматическое копирование. "
            + "Выделите сообщение и скопируйте его вручную."), field);
        dialog.getFooter().add(new Button("Закрыть", event -> dialog.close()));
        dialog.setWidth("600px");
        dialog.setMaxWidth("100%");
        dialog.open();
        field.focus();
    }
}
