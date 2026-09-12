package org.ipro.rls;

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
        authenticateAsSuperuser(grants);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
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
}
