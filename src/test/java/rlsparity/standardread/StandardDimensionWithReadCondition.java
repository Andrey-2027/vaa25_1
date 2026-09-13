package rlsparity.standardread;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.rls.RlsDimension;

/**
 * Фикстура отказа: стандартное (не custom) измерение объявило {@code readCondition}.
 * Его read-предикат выводится из {@code valuePaths}, поэтому объявленное условие было бы
 * молча проигнорировано — а выглядит это как «я переопределил предикат». Хуже молчания
 * только молчание с ощущением контроля, поэтому такой класс обязан ронять старт.
 */
@RlsDimension(value = "PARITY_STANDARD_READ", valuePaths = "ownerId",
    readCondition = "owner_id in (:allowedIds)")
@FilterDef(name = "PARITY_STANDARD_READ", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "PARITY_STANDARD_READ", condition = "owner_id in (:allowedIds)")
public class StandardDimensionWithReadCondition {

    private Long ownerId;

    public Long getOwnerId() {
        return ownerId;
    }
}
