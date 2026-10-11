package org.ip.application.register;

import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ipro.crud.ValidationException;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityUpdateContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0013 §4: ключ записи регистра неизменяем, ресурс — изменяем. */
class NomenclaturePriceLifecycleTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    private final NomenclaturePriceLifecycle lifecycle = new NomenclaturePriceLifecycle();

    @Test
    void changingTheResourceIsAllowed() {
        NomenclaturePrice original = price(1L, DAY, "100.00");
        NomenclaturePrice updated = price(1L, DAY, "90.00");

        assertThatCode(() -> lifecycle.beforeUpdate(updating(original, updated))).doesNotThrowAnyException();
    }

    @Test
    void changingTheNomenclatureIsRejected() {
        NomenclaturePrice original = price(1L, DAY, "100.00");
        NomenclaturePrice updated = price(2L, DAY, "100.00");

        assertThatThrownBy(() -> lifecycle.beforeUpdate(updating(original, updated)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Номенклатуру");
    }

    @Test
    void changingThePeriodIsRejected() {
        NomenclaturePrice original = price(1L, DAY, "100.00");
        NomenclaturePrice updated = price(1L, DAY.plusDays(1), "100.00");

        assertThatThrownBy(() -> lifecycle.beforeUpdate(updating(original, updated)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Период");
    }

    @Test
    void unknownOriginalIsRejected() {
        NomenclaturePrice same = price(1L, DAY, "100.00");

        assertThatThrownBy(() -> lifecycle.beforeUpdate(updating(same, same)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("исходные");
    }

    private static NomenclaturePrice price(Long nomenclatureId, LocalDateTime period, String value) {
        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setId(nomenclatureId);
        NomenclaturePrice price = new NomenclaturePrice(nomenclature, period, new BigDecimal(value));
        price.setId(10L);
        return price;
    }

    private static EntityUpdateContext<NomenclaturePrice> updating(NomenclaturePrice original,
                                                                   NomenclaturePrice updated) {
        return new EntityUpdateContext<>(original, updated,
            EventContext.forEntity(NomenclaturePrice.class, 10L, EventSource.SYSTEM, "update:NomenclaturePrice"));
    }
}
