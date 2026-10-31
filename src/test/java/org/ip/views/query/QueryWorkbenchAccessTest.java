package org.ip.views.query;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4: правило входа на самостоятельный query-экран названо один раз.
 *
 * <p>Маршрут, пункт меню и {@code beforeEnter} спрашивают {@link QueryWorkbenchAccess},
 * поэтому проверяется именно решение, а не три независимых условия. Анонимный
 * пользователь не попадает на экран; право читать конкретные сущности по-прежнему
 * проверяет guard запроса — доступ к экрану его не заменяет и не расширяет.</p>
 */
class QueryWorkbenchAccessTest {

    @Test
    void anonymousUserIsNotAllowed() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertFalse(QueryWorkbenchAccess.allows(anonymous));
        assertTrue(anonymous.isAuthenticated());
    }

    @Test
    void missingAuthenticationIsNotAllowed() {
        assertFalse(QueryWorkbenchAccess.allows((Authentication) null));
    }

    @Test
    void authenticatedUserIsAllowedRegardlessOfAuthorities() {
        Authentication user = UsernamePasswordAuthenticationToken.authenticated(
                "operator", "n/a", List.of());

        assertTrue(QueryWorkbenchAccess.allows(user));
    }

    @Test
    void unauthenticatedTokenIsNotAllowed() {
        Authentication token = UsernamePasswordAuthenticationToken.unauthenticated("operator", "n/a");

        assertFalse(QueryWorkbenchAccess.allows(token));
    }
}
