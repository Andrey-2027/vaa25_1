package org.ip.views.query;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * D4 первого среза: экран запросов доступен любому аутентифицированному
 * пользователю, но не анонимному.
 *
 * <p>Ответ один и тот же для маршрута, пункта меню и самого вида: три места не
 * должны расходиться в правиле входа. Права на конкретные сущности и строки
 * по-прежнему проверяет guard запроса, а не этот класс.</p>
 */
@Component
public class QueryWorkbenchAccess {

    public boolean allows() {
        return allows(SecurityContextHolder.getContext().getAuthentication());
    }

    public static boolean allows(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
