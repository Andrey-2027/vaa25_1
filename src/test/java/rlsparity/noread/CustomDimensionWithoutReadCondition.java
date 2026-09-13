package rlsparity.noread;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.rls.RlsDimension;

/**
 * Фикстура отказа: custom FILTERABLE-измерение с фильтром, но без {@code readCondition}.
 * Раньше такой класс собирался молча — именно это и было дырой ADX-06: read-предикат
 * сложной политики не был заявлен нигде, кроме строки SQL в {@code @Filter}.
 */
@RlsDimension(value = "PARITY_NO_READ", custom = true)
@FilterDef(name = "PARITY_NO_READ", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "PARITY_NO_READ", condition = "owner_id in (:allowedIds)")
public class CustomDimensionWithoutReadCondition {
}
