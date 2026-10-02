package org.ipro.vaadin.explorer.rlsfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionValue;

import java.util.List;
import java.util.Map;

/**
 * Проба сложной политики: одно измерение фильтруемое с объявленным {@code readCondition}, второе —
 * ворота ({@code CHECK_ONLY}) той же записи.
 *
 * <p>Оба объявлены {@code custom = true} и {@code valuePaths} не задают, но запись правила всё
 * равно несёт дефолт аннотации ({@code {"id"}}), который при сложной политике не действует: read
 * идёт по объявленному условию, значения — из {@link RlsDimensionValue}. Проба существует, чтобы
 * аспект не публиковал этот дефолт как правило фильтрации.</p>
 *
 * <p>{@code CHECK_ONLY} ради: у ворот фильтра нет и быть не должно — реестр роняет старт, если
 * такое измерение объявит {@code readCondition} или получит {@code @Filter}.</p>
 */
@Entity
@Table(name = "rls_probe_policy")
@EntityMetadata(listFormTitle = "Пробная сложная политика")
@RlsDimension(value = "PROBE_CUSTOM", custom = true, readCondition = PolicyProbe.READ_CONDITION)
@RlsDimension(value = "PROBE_GATE", kind = RlsDimensionKind.CHECK_ONLY, custom = true)
@FilterDef(name = "PROBE_CUSTOM", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "PROBE_CUSTOM", condition = PolicyProbe.READ_CONDITION)
public class PolicyProbe implements RlsDimensionValue {

    /** Одна константа на объявление и на фильтр — так сверка read/write физически не разойдётся. */
    public static final String READ_CONDITION = "owner_id in (:allowedIds)";

    @Id
    private Long id;

    @Override
    public Map<String, List<RlsCheckValue>> getRlsChecks() {
        return Map.of(
            "PROBE_CUSTOM", List.of(RlsCheckValue.of(1L)),
            "PROBE_GATE", List.of(RlsCheckValue.of(2L)));
    }
}
