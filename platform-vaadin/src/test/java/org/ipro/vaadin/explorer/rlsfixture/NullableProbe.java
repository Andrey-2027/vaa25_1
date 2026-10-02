package org.ipro.vaadin.explorer.rlsfixture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.rls.RlsDimension;

/**
 * Проба стандартного правила с нетривиальным путём и объявленным {@code nullsNotApplicable}: null в
 * пути означает «измерение к записи не применимо», и это видно в строке правила, а не только в
 * предикате фильтра.
 *
 * <p>Предикат выведен из пути и объявленного признака — реестр сверяет его с {@code @Filter} при
 * старте, поэтому условие здесь обязано быть именно выведенным, а не удобным.</p>
 */
@Entity
@Table(name = "rls_probe_nullable")
@EntityMetadata(listFormTitle = "Пробная строка")
@RlsDimension(value = "PROBE_ROW", valuePaths = "branchId", nullsNotApplicable = true)
@FilterDef(name = "PROBE_ROW", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "PROBE_ROW", condition = "(branch_id is null or branch_id in (:allowedIds))")
public class NullableProbe {

    @Id
    private Long id;

    @Column(name = "branch_id")
    private Long branchId;
}
