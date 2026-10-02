package org.ipro.vaadin.explorer.rlsunscanned;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.rls.RlsDimension;

/**
 * Проба объявления вне пакета скана ({@code rls.dimension-scan-package}): класс объявляет измерение,
 * но реестр о нём не знает — его аксессоры в этом случае отказывают.
 *
 * <p>Пакет намеренно лежит рядом, но не под корнем скана теста: так ошибка регистрации остаётся
 * ошибкой конфигурации, а не превращается в «измерений нет». Карточка обязана показать причину
 * диагностикой и не упасть — это и проверяется.</p>
 */
@Entity
@Table(name = "rls_probe_unscanned")
@EntityMetadata(listFormTitle = "Пробный незарегистрированный тип")
@RlsDimension("PROBE_UNSCANNED")
@FilterDef(name = "PROBE_UNSCANNED", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "PROBE_UNSCANNED", condition = "id in (:allowedIds)")
public class UnscannedProbe {

    @Id
    private Long id;
}
