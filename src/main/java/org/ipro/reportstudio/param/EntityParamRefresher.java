package org.ipro.reportstudio.param;

import jakarta.persistence.EntityManager;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;

/**
 * Строгий RLS-перезапрос сущностного параметра по id (Фаза 3): биндится НЕ то
 * значение с формы, а свежий инстанс, перезапрошенный в текущей сессии.
 * <p>
 * Два гейта, оба обязательные (решение из плана — «перезапрошенный по ID через
 * guarded-репозиторий с активными фильтрами RLS»):
 * <ul>
 * <li>{@link RlsReadGate#canRead} — CHECK_ONLY-измерения («доступ к виду
 *     документа целиком»): без гранта чтения класс отдаёт пусто вообще;</li>
 * <li>запрос выполняется под {@link RlsFilterActivator#ensureRlsEnabled} —
 *     построчные FILTERABLE-фильтры (JOURNAL/BRANCH) применены Hibernate к самому
 *     запросу (в отличие от {@code EntityManager.find}, который фильтры класса
 *     НЕ применяет — прямая выборка по id обошла бы RLS).</li>
 * </ul>
 * «Не найдено» и «недоступно по RLS» неразличимы намеренно (для отчёта это одно
 * и то же: выполнять нельзя) — обе ситуации дают {@code null} и жёсткое
 * прерывание с именем параметра и id.
 */
public class EntityParamRefresher {

    private final EntityManager entityManager;
    private final RlsFilterActivator rlsFilterActivator;
    private final RlsReadGate rlsReadGate;
    private final RlsCurrentUser currentUser;
    private final SessionFactoryImplementor sessionFactory;

    public EntityParamRefresher(EntityManager entityManager,
                                RlsFilterActivator rlsFilterActivator,
                                RlsReadGate rlsReadGate,
                                RlsCurrentUser currentUser,
                                SessionFactoryImplementor sessionFactory) {
        this.entityManager = entityManager;
        this.rlsFilterActivator = rlsFilterActivator;
        this.rlsReadGate = rlsReadGate;
        this.currentUser = currentUser;
        this.sessionFactory = sessionFactory;
    }

    /**
     * @return свежий инстанс сущности, доступный текущему пользователю;
     *         null — не найдена или недоступна по RLS
     * @throws IllegalArgumentException класс не является JPA-сущностью
     */
    public Object refresh(Class<?> entityClass, Object id) {
        if (id == null) {
            return null;
        }
        if (!rlsReadGate.canRead(entityClass, currentUser.username())) {
            return null;
        }
        rlsFilterActivator.ensureRlsEnabled(entityManager);
        String entityName = entityName(entityClass);
        return entityManager
            .createQuery("select e from " + entityName + " e where e.id = :id", entityClass)
            .setParameter("id", id)
            .getResultStream()
            .findFirst()
            .orElse(null);
    }

    private String entityName(Class<?> entityClass) {
        try {
            return sessionFactory.getMappingMetamodel().getEntityDescriptor(entityClass)
                .getEntityName();
        } catch (Exception unknownEntity) {
            throw new IllegalArgumentException(
                "Класс " + entityClass.getName() + " не является JPA-сущностью", unknownEntity);
        }
    }
}