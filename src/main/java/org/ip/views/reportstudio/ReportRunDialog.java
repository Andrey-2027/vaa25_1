package org.ip.views.reportstudio;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.EntityLookup;
import org.ip.views.components.ReportParamForm;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.run.ReportRunResult;
import org.ipro.rls.RlsAccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.vaadin.reports.WaitPrintWindow;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Диалог запуска отчёта: форма значений параметров (встроенная) + кнопка
 * «Сформировать».
 *
 * <p>Формирование выполняется асинхронно в фоновом потоке (паттерн
 * {@code runWithProgress} из ReportModuleAbstract библиотеки reportui-flow):
 * пока строится {@code JasperPrint}, поверх диалога показано окно
 * {@link WaitPrintWindow} с прогресс-баром; результат открывается в отдельном
 * окне превью {@link ReportPreviewDialog} с тулбаром экспорта.</p>
 */
public class ReportRunDialog extends Dialog {

    private final ReportTemplate template;
    private final ReportContext context;
    private final ReportExecutionService executionService;
    private final ReportParamForm paramForm;
    private final Paragraph status = new Paragraph();
    private ReportPreviewDialog previewDialog;

    public ReportRunDialog(
            ReportTemplate template,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(template, emptyContext(), executionService, lookupService, selectionFormAssembler);
    }

    public ReportRunDialog(
            ReportTemplate template,
            ReportContext context,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this.template = template;
        this.context = context;
        this.executionService = executionService;
        this.paramForm = new ReportParamForm(template.getParams(), context, lookupService, selectionFormAssembler);

        setHeaderTitle("Запуск отчёта: " + template.getName());
        setWidth("min(980px, 95vw)");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        Button run = new Button("Сформировать", event -> runReport());
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Закрыть", event -> close());
        HorizontalLayout actions = new HorizontalLayout(run, cancel);

        VerticalLayout content = new VerticalLayout(
                new H3("Параметры запуска"), paramForm, actions, status);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        add(content);
    }

    private void runReport() {
        status.setText("Формирование отчёта…");
        if (previewDialog != null) {
            previewDialog.close();
            previewDialog = null;
        }
        // Все обращения к Vaadin-компонентам и сессии — до создания фонового потока.
        String localeTag = getLocale().toLanguageTag();
        String zoneId = ZoneId.systemDefault().getId();
        Map<String, Object> formValues = paramForm.values();

        // Фоновый поток не наследует Spring request/session scope и SecurityContext.
        // Захватываем их здесь (в UI-потоке) и восстанавливаем вокруг executionService.run:
        // session-scoped RlsReadableIdsCache читает сессию через RequestContextHolder,
        // RlsCurrentUser — через SecurityContextHolder.
        //
        // HttpSession берём напрямую из VaadinSession: в Vaadin 25 клик-события идут по
        // WebSocket, и HttpServletRequest из VaadinServletRequest может быть синтетическим,
        // без привязанной сессии (getSession(false) -> null).
        HttpSession httpSession = null;
        VaadinSession vaadinSession = VaadinSession.getCurrent();
        if (vaadinSession != null && vaadinSession.getSession() instanceof WrappedHttpSession wrapped) {
            httpSession = wrapped.getHttpSession();
        }
        if (httpSession == null && VaadinServletRequest.getCurrent() != null) {
            httpSession = VaadinServletRequest.getCurrent().getHttpServletRequest().getSession(false);
        }
        RequestAttributes backgroundRequest = httpSession == null
            ? null : new HttpSessionRequestAttributes(httpSession);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        WaitPrintWindow progress = new WaitPrintWindow(template.getName(), true);
        UI currentUI = UI.getCurrent();
        progress.open();
        currentUI.setPollInterval(250);
        long startedNanos = System.nanoTime();

        executionService.executeAsync(authentication, backgroundRequest, () -> {
            try {
                ReportRunResult result = executionService.run(template, context, formValues, localeTag, zoneId);
                // Для быстрых отчётов прогресс-окно иначе закрылось бы в том же
                // рендер-цикле, что и открылось (пользователь его не увидит).
                long minDisplayMillis = 500;
                long remaining = minDisplayMillis - (System.nanoTime() - startedNanos) / 1_000_000;
                if (remaining > 0) {
                    try {
                        Thread.sleep(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                currentUI.access(() -> {
                    try {
                        progress.close();
                        close();
                        previewDialog = new ReportPreviewDialog(template, result);
                        previewDialog.open();
                    } finally {
                        currentUI.setPollInterval(-1);
                    }
                });
            } catch (RuntimeException executionError) {
                currentUI.access(() -> {
                    try {
                        progress.close();
                        status.setText("Ошибка запуска: " + executionError.getMessage());
                        Notification notification = Notification.show("Не удалось сформировать отчёт", 5_000,
                                Notification.Position.MIDDLE);
                        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
                    } finally {
                        currentUI.setPollInterval(-1);
                    }
                });
            }
        });
    }

    private static ReportContext emptyContext() {
        // Читаем Spring Security напрямую (та же конвенция, что у SecurityRlsUser):
        // platform-класс CurrentUser удалён, а диалог создаётся через new —
        // не Spring-бин.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : authentication.getName();
        if (username == null || username.isBlank() || "system".equals(username)
            || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new RlsAccessDeniedException("Запуск отчёта требует аутентифицированного пользователя");
        }
        return ReportContext.of(null, null, List.of(), null, username, Instant.now());
    }

    /**
     * Минимальный {@link RequestAttributes} поверх {@link HttpSession} для
     * фонового потока: session-scoped бины Spring (в т.ч. RlsReadableIdsCache)
     * читают/пишут атрибуты именно через этот интерфейс.
     */
    private static final class HttpSessionRequestAttributes implements RequestAttributes {

        private final HttpSession session;

        HttpSessionRequestAttributes(HttpSession session) {
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
            if (scope == SCOPE_SESSION) {
                return Collections.list(session.getAttributeNames()).toArray(new String[0]);
            }
            return new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
            // session-scope бины деструктируются при завершении сессии контейнером,
            // callback'и здесь не нужны
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
