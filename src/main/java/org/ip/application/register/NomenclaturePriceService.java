package org.ip.application.register;

import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.register.RegisterReader;
import org.ipro.data.EntityDataAccess;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Прикладная точка чтения регистра «Цены номенклатуры» (ADR-0013 §4).
 *
 * <p>Создание бина проверяет декларацию регистра ({@link RegisterReader}): ошибка в
 * {@code @RegisterMetadata} или в маппинге ключа остановит старт приложения, а не обнаружится при
 * первом чтении.</p>
 */
@Component
public class NomenclaturePriceService {

    private final RegisterReader<NomenclaturePrice> reader;

    public NomenclaturePriceService(EntityDataAccess access) {
        this.reader = new RegisterReader<>(NomenclaturePrice.class, access);
    }

    /** Последняя запись регистра с периодом не позже {@code asOf} (включительно). */
    public Optional<NomenclaturePrice> getLast(Nomenclature nomenclature, LocalDate asOf) {
        Objects.requireNonNull(nomenclature, "nomenclature");
        return reader.getLast(Map.of("nomenclature", nomenclature), asOf);
    }

    /** Цена на дату или пусто, если до этой даты цена не устанавливалась. */
    public Optional<BigDecimal> priceOn(Nomenclature nomenclature, LocalDate asOf) {
        return getLast(nomenclature, asOf).map(NomenclaturePrice::getPrice);
    }
}
