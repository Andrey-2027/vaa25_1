package rlsparity.mismatch;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.rls.RlsDimension;

/**
 * Фикстура отказа: заявленный read-предикат custom-измерения разошёлся с фактическим
 * {@code @Filter}. Это ровно тот сценарий, от которого проверка и защищает — фильтр
 * правили (или копировали) отдельно от заявленного intent.
 */
@RlsDimension(value = "PARITY_MISMATCH", custom = true,
    readCondition = "owner_id in (:allowedIds)")
@FilterDef(name = "PARITY_MISMATCH", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "PARITY_MISMATCH", condition = "owner_id in (:allowedIds) and archived = false")
public class CustomDimensionWithDriftingReadCondition {
}
