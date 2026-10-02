package org.ipro.vaadin.explorer.rlsfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.FilterDefs;
import org.hibernate.annotations.Filters;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.rls.RlsDimension;

/**
 * Проба измерения с каталогом грантов и вторым измерением того же типа: два объявления на одном
 * классе плюс проверка порядка строк.
 *
 * <p>Измерения объявлены намеренно <b>в обратном алфавитном порядке</b> (сначала {@code PROBE_ZULU}):
 * так видно, что порядок строк задаёт потребитель, а не порядок объявления и не порядок скана —
 * владелец отдаёт {@code Set} без контракта порядка.</p>
 *
 * <p>Фильтры настоящие: {@code FILTERABLE}-измерение без {@code @FilterDef}/{@code @Filter} с тем же
 * именем роняет реестр при старте, и проба обязана проходить те же контракты, что боевые типы, иначе
 * тест проверял бы несуществующий мир.</p>
 */
@Entity
@Table(name = "rls_probe_grant")
@EntityMetadata(listFormTitle = "Пробный каталог")
@RlsDimension("PROBE_ZULU")
@RlsDimension(value = "PROBE_BRANCH", grantValues = true)
@FilterDefs({
    @FilterDef(name = "PROBE_ZULU", parameters = @ParamDef(name = "allowedIds", type = Long.class)),
    @FilterDef(name = "PROBE_BRANCH", parameters = @ParamDef(name = "allowedIds", type = Long.class))
})
@Filters({
    @Filter(name = "PROBE_ZULU", condition = "id in (:allowedIds)"),
    @Filter(name = "PROBE_BRANCH", condition = "id in (:allowedIds)")
})
public class GrantProbe {

    @Id
    private Long id;
}
