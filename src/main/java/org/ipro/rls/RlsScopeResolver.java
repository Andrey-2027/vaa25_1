package org.ipro.rls;

import org.ipro.numbering.NumberingScopeResolver;

import java.util.List;

/**
 * Дефолт-реализация {@link NumberingScopeResolver} из данных RLS: для измерения берёт первое
 * значение из {@code RlsDimensionValue.getRlsChecks()} той же сущности (например "JOURNAL" у
 * PrdSpec/ReceivingDocument). {@code NotApplicable} или отсутствие измерения → null (сущность
 * не участвует).
 *
 * <p>Адаптер «RLS → нумерация», предоставляется модулем RLS: нумерация знает только контракт
 * {@code NumberingScopeResolver}, а это конкретное прочтение «значение оси = значение оси
 * доступа» — одна из возможных семантик, не структурная зависимость платформы нумерации от RLS.</p>
 */
public class RlsScopeResolver implements NumberingScopeResolver {

    @Override
    public Long scopeValue(String dimension, Object entity) {
        if (!(entity instanceof RlsDimensionValue rdv)) {
            return null;
        }
        List<RlsCheckValue> checks = rdv.getRlsChecks().get(dimension);
        if (checks == null || checks.isEmpty()) {
            return null;
        }
        RlsCheckValue first = checks.get(0);
        if (first instanceof RlsCheckValue.Check check) {
            return check.id();
        }
        return null;
    }
}