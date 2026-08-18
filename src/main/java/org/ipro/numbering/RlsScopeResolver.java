package org.ipro.numbering;

import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimensionValue;

import java.util.List;

/**
 * Встроенная дефолт-реализация {@link NumberingScopeResolver}: для измерения берёт первое
 * значение из {@code RlsDimensionValue.getRlsChecks()} той же сущности (например "JOURNAL" у
 * PrdSpec/ReceivingDocument). {@code NotApplicable} или отсутствие измерения → null (сущность
 * не участвует).
 *
 * <p>Не является структурной зависимостью нумерации от RLS: сущность может реализовать
 * {@code NumberingScopeResolver} сама, а этот резолвер — лишь реализация по умолчанию для
 * совпадающих случаев (scope нумерации = измерение доступа), чтобы не держать второй
 * параллельный способ достать «Journal этой записи».</p>
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
