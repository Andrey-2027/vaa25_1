package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import com.vaadin.flow.spring.scopes.VaadinUIScope;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Дефект живой приёмки 11.1: у {@link EntityExplorerView} оказалось три конструктора,
 * а {@code @Autowired} стоял на параметрах, не на конструкторе. Spring не выбирал
 * конструктор, и вход «Структура сущностей» падал на «No default constructor found».
 *
 * <p>Прежние тесты этот класс не ловили: они создают вид пакетным швом напрямую, а
 * UI-scope тест подставлял готовый mock-бин, минуя выбор конструктора. Здесь вид создаёт
 * настоящий контейнер — то же, что делает приложение при входе в Explorer.</p>
 */
class EntityExplorerViewSpringCreationTest {

    @Test
    void theSpringContainerCreatesTheExplorerViewThroughItsPublicConstructor() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        when(session.hasLock()).thenReturn(true);
        when(session.getLockInstance()).thenReturn(new ReentrantLock());
        Map<Class<?>, Object> attributes = new HashMap<>();
        when(session.getAttribute(any(Class.class))).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> {
            attributes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(session).setAttribute(any(Class.class), any());
        UI ui = mock(UI.class);
        when(ui.getUIId()).thenReturn(1);
        when(ui.getSession()).thenReturn(session);
        VaadinSession.setCurrent(session);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.addBeanFactoryPostProcessor(new VaadinUIScope());
            context.registerBean(EntitySummaryAssembler.class, () -> mock(EntitySummaryAssembler.class));
            context.registerBean(FormNavigator.class, () -> mock(FormNavigator.class));
            context.registerBean(EntityExplorerAccess.class, () -> mock(EntityExplorerAccess.class));
            // Без supplier: контейнер сам выбирает конструктор — именно этот выбор и падал.
            context.registerBean(EntityExplorerView.class);
            context.refresh();
            UI.setCurrent(ui);
            assertThat(context.getBean(EntityExplorerView.class)).isNotNull();
        } finally {
            UI.setCurrent(null);
            VaadinSession.setCurrent(null);
        }
    }
}
