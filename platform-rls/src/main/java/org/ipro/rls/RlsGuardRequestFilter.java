package org.ipro.rls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Граница HTTP-запроса для read-гейта ({@link RlsStatementGuard}): сбрасывает
 * состояние "фильтры обработаны" на потоке перед обработкой запроса.
 *
 * Зачем: HTTP-потоки пула переиспользуются между запросами разных пользователей
 * с разными сессиями Hibernate. Без сброса stale-метка от предыдущего запроса на
 * этом потоке скрыла бы "тихую утечку" в новом запросе, в котором активатор так
 * и не был вызван. Все интерактивные запросы приложения (включая Vaadin round-trip)
 * — HTTP-запросы, поэтому фильтра достаточно; фоновые задачи закрываются
 * {@link RlsContext#isBypassed()}.
 *
 * Регистрируется в RlsAutoConfiguration (пакет org.ipro.rls вне component-scan).
 */
public class RlsGuardRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RlsStatementGuard.clearSession();
        filterChain.doFilter(request, response);
    }
}
