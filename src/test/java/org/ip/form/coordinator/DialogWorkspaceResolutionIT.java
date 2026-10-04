package org.ip.form.coordinator;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.config.DataInitializer;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.FormType;
import org.ip.model.Workshop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Паритет Dialog / Workspace (PR-0.5): одна и та же (entityClass, variant) через
 * {@link FormCoordinator#openItemFormAsDialog} (private) и через
 * {@link ItemFormWrapperView#init} (Workspace-вкладку) должна давать одну и ту же
 * форму: тот же класс ItemForm (generic / кастомная), тот же набор табличных частей
 * и одинаковую инициализацию новой записи (id == null).
 *
 * <p>Тест намеренно регистрирует вариант <b>после</b> старта контекста, поэтому открывает
 * замороженный реестр форм через {@code ipro.form.registry.allow-runtime-registration}. Это
 * единственный такой потребитель: в приложении поздней регистрации нет, и свойство — сигнал
 * «здесь тест, а не продакшн-путь».</p>
 */
@SpringBootTest
@TestPropertySource(properties = "ipro.form.registry.allow-runtime-registration=true")
class DialogWorkspaceResolutionIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FormResolver formResolver;

    @Autowired
    private FormRegistry formRegistry;

    @Autowired
    private ObjectProvider<ItemFormWrapperView> viewProvider;

    private String registeredVariant;
    private UI previousUi;
    private VaadinSession previousSession;

    @BeforeEach
    void bindUiScope() {
        // Workspace-форма получает UI-scoped навигацию структуры через настоящий Spring scope.
        previousUi = UI.getCurrent();
        previousSession = VaadinSession.getCurrent();
        VaadinSession session = mock(VaadinSession.class);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        when(session.hasLock()).thenReturn(true);
        when(session.getLockInstance()).thenReturn(new ReentrantLock());
        Map<Class<?>, Object> attributes = new HashMap<>();
        when(session.getAttribute(any(Class.class))).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(session).setAttribute(any(Class.class), any());
        UI ui = new UI();
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);
    }

    @AfterEach
    void cleanup() {
        try {
            if (registeredVariant != null) {
                formRegistry.unregister(Workshop.class, FormType.ITEM, registeredVariant);
                registeredVariant = null;
            }
        } finally {
            UI.setCurrent(previousUi);
            VaadinSession.setCurrent(previousSession);
        }
    }

    @Test
    void genericFormResolvesEquallyForDialogAndWorkspace() {
        ItemForm<Workshop> dialogForm = formResolver.resolveItemForm(Workshop.class, null, null, null);
        dialogForm.initializeNewEntity();

        ItemFormWrapperView view = viewProvider.getObject();
        view.init(Workshop.class, null, null, saved -> {
        }, () -> {
        });
        ItemForm<?> workspaceForm = view.getItemForm();

        assertThat(workspaceForm).isNotNull();
        // тот же класс (generic ItemForm) и тот же состав табличных частей
        assertThat(workspaceForm.getClass()).isEqualTo(dialogForm.getClass());
        assertThat(workspaceFormTableSectionClasses(workspaceForm))
            .isEqualTo(dialogFormTableSectionClasses(dialogForm));
        // обе ветки для id == null инициализируют новую запись
        assertThat(workspaceForm.getEntity()).isNotNull();
        assertThat(workspaceForm.getEntity().getId()).isNull();
        assertThat(dialogForm.getEntity()).isNotNull();
        assertThat(dialogForm.getEntity().getId()).isNull();
    }

    @Test
    void namedVariantResolvesEquallyForDialogAndWorkspace() {
        registeredVariant = "it-brief-" + UUID.randomUUID().toString().substring(0, 8);
        formRegistry.registerItemForm(Workshop.class, registeredVariant,
            ctx -> new ItemForm<>(ctx.metadataResolver().resolve(Workshop.class), ctx.fieldFactory()));

        ItemForm<Workshop> dialogForm =
            formResolver.resolveItemForm(Workshop.class, registeredVariant, null, null);
        dialogForm.initializeNewEntity();

        ItemFormWrapperView view = viewProvider.getObject();
        view.init(Workshop.class, registeredVariant, null, saved -> {
        }, () -> {
        });
        ItemForm<?> workspaceForm = view.getItemForm();

        assertThat(workspaceForm).isNotNull();
        assertThat(workspaceForm.getClass()).isEqualTo(dialogForm.getClass());
        assertThat(workspaceFormTableSectionClasses(workspaceForm))
            .isEqualTo(dialogFormTableSectionClasses(dialogForm));
        assertThat(workspaceForm.getEntity()).isNotNull();
        assertThat(workspaceForm.getEntity().getId()).isNull();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static java.util.List<Class<?>> dialogFormTableSectionClasses(ItemForm<?> form) {
        return ((ItemForm) form).getTableSections().stream()
            .map(sec -> sec.getClass())
            .toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static java.util.List<Class<?>> workspaceFormTableSectionClasses(ItemForm<?> form) {
        return ((ItemForm) form).getTableSections().stream()
            .map(sec -> sec.getClass())
            .toList();
    }
}
