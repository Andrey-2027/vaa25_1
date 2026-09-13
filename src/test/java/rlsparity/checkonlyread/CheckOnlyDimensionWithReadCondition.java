package rlsparity.checkonlyread;

import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;

/**
 * Фикстура отказа: CHECK_ONLY-измерение объявило {@code readCondition}, но фильтра у него
 * нет — сверять предикат не с чем. Оставленное «на будущее» условие здесь опаснее
 * отсутствующего: оно выглядит как объявленный read-контракт, которого платформа не
 * проверяет и не исполняет.
 */
@RlsDimension(value = "PARITY_CHECK_ONLY_READ", kind = RlsDimensionKind.CHECK_ONLY,
    custom = true, readCondition = "owner_id in (:allowedIds)")
public class CheckOnlyDimensionWithReadCondition {
}
