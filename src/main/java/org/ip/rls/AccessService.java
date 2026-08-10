package org.ip.rls;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Единственное место, которое отвечает на вопрос "что пользователю доступно по измерению
 * X" — по ИЛИ между прямыми правами пользователя и правами всех его ролей (см. обсуждение,
 * аналог связки Роль/Пользователь в 1С).
 *
 * getReadableIds() — для включения Hibernate @Filter (см. @RlsDimension).
 * canUpdate()/canDelete() — для проверок в сервисах перед save()/delete() (тем же приёмом,
 * что GridFormViewService.checkEditable() — @Filter на UPDATE/DELETE не действует, это
 * только для SELECT).
 *
 * Не знает про конкретную модель User/Role приложения — роли пользователя добывает через
 * {@link RlsRoleResolver} (единственная точка сцепки с приложением, см. его javadoc).
 */
@Service
public class AccessService {

    /**
     * Подставляется вместо пустого списка разрешённых id — гарантированно не существующий id,
     * чтобы "нет ни одного разрешённого журнала" не превратилось в SQL "IN ()" (в некоторых
     * диалектах — синтаксическая ошибка) и, что важнее, чтобы пустой список не был случайно
     * прочитан как "без ограничений".
     */
    public static final long NO_ACCESS_SENTINEL = -1L;

    private final AccessGrantRepository grantRepository;
    private final RlsRoleResolver roleResolver;

    public AccessService(AccessGrantRepository grantRepository, RlsRoleResolver roleResolver) {
        this.grantRepository = grantRepository;
        this.roleResolver = roleResolver;
    }

    /**
     * Список id, разрешённых пользователю на чтение по измерению.
     *
     * null — доступ БЕЗ ограничений (есть wildcard-грант, dimensionValueId == null, с
     * canRead = true) — вызывающий код (см. RlsFilterActivator) в этом случае Hibernate-фильтр
     * вообще не включает, а не передаёт туда "разрешить всё" списком.
     *
     * Пустой список никогда не возвращается — вместо него [NO_ACCESS_SENTINEL].
     */
    public List<Long> getReadableIds(String dimension, String username) {
        List<AccessGrant> grants = findGrants(dimension, username);

        boolean hasWildcardRead = grants.stream()
            .anyMatch(g -> g.isCanRead() && g.getDimensionValueId() == null);
        if (hasWildcardRead) {
            return null;
        }

        List<Long> ids = grants.stream()
            .filter(AccessGrant::isCanRead)
            .map(AccessGrant::getDimensionValueId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        return ids.isEmpty() ? List.of(NO_ACCESS_SENTINEL) : ids;
    }

    /**
     * Есть ли у пользователя ХОТЬ КАКОЙ-ТО доступ по измерению — для скрытия
     * навигации/меню, а НЕ для фильтрации строк списка (для этого — getReadableIds()
     * напрямую, как обычно). Отдельное имя специально — чтобы у вызывающего кода было
     * явно видно намерение "показывать/не показывать пункт меню", а не "сузить выборку".
     *
     * Типичное применение — CHECK_ONLY-измерения (см. RlsDimensionKind): "доступ к виду
     * документа целиком", где нет смысла показывать список из нулевых строк — пункт меню
     * должен просто не отображаться.
     */
    public boolean hasAnyAccess(String dimension, String username) {
        List<Long> ids = getReadableIds(dimension, username);
        return ids == null || !(ids.size() == 1 && ids.get(0) == NO_ACCESS_SENTINEL);
    }

    public boolean canUpdate(String dimension, Long dimensionValueId, String username) {
        return hasAccess(dimension, dimensionValueId, username, AccessGrant::isCanUpdate);
    }

    public boolean canDelete(String dimension, Long dimensionValueId, String username) {
        return hasAccess(dimension, dimensionValueId, username, AccessGrant::isCanDelete);
    }

    private boolean hasAccess(String dimension, Long dimensionValueId, String username,
                              Predicate<AccessGrant> permission) {
        return findGrants(dimension, username).stream()
            .filter(permission)
            .anyMatch(g -> g.getDimensionValueId() == null || g.getDimensionValueId().equals(dimensionValueId));
    }

    /** Прямые права пользователя + права всех его ролей — по ИЛИ, без дублей. */
    private List<AccessGrant> findGrants(String dimension, String username) {
        List<AccessGrant> result = new ArrayList<>(grantRepository.findUserGrants(dimension, username));

        List<String> roleNames = roleResolver.rolesOf(username);
        if (!roleNames.isEmpty()) {
            result.addAll(grantRepository.findRoleGrants(dimension, roleNames));
        }
        return result;
    }
}