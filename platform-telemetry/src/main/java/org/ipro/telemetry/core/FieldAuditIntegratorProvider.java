package org.ipro.telemetry.core;

import java.util.List;

import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;

/**
 * IntegratorProvider для регистрации {@link FieldAuditIntegrator}
 * (свойство {@code hibernate.integrator_provider}).
 */
public final class FieldAuditIntegratorProvider implements IntegratorProvider {

    private static final List<Integrator> INTEGRATORS = List.of(new FieldAuditIntegrator());

    @Override
    public List<Integrator> getIntegrators() {
        return INTEGRATORS;
    }
}
