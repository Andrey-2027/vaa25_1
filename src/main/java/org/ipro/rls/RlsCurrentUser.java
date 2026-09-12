package org.ipro.rls;

/**
 * Имя текущего аутентифицированного пользователя — единственная точка, через которую
 * пакет RLS узнаёт о текущем пользователе (см. RlsFilterActivator).
 *
 * Реализуется приложением поверх его собственного механизма аутентификации (см.
 * SecurityRlsUser в org.ip.security, делегирующий в CurrentUser.username()).
 *
 * Без этой абстракции пакет был бы вынужден напрямую зависеть от статики приложения —
 * та же причина, по которой роли добываются через {@link RlsRoleResolver}.
 */
public interface RlsCurrentUser {

    /** Имя субъекта; legacy-реализация может вернуть "system" при отсутствии auth. */
    String username();

    /**
     * Субъект для protected operation. Неявный {@code system} не является authority:
     * системная операция обязана открыть типизированный {@link RlsContext}.
     */
    default String requireAuthenticatedUsername() {
        String username = username();
        if (username == null || username.isBlank() || "system".equals(username)) {
            throw new RlsAccessDeniedException(
                "Защищённая операция требует аутентифицированного пользователя");
        }
        return username;
    }
}
