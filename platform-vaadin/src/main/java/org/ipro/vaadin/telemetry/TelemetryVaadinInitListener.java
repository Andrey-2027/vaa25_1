package org.ipro.vaadin.telemetry;

import java.io.Serializable;

import com.vaadin.flow.server.DefaultErrorHandler;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;

/**
 * В Vaadin 25 ErrorHandler — свойство сессии ({@link VaadinSession#setErrorHandler}),
 * дефолтный {@link DefaultErrorHandler} ставится в конструкторе сессии. Этот слушатель
 * на serviceInit вешает SessionInitListener и для каждой новой сессии добавляет
 * {@link TelemetryErrorHandler} ПЕРЕД существующим (цепочка: сначала телеметрия,
 * затем стандартная обработка Vaadin).
 *
 * <p>D3.5.6: слушатель отвечает <b>только</b> за обработку ошибок UI. Конструктор больше
 * не пишет в {@code TelemetryBridge}: установка и снятие sink'а — lifecycle подсистемы
 * наблюдения ({@code TelemetryAutoConfiguration.SinkConfiguration}), а создание UI-бина не
 * имеет права быть условием её работоспособности. Гейт применимости — на конфигурации
 * ({@code @ConditionalOnBean(EventSink)}); здесь остаётся рантайм-проверка в самом
 * {@link TelemetryErrorHandler}, который при пустом мосте молча ничего не пишет.</p>
 */
public final class TelemetryVaadinInitListener implements VaadinServiceInitListener, Serializable {

    private static final long serialVersionUID = 1L;

    public TelemetryVaadinInitListener() {
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        VaadinService service = event.getSource();
        if (service == null) {
            return;
        }
        service.addSessionInitListener(this::onSessionInit);
    }

    private void onSessionInit(SessionInitEvent event) {
        VaadinSession session = event.getSession();
        if (session == null) {
            return;
        }
        ErrorHandler existing = session.getErrorHandler();
        if (existing == null) {
            session.setErrorHandler(new TelemetryErrorHandler());
            return;
        }
        if (containsTelemetryHandler(existing)) {
            // уже обёрнуто: повторная инициализация сессии не должна давать вторую запись
            return;
        }
        session.setErrorHandler(new ChainedErrorHandler(new TelemetryErrorHandler(), existing));
    }

    /**
     * Ищет телеметрический обработчик <b>по типу</b>, а не по ссылке.
     *
     * <p>D3.5.6: прежняя проверка ({@code first == handler}) не срабатывала никогда —
     * {@link TelemetryErrorHandler} создаётся заново на каждую инициализацию сессии, поэтому
     * сравнение ссылок не находило уже вставленный обработчик, и цепочка становилась
     * {@code telemetry → telemetry → ...}: одна ошибка UI давала несколько записей в журнале.
     * Тип — единственное, что здесь устойчиво (обработчик без состояния).</p>
     */
    private static boolean containsTelemetryHandler(ErrorHandler handler) {
        if (handler instanceof TelemetryErrorHandler) {
            return true;
        }
        return handler instanceof ChainedErrorHandler chained && chained.containsTelemetryHandler();
    }

    /**
     * Цепочка из двух обработчиков: сначала телеметрия, затем существовавшая обработка.
     *
     * <p>Поля <b>не</b> {@code transient} (изменено в D3.5.6). До этого цепочка переживала
     * сериализацию сессии как пустая оболочка: либо оба поля оказывались {@code null} (failover
     * молча выключал обработку ошибок UI целиком), либо — в промежуточной редакции —
     * восстанавливались «по догадке» ({@code new DefaultErrorHandler()}), что тихо подменяло
     * обработчик, установленный приложением. Основания держать поля transient нет:
     * {@link ErrorHandler} сам расширяет {@link Serializable}, то есть обработчик сессии обязан
     * быть сериализуемым по контракту Vaadin, а {@link TelemetryErrorHandler} не хранит состояния.
     * Так восстановленная сессия сохраняет и наблюдение, и стандартную обработку — и остаётся
     * узнаваемой для дедупликации.</p>
     */
    private static final class ChainedErrorHandler implements ErrorHandler, Serializable {

        private static final long serialVersionUID = 1L;

        private final ErrorHandler first;
        private final ErrorHandler second;

        private ChainedErrorHandler(ErrorHandler first, ErrorHandler second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void error(ErrorEvent errorEvent) {
            try {
                first.error(errorEvent);
            } finally {
                // finally, а не последовательные вызовы (D3.5.8-fix): падение или исчезновение
                // sink'а внутри телеметрии не должно прерывать штатную обработку ошибки Vaadin.
                // TelemetryErrorHandler изолирует собственные сбои, но контракт цепочки не должен
                // держаться на дисциплине первого звена.
                second.error(errorEvent);
            }
        }

        /** Тип, а не ссылка: обработчик создаётся заново на каждую сессию, поэтому сравнение
         *  ссылок не находило уже вставленный и допускало вложенную цепочку. */
        boolean containsTelemetryHandler() {
            return first instanceof TelemetryErrorHandler || second instanceof TelemetryErrorHandler;
        }
    }
}