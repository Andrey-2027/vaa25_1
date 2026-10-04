package org.ipro.rls;

import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Test-data helper that uses the real authorization path instead of opening an RLS bypass.
 * Each invocation gets an authenticated subject with an explicit wildcard grant.
 */
public final class RlsTestFixture {

    private RlsTestFixture() {
    }

    public static void runAsSuperuser(AccessGrantRepository grants, Runnable action) {
        callAsSuperuser(grants, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAsSuperuser(AccessGrantRepository grants, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        String username = authenticateAsSuperuser(grants);
        try {
            return action.get();
        } finally {
            try {
                grants.deleteAll(grants.findBySubjectTypeAndSubjectKeyAndDimension(
                    AccessGrant.SubjectType.USER, username, "*"));
                grants.flush();
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        }
    }

    /** Authenticate the current test thread until its context is explicitly cleared. */
    public static String authenticateAsSuperuser(AccessGrantRepository grants) {
        String username = "rls-test-" + UUID.randomUUID();
        SecurityContext testContext = SecurityContextHolder.createEmptyContext();
        testContext.setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        SecurityContextHolder.setContext(testContext);

        AccessGrant wildcard = new AccessGrant();
        wildcard.setSubjectType(AccessGrant.SubjectType.USER);
        wildcard.setSubjectKey(username);
        wildcard.setDimension("*");
        wildcard.setCanRead(true);
        wildcard.setCanUpdate(true);
        wildcard.setCanDelete(true);
        grants.saveAndFlush(wildcard);
        return username;
    }

    /** Add explicit read grants for canonical-read tests; an RLS wildcard is a separate permission. */
    public static void grantC5EntityRead(AccessGrantRepository grants, String username,
                                         Class<?>... entityTypes) {
        for (Class<?> entityType : entityTypes) {
            AccessGrant entityRead = new AccessGrant();
            entityRead.setSubjectType(AccessGrant.SubjectType.USER);
            entityRead.setSubjectKey(username);
            entityRead.setDimension(C5PermissionKeys.entityRead(entityType));
            entityRead.setDimensionValueId(null);
            entityRead.setCanRead(true);
            grants.save(entityRead);
        }
        grants.flush();
    }
}
