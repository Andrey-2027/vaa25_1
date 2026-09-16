package org.ipro.rls;

import org.hibernate.Hibernate;

/** Static bridge used by Hibernate-created event listeners. */
public final class RlsWriteGuardBridge {

    private static volatile RlsDimensionRegistry registry;

    private RlsWriteGuardBridge() {
    }

    public static void install(RlsDimensionRegistry configuredRegistry) {
        registry = configuredRegistry;
    }

    static void requireAuthorized(Object entity, RlsWriteAuthorization.Operation operation) {
        RlsDimensionRegistry current = registry;
        if (current == null || entity == null || RlsContext.isBypassed()) {
            return;
        }
        Class<?> type = Hibernate.getClass(entity);
        RlsPolicyDescriptor policy = current.policyOf(type);
        if (!policy.protectedEntity()) {
            return;
        }
        if (!RlsWriteAuthorization.consume(entity, operation, policy.checksOf(entity))) {
            throw new RlsAccessDeniedException("Protected " + operation
                + " reached Hibernate flush without an RLS authorization: " + type.getName());
        }
    }
}
