package org.ip.rls;

import java.util.List;

/**
 * Единственная точка, через которую {@link AccessService} узнаёт о ролях пользователя —
 * реализуется приложением поверх его СОБСТВЕННОЙ модели User/Role (см.
 * UserRepositoryRlsRoleResolver в org.ip.security — реализация для этого проекта).
 *
 * Без этой абстракции AccessService был бы вынужден напрямую знать про UserRepository/
 * User/Role — единственная содержательная зависимость, которая держала весь пакет
 * org.ip.rls внутри приложения, а не в отдельно переносимой библиотеке.
 */
public interface RlsRoleResolver {

    /** Имена ролей пользователя; пустой список — если пользователя нет или у него нет ролей. */
    List<String> rolesOf(String username);
}