package org.ipro.telemetry.core;

import java.io.Serializable;

import org.ipro.telemetry.api.EventSink;

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
 * дефолтный DefaultErrorHandler ставится в конструкторе сессии. Этот слушатель
 * на serviceInit вешает SessionInitListener и для каждой новой сессии добавляет
 * {@link TelemetryErrorHandler} ПЕРЕД существующим (цепочка: сначала телеметрия,
 * затем стандартная обработка Vaadin).
 */
public final class TelemetryVaadinInitListener implements VaadinServiceInitListener, Serializable {

    private static final long serialVersionUID = 1L;

    public TelemetryVaadinInitListener(EventSink sink) {
        TelemetryBridge.setSink(sink);
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
        ErrorHandler telemetry = new TelemetryErrorHandler();
        ErrorHandler existing = session.getErrorHandler();
        if (existing == null) {
            session.setErrorHandler(telemetry);
        } else if (existing instanceof ChainedErrorHandler chained && chained.contains(telemetry)) {
            // уже обёрнуто
        } else {
            session.setErrorHandler(new ChainedErrorHandler(telemetry, existing));
        }
    }

    /**
     * Цепочка из двух обработчиков. Поля transient: после десериализации сессии
     * (failover/кластер) они null — при первом же вызове error() цепочка
     * пересоздаётся (телеметрия из статического bridge, DefaultErrorHandler
     * заново), поэтому стандартная обработка ошибок Vaadin после restore
     * сохраняется.
     */
    private static final class ChainedErrorHandler implements ErrorHandler, Serializable {

        private static final long serialVersionUID = 1L;

        private transient ErrorHandler first;
        private transient ErrorHandler second;

        private ChainedErrorHandler(ErrorHandler first, ErrorHandler second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void error(ErrorEvent errorEvent) {
            if (first == null) {
                first = new TelemetryErrorHandler();
            }
            if (second == null) {
                second = new DefaultErrorHandler();
            }
            if (first != null) {
                first.error(errorEvent);
            }
            if (second != null) {
                second.error(errorEvent);
            }
        }

        boolean contains(ErrorHandler handler) {
            return first == handler || second == handler;
        }
    }
}