package org.ipro.rls;

import org.hibernate.Hibernate;

import java.util.Map;

/**
 * Граница записи без собственного состояния: реестр измерений приходит параметром.
 *
 * <p>Раньше реестр жил в статическом поле, потому что слушатель Hibernate создаётся без
 * доступа к контексту. Цена оказалась выше выгоды: вооружённый контекст (и вообще любой
 * контекст с другим набором типов) оставлял свой реестр для всех последующих контекстов того
 * же JVM — граница одного приложения решала за другое. Теперь реестр принадлежит тому, кто
 * ставит слушателя ({@link RlsHibernateWriteGuardInstaller}), и тому, кто фиксирует
 * авторизацию ({@link RlsPolicyEnforcer}); статической остаётся только журнальная запись
 * транзакции {@link RlsWriteAuthorization} — она по природе принадлежит потоку и транзакции,
 * а не контексту.</p>
 *
 * <p>Две ветви одной границы (ADR-0017 §9). Тип с RLS-политикой проверяется как прежде: строка
 * решается до flush, а flush сверяет снимок проверок. Тип без измерений, но с canonical
 * write handle ({@link RlsDimensionRegistry#isWriteGoverned}), был вне границы вовсе — теперь
 * его запись обязана предъявить авторизацию, зафиксированную canonical path. Обе ветви
 * одноразовые и живут в одном журнале транзакции, поэтому implicit dirty-checking на commit
 * отклоняется одинаково.</p>
 */
public final class RlsWriteGuardBridge {

    private RlsWriteGuardBridge() {
    }

    /**
     * Фиксирует авторизацию canonical write: решение об операции уже принято (capability плюс
     * C5), и flush вправе ожидать именно его.
     *
     * <p>Для типа с RLS запись не делается: снимок проверок строк здесь неизвестен, а пустой
     * снимок отклонял бы штатную запись. Тип вне границы также пропускается — без
     * зарегистрированного canonical handle у него нет ни запрета, ни разрешения.</p>
     */
    public static void recordCanonicalWrite(RlsDimensionRegistry registry, Object entity) {
        record(registry, entity, RlsWriteAuthorization.Operation.WRITE);
    }

    /** То же для удаления: право на `DELETE` решено на preflight, flush лишь потребляет его. */
    public static void recordCanonicalDelete(RlsDimensionRegistry registry, Object entity) {
        record(registry, entity, RlsWriteAuthorization.Operation.DELETE);
    }

    private static void record(RlsDimensionRegistry registry, Object entity,
                               RlsWriteAuthorization.Operation operation) {
        if (registry == null || entity == null || RlsContext.isBypassed()) {
            return;
        }
        Class<?> type = Hibernate.getClass(entity);
        if (registry.policyOf(type).protectedEntity()) {
            // RLS-ветвь фиксирует авторизацию сама, вместе с проверками строк.
            return;
        }
        if (!registry.isWriteGoverned(type)) {
            return;
        }
        RlsWriteAuthorization.grant(entity, operation, Map.of());
    }

    static void requireAuthorized(RlsDimensionRegistry registry, Object entity,
                                  RlsWriteAuthorization.Operation operation) {
        if (registry == null || entity == null || RlsContext.isBypassed()) {
            return;
        }
        Class<?> type = Hibernate.getClass(entity);
        RlsPolicyDescriptor policy = registry.policyOf(type);
        if (policy.protectedEntity()) {
            if (!RlsWriteAuthorization.consume(entity, operation, policy.checksOf(entity))) {
                throw new RlsAccessDeniedException("Protected " + operation
                    + " reached Hibernate flush without an RLS authorization: " + type.getName());
            }
            return;
        }
        if (!registry.isWriteGoverned(type)) {
            return;
        }
        if (!RlsWriteAuthorization.consume(entity, operation, Map.of())) {
            throw new RlsAccessDeniedException("Canonical write of " + type.getName() + " reached"
                + " Hibernate flush (" + operation + ") without an authorization fixed by the"
                + " canonical write preflight; the type has a canonical handle, so the write must"
                + " go through the canonical path");
        }
    }
}
