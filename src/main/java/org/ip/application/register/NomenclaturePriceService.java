package org.ip.application.register;

import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterKeyWrites;
import org.ipro.register.RegisterReader;
import org.ipro.register.RegisterRegistry;
import org.springframework.stereotype.Component;

import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Прикладная точка чтения и записи регистра «Цены номенклатуры» (ADR-0013 §4, ADR-0023).
 *
 * <p>Создание бина проверяет декларацию регистра ({@link RegisterReader}): ошибка в
 * {@code @RegisterMetadata} или в маппинге ключа остановит старт приложения, а не обнаружится при
 * первом чтении.</p>
 *
 * <p><b>Запись.</b> Цена записывается командой регистра по полному ключу {@code (номенклатура,
 * дата)}, а не решением приложения «создать или обновить». Upsert, проверка исходной версии,
 * классификация конфликтов и bounded retry живут в команде; приложение получает либо строку, либо
 * понятный отказ. Обычная правка одной строки из формы по-прежнему идёт canonical CRUD — он
 * разрешён самостоятельному регистру, но ключ этой записью не меняется.</p>
 */
@Component
public class NomenclaturePriceService {

    private final RegisterReader<NomenclaturePrice> reader;
    private final RegisterKeyWrites writes;

    public NomenclaturePriceService(EntityDataAccess access, RegisterRegistry registry,
            RegisterKeyWrites writes) {
        this.reader = new RegisterReader<>(NomenclaturePrice.class, access, registry);
        this.writes = writes;
    }

    /** Последняя запись регистра с периодом не позже {@code asOf} (включительно). */
    public Optional<NomenclaturePrice> getLast(Nomenclature nomenclature, LocalDateTime asOf) {
        Objects.requireNonNull(nomenclature, "nomenclature");
        return reader.getLast(Map.of("nomenclature", nomenclature), asOf);
    }

    /** Цена на дату или пусто, если до этой даты цена не устанавливалась. */
    public Optional<BigDecimal> priceOn(Nomenclature nomenclature, LocalDateTime asOf) {
        return getLast(nomenclature, asOf).map(NomenclaturePrice::getPrice);
    }

    /**
     * Записать цену на дату: отсутствующая строка создаётся, существующая получает новую цену.
     *
     * <p>Повтор с той же ценой — no-op: строка не обновляется, версия не растёт, callbacks и аудит
     * не вызываются. Гонку двух писателей одного отсутствующего ключа решает физический UNIQUE, а
     * транзиентный проигрыш повторяется командой в рамках её бюджета.</p>
     */
    public NomenclaturePrice setPrice(Nomenclature nomenclature, LocalDateTime period, BigDecimal price) {
        return accepted(writes.set(NomenclaturePrice.class, key(nomenclature), price(price), period));
    }

    /**
     * Изменить цену существующей строки с проверкой исходной версии.
     *
     * <p>Отсутствие строки и несовпадение версии — отказ: команда не создаёт строку и не
     * перезаписывает чужое решение молча.</p>
     */
    public NomenclaturePrice changePrice(Nomenclature nomenclature, LocalDateTime period,
            BigDecimal price, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new ValidationException("Перезагрузите запись: нужна исходная версия цены.");
        }
        return accepted(writes.set(NomenclaturePrice.class, key(nomenclature), price(price), period,
            expectedVersion));
    }

    /** Удалить цену на дату с проверкой версии; {@code false} — строки не было. */
    public boolean removePrice(Nomenclature nomenclature, LocalDateTime period, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new ValidationException("Перезагрузите запись: нужна исходная версия цены.");
        }
        RegisterKeyWrites.Result<NomenclaturePrice> result = writes.delete(NomenclaturePrice.class,
            key(nomenclature), period, expectedVersion);
        return switch (result.verdict()) {
            case DELETED -> true;
            case ABSENT -> false;
            case VERSION_CONFLICT -> throw conflict();
            default -> throw refused(result.verdict(), "удалить цену");
        };
    }

    private static NomenclaturePrice accepted(RegisterKeyWrites.Result<NomenclaturePrice> result) {
        return switch (result.verdict()) {
            case CREATED, UPDATED, UNCHANGED -> result.row();
            case ABSENT -> throw new ValidationException(
                "Цена на выбранную дату ещё не установлена: создайте запись на эту дату.");
            case VERSION_CONFLICT -> throw conflict();
            default -> throw refused(result.verdict(), "записать цену");
        };
    }

    private static OptimisticLockException conflict() {
        return new OptimisticLockException(
            "Цена на эту дату уже изменена. Перезагрузите запись и повторите действие.");
    }

    private static ValidationException refused(RegisterKeyWrites.Verdict verdict, String action) {
        return new ValidationException("Не удалось " + action + ": " + verdict
            + ". Обновите данные и повторите действие.");
    }

    private static Map<String, Object> key(Nomenclature nomenclature) {
        Objects.requireNonNull(nomenclature, "nomenclature");
        if (nomenclature.getId() == null) {
            throw new ValidationException("Сначала сохраните номенклатуру, затем укажите цену.");
        }
        return Map.of("nomenclature", nomenclature.getId());
    }

    private static Map<String, Object> price(BigDecimal price) {
        if (price == null) throw new ValidationException("Укажите цену.");
        return Map.of("price", price);
    }
}
