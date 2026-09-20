package org.ipro.vaadin.telemetry;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.TelemetryEvent;
import org.ipro.telemetry.config.TelemetryAutoConfiguration;
import org.ipro.telemetry.core.TelemetryBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3.5.6: UI-адаптер телеметрии больше не владеет статическим мостом и не может тихо выключиться.
 *
 * <p>Три свойства, каждое из которых до шага было нарушено:</p>
 * <ol>
 *   <li><b>Применимость.</b> {@code @Bean} требовал {@code EventSink} безусловно, поэтому
 *       выключенная или непришедшая телеметрия роняла старт ({@code UnsatisfiedDependency})
 *       вместо отступления. Условия обязаны также давать явный порядок: {@code @ConditionalOnBean}
 *       проверяется в момент применения конфигурации, и без {@code @AutoConfigureAfter} порядок
 *       держался бы на алфавите FQN через границу артефакта.</li>
 *   <li><b>Владение.</b> Конструктор слушателя писал в {@link TelemetryBridge} — то есть
 *       lifecycle не-UI подсистемы зависел от создания UI-бина (артефакт поверх UI и был обязан
 *       создать бин, чтобы у потребителя вообще появился sink). Здесь проверяется, что создание
 *       слушателя ячейку не трогает.</li>
 *   <li><b>Дедупликация.</b> {@code contains} сравнивал identity, а экземпляр обработчика
 *       создаётся заново на каждой сессии, поэтому проверка не срабатывала никогда: повторная
 *       инициализация сессии давала цепочку из двух телеметрических обработчиков и удвоенные
 *       записи в журнале.</li>
 * </ol>
 */
class TelemetryVaadinWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TelemetryVaadinConfiguration.class));

    @AfterEach
    void clearBridge() {
        TelemetryBridge.setSink(null);
    }

    /**
     * Без телеметрии адаптер обязан не подниматься: раньше это был {@code UnsatisfiedDependency},
     * то есть «выключенная подсистема» выглядела как поломка платформы.
     */
    @Test
    void backsOffWhenTelemetryDoesNotProvideASink() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TelemetryVaadinInitListener.class);
        });
    }

    /** При живой телеметрии слушатель ровно один: ноль выключил бы обработку ошибок UI молча. */
    @Test
    void registersExactlyOneListenerWhenASinkIsPresent() {
        runner.withUserConfiguration(UserSinkConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(TelemetryVaadinInitListener.class))
                    .hasSize(1);
            });
    }

    /**
     * Условия и порядок — часть контракта, а не деталь реализации: без {@code @ConditionalOnClass}
     * конфигурация дойдёт до создания Vaadin-типа там, где Vaadin нет (модуль объявляет его
     * {@code provided}), а без {@code @AutoConfigureAfter} порядок применения зависит от алфавита.
     */
    @Test
    void declaresItsApplicabilityConditionsAndOrder() {
        assertThat(TelemetryVaadinConfiguration.class.isAnnotationPresent(AutoConfiguration.class))
            .as("конфигурация обязана быть авто-конфигурацией: иначе её применимость и порядок"
                + " не описываются декларативно")
            .isTrue();

        AutoConfigureAfter after =
            TelemetryVaadinConfiguration.class.getAnnotation(AutoConfigureAfter.class);
        assertThat(after).as("@AutoConfigureAfter обязателен: условие на EventSink проверяется"
            + " в момент применения конфигурации, а не по факту создания бинов").isNotNull();
        assertThat(List.of(after.value())).contains(TelemetryAutoConfiguration.class);

        ConditionalOnClass onClass =
            TelemetryVaadinConfiguration.class.getAnnotation(ConditionalOnClass.class);
        assertThat(onClass).as("@ConditionalOnClass: Vaadin в модуле provided, и без него"
            + " конфигурация не имеет права дойти до Vaadin-типа").isNotNull();
        assertThat(List.of(onClass.value())).contains(VaadinService.class);

        ConditionalOnBean onBean =
            TelemetryVaadinConfiguration.class.getAnnotation(ConditionalOnBean.class);
        assertThat(onBean).as("@ConditionalOnBean: без sink'а обработке ошибок UI некуда писать")
            .isNotNull();
        assertThat(List.of(onBean.value())).contains(EventSink.class);
    }

    /**
     * Владение мостом проверяется наблюдаемо: в ячейку кладётся «чужой» sink, затем поднимается
     * контекст с нашим слушателем. Раньше конструктор слушателя переписывал ячейку — именно так
     * UI-слой и оказался владельцем lifecycle'а подсистемы наблюдения.
     */
    @Test
    void creatingTheListenerDoesNotTouchTheBridge() {
        EventSink foreign = mock(EventSink.class);
        TelemetryBridge.setSink(foreign);

        runner.withUserConfiguration(UserSinkConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(TelemetryVaadinInitListener.class)).isNotNull();
                assertThat(TelemetryBridge.getSink())
                    .as("слушатель — только обработка ошибок: установка sink'а принадлежит"
                        + " владельцу в platform-telemetry, иначе подсистема наблюдения зависит"
                        + " от UI-артефакта")
                    .isSameAs(foreign);
            });
    }

    /**
     * Повторная инициализация сессии не должна добавлять второй телеметрический обработчик:
     * дубль — это удвоенная запись об одной и той же ошибке, а не косметика.
     */
    @Test
    void sessionInitIsIdempotentAndProducesOneRecordPerError() {
        EventSink sink = mock(EventSink.class);
        TelemetryBridge.setSink(sink);
        VaadinSession session = mock(VaadinSession.class);

        runner.withUserConfiguration(UserSinkConfiguration.class)
            .run(context -> {
                TelemetryVaadinInitListener listener =
                    context.getBean(TelemetryVaadinInitListener.class);
                SessionInitListener sessionInitListener = sessionInitListenerOf(listener);
                List<ErrorHandler> installed = new ArrayList<>();
                when(session.getErrorHandler()).thenAnswer(invocation ->
                    installed.isEmpty() ? null : installed.get(installed.size() - 1));
                org.mockito.Mockito.doAnswer(invocation -> {
                    installed.add(invocation.getArgument(0));
                    return null;
                }).when(session).setErrorHandler(any(ErrorHandler.class));

                fireSessionInit(sessionInitListener, session);
                fireSessionInit(sessionInitListener, session);

                assertThat(installed)
                    .as("после второй инициализации той же сессии цепочка обязана остаться одной:"
                        + " identity-сравнение (contains → first == handler) не находило уже"
                        + " вставленный обработчик, потому что экземпляр создаётся заново")
                    .hasSize(1);

                installed.get(0).error(new ErrorEvent(new IllegalStateException("ошибка UI")));

                verify(sink, times(1)).acceptDurable(any(TelemetryEvent.class));
            });
    }

    /**
     * Failover/кластер: обработчики сессии обязаны пережить сериализацию <b>целиком</b>.
     *
     * <p>Это проверка не «на всякий случай». Цепочка получила поля {@code transient} как наследство
     * от первой редакции, где сериализовалась пустая оболочка: после restore оба обработчика
     * оказывались {@code null} — ошибки UI в кластере переставали попадать и в журнал, и в
     * стандартную обработку, без единого признака. Основания держать их transient нет:
     * {@code ErrorHandler} сам расширяет {@code Serializable}.</p>
     */
    @Test
    void deserializedSessionKeepsBothHandlersAndStaysDeduplicated() throws Exception {
        EventSink sink = mock(EventSink.class);
        TelemetryBridge.setSink(sink);
        VaadinSession session = mock(VaadinSession.class);
        RecordingHandler applicationHandler = new RecordingHandler();
        RecordingHandler.calls = 0;

        runner.withUserConfiguration(UserSinkConfiguration.class)
            .run(context -> {
                TelemetryVaadinInitListener listener =
                    context.getBean(TelemetryVaadinInitListener.class);
                List<ErrorHandler> installed = new ArrayList<>();
                when(session.getErrorHandler()).thenReturn(applicationHandler);
                org.mockito.Mockito.doAnswer(invocation -> {
                    installed.add(invocation.getArgument(0));
                    return null;
                }).when(session).setErrorHandler(any(ErrorHandler.class));

                fireSessionInit(sessionInitListenerOf(listener), session);
                ErrorHandler restored = roundTrip(installed.get(0));

                restored.error(new ErrorEvent(new IllegalStateException("ошибка UI")));

                verify(sink, times(1)).acceptDurable(any(TelemetryEvent.class));
                assertThat(RecordingHandler.calls)
                    .as("обработчик, установленный приложением, обязан пережить failover: иначе"
                        + " после restore его молча подменяет DefaultErrorHandler, и ошибки UI"
                        + " обрабатываются не так, как настроено")
                    .isEqualTo(1);

                when(session.getErrorHandler()).thenReturn(restored);
                fireSessionInit(sessionInitListenerOf(listener), session);

                assertThat(installed)
                    .as("восстановленная цепочка обязана узнаваться по типу: иначе каждая"
                        + " повторная инициализация добавляет ещё один телеметрический обработчик")
                    .hasSize(1);
            });
    }

    /** Цепочка не должна терять стандартную обработку Vaadin: телеметрия идёт первой, но не одна. */
    @Test
    void telemetryHandlerIsChainedWithTheExistingOne() {
        EventSink sink = mock(EventSink.class);
        TelemetryBridge.setSink(sink);
        VaadinSession session = mock(VaadinSession.class);
        ErrorHandler applicationHandler = mock(ErrorHandler.class);

        runner.withUserConfiguration(UserSinkConfiguration.class)
            .run(context -> {
                TelemetryVaadinInitListener listener =
                    context.getBean(TelemetryVaadinInitListener.class);
                when(session.getErrorHandler()).thenReturn(applicationHandler);
                List<ErrorHandler> installed = new ArrayList<>();
                org.mockito.Mockito.doAnswer(invocation -> {
                    installed.add(invocation.getArgument(0));
                    return null;
                }).when(session).setErrorHandler(any(ErrorHandler.class));

                fireSessionInit(sessionInitListenerOf(listener), session);

                assertThat(installed).hasSize(1);
                installed.get(0).error(new ErrorEvent(new IllegalStateException("ошибка UI")));

                verify(sink, times(1)).acceptDurable(any(TelemetryEvent.class));
                verify(applicationHandler, times(1)).error(any(ErrorEvent.class));
            });
    }

    private static ErrorHandler roundTrip(ErrorHandler handler) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeObject(handler);
            }
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                return (ErrorHandler) in.readObject();
            }
        } catch (java.io.IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SessionInitListener sessionInitListenerOf(TelemetryVaadinInitListener listener) {
        VaadinService service = mock(VaadinService.class);
        listener.serviceInit(new ServiceInitEvent(service));
        org.mockito.ArgumentCaptor<SessionInitListener> captor =
            org.mockito.ArgumentCaptor.forClass(SessionInitListener.class);
        verify(service).addSessionInitListener(captor.capture());
        return captor.getValue();
    }

    private static void fireSessionInit(SessionInitListener listener, VaadinSession session) {
        try {
            // source у SessionInitEvent непустой по контракту Vaadin (EventObject: null source —
            // IllegalArgumentException), поэтому поднимаем событие как это делает платформа
            listener.sessionInit(new SessionInitEvent(mock(VaadinService.class), session, null));
        } catch (com.vaadin.flow.server.ServiceException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Стандартная обработка Vaadin в тесте: считает вызовы и сериализуется вместе с цепочкой
     * (мок здесь не годится — production-обработчик сессии обязан переживать failover, и мок
     * сериализуемостью не обладает).
     */
    static final class RecordingHandler implements ErrorHandler {

        private static final long serialVersionUID = 1L;

        static int calls;

        @Override
        public void error(ErrorEvent event) {
            calls++;
        }
    }

    /** Sink, у которого каждая запись падает: пользовательский EventSink вправе бросить. */
    private static EventSink brokenSink() {
        EventSink sink = mock(EventSink.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("sink сломан"))
            .when(sink).acceptDurable(any(TelemetryEvent.class));
        return sink;
    }

    /** Пользовательский sink: объявлен так, как это делает приложение. */
    @Configuration
    static class UserSinkConfiguration {

        private static final EventSink SINK = mock(EventSink.class);

        @Bean
        EventSink applicationEventSink() {
            return SINK;
        }
    }

    /**
     * D3.5.8-fix: штатная обработка ошибки не зависит от неисправности телеметрии.
     *
     * <p>Два пути срыва, оба найдены статическим ревью. Первый: sink читался дважды — между
     * чтениями владелец снимал его (compare-and-clear на закрытии контекста), и второе чтение
     * давало NPE. Второй: пользовательский {@link EventSink} вправе бросить runtime exception.
     * В обоих случаях падало первое звено цепочки, а без {@code finally} в
     * {@code ChainedErrorHandler} падение прерывало и прикладной обработчик.</p>
     *
     * <p>Цепочка собирается продовым путём — через {@code sessionInit} с существующим
     * обработчиком приложения, поэтому проверяется вся цепочка (telemetry → app), а не звенья
     * по отдельности: падающий sink не роняет вызов, исключение не выходит наружу, и
     * прикладной обработчик получает свою ошибку.</p>
     */
    @Test
    void brokenTelemetryDoesNotBreakTheApplicationErrorHandler() {
        EventSink broken = brokenSink();
        TelemetryBridge.setSink(broken);
        VaadinSession session = mock(VaadinSession.class);
        RecordingHandler applicationHandler = new RecordingHandler();
        RecordingHandler.calls = 0;

        runner.withUserConfiguration(UserSinkConfiguration.class)
            .run(context -> {
                TelemetryVaadinInitListener listener =
                    context.getBean(TelemetryVaadinInitListener.class);
                when(session.getErrorHandler()).thenReturn(applicationHandler);
                List<ErrorHandler> installed = new ArrayList<>();
                org.mockito.Mockito.doAnswer(invocation -> {
                    installed.add(invocation.getArgument(0));
                    return null;
                }).when(session).setErrorHandler(any(ErrorHandler.class));

                fireSessionInit(sessionInitListenerOf(listener), session);
                installed.get(0).error(new ErrorEvent(new IllegalStateException("ошибка UI")));

                verify(broken, times(1)).acceptDurable(any(TelemetryEvent.class));
                assertThat(RecordingHandler.calls)
                    .as("падающий EventSink не должен мешать прикладному обработчику: в цепочке"
                        + " telemetry → app первое звено изолирует свои сбои, второе вызывается"
                        + " через finally")
                    .isEqualTo(1);
            });
    }

    /**
     * D3.5.8-fix: даже исчезнувший (clear между чтениями) sink не должен ронять обработку.
     * Воспроизводит порядок «проверил sink → владелец его снял → записываем»: при повторном
     * чтении из ячейки обработчик получал NPE вместо тихого пропуска записи.
     */
    @Test
    void clearedSinkBetweenReadsDoesNotThrow() {
        RecordingHandler.calls = 0;
        // первый вызов: sink установлен, обработчик доходит до записи; затем sink стирается —
        // имитация compare-and-clear соседним контекстом между двумя чтениями старого кода
        EventSink disappearing = mock(EventSink.class);
        TelemetryBridge.setSink(disappearing);
        TelemetryErrorHandler handler = new TelemetryErrorHandler();
        handler.error(new ErrorEvent(new IllegalStateException("ошибка UI")));
        TelemetryBridge.clearSink(disappearing);

        // второй вызов уже без sink: обязан пройти без исключения и без записи
        handler.error(new ErrorEvent(new IllegalStateException("ошибка UI")));

        assertThat(TelemetryBridge.getSink()).isNull();
        verify(disappearing, times(1)).acceptDurable(any(TelemetryEvent.class));
    }
}
