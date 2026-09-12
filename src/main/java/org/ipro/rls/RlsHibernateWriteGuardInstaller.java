package org.ipro.rls;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.beans.factory.InitializingBean;

/** Installs the write guard independently of optional telemetry integrations. */
public final class RlsHibernateWriteGuardInstaller implements InitializingBean {

    private final EntityManagerFactory entityManagerFactory;
    private final RlsDimensionRegistry dimensionRegistry;

    public RlsHibernateWriteGuardInstaller(EntityManagerFactory entityManagerFactory,
                                            RlsDimensionRegistry dimensionRegistry) {
        this.entityManagerFactory = entityManagerFactory;
        this.dimensionRegistry = dimensionRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        RlsWriteGuardBridge.install(dimensionRegistry);
        SessionFactoryImplementor sessionFactory = entityManagerFactory
            .unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry listeners = sessionFactory.getServiceRegistry()
            .requireService(EventListenerRegistry.class);
        RlsWriteEnforcementListener listener = new RlsWriteEnforcementListener();
        listeners.prependListeners(EventType.PRE_INSERT, listener);
        listeners.prependListeners(EventType.PRE_UPDATE, listener);
        listeners.prependListeners(EventType.PRE_DELETE, listener);
    }
}
