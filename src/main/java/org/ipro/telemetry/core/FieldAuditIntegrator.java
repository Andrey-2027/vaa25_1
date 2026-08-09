package org.ipro.telemetry.core;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Регистрация {@link FieldAuditListener} на события PreInsert/PreUpdate/
 * PreDelete каждой SessionFactory. Подключается через IntegratorProvider
 * (свойство {@code hibernate.integrator_provider}) — Hibernate создаёт
 * листенер сам, без Spring (паттерн SqlStatementInspector/SqlStatementListener).
 */
public final class FieldAuditIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                          SessionFactoryImplementor sessionFactory) {
        // Внимание: sessionFactory.getEventListenerGroups() в момент integrate()
        // ещё не инициализирован (Hibernate 7 строит группы событий ПОСЛЕ
        // вызова интеграторов). События регистрируются в сервисе
        // EventListenerRegistry — SessionFactoryImpl читает группы из него же,
        // поэтому листенеры попадают в рабочую цепочку.
        EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                .requireService(EventListenerRegistry.class);
        FieldAuditListener listener = new FieldAuditListener();
        registry.appendListeners(EventType.PRE_INSERT, listener);
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.PRE_UPDATE, listener);
        registry.appendListeners(EventType.PRE_DELETE, listener);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                             SessionFactoryServiceRegistry serviceRegistry) {
    }
}
